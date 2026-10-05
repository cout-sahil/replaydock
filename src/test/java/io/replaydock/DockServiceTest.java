package io.replaydock;

import static io.replaydock.Models.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.ResponseStatusException;

@Import(TestDatabase.class)
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:service-tests;DB_CLOSE_DELAY=-1", "replaydock.worker-enabled=false", "replaydock.admin-password=test-password"})
class DockServiceTest {
    @Autowired DockService dock;
    @Autowired TestDatabase db;
    String endpoint;
    @BeforeEach void setup() {
        db.clear();
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
    @Test void concurrentClaimsHaveOnlyOneWinner() throws Exception {
        Receipt receipt = dock.accept(endpoint, "claim-race", "{}");
        var results = concurrently(dock::claimDue);
        assertThat(results.stream().filter(java.util.Objects::nonNull).map(Event::id).toList()).containsExactly(receipt.eventId());
        assertThat(dock.event(receipt.eventId()).status()).isEqualTo("DELIVERING");
    }
    @Test void concurrentDuplicateAcceptanceQueuesOneEvent() throws Exception {
        var results = concurrently(() -> dock.accept(endpoint, "accept-race", "{}"));
        assertThat(results.stream().map(Receipt::eventId).distinct().count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> !r.duplicate()).count()).isEqualTo(1);
        assertThat(dock.events()).hasSize(1);
    }
    @Test void concurrentMockDuplicatesPerformOneBusinessAction() throws Exception {
        dock.configureMock(endpoint, new MockConfig(0, 503, 0));
        var results = concurrently(() -> dock.processMock(endpoint, "mock-race"));
        assertThat(results).allMatch(r -> r.status() == 200);
        assertThat(results.stream().filter(r -> r.message().equals("Business action processed once")).count()).isEqualTo(1);
        assertThat(dock.mockState(endpoint).processedCount()).isEqualTo(1);
    }
    private <T> java.util.List<T> concurrently(java.util.concurrent.Callable<T> task) throws Exception {
        var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
        var ready = new java.util.concurrent.CountDownLatch(8);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<T>>();
            for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> { ready.countDown(); start.await(); return task.call(); }));
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = new java.util.ArrayList<T>();
            for (var future : futures) results.add(future.get(10, java.util.concurrent.TimeUnit.SECONDS));
            return results;
        } finally { start.countDown(); executor.shutdownNow(); }
    }
    private Event claim(String expected) { Event event = dock.claimDue(); assertThat(event.id()).isEqualTo(expected); return event; }
    private void makeDue(String id) { db.makeDue(id); }
}
