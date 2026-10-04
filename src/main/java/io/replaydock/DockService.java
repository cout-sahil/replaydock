package io.replaydock;

import static io.replaydock.Models.*;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Single-instance lab. All state transitions commit before acknowledgment or delivery. */
@Service
public class DockService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final TargetPolicy targets;
    public DockService(JdbcTemplate db, TransactionTemplate tx, TargetPolicy targets) {
        this.db = db; this.tx = tx; this.targets = targets;
    }
    public synchronized CreatedEndpoint createEndpoint(NewEndpoint input) {
        if (input.name() == null || input.name().isBlank() || input.name().length() > 80) bad("Name must contain 1–80 characters");
        int max = input.maxAttempts() == null ? 5 : input.maxAttempts();
        long delay = input.baseDelayMs() == null ? 1000 : input.baseDelayMs();
        if (max < 1 || max > 10 || delay < 100 || delay > 60000) bad("Attempts must be 1–10; delay must be 100–60000 ms");
        String target = targets.validate(input.targetUrl());
        String id = UUID.randomUUID().toString(), secret = Signatures.newSecret();
        Instant now = Instant.now();
        tx.executeWithoutResult(status -> {
            db.update("INSERT INTO endpoints VALUES(?,?,?,?,?,?,?)", id, input.name().trim(), target, secret, max, delay, stamp(now));
            db.update("INSERT INTO mock_controls(endpoint_id) VALUES(?)", id);
        });
        return new CreatedEndpoint(endpoint(id), secret);
    }
    public List<Endpoint> endpoints() { return db.query("SELECT * FROM endpoints ORDER BY created_at DESC", DockService::mapEndpoint); }
    public Endpoint endpoint(String id) {
        return db.query("SELECT * FROM endpoints WHERE id=?", DockService::mapEndpoint, id).stream()
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Endpoint not found"));
    }
    public String secret(String id) {
        endpoint(id);
        return db.queryForObject("SELECT secret FROM endpoints WHERE id=?", String.class, id);
    }
    public synchronized Receipt accept(String endpointId, String sourceId, String payload) {
        endpoint(endpointId);
        if (sourceId == null || !sourceId.matches("[A-Za-z0-9._:-]{1,128}")) bad("Event ID must contain 1–128 letters, digits, dots, underscores, colons or hyphens");
        if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Payload limit is 64 KiB");
        }
        List<Event> existing = db.query("SELECT * FROM events WHERE endpoint_id=? AND source_id=?", DockService::mapEvent, endpointId, sourceId);
        if (!existing.isEmpty()) {
            Event event = existing.get(0);
            if (!event.payload().equals(payload)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Event ID already exists with a different body");
            return new Receipt(event.id(), true, event.status());
        }
        String id = UUID.randomUUID().toString(); Instant now = Instant.now();
        tx.executeWithoutResult(status -> db.update("INSERT INTO events VALUES(?,?,?,?,?,?,?,?,?)", id, endpointId, sourceId,
                payload, "PENDING", 0, stamp(now), stamp(now), stamp(now)));
        return new Receipt(id, false, "PENDING");
    }
    public List<Event> events() { return db.query("SELECT * FROM events ORDER BY created_at DESC LIMIT 200", DockService::mapEvent); }
    public Event event(String id) {
        return db.query("SELECT * FROM events WHERE id=?", DockService::mapEvent, id).stream()
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
    }
    public List<Attempt> attempts(String id) {
        event(id);
        return db.query("SELECT * FROM attempts WHERE event_id=? ORDER BY created_at,id", (r, row) -> new Attempt(
                r.getString("id"), r.getString("event_id"), r.getInt("attempt_number"), (Integer) r.getObject("http_status"),
                r.getString("outcome"), r.getString("detail"), r.getLong("duration_ms"), instant(r, "created_at")), id);
    }
    public synchronized Event replay(String id) {
        Event event = event(id);
        if (!List.of("DELIVERED", "DEAD").contains(event.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only completed or exhausted events can be replayed");
        db.update("UPDATE events SET status='PENDING', attempt_count=0, next_attempt_at=?, updated_at=? WHERE id=?", stamp(Instant.now()), stamp(Instant.now()), id);
        return event(id);
    }
    public synchronized void recoverInterrupted() {
        db.update("UPDATE events SET status='PENDING', next_attempt_at=?, updated_at=? WHERE status='DELIVERING'", stamp(Instant.now()), stamp(Instant.now()));
    }
    public synchronized Event claimDue() {
        return tx.execute(status -> {
            List<Event> due = db.query("SELECT * FROM events WHERE status='PENDING' AND next_attempt_at<=? ORDER BY next_attempt_at LIMIT 1", DockService::mapEvent, stamp(Instant.now()));
            if (due.isEmpty()) return null;
            Event event = due.get(0);
            db.update("UPDATE events SET status='DELIVERING', updated_at=? WHERE id=?", stamp(Instant.now()), event.id());
            return event(event.id());
        });
    }
    public synchronized void complete(Event event, DeliveryResult result) {
        Endpoint endpoint = endpoint(event.endpointId());
        int number = event.attemptCount() + 1;
        Integer code = result.httpStatus();
        boolean success = code != null && code >= 200 && code < 300;
        boolean retryable = code == null || code == 408 || code == 429 || code >= 500;
        String status = success ? "DELIVERED" : retryable && number < endpoint.maxAttempts() ? "PENDING" : "DEAD";
        long delay = Math.min(300000, endpoint.baseDelayMs() * (1L << (number - 1)));
        Instant now = Instant.now();
        tx.executeWithoutResult(transaction -> {
            db.update("INSERT INTO attempts VALUES(?,?,?,?,?,?,?,?)", UUID.randomUUID().toString(), event.id(), number, code,
                    success ? "SUCCESS" : status.equals("PENDING") ? "RETRY_SCHEDULED" : "EXHAUSTED", truncate(result.detail()), result.durationMs(), stamp(now));
            db.update("UPDATE events SET status=?, attempt_count=?, next_attempt_at=?, updated_at=? WHERE id=?",
                    status, number, stamp(now.plusMillis(delay)), stamp(now), event.id());
        });
    }
    public synchronized MockState configureMock(String id, MockConfig config) {
        endpoint(id);
        if (config.failuresLeft() < 0 || config.failuresLeft() > 20 || config.failureStatus() < 400 || config.failureStatus() > 599
                || config.delayMs() < 0 || config.delayMs() > 10000) bad("Invalid mock controls");
        db.update("UPDATE mock_controls SET failures_left=?, failure_status=?, delay_ms=? WHERE endpoint_id=?", config.failuresLeft(), config.failureStatus(), config.delayMs(), id);
        return mockState(id);
    }
    public MockState mockState(String id) {
        endpoint(id);
        return db.queryForObject("SELECT * FROM mock_controls WHERE endpoint_id=?", (r, row) -> new MockState(
                r.getInt("failures_left"), r.getInt("failure_status"), r.getInt("delay_ms"),
                db.queryForObject("SELECT COUNT(*) FROM mock_processed WHERE endpoint_id=?", Long.class, id)), id);
    }
    public synchronized MockResult processMock(String id, String sourceId) {
        if (sourceId == null || !sourceId.matches("[A-Za-z0-9._:-]{1,128}")) bad("Valid event ID required");
        return tx.execute(status -> {
            MockState control = mockState(id);
            Long processed = db.queryForObject("SELECT COUNT(*) FROM mock_processed WHERE endpoint_id=? AND source_id=?", Long.class, id, sourceId);
            if (processed != null && processed > 0) return new MockResult(200, "Duplicate acknowledged; business action skipped");
            if (control.failuresLeft() > 0) {
                db.update("UPDATE mock_controls SET failures_left=failures_left-1 WHERE endpoint_id=?", id);
                return new MockResult(control.failureStatus(), "Simulated receiver failure");
            }
            try { db.update("INSERT INTO mock_processed VALUES(?,?,?)", id, sourceId, stamp(Instant.now())); }
            catch (DuplicateKeyException ex) { return new MockResult(200, "Duplicate acknowledged"); }
            return new MockResult(200, "Business action processed once");
        });
    }
    private static String truncate(String s) { return s == null ? "" : s.substring(0, Math.min(s.length(), 1024)); }
    private static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static Timestamp stamp(Instant instant) { return Timestamp.from(instant); }
    private static Instant instant(ResultSet r, String name) throws SQLException { return r.getTimestamp(name).toInstant(); }
    private static Endpoint mapEndpoint(ResultSet r, int row) throws SQLException {
        return new Endpoint(r.getString("id"), r.getString("name"), r.getString("target_url"), r.getInt("max_attempts"), r.getLong("base_delay_ms"), instant(r, "created_at"));
    }
    private static Event mapEvent(ResultSet r, int row) throws SQLException {
        return new Event(r.getString("id"), r.getString("endpoint_id"), r.getString("source_id"), r.getString("payload"),
                r.getString("status"), r.getInt("attempt_count"), instant(r, "next_attempt_at"), instant(r, "created_at"), instant(r, "updated_at"));
    }
}
