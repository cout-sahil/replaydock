package io.replaydock.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "attempts")
public class DeliveryAttempt {
    @Id @Column(name = "id", nullable = false, length = 36)
    private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "event_id", nullable = false)
    private WebhookEvent event;
    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;
    @Column(name = "http_status", nullable = true)
    private Integer httpStatus;
    @Column(name = "outcome", nullable = false, length = 30)
    private String outcome;
    @Column(name = "detail", nullable = false, length = 1024)
    private String detail;
    @Column(name = "duration_ms", nullable = false)
    private long durationMs;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DeliveryAttempt() {}
    public DeliveryAttempt(String id, WebhookEvent event, int attemptNumber, Integer httpStatus, String outcome, String detail, long durationMs, Instant createdAt) {
        this.id = id;
        this.event = event;
        this.attemptNumber = attemptNumber;
        this.httpStatus = httpStatus;
        this.outcome = outcome;
        this.detail = detail;
        this.durationMs = durationMs;
        this.createdAt = createdAt;
    }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public WebhookEvent getEvent() { return event; }
    public void setEvent(WebhookEvent event) { this.event = event; }
    public int getAttemptNumber() { return attemptNumber; }
    public void setAttemptNumber(int attemptNumber) { this.attemptNumber = attemptNumber; }
    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
