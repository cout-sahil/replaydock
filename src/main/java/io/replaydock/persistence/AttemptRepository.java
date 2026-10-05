package io.replaydock.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface AttemptRepository extends JpaRepository<DeliveryAttempt, String> {
    List<DeliveryAttempt> findByEvent_IdOrderByCreatedAtAscIdAsc(String eventId);
}
