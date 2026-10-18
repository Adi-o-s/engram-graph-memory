package com.memora.orchestration.tool.builtin;

import static com.memora.orchestration.tool.builtin.Schemas.any;
import static com.memora.orchestration.tool.builtin.Schemas.anyArray;
import static com.memora.orchestration.tool.builtin.Schemas.anyObject;
import static com.memora.orchestration.tool.builtin.Schemas.enumOf;
import static com.memora.orchestration.tool.builtin.Schemas.object;
import static com.memora.orchestration.tool.builtin.Schemas.req;
import static com.memora.orchestration.tool.builtin.Schemas.string;

import com.memora.common.Json;
import com.memora.orchestration.expr.Conditions;
import com.memora.orchestration.expr.Expressions;
import com.memora.orchestration.expr.MiniSchema;
import com.memora.orchestration.run.ArtifactService;
import com.memora.orchestration.run.WorkflowArtifact;
import com.memora.orchestration.tool.Tool;
import com.memora.orchestration.tool.ToolContext;
import com.memora.orchestration.tool.ToolDescriptor;
import com.memora.orchestration.tool.ToolException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Composing documents from structured data, and verifying outputs against explicit checks. */
public final class DocumentTools {

    private static final Pattern MUSTACHE = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");

    private DocumentTools() {
    }

    /** {@code {{field}}} or {@code {{item.field}}} against one list item. */
    @SuppressWarnings("unchecked")
    static String fill(String template, Object item) {
        Map<String, Object> scope = new LinkedHashMap<>();
        if (item instanceof Map<?, ?> m) {
            scope.putAll((Map<String, Object>) m);
        }
        scope.put("item", item);
        Matcher matcher = MUSTACHE.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(Expressions.toText(Expressions.lookup(scope, matcher.group(1)))));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    static String describe(Object item) {
        if (item instanceof Map<?, ?> m) {
            Object title = m.get("title") != null ? m.get("title") : m.get("name") != null ? m.get("name") : m.get("text");
            if (title != null) {
                return String.valueOf(title);
            }
            List<String> parts = new ArrayList<>();
            ((Map<String, Object>) m).forEach((k, v) -> parts.add(k + ": " + Expressions.toText(v)));
            return String.join("; ", parts);
        }
        return Expressions.toText(item);
    }

    @Component
    public static class ComposeTool implements Tool {

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor("document.compose", "Compose document",
                    "Builds a Markdown document from a title and sections. A section can have body text, a bullet or numbered "
                            + "list rendered from items with an {{field}} template, and a table. Deterministic formatting only.",
                    "documents", "document", false, false, 2000,
                    object(req("title", "sections"), "title", string("Document title", 300), "intro", string("Opening paragraph", 20000),
                            "sections", anyArray("{heading, body, items, itemTemplate, numbered, table{rows, columns[{header, field}]}, emptyText}", 60),
                            "footer", string("Closing note", 4000),
                            "style", enumOf("markdown (default) or plain: headings become comment lines, lists plain lines",
                                    "markdown", "plain"),
                            "commentPrefix", string("Heading prefix for plain style, e.g. -- for SQL", 5)),
                    object(req("markdown")), List.of("Pure function", "No free-form generation"), "Never fails for valid input", true, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> invoke(ToolContext ctx, Map<String, Object> args) {
            if ("plain".equals(args.get("style"))) {
                return plain(args);
            }
            StringBuilder md = new StringBuilder("# ").append(args.get("title")).append("\n\n");
            if (args.get("intro") != null && !String.valueOf(args.get("intro")).isBlank()) {
                md.append(args.get("intro")).append("\n\n");
            }
            int sectionCount = 0;
            for (Object o : (Collection<Object>) args.get("sections")) {
                if (!(o instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> s = (Map<String, Object>) raw;
                sectionCount++;
                md.append("## ").append(s.getOrDefault("heading", "Section " + sectionCount)).append("\n\n");
                if (s.get("body") != null && !String.valueOf(s.get("body")).isBlank()) {
                    md.append(s.get("body")).append("\n\n");
                }
                if (s.containsKey("items")) {
                    List<Object> items = s.get("items") instanceof Collection<?> c ? new ArrayList<>(c)
                            : s.get("items") == null ? List.of() : List.of(s.get("items"));
                    if (items.isEmpty()) {
                        md.append("_").append(s.getOrDefault("emptyText", "None.")).append("_\n\n");
                    } else {
                        boolean numbered = Boolean.TRUE.equals(s.get("numbered"));
                        String template = s.get("itemTemplate") == null ? null : String.valueOf(s.get("itemTemplate"));
                        int n = 1;
                        for (Object item : items) {
                            String line = template == null ? describe(item) : fill(template, item);
                            md.append(numbered ? (n++) + ". " : "- ").append(line.replace("\n", "\n  ")).append('\n');
                        }
                        md.append('\n');
                    }
                }
                if (s.get("table") instanceof Map<?, ?> table) {
                    renderTable(md, (Map<String, Object>) table);
                }
            }
            if (args.get("footer") != null) {
                md.append("---\n\n").append(args.get("footer")).append('\n');
            }
            String markdown = md.toString();
            return Json.map("markdown", markdown, "sections", sectionCount, "wordCount", markdown.split("\\s+").length,
                    "chars", markdown.length());
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> plain(Map<String, Object> args) {
            String c = args.get("commentPrefix") == null ? "#" : String.valueOf(args.get("commentPrefix"));
            StringBuilder out = new StringBuilder(c).append(' ').append(args.get("title")).append("\n");
            if (args.get("intro") != null) {
                for (String line : String.valueOf(args.get("intro")).split("\n")) {
                    out.append(c).append(' ').append(line).append('\n');
                }
            }
            for (Object o : (Collection<Object>) args.get("sections")) {
                if (!(o instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> s = (Map<String, Object>) raw;
                out.append('\n').append(c).append(' ').append(s.getOrDefault("heading", "")).append('\n');
                if (s.get("body") != null) {
                    out.append(s.get("body")).append('\n');
                }
                if (s.get("items") instanceof Collection<?> items) {
                    String template = s.get("itemTemplate") == null ? null : String.valueOf(s.get("itemTemplate"));
                    for (Object item : items) {
                        out.append(template == null ? describe(item) : fill(template, item)).append('\n');
                    }
                }
            }
            String text = out.toString();
            return Json.map("markdown", text, "text", text, "chars", text.length());
        }

        @SuppressWarnings("unchecked")
        private static void renderTable(StringBuilder md, Map<String, Object> table) {
            List<Map<String, Object>> columns = table.get("columns") instanceof List<?> c ? (List<Map<String, Object>>) c : List.of();
            List<Object> rows = table.get("rows") instanceof Collection<?> r ? new ArrayList<>(r) : List.of();
            if (columns.isEmpty()) {
                return;
            }
            md.append('|');
            columns.forEach(c -> md.append(' ').append(c.getOrDefault("header", c.get("field"))).append(" |"));
            md.append("\n|");
            columns.forEach(c -> md.append(" --- |"));
            md.append('\n');
            for (Object row : rows) {
                md.append('|');
                for (Map<String, Object> c : columns) {
                    Object v = row instanceof Map<?, ?> m ? Expressions.lookup((Map<String, Object>) m, String.valueOf(c.get("field"))) : row;
                    md.append(' ').append(Expressions.toText(v).replace("|", "\\|").replace("\n", " ")).append(" |");
                }
                md.append('\n');
            }
            if (rows.isEmpty()) {
                md.append("| _none_ |").append(" |".repeat(Math.max(0, columns.size() - 1))).append('\n');
            }
            md.append('\n');
        }
    }

    @Component
    public static class ChecksTool implements Tool {

        private final ArtifactService artifacts;

        public ChecksTool(ArtifactService artifacts) {
            this.artifacts = artifacts;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor("checks.evaluate", "Evaluate checks",
                    "Verifies actual outputs instead of trusting status labels: artifacts exist and contain required text, every "
                            + "requirement id is covered by named artifacts, values satisfy conditions. Returns pass/fail per check.",
                    "verification", "verify", false, false, 5000,
                    object(req("checks"), "checks", anyArray("Checks: {name, type: artifactExists|artifactContains|artifactLacks|coverage|"
                            + "condition|nonEmpty|equals|count, severity: error|warning, ...}", 100)),
                    object(req("passed", "results")), List.of("Reads artifacts of the current run only"),
                    "Permanent failure for an unknown check type", true, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> invoke(ToolContext ctx, Map<String, Object> args) {
            List<Map<String, Object>> results = new ArrayList<>();
            Set<String> missing = new LinkedHashSet<>();
            boolean passed = true;
            int passedCount = 0;
            for (Object o : (Collection<Object>) args.get("checks")) {
                Map<String, Object> c = (Map<String, Object>) o;
                String name = String.valueOf(c.getOrDefault("name", c.get("type")));
                String type = String.valueOf(c.get("type"));
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("name", name);
                r.put("type", type);
                boolean ok;
                String detail;
                switch (type) {
                    case "artifactExists" -> {
                        Optional<WorkflowArtifact> a = artifact(ctx, c);
                        ok = a.isPresent();
                        detail = a.map(x -> x.getName() + " v" + x.getVersion() + " sha256 " + x.getChecksum().substring(0, 12))
                                .orElse("artifact " + c.get("artifact") + " not found");
                    }
                    case "artifactContains" -> {
                        Optional<WorkflowArtifact> a = artifact(ctx, c);
                        List<String> needles = new ArrayList<>();
                        if (c.get("text") != null) {
                            needles.add(String.valueOf(c.get("text")));
                        }
                        if (c.get("allOf") instanceof Collection<?> all) {
                            all.forEach(x -> needles.add(String.valueOf(x)));
                        }
                        List<String> absent = a.isEmpty() ? needles : needles.stream()
                                .filter(n -> !a.get().getContent().toLowerCase().contains(n.toLowerCase())).toList();
                        ok = a.isPresent() && absent.isEmpty();
                        detail = a.isEmpty() ? "artifact " + c.get("artifact") + " not found"
                                : absent.isEmpty() ? "contains all " + needles.size() + " expected text(s)" : "missing: " + absent;
                    }
                    case "artifactLacks" -> {
                        Optional<WorkflowArtifact> a = artifact(ctx, c);
                        List<String> forbidden = new ArrayList<>();
                        if (c.get("text") != null) {
                            forbidden.add(String.valueOf(c.get("text")));
                        }
                        if (c.get("anyOf") instanceof Collection<?> any) {
                            any.forEach(x -> forbidden.add(String.valueOf(x)));
                        }
                        List<String> present = a.isEmpty() ? List.of() : forbidden.stream()
                                .filter(n -> a.get().getContent().toLowerCase().contains(n.toLowerCase())).toList();
                        ok = a.isPresent() && present.isEmpty();
                        detail = a.isEmpty() ? "artifact " + c.get("artifact") + " not found"
                                : present.isEmpty() ? "none of " + forbidden.size() + " forbidden text(s) present" : "found: " + present;
                    }
                    case "coverage" -> {
                        List<String> ids = ids(c.get("ids"), c.get("idField") == null ? "id" : String.valueOf(c.get("idField")));
                        StringBuilder corpus = new StringBuilder();
                        List<String> absentArtifacts = new ArrayList<>();
                        for (Object n : c.get("artifacts") instanceof Collection<?> names ? names : List.of()) {
                            Optional<WorkflowArtifact> a = artifacts.latest(ctx.tenantId(), ctx.runId(), String.valueOf(n));
                            if (a.isPresent()) {
                                corpus.append(a.get().getContent()).append('\n');
                            } else {
                                absentArtifacts.add(String.valueOf(n));
                            }
                        }
                        List<String> uncovered = ids.stream()
                                .filter(id -> !Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(id) + "(?![A-Za-z0-9])")
                                        .matcher(corpus).find()).toList();
                        missing.addAll(uncovered);
                        ok = uncovered.isEmpty() && !ids.isEmpty();
                        detail = ids.isEmpty() ? "no ids to check" : (ids.size() - uncovered.size()) + "/" + ids.size() + " covered"
                                + (uncovered.isEmpty() ? "" : "; not covered: " + uncovered)
                                + (absentArtifacts.isEmpty() ? "" : "; artifacts not found: " + absentArtifacts);
                        r.put("missing", uncovered);
                    }
                    case "condition" -> {
                        Conditions.Evaluation ev;
                        try {
                            ev = Conditions.explain((Map<String, Object>) c.get("condition"), Map.of("value", c.getOrDefault("value", Map.of())));
                        } catch (RuntimeException e) {
                            throw ToolException.permanent("Check " + name + ": invalid condition: " + e.getMessage());
                        }
                        ok = ev.result();
                        detail = String.join("; ", ev.trace());
                    }
                    case "nonEmpty" -> {
                        Object v = c.get("value");
                        ok = v != null && !(v instanceof Collection<?> l && l.isEmpty()) && !(v instanceof Map<?, ?> m && m.isEmpty())
                                && !String.valueOf(v).isBlank();
                        detail = ok ? "present" : "empty";
                    }
                    case "equals" -> {
                        ok = Conditions.evaluate(Map.of("path", "a", "op", "eq", "value", c.getOrDefault("expected", "")),
                                Json.map("a", c.get("actual")));
                        detail = "actual " + c.get("actual") + ", expected " + c.get("expected");
                    }
                    case "count" -> {
                        int size = c.get("value") instanceof Collection<?> l ? l.size() : 0;
                        int min = c.get("min") instanceof Number n ? n.intValue() : 0;
                        int max = c.get("max") instanceof Number n ? n.intValue() : Integer.MAX_VALUE;
                        ok = size >= min && size <= max;
                        detail = size + " item(s), expected " + min + (max == Integer.MAX_VALUE ? "+" : "-" + max);
                    }
                    default -> throw ToolException.permanent("Unknown check type '" + type + "'");
                }
                String severity = String.valueOf(c.getOrDefault("severity", "error"));
                r.put("severity", severity);
                r.put("passed", ok);
                r.put("detail", detail);
                results.add(r);
                if (ok) {
                    passedCount++;
                } else if (!"warning".equals(severity)) {
                    passed = false;
                }
            }
            List<String> failures = results.stream().filter(r -> !Boolean.TRUE.equals(r.get("passed")))
                    .map(r -> String.valueOf(r.get("name"))).toList();
            return Json.map("passed", passed, "results", results, "passedCount", passedCount,
                    "failedCount", results.size() - passedCount, "total", results.size(), "failures", failures,
                    "missing", new ArrayList<>(missing),
                    "summary", passedCount + "/" + results.size() + " checks passed" + (passed ? "" : "; failing: " + failures));
        }

        private Optional<WorkflowArtifact> artifact(ToolContext ctx, Map<String, Object> c) {
            return artifacts.latest(ctx.tenantId(), ctx.runId(), String.valueOf(c.get("artifact")));
        }

        @SuppressWarnings("unchecked")
        private static List<String> ids(Object value, String field) {
            List<String> out = new ArrayList<>();
            if (value instanceof Collection<?> c) {
                for (Object o : c) {
                    if (o instanceof Map<?, ?> m) {
                        Object id = ((Map<String, Object>) m).get(field);
                        if (id != null) {
                            out.add(String.valueOf(id));
                        }
                    } else if (o != null) {
                        out.add(String.valueOf(o));
                    }
                }
            }
            return out;
        }
    }

    @Component
    public static class SchemaValidateTool implements Tool {

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor("schema.validate", "Validate against a schema",
                    "Validates a value against a JSON-schema subset (type, required, properties, items, enum, min/max).",
                    "verification", "verify", false, false, 2000,
                    object(req("schema"), "schema", anyObject("The schema"), "value", any("The value")),
                    object(req("valid", "errors")), List.of("Pure function"), "Permanent failure for an unsupported schema", true, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> invoke(ToolContext ctx, Map<String, Object> args) {
            Map<String, Object> schema = (Map<String, Object>) args.get("schema");
            List<String> schemaErrors = MiniSchema.checkSchema(schema, "schema");
            if (!schemaErrors.isEmpty()) {
                throw ToolException.permanent(String.join("; ", schemaErrors));
            }
            List<String> errors = MiniSchema.validate(schema, args.get("value"));
            return Json.map("valid", errors.isEmpty(), "errors", errors);
        }
    }
}
