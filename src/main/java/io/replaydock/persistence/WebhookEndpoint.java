package io.replaydock.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "endpoints")
public class WebhookEndpoint extends AssignedEntity<String> {
    @Id @Column(name = "id", nullable = false, length = 36)
    private String id;
    @Column(name = "name", nullable = false, length = 80)
    private String name;
    @Column(name = "target_url", nullable = true, length = 2048)
    private String targetUrl;
    @Column(name = "secret", nullable = false, length = 64)
    private String secret;
    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;
    @Column(name = "base_delay_ms", nullable = false)
    private long baseDelayMs;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WebhookEndpoint() {}
    public WebhookEndpoint(String id, String name, String targetUrl, String secret, int maxAttempts, long baseDelayMs, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.targetUrl = targetUrl;
        this.secret = secret;
        this.maxAttempts = maxAttempts;
        this.baseDelayMs = baseDelayMs;
        this.createdAt = createdAt;
    }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTargetUrl() { return targetUrl; }
    public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public long getBaseDelayMs() { return baseDelayMs; }
    public void setBaseDelayMs(long baseDelayMs) { this.baseDelayMs = baseDelayMs; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
