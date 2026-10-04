package io.replaydock;

import static io.replaydock.Models.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:service-tests;DB_CLOSE_DELAY=-1", "replaydock.worker-enabled=false", "replaydock.admin-password=test-password"})
class DockServiceTest {
    @Autowired DockService dock;
    @Autowired JdbcTemplate db;
    String endpoint;
    @BeforeEach void setup() {
        for (String table : new String[]{"attempts", "mock_processed", "mock_controls", "events", "endpoints"}) db.update("DELETE FROM " + table);
        endpoint = dock.createEndpoint(new NewEndpoint("Test", null, 3, 100L)).endpoint().id();
    }
    @Test void duplicateAcceptanceDoesNotQueueAnotherEvent() {
        Receipt first = dock.accept(endpoint, "evt-1", "{}");
        Receipt second = dock.accept(endpoint, "evt-1", "{}");
        assertThat(second.eventId()).isEqualTo(first.eventId());
        assertThat(second.duplicate()).isTrue();
        assertThatThrownBy(() -> dock.accept(endpoint, "evt-1", "{\"changed\":true}"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
    }
    @Test void failuresRecoverAndReplayDoesNotRepeatBusinessAction() {
        Receipt receipt = dock.accept(endpoint, "payment-1", "{}");
        for (int i = 0; i < 3; i++) {
            makeDue(receipt.eventId()); Event event = claim(receipt.eventId());
            MockResult response = dock.processMock(endpoint, event.sourceId());
            dock.complete(event, new DeliveryResult(response.status(), response.message(), 10));
        }
        assertThat(dock.event(receipt.eventId()).status()).isEqualTo("DELIVERED");
        assertThat(dock.attempts(receipt.eventId())).extracting(Attempt::httpStatus).containsExactly(503, 503, 200);
        assertThat(dock.mockState(endpoint).processedCount()).isEqualTo(1);
        dock.replay(receipt.eventId()); Event replay = claim(receipt.eventId());
        MockResult duplicate = dock.processMock(endpoint, replay.sourceId());
        dock.complete(replay, new DeliveryResult(duplicate.status(), duplicate.message(), 10));
        assertThat(dock.mockState(endpoint).processedCount()).isEqualTo(1);
        assertThat(dock.attempts(receipt.eventId())).hasSize(4);
    }
    @Test void retriesExhaustAndPermanentErrorsStopImmediately() {
        Receipt receipt = dock.accept(endpoint, "exhaust", "{}");
        for (int i = 0; i < 3; i++) { makeDue(receipt.eventId()); dock.complete(claim(receipt.eventId()), new DeliveryResult(503, "failed", 1)); }
        assertThat(dock.event(receipt.eventId()).status()).isEqualTo("DEAD");
        Receipt permanent = dock.accept(endpoint, "permanent", "{}");
        dock.complete(claim(permanent.eventId()), new DeliveryResult(400, "invalid", 1));
        assertThat(dock.event(permanent.eventId()).status()).isEqualTo("DEAD");
        assertThat(dock.attempts(permanent.eventId())).hasSize(1);
    }
    @Test void activeReplayIsRejectedAndInterruptedWorkIsRecovered() {
        Receipt receipt = dock.accept(endpoint, "crash", "{}");
        claim(receipt.eventId());
        assertThatThrownBy(() -> dock.replay(receipt.eventId())).hasMessageContaining("409");
        dock.recoverInterrupted();
        assertThat(dock.event(receipt.eventId()).status()).isEqualTo("PENDING");
        assertThat(claim(receipt.eventId()).sourceId()).isEqualTo("crash");
        dock.complete(dock.event(receipt.eventId()), new DeliveryResult(200, "ok", 1));
    }
    @Test void unsafeDestinationsAndOversizedBodiesAreRejected() {
        assertThatThrownBy(() -> dock.createEndpoint(new NewEndpoint("bad", "http://169.254.169.254/", 3, 100L))).hasMessageContaining("400");
        assertThatThrownBy(() -> dock.accept(endpoint, "large", "x".repeat(65537))).hasMessageContaining("413");
    }
    private Event claim(String expected) { Event event = dock.claimDue(); assertThat(event.id()).isEqualTo(expected); return event; }
    private void makeDue(String id) { db.update("UPDATE events SET next_attempt_at=CURRENT_TIMESTAMP WHERE id=?", id); }
}
