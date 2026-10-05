package io.replaydock.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "events", uniqueConstraints = @UniqueConstraint(name = "unique_source_event", columnNames = {"endpoint_id", "source_id"}), indexes = @Index(name = "due_events", columnList = "status,next_attempt_at"))
public class WebhookEvent extends AssignedEntity<String> {
    @Id @Column(name = "id", nullable = false, length = 36)
    private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "endpoint_id", nullable = false)
    private WebhookEndpoint endpoint;
    @Column(name = "source_id", nullable = false, length = 128)
    private String sourceId;
    @Lob @Column(name = "payload", nullable = false)
    private String payload;
    @Column(name = "status", nullable = false, length = 20)
    private String status;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WebhookEvent() {}
    public WebhookEvent(String id, WebhookEndpoint endpoint, String sourceId, String payload, String status, int attemptCount, Instant nextAttemptAt, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.endpoint = endpoint;
        this.sourceId = sourceId;
        this.payload = payload;
        this.status = status;
        this.attemptCount = attemptCount;
        this.nextAttemptAt = nextAttemptAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public WebhookEndpoint getEndpoint() { return endpoint; }
    public void setEndpoint(WebhookEndpoint endpoint) { this.endpoint = endpoint; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
