package io.replaydock;

import static io.replaydock.Models.*;
import io.replaydock.persistence.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Entities stay inside transactions; controllers receive immutable API records. */
@Service
@Transactional(readOnly = true)
public class DockService {
    private final EndpointRepository endpoints;
    private final EventRepository events;
    private final AttemptRepository attempts;
    private final MockControlRepository controls;
    private final ProcessedEventRepository processed;
    private final TargetPolicy targets;

    public DockService(EndpointRepository endpoints, EventRepository events, AttemptRepository attempts,
                       MockControlRepository controls, ProcessedEventRepository processed, TargetPolicy targets) {
        this.endpoints = endpoints; this.events = events; this.attempts = attempts;
        this.controls = controls; this.processed = processed; this.targets = targets;
    }

    @Transactional
    public CreatedEndpoint createEndpoint(NewEndpoint input) {
        if (input.name() == null || input.name().isBlank() || input.name().length() > 80) bad("Name must contain 1–80 characters");
        int max = input.maxAttempts() == null ? 5 : input.maxAttempts();
        long delay = input.baseDelayMs() == null ? 1000 : input.baseDelayMs();
        if (max < 1 || max > 10 || delay < 100 || delay > 60000) bad("Attempts must be 1–10; delay must be 100–60000 ms");
        String secret = Signatures.newSecret();
        WebhookEndpoint endpoint = endpoints.save(new WebhookEndpoint(UUID.randomUUID().toString(), input.name().trim(),
                targets.validate(input.targetUrl()), secret, max, delay, Instant.now()));
        controls.save(new MockControl(endpoint.getId(), endpoint, 2, 503, 0));
        return new CreatedEndpoint(view(endpoint), secret);
    }

    public List<Endpoint> endpoints() { return endpoints.findAllByOrderByCreatedAtDesc().stream().map(DockService::view).toList(); }
    public Endpoint endpoint(String id) { return view(endpointEntity(id)); }
    public String secret(String id) { return endpointEntity(id).getSecret(); }

    @Transactional
    public Receipt accept(String endpointId, String sourceId, String payload) {
        validateSourceId(sourceId);
        if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Payload limit is 64 KiB");
        }
        // Serialize acceptance for this endpoint, including the duplicate lookup and insert.
        WebhookEndpoint endpoint = endpoints.lockById(endpointId).orElseThrow(() -> missing("Endpoint"));
        WebhookEvent existing = events.findByEndpoint_IdAndSourceId(endpointId, sourceId).orElse(null);
        if (existing != null) {
            if (!existing.getPayload().equals(payload)) conflict("Event ID already exists with a different body");
            return new Receipt(existing.getId(), true, existing.getStatus());
        }
        Instant now = Instant.now();
        WebhookEvent event = events.save(new WebhookEvent(UUID.randomUUID().toString(), endpoint, sourceId, payload,
                "PENDING", 0, now, now, now));
        // The service proxy commits before the controller can return 202.
        return new Receipt(event.getId(), false, event.getStatus());
    }

    public List<Event> events() { return events.findTop200ByOrderByCreatedAtDesc().stream().map(DockService::view).toList(); }
    public Event event(String id) { return view(eventEntity(id)); }
    public List<Attempt> attempts(String id) {
        eventEntity(id);
        return attempts.findByEvent_IdOrderByCreatedAtAscIdAsc(id).stream().map(DockService::view).toList();
    }

    @Transactional
    public Event replay(String id) {
        WebhookEvent event = events.lockById(id).orElseThrow(() -> missing("Event"));
        if (!List.of("DELIVERED", "DEAD").contains(event.getStatus())) conflict("Only completed or exhausted events can be replayed");
        event.setStatus("PENDING"); event.setAttemptCount(0);
        event.setNextAttemptAt(Instant.now()); event.setUpdatedAt(Instant.now());
        return view(event);
    }

    @Transactional
    public void recoverInterrupted() { events.recoverInterrupted(Instant.now()); }

    @Transactional
    public Event claimDue() {
        Instant now = Instant.now();
        List<WebhookEvent> candidates = events.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc("PENDING", now, PageRequest.of(0, 1));
        if (candidates.isEmpty()) return null;
        String id = candidates.get(0).getId();
        // A conditional JPQL update elects exactly one claimant even if both read the same candidate.
        if (events.claim(id, now) != 1) return null;
        return view(eventEntity(id));
    }

    @Transactional
    public void complete(Event claimed, DeliveryResult result) {
        WebhookEvent event = events.lockById(claimed.id()).orElseThrow(() -> missing("Event"));
        if (!"DELIVERING".equals(event.getStatus())) conflict("Event is not currently delivering");
        WebhookEndpoint endpoint = event.getEndpoint();
        int number = event.getAttemptCount() + 1;
        Integer code = result.httpStatus();
        boolean success = code != null && code >= 200 && code < 300;
        boolean retryable = code == null || code == 408 || code == 429 || code >= 500;
        String status = success ? "DELIVERED" : retryable && number < endpoint.getMaxAttempts() ? "PENDING" : "DEAD";
        long delay = Math.min(300000, endpoint.getBaseDelayMs() * (1L << (number - 1)));
        Instant now = Instant.now();
        attempts.save(new DeliveryAttempt(UUID.randomUUID().toString(), event, number, code,
                success ? "SUCCESS" : status.equals("PENDING") ? "RETRY_SCHEDULED" : "EXHAUSTED",
                truncate(result.detail()), result.durationMs(), now));
        event.setStatus(status); event.setAttemptCount(number);
        event.setNextAttemptAt(now.plusMillis(delay)); event.setUpdatedAt(now);
        // Dirty checking updates the managed event in the same transaction as the attempt insert.
    }

    @Transactional
    public MockState configureMock(String id, MockConfig config) {
        if (config.failuresLeft() < 0 || config.failuresLeft() > 20 || config.failureStatus() < 400 || config.failureStatus() > 599
                || config.delayMs() < 0 || config.delayMs() > 10000) bad("Invalid mock controls");
        MockControl control = controls.lockById(id).orElseThrow(() -> missing("Endpoint"));
        control.setFailuresLeft(config.failuresLeft()); control.setFailureStatus(config.failureStatus()); control.setDelayMs(config.delayMs());
        return mockView(control);
    }

    public MockState mockState(String id) {
        return mockView(controls.findById(id).orElseThrow(() -> missing("Endpoint")));
    }

    @Transactional
    public MockResult processMock(String id, String sourceId) {
        validateSourceId(sourceId);
        // Lock before checking deduplication: concurrent duplicate requests cannot both act.
        MockControl control = controls.lockById(id).orElseThrow(() -> missing("Endpoint"));
        ProcessedEventId key = new ProcessedEventId(id, sourceId);
        if (processed.existsById(key)) return new MockResult(200, "Duplicate acknowledged; business action skipped");
        if (control.getFailuresLeft() > 0) {
            control.setFailuresLeft(control.getFailuresLeft() - 1);
            return new MockResult(control.getFailureStatus(), "Simulated receiver failure");
        }
        processed.save(new ProcessedEvent(key, control.getEndpoint(), Instant.now()));
        return new MockResult(200, "Business action processed once");
    }

    private WebhookEndpoint endpointEntity(String id) { return endpoints.findById(id).orElseThrow(() -> missing("Endpoint")); }
    private WebhookEvent eventEntity(String id) { return events.findById(id).orElseThrow(() -> missing("Event")); }
    private MockState mockView(MockControl control) {
        return new MockState(control.getFailuresLeft(), control.getFailureStatus(), control.getDelayMs(), processed.countById_EndpointId(control.getEndpointId()));
    }
    private static Endpoint view(WebhookEndpoint e) {
        return new Endpoint(e.getId(), e.getName(), e.getTargetUrl(), e.getMaxAttempts(), e.getBaseDelayMs(), e.getCreatedAt());
    }
    private static Event view(WebhookEvent e) {
        return new Event(e.getId(), e.getEndpoint().getId(), e.getSourceId(), e.getPayload(), e.getStatus(),
                e.getAttemptCount(), e.getNextAttemptAt(), e.getCreatedAt(), e.getUpdatedAt());
    }
    private static Attempt view(DeliveryAttempt a) {
        return new Attempt(a.getId(), a.getEvent().getId(), a.getAttemptNumber(), a.getHttpStatus(), a.getOutcome(), a.getDetail(), a.getDurationMs(), a.getCreatedAt());
    }
    private static void validateSourceId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9._:-]{1,128}")) bad("Event ID must contain 1–128 letters, digits, dots, underscores, colons or hyphens");
    }
    private static String truncate(String s) { return s == null ? "" : s.substring(0, Math.min(s.length(), 1024)); }
    private static ResponseStatusException missing(String type) { return new ResponseStatusException(HttpStatus.NOT_FOUND, type + " not found"); }
    private static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static void conflict(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
