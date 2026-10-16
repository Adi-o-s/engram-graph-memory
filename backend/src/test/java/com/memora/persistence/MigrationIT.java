package com.memora.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.memora.support.IntegrationTest;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The test context cleans the schema and migrates from empty on startup (see TestFlywayConfig),
 * and Hibernate validates the entities against the result. This checks the outcome explicitly.
 */
class MigrationIT extends IntegrationTest {

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void allMigrationsApplyCleanlyFromAnEmptyDatabase() {
        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied).isNotEmpty();
        assertThat(applied).allMatch(m -> m.getState() == MigrationState.SUCCESS);
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(Arrays.stream(applied).map(m -> m.getVersion().getVersion()).toList()).containsExactly("1", "2", "3");
        flyway.validate();
    }

    @Test
    void everyTenantOwnedTableHasForcedRowLevelSecurity() {
        List<String> tables = List.of("memberships", "projects", "agents", "conversations", "messages", "tasks",
                "task_events", "memories", "handoffs", "audit_events", "lab_runs");
        for (String table : tables) {
            Boolean forced = jdbc.queryForObject(
                    "SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE relname = ? AND relkind = 'r'",
                    Boolean.class, table);
            assertThat(forced).as(table).isTrue();
        }
    }

    @Test
    void searchAndTenantIndexesExist() {
        List<String> indexes = jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'memories'", String.class);
        assertThat(indexes).contains("idx_memories_search", "idx_memories_search_text_trgm", "uq_memories_current_fact",
                "idx_memories_tenant_type_status");
    }
}
