package io.replaydock.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface EndpointRepository extends JpaRepository<WebhookEndpoint, String> {
    List<WebhookEndpoint> findAllByOrderByCreatedAtDesc();
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from WebhookEndpoint e where e.id = :id")
    Optional<WebhookEndpoint> lockById(@Param("id") String id);
}
