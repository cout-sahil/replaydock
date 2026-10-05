package io.replaydock.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "mock_processed")
public class ProcessedEvent extends AssignedEntity<ProcessedEventId> {
    @EmbeddedId
    private ProcessedEventId id;
    @MapsId("endpointId") @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "endpoint_id", nullable = false)
    private WebhookEndpoint endpoint;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {}
    public ProcessedEvent(ProcessedEventId id, WebhookEndpoint endpoint, Instant processedAt) {
        this.id = id;
        this.endpoint = endpoint;
        this.processedAt = processedAt;
    }
    public ProcessedEventId getId() { return id; }
    public void setId(ProcessedEventId id) { this.id = id; }
    public WebhookEndpoint getEndpoint() { return endpoint; }
    public void setEndpoint(WebhookEndpoint endpoint) { this.endpoint = endpoint; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
