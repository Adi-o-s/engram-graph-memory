package com.memora.orchestration.planning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a goal written in plain language into a numbered list of requirements, using sentence and list
 * splitting plus a keyword vocabulary. It is pattern matching, not language understanding: it finds the
 * features a person listed, labels each with a category, and attaches template acceptance criteria. What it
 * cannot interpret is reported as "unclear" instead of being guessed.
 */
public final class RequirementExtractor {

    private static final Pattern LEAD = Pattern.compile(
            "(?i)^\\s*(?:please\\s+)?(?:i\\s+(?:want|need)\\s+(?:you\\s+)?to\\s+|we\\s+(?:want|need)\\s+to\\s+)?"
                    + "(build|create|develop|make|design|write|produce|prepare|research|analy[sz]e|plan|launch|run|implement|set up|draft|compile|investigate|review|compare)\\s+"
                    + "(?:(?:a|an|the|our|my)\\s+)?(.+?)\\s+(with|that|which|including|featuring|covering|about|on|for|to)\\s+(.+)$");
    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*•]|\\d+[.)])\\s+(.+)$");
    private static final Pattern SPLIT = Pattern.compile("\\s*(?:,|;|\\band also\\b|\\bas well as\\b|\\bplus\\b|\\band\\b)\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern MODAL = Pattern.compile("(?i)\\b(must|should|shall|needs? to|has to|have to|required|requires)\\b");
    private static final Pattern SHOULD = Pattern.compile("(?i)\\b(should|nice to have|optional|ideally|if possible|could)\\b");
    private static final Set<String> FILLER = Set.of("a", "an", "the", "some", "also", "support", "for", "with", "including");
    private static final List<String> VAGUE = List.of("etc", "and more", "stuff", "things", "various", "other features", "and so on",
            "whatever", "something");

    /** Ordered: the first category whose keywords match wins. */
    static final Map<String, List<String>> CATEGORIES = new LinkedHashMap<>();

    static {
        CATEGORIES.put("authentication", List.of("auth", "login", "log in", "sign in", "sign-in", "signin", "sign up", "signup",
                "register", "registration", "account", "oauth", "sso", "2fa", "two-factor", "password", "permission", "role"));
        CATEGORIES.put("payments", List.of("checkout", "payment", "pay ", "stripe", "billing", "invoice", "subscription", "refund"));
        CATEGORIES.put("cart", List.of("cart", "basket", "wishlist"));
        CATEGORIES.put("orders", List.of("order", "fulfil", "shipping", "delivery", "tracking", "returns"));
        CATEGORIES.put("search", List.of("search", "filter", "find", "lookup", "browse"));
        CATEGORIES.put("catalog", List.of("product", "catalog", "catalogue", "inventory", "listing", "item"));
        CATEGORIES.put("persistence", List.of("database", "persistence", "persist", "storage", "postgres", "mysql", "data model",
                "schema", "store data"));
        CATEGORIES.put("testing", List.of("test", "qa", "quality", "coverage", "verification"));
        CATEGORIES.put("interface", List.of("responsive", "mobile", "ui", "ux", "design", "accessib", "layout", "theme",
                "screen", "page", "frontend", "front-end"));
        CATEGORIES.put("notifications", List.of("email", "notification", "sms", "alert", "reminder", "newsletter"));
        CATEGORIES.put("administration", List.of("admin", "back office", "back-office", "management", "manage", "moderation",
                "dashboard"));
        CATEGORIES.put("analytics", List.of("analytics", "metric", "kpi", "chart", "statistic", "trend", "dataset", "csv", "data analysis"));
        CATEGORIES.put("research", List.of("research", "compare", "comparison", "survey", "literature", "source", "evidence",
                "market", "competitor", "pros and cons", "trade-off", "tradeoff"));
        CATEGORIES.put("content", List.of("blog", "article", "copy", "content", "post", "campaign", "social", "landing page",
                "slogan", "message"));
        CATEGORIES.put("reporting", List.of("report", "summary", "recommendation", "findings", "conclusion", "executive"));
        CATEGORIES.put("integration", List.of("api", "integration", "webhook", "import", "export", "sync"));
        CATEGORIES.put("performance", List.of("performance", "fast", "latency", "scalab", "scale", "cache", "load"));
        CATEGORIES.put("deployment", List.of("deploy", "hosting", "release", "ci/cd", "docker", "kubernetes", "production"));
        CATEGORIES.put("security", List.of("security", "encrypt", "gdpr", "privacy", "audit", "compliance", "pii", "personal data"));
    }

    private static final Map<String, List<String>> ACCEPTANCE = Map.ofEntries(
            Map.entry("authentication", List.of("Users can register, sign in and sign out",
                    "Protected operations reject unauthenticated requests", "Credentials are never stored or logged in plain text")),
            Map.entry("payments", List.of("A user can complete %s end to end", "Failed or declined payments leave the order unpaid and visible",
                    "No card data is stored by the application")),
            Map.entry("cart", List.of("Items can be added, updated and removed", "The cart total is recalculated on every change",
                    "The cart survives a page reload for a signed-in user")),
            Map.entry("orders", List.of("An order is created from the cart with its line items and total",
                    "Orders move through explicit states (placed, paid, shipped, delivered, cancelled)",
                    "Staff can list and update orders")),
            Map.entry("search", List.of("Results match the query by name and description", "Results can be filtered and paginated",
                    "An empty result shows a helpful message")),
            Map.entry("catalog", List.of("Items can be listed, viewed and managed", "Each item has a name, description, price and availability")),
            Map.entry("persistence", List.of("All business data is stored in the database and survives a restart",
                    "Schema changes are applied through versioned migrations")),
            Map.entry("testing", List.of("Automated tests cover every requirement's main path",
                    "Tests run in one command and report pass/fail per requirement")),
            Map.entry("interface", List.of("Every screen works at phone, tablet and desktop widths",
                    "Interactive elements are keyboard accessible and labelled")),
            Map.entry("notifications", List.of("The notification is sent when its trigger happens", "Users can see or opt out of notifications")),
            Map.entry("administration", List.of("Authorised staff can perform %s", "Every administrative change is recorded")),
            Map.entry("analytics", List.of("Each metric has a stated definition and source", "Figures can be reproduced from the input data")),
            Map.entry("research", List.of("Every claim cites at least one recorded source", "Alternatives are compared on the same criteria")),
            Map.entry("content", List.of("The content addresses the stated audience", "The content passes an editorial review checklist")),
            Map.entry("reporting", List.of("The report states its question, method, findings and recommendation",
                    "Findings are traceable to their evidence")),
            Map.entry("integration", List.of("The interface has a documented contract", "Invalid input is rejected with a clear error")),
            Map.entry("performance", List.of("A measurable target is stated and checked")),
            Map.entry("deployment", List.of("Deployment steps are documented and repeatable", "Deployment requires explicit approval")),
            Map.entry("security", List.of("Sensitive data is identified and protected", "Security-relevant actions are audited")),
            Map.entry("general", List.of("%s is specified with its inputs, outputs and error cases",
                    "A reviewer can verify %s against this criterion")));

    private static final Map<String, String> MISSING = Map.ofEntries(
            Map.entry("payments", "Which payment provider, currencies and refund policy?"),
            Map.entry("authentication", "Which sign-in methods (email and password, social login, single sign-on)?"),
            Map.entry("persistence", "Expected data volume, retention period and backup needs?"),
            Map.entry("deployment", "Which hosting environment and who approves releases?"),
            Map.entry("notifications", "Which channels (email, SMS) and which provider?"),
            Map.entry("research", "Which sources are acceptable, and how recent must they be?"),
            Map.entry("analytics", "Where does the data come from, and which period does it cover?"),
            Map.entry("content", "Who is the audience and what is the tone of voice?"),
            Map.entry("performance", "What are the measurable targets (latency, throughput, users)?"));

    private RequirementExtractor() {
    }

    public record Requirement(String id, String text, String feature, String category, String priority,
                              List<String> acceptance) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("text", text);
            m.put("feature", feature);
            m.put("category", category);
            m.put("priority", priority);
            m.put("acceptance", acceptance);
            return m;
        }
    }

    public record Result(String subject, String verb, List<Requirement> requirements, List<String> categories,
                         List<String> unclear, List<String> missingInformation, List<String> assumptions) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subject", subject);
            m.put("verb", verb);
            m.put("requirements", requirements.stream().map(Requirement::toMap).toList());
            m.put("count", requirements.size());
            m.put("categories", categories);
            m.put("unclear", unclear);
            m.put("missingInformation", missingInformation);
            m.put("assumptions", assumptions);
            return m;
        }
    }

    public static Result extract(String text, int maxItems) {
        String clean = text == null ? "" : text.replace('\r', '\n').trim();
        List<String[]> features = new ArrayList<>();
        String subject = null;
        String verb = null;
        List<String> sentences = new ArrayList<>();
        for (String line : clean.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            Matcher bullet = BULLET.matcher(line);
            if (bullet.matches()) {
                features.add(new String[]{tidy(bullet.group(1)), priorityOf(bullet.group(1))});
            } else {
                for (String s : line.trim().split("(?<=[.!?])\\s+")) {
                    if (!s.isBlank()) {
                        sentences.add(s.trim());
                    }
                }
            }
        }
        for (String sentence : sentences) {
            String body = sentence.replaceAll("[.!?]+$", "");
            Matcher lead = LEAD.matcher(body);
            if (subject == null && lead.matches()) {
                verb = lead.group(1).toLowerCase(Locale.ROOT);
                subject = tidy(lead.group(2));
                for (String part : splitList(lead.group(4))) {
                    features.add(new String[]{part, "MUST"});
                }
            } else if (MODAL.matcher(body).find()) {
                features.add(new String[]{tidy(body), priorityOf(body)});
            } else {
                List<String> parts = splitList(body);
                if (parts.size() > 1) {
                    parts.forEach(p -> features.add(new String[]{p, priorityOf(body)}));
                } else if (!parts.isEmpty()) {
                    features.add(new String[]{parts.get(0), priorityOf(body)});
                }
            }
        }
        if (subject == null && !sentences.isEmpty()) {
            subject = tidy(sentences.get(0).replaceAll("[.!?]+$", ""));
            if (subject.split("\\s+").length > 8) {
                subject = String.join(" ", List.of(subject.split("\\s+")).subList(0, 8)) + "…";
            }
        }

        List<Requirement> requirements = new ArrayList<>();
        List<String> unclear = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> categories = new LinkedHashSet<>();
        for (String[] f : features) {
            String feature = f[0];
            if (feature.isBlank() || !seen.add(feature.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String lower = feature.toLowerCase(Locale.ROOT);
            if (VAGUE.stream().anyMatch(lower::contains) || lower.split("\\s+").length == 1 && lower.length() < 4) {
                unclear.add(feature);
                continue;
            }
            if (requirements.size() >= Math.max(1, maxItems)) {
                unclear.add(feature + " (not included: more than " + maxItems + " items)");
                continue;
            }
            String category = categorize(lower);
            categories.add(category);
            String id = "R" + (requirements.size() + 1);
            String statement = MODAL.matcher(feature).find() ? capitalize(feature)
                    : "The " + (subject == null ? "deliverable" : stripArticles(subject)) + " must provide " + feature + ".";
            List<String> acceptance = ACCEPTANCE.getOrDefault(category, ACCEPTANCE.get("general")).stream()
                    .map(a -> a.contains("%s") ? String.format(a, feature) : a).toList();
            requirements.add(new Requirement(id, statement.endsWith(".") ? statement : statement + ".", feature, category, f[1],
                    acceptance));
        }

        List<String> missing = new ArrayList<>();
        for (String c : categories) {
            if (MISSING.containsKey(c)) {
                missing.add(MISSING.get(c));
            }
        }
        String lowerAll = clean.toLowerCase(Locale.ROOT);
        if (!lowerAll.matches("(?s).*\\b(user|users|customer|customers|audience|reader|readers|staff|team|client|clients)\\b.*")) {
            missing.add("Who are the intended users or readers?");
        }
        if (requirements.isEmpty()) {
            missing.add("No concrete requirement could be identified; list the features or questions explicitly.");
        }
        List<String> assumptions = new ArrayList<>();
        if (categories.contains("interface") || categories.contains("authentication") || categories.contains("cart")) {
            assumptions.add("The deliverable is a web application used through a browser.");
        }
        if (categories.contains("persistence") || categories.contains("orders") || categories.contains("cart")) {
            assumptions.add("Data is stored in a relational database (PostgreSQL unless shared memory records another choice).");
        }
        if (categories.contains("research") || categories.contains("reporting")) {
            assumptions.add("Only sources recorded in Memora (memory or workspace files) are used; there is no web access.");
        }
        assumptions.add("Requirements were extracted by pattern matching; review and edit them before relying on the plan.");
        return new Result(subject, verb, requirements, List.copyOf(categories), unclear, missing, assumptions);
    }

    public static String categorize(String lowerText) {
        String padded = " " + lowerText + " ";
        for (Map.Entry<String, List<String>> e : CATEGORIES.entrySet()) {
            for (String keyword : e.getValue()) {
                if (matchesKeyword(padded, keyword)) {
                    return e.getKey();
                }
            }
        }
        return "general";
    }

    /** Keywords match at the start of a word ("test" matches "tests", not "latest"). */
    public static boolean matchesKeyword(String lowerText, String keyword) {
        return KEYWORD_PATTERNS.computeIfAbsent(keyword,
                k -> Pattern.compile("(?<![a-z0-9])" + Pattern.quote(k))).matcher(lowerText).find();
    }

    private static final Map<String, Pattern> KEYWORD_PATTERNS = new java.util.concurrent.ConcurrentHashMap<>();

    static List<String> splitList(String text) {
        List<String> out = new ArrayList<>();
        for (String part : SPLIT.split(text)) {
            String t = tidy(part);
            if (!t.isBlank()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String tidy(String s) {
        String t = s.trim().replaceAll("^[\"'`(]+|[\"'`).,;:!?]+$", "").trim();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String f : FILLER) {
                if (t.toLowerCase(Locale.ROOT).startsWith(f + " ")) {
                    t = t.substring(f.length() + 1).trim();
                    changed = true;
                }
            }
        }
        return t.replaceAll("\\s+", " ");
    }

    private static String stripArticles(String s) {
        return s.replaceAll("(?i)^(a|an|the)\\s+", "");
    }

    private static String priorityOf(String s) {
        return SHOULD.matcher(s).find() ? "SHOULD" : "MUST";
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
