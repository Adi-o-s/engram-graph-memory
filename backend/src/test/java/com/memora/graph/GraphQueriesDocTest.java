package com.memora.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** docs/GRAPH_QUERIES.md must contain every Cypher statement in the code, verbatim. */
class GraphQueriesDocTest {

    @Test
    void theQueryCatalogueIsAVerbatimCopyOfTheCode() throws Exception {
        String doc = Files.readString(Path.of("..", "docs", "GRAPH_QUERIES.md"));
        int n = 0;
        for (var cls : GraphQueryCatalog.queries().entrySet()) {
            for (var q : cls.getValue().entrySet()) {
                assertThat(doc).as("%s.%s is missing or out of date in docs/GRAPH_QUERIES.md", cls.getKey(), q.getKey())
                        .contains(q.getValue());
                n++;
            }
        }
        assertThat(n).isGreaterThan(30);
    }
}
