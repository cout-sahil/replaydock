package io.replaydock.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<WebhookEvent, String> {
    Optional<WebhookEvent> findByEndpoint_IdAndSourceId(String endpointId, String sourceId);
    List<WebhookEvent> findTop200ByOrderByCreatedAtDesc();
    List<WebhookEvent> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(String status, Instant now, Pageable page);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from WebhookEvent e where e.id = :id")
    Optional<WebhookEvent> lockById(@Param("id") String id);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update WebhookEvent e set e.status = 'DELIVERING', e.updatedAt = :now where e.id = :id and e.status = 'PENDING' and e.nextAttemptAt <= :now")
    int claim(@Param("id") String id, @Param("now") Instant now);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update WebhookEvent e set e.status = 'PENDING', e.nextAttemptAt = :now, e.updatedAt = :now where e.status = 'DELIVERING'")
    int recoverInterrupted(@Param("now") Instant now);
}
