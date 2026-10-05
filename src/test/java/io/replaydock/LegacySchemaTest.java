package io.replaydock;

import static org.assertj.core.api.Assertions.*;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Raw SQL is intentional here: fixture rows mimic the original JDBC application's database. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:legacy-tests;DB_CLOSE_DELAY=-1", "replaydock.worker-enabled=false", "replaydock.admin-password=test-password"})
class LegacySchemaTest {
    @Autowired JdbcTemplate legacy;
    @Autowired DockService dock;
    @Test void readsAndReplaysExistingRowsWithoutSchemaChanges() {
        Instant created = Instant.parse("2026-10-04T12:34:56Z");
        Timestamp stamp = Timestamp.from(created);
        legacy.update("INSERT INTO endpoints VALUES(?,?,?,?,?,?,?)", "legacy-endpoint", "Existing endpoint", null, "existing-secret", 5, 1000, stamp);
        legacy.update("INSERT INTO events VALUES(?,?,?,?,?,?,?,?,?)", "legacy-event", "legacy-endpoint", "legacy-source", "{\"original\":true}", "DELIVERED", 1, stamp, stamp, stamp);
        legacy.update("INSERT INTO attempts VALUES(?,?,?,?,?,?,?,?)", "legacy-attempt", "legacy-event", 1, 200, "SUCCESS", "Original delivery", 12, stamp);
        legacy.update("INSERT INTO mock_controls VALUES(?,?,?,?)", "legacy-endpoint", 0, 503, 0);
        legacy.update("INSERT INTO mock_processed VALUES(?,?,?)", "legacy-endpoint", "legacy-source", stamp);
        assertThat(dock.endpoint("legacy-endpoint").createdAt()).isEqualTo(created);
        assertThat(dock.secret("legacy-endpoint")).isEqualTo("existing-secret");
        assertThat(dock.event("legacy-event").payload()).isEqualTo("{\"original\":true}");
        assertThat(dock.event("legacy-event").createdAt()).isEqualTo(created);
        assertThat(dock.attempts("legacy-event")).hasSize(1);
        assertThat(dock.accept("legacy-endpoint", "legacy-source", "{\"original\":true}").duplicate()).isTrue();
        dock.replay("legacy-event");
        assertThat(dock.event("legacy-event").status()).isEqualTo("PENDING");
        assertThat(dock.processMock("legacy-endpoint", "legacy-source").message()).contains("Duplicate");
        assertThat(dock.mockState("legacy-endpoint").processedCount()).isEqualTo(1);
    }
}
