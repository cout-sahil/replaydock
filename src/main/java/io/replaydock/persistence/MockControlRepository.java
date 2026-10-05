package io.replaydock.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface MockControlRepository extends JpaRepository<MockControl, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from MockControl c where c.endpointId = :id")
    Optional<MockControl> lockById(@Param("id") String id);
}
