package io.replaydock.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class ProcessedEventId implements Serializable {
    @Column(name = "endpoint_id", length = 36, nullable = false)
    private String endpointId;
    @Column(name = "source_id", length = 128, nullable = false)
    private String sourceId;
    protected ProcessedEventId() {}
    public ProcessedEventId(String endpointId, String sourceId) { this.endpointId = endpointId; this.sourceId = sourceId; }
    public String getEndpointId() { return endpointId; }
    public String getSourceId() { return sourceId; }
    @Override public boolean equals(Object other) {
        return other instanceof ProcessedEventId id && Objects.equals(endpointId, id.endpointId) && Objects.equals(sourceId, id.sourceId);
    }
    @Override public int hashCode() { return Objects.hash(endpointId, sourceId); }
}
