package io.replaydock;

import java.time.Instant;

public final class Models {
    private Models() {}
    public record Endpoint(String id, String name, String targetUrl, int maxAttempts,
                           long baseDelayMs, Instant createdAt) {}
    public record NewEndpoint(String name, String targetUrl, Integer maxAttempts, Long baseDelayMs) {}
    public record CreatedEndpoint(Endpoint endpoint, String signingSecret) {}
    public record Event(String id, String endpointId, String sourceId, String payload, String status,
                        int attemptCount, Instant nextAttemptAt, Instant createdAt, Instant updatedAt) {}
    public record Attempt(String id, String eventId, int attemptNumber, Integer httpStatus,
                          String outcome, String detail, long durationMs, Instant createdAt) {}
    public record Receipt(String eventId, boolean duplicate, String status) {}
    public record DemoEvent(String sourceId, String payload) {}
    public record MockConfig(int failuresLeft, int failureStatus, int delayMs) {}
    public record MockState(int failuresLeft, int failureStatus, int delayMs, long processedCount) {}
    public record MockResult(int status, String message) {}
    public record DeliveryResult(Integer httpStatus, String detail, long durationMs) {}
}
