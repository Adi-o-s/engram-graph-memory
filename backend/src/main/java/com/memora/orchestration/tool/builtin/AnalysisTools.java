package com.memora.orchestration.tool.builtin;

import static com.memora.orchestration.tool.builtin.Schemas.anyArray;
import static com.memora.orchestration.tool.builtin.Schemas.anyObject;
import static com.memora.orchestration.tool.builtin.Schemas.array;
import static com.memora.orchestration.tool.builtin.Schemas.integer;
import static com.memora.orchestration.tool.builtin.Schemas.object;
import static com.memora.orchestration.tool.builtin.Schemas.req;
import static com.memora.orchestration.tool.builtin.Schemas.string;

import com.memora.common.Json;
import com.memora.orchestration.expr.Conditions;
import com.memora.orchestration.planning.RequirementExtractor;
import com.memora.orchestration.tool.Tool;
import com.memora.orchestration.tool.ToolContext;
import com.memora.orchestration.tool.ToolDescriptor;
import com.memora.orchestration.tool.ToolException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/** Deterministic text and data analysis. Pure functions of their arguments: no reads, no writes. */
public final class AnalysisTools {

    private AnalysisTools() {
    }

    @Component
    public static class ExtractRequirementsTool implements Tool {

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor("text.extract_requirements", "Extract requirements",
                    "Splits a goal or brief into numbered requirements with a category, priority and template acceptance "
                            + "criteria, and lists what is unclear or missing. Pattern matching, not language understanding.",
                    "analysis", "analysis", false, false, 2000,
                    object(req("text"), "text", string("The goal or brief", 20000), "maxItems", integer("Upper bound", 1, 60)),
                    object(req("requirements", "count")), List.of("Pure function"), "Never fails; may return zero requirements", true, null);
        }

        @Override
        public Map<String, Object> invoke(ToolContext ctx, Map<String, Object> args) {
            int max = args.get("maxItems") instanceof Number n ? n.intValue() : 30;
            return RequirementExtractor.extract(String.valueOf(args.get("text")), max).toMap();
        }
    }

    @Component
    public static class CatalogMatchTool implements Tool {

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor("catalog.match", "Match items to a catalog",
                    "Maps each item (e.g. a requirement) to catalog entries whose keywords appear in its text, and reports "
                            + "items that match nothing. Agents use their own catalogs (API endpoints, screens, report sections...) "
                            + "to turn requirements into structured designs.",
                    "analysis", "analysis", false, false, 2000,
                    object(req("items", "catalog"), "items", anyArray("Objects to classify", 200),
                            "catalog", anyArray("Entries: {key, title, keywords[], produce{}}", 200),
                            "textFields", array("Item fields to read (default text, feature)", Map.of("type", "string"), 5),
                            "idField", string("Item id field (default id)", 40)),
                    object(req("matches", "entries", "unmatched")), List.of("Pure function"),
                    "Permanent failure for malformed catalog entries", true, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> invoke(ToolContext ctx, Map<String, Object> args) {
            List<String> fields = args.get("textFields") instanceof List<?> f ? (List<String>) f : List.of("text", "feature");
            String idField = args.get("idField") == null ? "id" : String.valueOf(args.get("idField"));
            List<Map<String, Object>> catalog = new ArrayList<>();
            for (Object o : (Collection<Object>) args.get("catalog")) {
                if (!(o instanceof Map<?, ?> e) || e.get("key") == null || !(e.get("keywords") instanceof Collection<?>)) {
                    throw ToolException.permanent("Each catalog entry needs a key and a keywords list");
                }
                catalog.add((Map<String, Object>) e);
            }
            Map<String, List<String>> coveredBy = new LinkedHashMap<>();
            List<Map<String, Object>> matches = new ArrayList<>();
            List<Map<String, Object>> unmatched = new ArrayList<>();
            int index = 0;
            for (Object o : (Collection<Object>) args.get("items")) {
                index++;
                Map<String, Object> item = o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of("text", String.valueOf(o));
                String id = item.get(idField) == null ? "item" + index : String.valueOf(item.get(idField));
                StringBuilder text = new StringBuilder(" ");
                fields.forEach(f -> text.append(item.get(f) == null ? "" : item.get(f)).append(' '));
                String lower = text.toString().toLowerCase(Locale.ROOT);
                List<String> keys = new ArrayList<>();
                for (Map<String, Object> entry : catalog) {
                    for (Object kw : (Collection<Object>) entry.get("keywords")) {
                        if (RequirementExtractor.matchesKeyword(lower, String.valueOf(kw).toLowerCase(Locale.ROOT))) {
                            keys.add(String.valueOf(entry.get("key")));
                            coveredBy.computeIfAbsent(String.valueOf(entry.get("key")), k -> new ArrayList<>()).add(id);
                            break;
                        }
                    }
                }
                String display = String.valueOf(item.getOrDefault("feature", item.getOrDefault("text", "")));
                if (keys.isEmpty()) {
                    unmatched.add(Json.map("id", id, "text", display));
                } else {
                    matches.add(Json.map("id", id, "text", display, "entries", keys));
                }
            }
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Map<String, Object> entry : catalog) {
                List<String> items = coveredBy.get(String.valueOf(entry.get("key")));
                if (items == null) {
                    continue;
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("key", entry.get("key"));
                out.put("title", entry.getOrDefault("title", entry.get("key")));
                if (entry.get("produce") instanceof Map<?, ?> produce) {
                    produce.forEach((k, v) -> out.put(String.valueOf(k), v));
                }
                out.put("covers", items);
                entries.add(out);
            }
            return Json.map("matches", matches, "entries", entries, "unmatched", unmatched, "matchedCount", matches.size(),
                    "unmatchedCount", unmatched.size(), "entryCount", entries.size(),
                    "coveredIds", matches.stream().map(m -> m.get("id")).toList(),
                    "unmatchedIds", unmatched.stream().map(m -> m.get("id")).toList());
        }
    }

    @Component
    public static class KeywordsTool implements Tool {

        private static final Set<String> STOP = Set.of("the", "and", "for", "with", "that", "this", "from", "are", "was", "were",
                "will", "have", "has", "had", "not", "but", "you", "your", "our", "their", "they", "its", "into", "than",
                "then", "which", "what", "when", "where", "who", "how", "all", "any", "can", "could", "should", "would",
                "must", "may", "also", "more", "most", "such", "each", "other", "some", "about", "over", "only", "very",
                "there", "these", "those", "been", "being", "use", "used", "using", "one", "two", "per", "via");

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor("text.keywords", "Keyword frequency",
                    "Counts the most frequent meaningful words across texts (stop words removed). Useful to group notes into themes.",
                    "analysis", "analysis", false, false, 2000,
                    object(req("texts"), "texts", anyArray("Strings, or objects whose 'content' or 'text' is read", 500),
                            "top", integer("How many terms", 1, 100)),
                    object(req("keywords")), List.of("Pure function"), "Never fails", true, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> invoke(ToolContext ctx, Map<String, Object> args) {
            int top = args.get("top") instanceof Number n ? n.intValue() : 10;
            Map<String, Integer> counts = new HashMap<>();
            for (Object o : (Collection<Object>) args.get("texts")) {
                String text = o instanceof Map<?, ?> m ? String.valueOf(((Map<String, Object>) m).getOrDefault("content",
                        ((Map<String, Object>) m).getOrDefault("text", ""))) : String.valueOf(o);
                for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                    if (w.length() >= 3 && !STOP.contains(w) && !w.chars().allMatch(Character::isDigit)) {
                        counts.merge(w, 1, Integer::sum);
                    }
                }
            }
            List<Map<String, Object>> keywords = counts.entrySet().stream()
                    .sorted((a, b) -> b.getValue().equals(a.getValue()) ? a.getKey().compareTo(b.getKey()) : b.getValue() - a.getValue())
                    .limit(top).map(e -> Json.map("term", e.getKey(), "count", e.getValue())).toList();
            return Json.map("keywords", keywords, "terms", keywords.stream().map(k -> k.get("term")).toList());
        }
    }

    @Component
    public static class ParseCsvTool implements Tool {

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor("data.parse_csv", "Parse CSV",
                    "Parses CSV text (header row, quoted fields) into rows. Numeric cells become numbers.",
                    "data", "analysis", false, false, 3000,
                    object(req("content"), "content", string("CSV text", 200_000), "delimiter", string("Default ,", 1)),
                    object(req("columns", "rows", "rowCount")), List.of("At most 5000 rows"),
                    "Permanent failure for an empty input", true, null);
        }

        @Override
        public Map<String, Object> invoke(ToolContext ctx, Map<String, Object> args) {
            char delimiter = args.get("delimiter") == null ? ',' : String.valueOf(args.get("delimiter")).charAt(0);
            List<List<String>> records = parse(String.valueOf(args.get("content")), delimiter);
            if (records.isEmpty()) {
                throw ToolException.permanent("The CSV is empty");
            }
            List<String> columns = records.get(0).stream().map(String::trim).toList();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 1; i < records.size() && rows.size() < 5000; i++) {
                List<String> r = records.get(i);
                if (r.size() == 1 && r.get(0).isBlank()) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (int c = 0; c < columns.size(); c++) {
                    String v = c < r.size() ? r.get(c).trim() : "";
                    row.put(columns.get(c), number(v));
                }
                rows.add(row);
            }
            return Json.map("columns", columns, "rows", rows, "rowCount", rows.size());
        }

        private static Object number(String v) {
            if (v.matches("-?\\d+")) {
                try {
                    return Long.parseLong(v);
                } catch (NumberFormatException e) {
                    return v;
                }
            }
            if (v.matches("-?\\d*\\.\\d+")) {
                return Double.parseDouble(v);
            }
            return v;
        }

        static List<List<String>> parse(String text, char d) {
            List<List<String>> out = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (quoted) {
                    if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else if (ch == '"') {
                        quoted = false;
                    } else {
                        cell.append(ch);
                    }
                } else if (ch == '"') {
                    quoted = true;
                } else if (ch == d) {
                    row.add(cell.toString());
                    cell.setLength(0);
                } else if (ch == '\n') {
                    row.add(cell.toString().replace("\r", ""));
                    cell.setLength(0);
                    out.add(row);
                    row = new ArrayList<>();
                } else {
                    cell.append(ch);
                }
            }
            if (cell.length() > 0 || !row.isEmpty()) {
                row.add(cell.toString().replace("\r", ""));
                out.add(row);
            }
            return out;
        }
    }

    @Component
    public static class AggregateTool implements Tool {

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor("data.aggregate", "Aggregate rows",
                    "Groups rows by a field and computes count, sum, avg, min and max of numeric fields.",
                    "data", "analysis", false, false, 3000,
                    object(req("rows"), "rows", anyArray("Row objects", 5000), "groupBy", string("Field to group by", 100),
                            "metrics", anyArray("{field, op} with op count|sum|avg|min|max", 20)),
                    object(req("groups", "rowCount")), List.of("Pure function"), "Non-numeric values are ignored by numeric ops", true, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> invoke(ToolContext ctx, Map<String, Object> args) {
            String groupBy = args.get("groupBy") == null ? null : String.valueOf(args.get("groupBy"));
            List<Map<String, Object>> metrics = args.get("metrics") instanceof List<?> l ? (List<Map<String, Object>>) l
                    : List.of(Map.of("op", "count"));
            Map<String, List<Map<String, Object>>> groups = new TreeMap<>();
            List<Map<String, Object>> rows = (List<Map<String, Object>>) args.get("rows");
            for (Map<String, Object> row : rows) {
                String g = groupBy == null ? "all" : String.valueOf(row.get(groupBy));
                groups.computeIfAbsent(g, k -> new ArrayList<>()).add(row);
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map.Entry<String, List<Map<String, Object>>> e : groups.entrySet()) {
                Map<String, Object> values = new LinkedHashMap<>();
                for (Map<String, Object> m : metrics) {
                    String op = String.valueOf(m.getOrDefault("op", "count"));
                    String field = m.get("field") == null ? null : String.valueOf(m.get("field"));
                    List<Double> nums = new ArrayList<>();
                    for (Map<String, Object> r : e.getValue()) {
                        if (field != null && r.get(field) instanceof Number n) {
                            nums.add(n.doubleValue());
                        }
                    }
                    Object v = switch (op) {
                        case "count" -> field == null ? e.getValue().size() : nums.size();
                        case "sum" -> round(nums.stream().mapToDouble(Double::doubleValue).sum());
                        case "avg" -> nums.isEmpty() ? null : round(nums.stream().mapToDouble(Double::doubleValue).average().orElse(0));
                        case "min" -> nums.stream().mapToDouble(Double::doubleValue).min().stream().boxed().findFirst().orElse(null);
                        case "max" -> nums.stream().mapToDouble(Double::doubleValue).max().stream().boxed().findFirst().orElse(null);
                        default -> throw ToolException.permanent("Unknown metric op " + op);
                    };
                    values.put(field == null ? op : op + "(" + field + ")", v);
                }
                out.add(Json.map("group", e.getKey(), "count", e.getValue().size(), "metrics", values));
            }
            return Json.map("groups", out, "rowCount", rows.size(), "groupCount", out.size());
        }

        private static double round(double v) {
            return Math.round(v * 1000.0) / 1000.0;
        }
    }

    @Component
    public static class SelectTool implements Tool {

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor("data.select", "Filter and project rows",
                    "Keeps rows matching a condition (paths start with row., e.g. row.category) and optionally only some fields.",
                    "data", "analysis", false, false, 2000,
                    object(req("rows"), "rows", anyArray("Rows (objects)", 5000), "where", anyObject("Condition over row.*"),
                            "fields", array("Fields to keep", Map.of("type", "string"), 50),
                            "pluck", string("Also return this field of each kept row as 'values'", 100),
                            "format", string("Also return each kept row rendered with this {{field}} template as 'values'", 500)),
                    object(req("rows", "count")), List.of("Pure function"), "Permanent failure for a malformed condition", true, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> invoke(ToolContext ctx, Map<String, Object> args) {
            Map<String, Object> where = (Map<String, Object>) args.get("where");
            List<String> fields = args.get("fields") instanceof List<?> f ? (List<String>) f : null;
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : (Collection<Object>) args.get("rows")) {
                Map<String, Object> row = o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of("value", o);
                boolean keep;
                try {
                    keep = where == null || Conditions.evaluate(where, Map.of("row", row));
                } catch (RuntimeException e) {
                    throw ToolException.permanent("Invalid condition: " + e.getMessage());
                }
                if (keep) {
                    if (fields == null) {
                        out.add(row);
                    } else {
                        Map<String, Object> projected = new LinkedHashMap<>();
                        fields.forEach(f -> projected.put(f, row.get(f)));
                        out.add(projected);
                    }
                }
            }
            String pluck = args.get("pluck") == null ? null : String.valueOf(args.get("pluck"));
            List<Object> values = new ArrayList<>();
            if (pluck != null) {
                out.forEach(r -> values.add(r.get(pluck)));
            } else if (args.get("format") != null) {
                String format = String.valueOf(args.get("format"));
                out.forEach(r -> values.add(DocumentTools.fill(format, r)));
            }
            return Json.map("rows", out, "count", out.size(), "values", values);
        }
    }

    @Component
    public static class JoinTool implements Tool {

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor("data.join", "Join rows",
                    "Joins two lists of objects on a shared field (left join). Matched rows are merged; the right side's fields are "
                            + "prefixed. Reports left rows without a match.",
                    "data", "analysis", false, false, 2000,
                    object(req("left", "right", "on"), "left", anyArray("Left rows", 2000), "right", anyArray("Right rows", 2000),
                            "on", string("Field present in both", 100), "rightPrefix", string("Prefix for right fields (default right_)", 40)),
                    object(req("rows", "unmatchedLeft")), List.of("Pure function"), "Never fails", true, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> invoke(ToolContext ctx, Map<String, Object> args) {
            String on = String.valueOf(args.get("on"));
            String prefix = args.get("rightPrefix") == null ? "right_" : String.valueOf(args.get("rightPrefix"));
            Map<String, Map<String, Object>> right = new LinkedHashMap<>();
            for (Object o : (Collection<Object>) args.get("right")) {
                if (o instanceof Map<?, ?> m && m.get(on) != null) {
                    right.putIfAbsent(String.valueOf(m.get(on)), (Map<String, Object>) m);
                }
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            List<Map<String, Object>> unmatched = new ArrayList<>();
            for (Object o : (Collection<Object>) args.get("left")) {
                if (!(o instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> left = (Map<String, Object>) raw;
                Map<String, Object> match = right.get(String.valueOf(left.get(on)));
                Map<String, Object> merged = new LinkedHashMap<>(left);
                if (match == null) {
                    unmatched.add(left);
                } else {
                    match.forEach((k, v) -> merged.put(prefix + k, v));
                }
                merged.put("matched", match != null);
                rows.add(merged);
            }
            return Json.map("rows", rows, "count", rows.size(), "unmatchedLeft", unmatched, "unmatchedCount", unmatched.size());
        }
    }
}
