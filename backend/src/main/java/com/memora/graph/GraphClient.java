package com.memora.graph;

import com.falkordb.Driver;
import com.falkordb.FalkorDB;
import com.falkordb.Record;
import com.falkordb.ResultSet;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper over JFalkorDB. Every statement is a constant Cypher string with parameters (user input
 * is only ever passed as a parameter value, never concatenated into Cypher). Reads go through
 * {@code GRAPH.RO_QUERY}. Each statement has a name for its Micrometer timer ({@code memora.graph.query}).
 */
@Component
public class GraphClient {

    private final Driver driver;
    private final GraphProperties properties;
    private final MeterRegistry meters;

    public GraphClient(GraphProperties properties, MeterRegistry meters) {
        this.properties = properties;
        this.meters = meters;
        this.driver = properties.password() == null
                ? FalkorDB.driver(properties.host(), properties.port())
                : FalkorDB.driver(properties.host(), properties.port(), "default", properties.password());
    }

    /** A row as column name to value (nodes, edges and paths are not returned; queries project scalars and lists). */
    public List<Map<String, Object>> read(String graph, String name, String cypher, Map<String, Object> params) {
        return run(graph, name, cypher, params, true);
    }

    public List<Map<String, Object>> write(String graph, String name, String cypher, Map<String, Object> params) {
        return run(graph, name, cypher, params, false);
    }

    private List<Map<String, Object>> run(String graph, String name, String cypher, Map<String, Object> params, boolean readOnly) {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "ok";
        try {
            var g = driver.graph(graph);
            ResultSet rs = readOnly ? g.readOnlyQuery(cypher, params, properties.queryTimeoutMs())
                    : g.query(cypher, params, properties.queryTimeoutMs());
            List<Map<String, Object>> rows = new ArrayList<>(rs.size());
            for (Record r : rs) {
                Map<String, Object> row = new LinkedHashMap<>();
                List<String> keys = r.keys();
                for (int i = 0; i < keys.size(); i++) {
                    row.put(keys.get(i), r.getValue(i));
                }
                rows.add(row);
            }
            return rows;
        } catch (RuntimeException e) {
            outcome = "error";
            throw new GraphException("Graph query '" + name + "' failed: " + e.getMessage(), e);
        } finally {
            sample.stop(meters.timer("memora.graph.query", "query", name, "outcome", outcome));
        }
    }

    public void deleteGraph(String graph) {
        try {
            driver.graph(graph).deleteGraph();
        } catch (RuntimeException e) {
            if (!String.valueOf(e.getMessage()).toLowerCase().contains("empty key") && !String.valueOf(e.getMessage()).contains("Invalid graph")) {
                throw new GraphException("Could not delete graph " + graph + ": " + e.getMessage(), e);
            }
        }
    }

    public List<String> listGraphs() {
        return driver.listGraphs();
    }

    public boolean ping() {
        try (var jedis = driver.getConnection()) {
            return "PONG".equalsIgnoreCase(jedis.ping());
        }
    }

    @PreDestroy
    void close() throws java.io.IOException {
        driver.close();
    }

    public static class GraphException extends RuntimeException {
        public GraphException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
