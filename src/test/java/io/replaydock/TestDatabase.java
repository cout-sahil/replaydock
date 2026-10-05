package io.replaydock;

import io.replaydock.persistence.*;
import java.time.Instant;
import org.springframework.transaction.annotation.Transactional;

/** Test fixtures use the same JPA repositories as the application. */
public class TestDatabase {
    private final AttemptRepository attempts;
    private final ProcessedEventRepository processed;
    private final MockControlRepository controls;
    private final EventRepository events;
    private final EndpointRepository endpoints;
    public TestDatabase(AttemptRepository attempts, ProcessedEventRepository processed, MockControlRepository controls,
                        EventRepository events, EndpointRepository endpoints) {
        this.attempts = attempts; this.processed = processed; this.controls = controls; this.events = events; this.endpoints = endpoints;
    }
    @Transactional public void clear() {
        attempts.deleteAllInBatch(); processed.deleteAllInBatch(); controls.deleteAllInBatch();
        events.deleteAllInBatch(); endpoints.deleteAllInBatch();
    }
    @Transactional public void makeDue(String id) {
        events.findById(id).orElseThrow().setNextAttemptAt(Instant.now().minusSeconds(1));
    }
}
