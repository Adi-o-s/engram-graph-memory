package com.memora.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MemorySearchQueryTest {

    @Test
    void userInputCannotInjectTsqueryOperators() {
        assertThat(MemorySearchService.toTsQuery("postgres & !secret | (x) <-> y", false))
                .isEqualTo("postgres:* | secret:* | x:* | y:*");
    }

    @Test
    void matchAllUsesAnd() {
        assertThat(MemorySearchService.toTsQuery("health database", true)).isEqualTo("health:* & database:*");
    }

    @Test
    void blankQueryProducesEmptyTsquery() {
        assertThat(MemorySearchService.toTsQuery("  ", false)).isEmpty();
        assertThat(MemorySearchService.toTsQuery("!!!", false)).isEmpty();
    }

    @Test
    void factKeysAreCaseAndWhitespaceInsensitive() {
        assertThat(MemoryService.factKey(" Project ", "Uses.Database")).isEqualTo(MemoryService.factKey("project", "uses.database"));
    }
}
