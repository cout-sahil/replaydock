package io.replaydock.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "mock_controls")
public class MockControl extends AssignedEntity<String> {
    @Id @Column(name = "endpoint_id", nullable = false, length = 36)
    private String endpointId;
    @MapsId @OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "endpoint_id", nullable = false)
    private WebhookEndpoint endpoint;
    @Column(name = "failures_left", nullable = false)
    private int failuresLeft;
    @Column(name = "failure_status", nullable = false)
    private int failureStatus;
    @Column(name = "delay_ms", nullable = false)
    private int delayMs;

    protected MockControl() {}
    public MockControl(String endpointId, WebhookEndpoint endpoint, int failuresLeft, int failureStatus, int delayMs) {
        this.endpointId = endpointId;
        this.endpoint = endpoint;
        this.failuresLeft = failuresLeft;
        this.failureStatus = failureStatus;
        this.delayMs = delayMs;
    }
    @Override public String getId() { return endpointId; }
    public String getEndpointId() { return endpointId; }
    public void setEndpointId(String endpointId) { this.endpointId = endpointId; }
    public WebhookEndpoint getEndpoint() { return endpoint; }
    public void setEndpoint(WebhookEndpoint endpoint) { this.endpoint = endpoint; }
    public int getFailuresLeft() { return failuresLeft; }
    public void setFailuresLeft(int failuresLeft) { this.failuresLeft = failuresLeft; }
    public int getFailureStatus() { return failureStatus; }
    public void setFailureStatus(int failureStatus) { this.failureStatus = failureStatus; }
    public int getDelayMs() { return delayMs; }
    public void setDelayMs(int delayMs) { this.delayMs = delayMs; }
}
