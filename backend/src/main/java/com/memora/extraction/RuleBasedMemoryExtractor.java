package com.memora.extraction;

import com.memora.common.Json;
import com.memora.memory.MemoryType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic, explainable extraction. Each sentence is matched against an ordered list of rules;
 * the first rule that matches decides what (if anything) is recorded. Every result names its rule.
 *
 * <p>This is pattern matching, not language understanding: it only recognises the phrasings listed
 * here, ignores questions and negations, and never invents facts. Callers that need richer input
 * can always submit structured memories directly through the API.
 */
@Component
public class RuleBasedMemoryExtractor implements MemoryExtractor {

    public static final String NAME = "rule-based-v1";

    private static final int I = Pattern.CASE_INSENSITIVE;
    private static final String WE = "(?:we|the team|our team|the project|this project|our (?:app|application|service|system|backend|frontend|stack))";

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?;])\\s+|\\n+");
    private static final Pattern NEGATION = Pattern.compile("\\b(?:not|n't|never|no longer|stopped|don't|doesn't)\\b", I);

    private static final Pattern SWITCH = Pattern.compile(
            "^" + WE + "\\s+(?:have\\s+|has\\s+)?(?:just\\s+)?(?:switched|migrated|moved|changed)\\s+(?:our\\s+\\w+\\s+)?from\\s+(.+?)\\s+to\\s+(.+)$", I);
    private static final Pattern PREFER_I = Pattern.compile(
            "^i\\s+(?:really\\s+|strongly\\s+|generally\\s+)?(?:prefer|like|love|favou?r)\\s+(?:to use\\s+|using\\s+|working (?:in|with)\\s+)?(.+?)(?:\\s+(?:over|rather than|instead of|to)\\s+(.+))?$", I);
    private static final Pattern PREFER_MY = Pattern.compile(
            "^my\\s+(?:preferred|favou?rite)\\s+(.+?)\\s+is\\s+(.+)$", I);
    private static final Pattern PREFER_WE = Pattern.compile(
            "^" + WE + "\\s+(?:prefers?)\\s+(.+?)(?:\\s+(?:over|rather than|instead of|to)\\s+(.+))?$", I);
    private static final Pattern USES = Pattern.compile(
            "^" + WE + "\\s+(?:currently\\s+|now\\s+|still\\s+|mainly\\s+|primarily\\s+)?(?:use|uses|are using|is using|rely on|relies on|run on|runs on|are built (?:on|with)|is built (?:on|with)|build with|builds with|deploy(?:s)? (?:on|to))\\s+(.+)$", I);
    /** "billing-service uses MySQL", "postgres is hosted on RDS", "billing-service deploys through ArgoCD". */
    private static final Pattern NAMED_RELATION = Pattern.compile(
            "^(?:the\\s+)?([\\p{L}\\p{N}][\\p{L}\\p{N}._-]*(?:\\s+(?:service|api|app|db|database|cluster))?)\\s+"
                    + "(uses|relies on|depends on|is hosted on|is hosted in|runs on|is deployed (?:on|to)|deploys? (?:through|via|with)|is deployed (?:through|via|with)|calls|talks to|stores data in|writes to|reads from)\\s+(.+)$", I);
    /** "We moved billing-service to Postgres", "we migrated billing-service from MySQL to Postgres". */
    private static final Pattern NAMED_SWITCH = Pattern.compile(
            "^" + WE + "\\s+(?:have\\s+|has\\s+)?(?:just\\s+)?(?:switched|migrated|moved)\\s+(?:the\\s+)?([\\p{L}\\p{N}][\\p{L}\\p{N}._-]*)\\s+(?:from\\s+(.+?)\\s+)?(?:to|onto)\\s+(.+)$", I);
    private static final java.util.Set<String> NOT_SUBJECTS = java.util.Set.of("we", "i", "you", "they", "he", "she", "it",
            "this", "that", "everyone", "everybody", "nobody", "someone", "team", "project", "our", "my", "who", "which");
    private static final Pattern OUR_X_IS = Pattern.compile(
            "^(?:our|the)\\s+((?:main |primary )?(?:database|db|datastore|language|programming language|framework|backend framework|web framework|frontend|frontend framework|build tool|cache|cloud|cloud provider|ci|issue tracker|message broker|queue))\\s+(?:is|will be)\\s+(.+)$", I);
    private static final Pattern DECISION = Pattern.compile(
            "^(?:decision:\\s*(.+)|" + WE + "?\\s*(?:have\\s+)?(?:decided|agreed)\\s+(?:to|that|on)\\s+(.+)|we(?:'ll| will) go with\\s+(.+))$", I);
    private static final Pattern WHEN_THEN = Pattern.compile(
            "^(when|whenever|if|after|before|once)\\s+(.+?),\\s*(?:then\\s+)?(.+)$", I);
    private static final Pattern ALWAYS_NEVER = Pattern.compile("^(always|never)\\s+(.+)$", I);
    private static final Pattern TO_DO = Pattern.compile("^(?:to|in order to)\\s+(.+?),\\s*(.+)$", I);
    private static final Pattern LESSON = Pattern.compile(
            "^(lesson learned|lessons learned|lesson|tip|best practice|rule of thumb|procedure|runbook|how to)\\s*:\\s*(.+)$", I);
    private static final Pattern REQUIREMENT_PREFIX = Pattern.compile("^(?:requirement|constraint|acceptance criteri(?:on|a))\\s*:\\s*(.+)$", I);
    private static final Pattern REQUIREMENT_MODAL = Pattern.compile(
            "^((?:the|our|every|each|all)\\s+[\\w\\s/-]{1,60}?)\\s+(must|shall|has to|have to|needs to|need to|is required to|are required to)\\s+(.+)$", I);
    private static final Pattern EVENT = Pattern.compile(
            "\\b(deployed|released|shipped|fixed|resolved|failed|broke|crashed|merged|completed|finished|implemented|migrated|discovered|found|investigated|rolled back|launched|passed|timed out|regressed)\\b", I);
    private static final Pattern EVENT_SUBJECT = Pattern.compile(
            "^(?:yesterday|today|this morning|last (?:night|week|sprint)|on \\w+day|earlier|we|i|the team|the build|the deploy(?:ment)?|the release|the (?:integration |unit |e2e )?tests?|ci|the pipeline|the migration|the (?:\\w+ )?(?:service|api|job|server)|incident)\\b", I);
    private static final Pattern FAILURE_WORDS = Pattern.compile("\\b(failed|broke|crashed|timed out|regressed|outage|incident)\\b", I);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<ExtractedMemory> extract(ExtractionInput input) {
        List<ExtractedMemory> out = new ArrayList<>();
        if (input.text() == null || input.text().isBlank()) {
            return out;
        }
        for (String raw : SENTENCE_SPLIT.split(input.text().trim())) {
            String sentence = clean(raw);
            if (sentence.isEmpty() || raw.trim().endsWith("?") || sentence.split("\\s+").length < 3
                    || SensitiveContentGuard.looksSensitive(sentence)) {
                continue;
            }
            out.addAll(extractSentence(sentence, input.authoredByUser()));
        }
        return out;
    }

    private List<ExtractedMemory> extractSentence(String s, boolean authoredByUser) {
        Matcher m;
        if ((m = SWITCH.matcher(s)).matches() && !NEGATION.matcher(s).find()) {
            return switchFact(s, m.group(1), m.group(2));
        }
        if ((m = PREFER_I.matcher(s)).matches() && authoredByUser && !NEGATION.matcher(s).find()) {
            return List.of(preference(s, "user", m.group(1), m.group(2), true));
        }
        if ((m = PREFER_MY.matcher(s)).matches() && authoredByUser) {
            return List.of(namedPreference(s, m.group(1), m.group(2)));
        }
        if ((m = PREFER_WE.matcher(s)).matches() && !NEGATION.matcher(s).find()) {
            return List.of(preference(s, "team", m.group(1), m.group(2), false));
        }
        if ((m = USES.matcher(s)).matches() && !NEGATION.matcher(s).find()) {
            return usesFacts(s, m.group(1), Optional.empty());
        }
        if ((m = OUR_X_IS.matcher(s)).matches() && !NEGATION.matcher(s).find()) {
            String noun = m.group(1).replaceFirst("(?i)^(main|primary)\\s+", "");
            return usesFacts(s, m.group(2), TechVocabulary.categoryFromNoun(noun));
        }
        if ((m = NAMED_SWITCH.matcher(s)).matches() && !NEGATION.matcher(s).find()
                && !NOT_SUBJECTS.contains(m.group(1).toLowerCase(Locale.ROOT))) {
            return namedSwitch(m.group(1), m.group(2), m.group(3));
        }
        if ((m = NAMED_RELATION.matcher(s)).matches() && !NEGATION.matcher(s).find()
                && !NOT_SUBJECTS.contains(m.group(1).toLowerCase(Locale.ROOT))) {
            List<ExtractedMemory> named = namedRelation(m.group(1), m.group(2), m.group(3));
            if (!named.isEmpty()) {
                return named;
            }
        }
        if ((m = DECISION.matcher(s)).matches()) {
            String what = firstNonNull(m.group(1), m.group(2), m.group(3));
            return List.of(semantic("decision", "Decision: " + capitalize(what), null, null, null, false, 4, 0.8,
                    List.of("decision"), "decision", Map.of()));
        }
        if ((m = REQUIREMENT_PREFIX.matcher(s)).matches()) {
            return List.of(requirement(m.group(1)));
        }
        if ((m = LESSON.matcher(s)).matches()) {
            String kind = m.group(1).toLowerCase(Locale.ROOT);
            String category = kind.startsWith("lesson") ? "lesson" : "procedure";
            return List.of(procedural(category, capitalize(m.group(2)), 4, 0.85, "lesson-prefix",
                    Json.map("kind", kind)));
        }
        // "When X happens, do Y" is a procedure; "When we deployed, it crashed" is a past event.
        if ((m = WHEN_THEN.matcher(s)).matches() && (looksImperative(m.group(3)) || !EVENT.matcher(m.group(2)).find())) {
            return List.of(procedural("procedure", capitalize(s), 4, 0.75, "when-then",
                    Json.map("trigger", m.group(1).toLowerCase(Locale.ROOT) + " " + m.group(2), "action", m.group(3))));
        }
        if ((m = ALWAYS_NEVER.matcher(s)).matches()) {
            return List.of(procedural("rule", capitalize(s), 4, 0.75, "always-never",
                    Json.map("polarity", m.group(1).toLowerCase(Locale.ROOT))));
        }
        if ((m = TO_DO.matcher(s)).matches()) {
            return List.of(procedural("procedure", capitalize(s), 3, 0.7, "to-do",
                    Json.map("goal", m.group(1), "action", m.group(2))));
        }
        if ((m = REQUIREMENT_MODAL.matcher(s)).matches()) {
            return List.of(requirement(s));
        }
        if (EVENT_SUBJECT.matcher(s).lookingAt() && EVENT.matcher(s).find()) {
            boolean failure = FAILURE_WORDS.matcher(s).find();
            Matcher verb = EVENT.matcher(s);
            verb.find();
            return List.of(new ExtractedMemory(MemoryType.EPISODIC, "event", capitalize(s), null, null, null, false,
                    failure ? 4 : 3, 0.7, List.of("event", failure ? "failure" : "success"), "past-event",
                    Json.map("outcome", failure ? "failure" : "success", "verb", verb.group(1).toLowerCase(Locale.ROOT))));
        }
        return List.of();
    }

    private List<ExtractedMemory> switchFact(String sentence, String fromPhrase, String toPhrase) {
        String from = trimValue(fromPhrase);
        String to = trimValue(toPhrase);
        Optional<TechVocabulary.Term> toTerm = TechVocabulary.lookup(to);
        Optional<TechVocabulary.Term> fromTerm = TechVocabulary.lookup(from);
        String category = toTerm.map(TechVocabulary.Term::category)
                .or(() -> fromTerm.map(TechVocabulary.Term::category))
                .orElse(null);
        String toDisplay = toTerm.map(TechVocabulary.Term::display).orElse(to);
        String fromDisplay = fromTerm.map(TechVocabulary.Term::display).orElse(from);
        // Unknown tools: key the fact on what was replaced, so "We use Jira" and
        // "We switched from Jira to Linear" land on the same key and the newer one wins.
        String predicate = category != null ? "uses." + category : "uses." + slug(from);
        String content = category != null
                ? "The project uses " + toDisplay + " as its " + category + " (switched from " + fromDisplay + ")."
                : "The project uses " + toDisplay + " (switched from " + fromDisplay + ").";
        String value = toTerm.map(TechVocabulary.Term::canonical).orElse(TechVocabulary.normalize(to));
        List<ExtractedMemory> out = new ArrayList<>();
        out.add(semantic("fact", content, "project", predicate, value, false, 4, 0.9,
                tags("technology", category), "switch",
                Json.map("previous", fromTerm.map(TechVocabulary.Term::canonical).orElse(TechVocabulary.normalize(from)),
                        "category", category)));
        out.add(new ExtractedMemory(MemoryType.EPISODIC, "event",
                "Switched " + (category != null ? category + " " : "") + "from " + fromDisplay + " to " + toDisplay + ".",
                null, null, null, false, 3, 0.9, List.of("event", "migration"), "switch-event",
                Json.map("outcome", "success", "verb", "switched")));
        return out;
    }

    /** A fact about a named component; the subject becomes a graph entity, the value another one. */
    private List<ExtractedMemory> namedRelation(String subjectPhrase, String verb, String valuePhrase) {
        String subject = componentName(subjectPhrase);
        String value = trimValue(valuePhrase.replaceFirst("(?i)\\s+(for|because|since|in order to)\\s+.*$", ""));
        if (value.isEmpty() || value.split("\\s+").length > 4
                || value.matches("(?i)^(must|should|shall|will|can|could|has|have|need|needs|is|are|be|to|the same)\\b.*")) {
            return List.of();
        }
        Optional<TechVocabulary.Term> term = TechVocabulary.lookup(value);
        String v = verb.toLowerCase(Locale.ROOT);
        String relation = v.matches("uses|relies on|depends on") ? "uses"
                : v.matches("is hosted (on|in)|runs on|is deployed (on|to)") ? "hosted_on"
                : v.matches("deploys? (through|via|with)|is deployed (through|via|with)") ? "deploys_via"
                : v.matches("calls|talks to") ? "calls" : "stores_in";
        String category = term.map(TechVocabulary.Term::category).orElse(null);
        String predicate = relation.equals("uses") && category != null ? "uses." + category : relation;
        String display = term.map(TechVocabulary.Term::display).orElse(value);
        String canonical = term.map(TechVocabulary.Term::canonical).orElse(TechVocabulary.normalize(value));
        String phrase = switch (relation) {
            case "uses" -> category != null ? " uses " + display + " as its " + category : " uses " + display;
            case "hosted_on" -> " is hosted on " + display;
            case "deploys_via" -> " deploys through " + display;
            case "calls" -> " calls " + display;
            default -> " stores data in " + display;
        };
        return List.of(semantic("fact", subject + phrase + ".", subject, predicate, canonical, false, 4,
                0.85, tags("technology", category), "named-relation", Json.map("category", category, "relation", relation)));
    }

    private List<ExtractedMemory> namedSwitch(String subjectPhrase, String fromPhrase, String toPhrase) {
        String subject = componentName(subjectPhrase);
        String to = trimValue(toPhrase);
        Optional<TechVocabulary.Term> toTerm = TechVocabulary.lookup(to);
        Optional<TechVocabulary.Term> fromTerm = fromPhrase == null ? Optional.empty() : TechVocabulary.lookup(trimValue(fromPhrase));
        String category = toTerm.map(TechVocabulary.Term::category).or(() -> fromTerm.map(TechVocabulary.Term::category)).orElse(null);
        String predicate = category != null ? "uses." + category : "hosted_on";
        String toDisplay = toTerm.map(TechVocabulary.Term::display).orElse(to);
        String content = subject + (category != null ? " uses " + toDisplay + " as its " + category
                : " is hosted on " + toDisplay)
                + (fromPhrase != null ? " (moved from " + fromTerm.map(TechVocabulary.Term::display).orElse(trimValue(fromPhrase)) + ")." : ".");
        List<ExtractedMemory> out = new ArrayList<>();
        out.add(semantic("fact", content, subject, predicate, toTerm.map(TechVocabulary.Term::canonical)
                        .orElse(TechVocabulary.normalize(to)), false, 4, 0.9, tags("technology", category), "named-switch",
                Json.map("category", category, "previous", fromTerm.map(TechVocabulary.Term::canonical).orElse(null))));
        out.add(new ExtractedMemory(MemoryType.EPISODIC, "event", "Moved " + subject + " to " + toDisplay + ".",
                null, null, null, false, 3, 0.9, List.of("event", "migration"), "named-switch-event",
                Json.map("outcome", "success", "verb", "moved")));
        return out;
    }

    private static String componentName(String phrase) {
        String p = TechVocabulary.normalize(phrase).replaceFirst("^the\\s+", "");
        return TechVocabulary.lookup(p).map(TechVocabulary.Term::canonical).orElse(p.replaceAll("\\s+", "-"));
    }

    private ExtractedMemory preference(String sentence, String subject, String likedPhrase, String otherPhrase,
                                       boolean userScoped) {
        String liked = trimValue(likedPhrase);
        Optional<TechVocabulary.Term> term = TechVocabulary.lookup(liked);
        String predicate;
        String value;
        String display = term.map(TechVocabulary.Term::display).orElse(liked);
        if (term.isPresent()) {
            predicate = "prefers." + term.get().category();
            value = term.get().canonical();
        } else if (otherPhrase != null) {
            // "tabs over spaces" and "spaces over tabs" share a key, so the newer one supersedes.
            String a = slug(liked);
            String b = slug(trimValue(otherPhrase));
            predicate = "prefers." + (a.compareTo(b) < 0 ? a + "-vs-" + b : b + "-vs-" + a);
            value = TechVocabulary.normalize(liked);
        } else {
            predicate = "prefers." + slug(liked);
            value = TechVocabulary.normalize(liked);
        }
        String who = userScoped ? "User" : "The team";
        String content = who + " prefers " + display
                + (otherPhrase != null ? " over " + trimValue(otherPhrase) : "")
                + term.map(t -> " (" + t.category() + ")").orElse("") + ".";
        return semantic("preference", content, subject, predicate, value, userScoped, 3, 0.85,
                tags("preference", term.map(TechVocabulary.Term::category).orElse(null)), "preference",
                Json.map("alternative", otherPhrase == null ? null : trimValue(otherPhrase)));
    }

    private ExtractedMemory namedPreference(String sentence, String noun, String valuePhrase) {
        String value = trimValue(valuePhrase);
        Optional<TechVocabulary.Term> term = TechVocabulary.lookup(value);
        String category = TechVocabulary.categoryFromNoun(noun).orElse(slug(noun));
        String display = term.map(TechVocabulary.Term::display).orElse(value);
        return semantic("preference", "User's preferred " + TechVocabulary.normalize(noun) + " is " + display + ".",
                "user", "prefers." + category, term.map(TechVocabulary.Term::canonical).orElse(TechVocabulary.normalize(value)),
                true, 3, 0.85, tags("preference", category), "preference-named", Map.of());
    }

    private List<ExtractedMemory> usesFacts(String sentence, String listPhrase, Optional<String> explicitCategory) {
        List<ExtractedMemory> out = new ArrayList<>();
        String cleaned = listPhrase.replaceFirst("(?i)\\s+(for|as|because|since|to|in|on|with)\\s+.*$", "");
        for (String item : cleaned.split("\\s*,\\s*|\\s+and\\s+|\\s*&\\s*")) {
            String value = trimValue(item);
            if (value.isEmpty() || value.split("\\s+").length > 4) {
                continue;
            }
            Optional<TechVocabulary.Term> term = TechVocabulary.lookup(value);
            String category = explicitCategory.orElse(term.map(TechVocabulary.Term::category).orElse(null));
            String display = term.map(TechVocabulary.Term::display).orElse(value);
            String canonical = term.map(TechVocabulary.Term::canonical).orElse(TechVocabulary.normalize(value));
            String predicate = category != null ? "uses." + category : "uses." + slug(value);
            String content = category != null
                    ? "The project uses " + display + " as its " + category + "."
                    : "The project uses " + display + ".";
            out.add(semantic("fact", content, "project", predicate, canonical, false, 4, 0.85,
                    tags("technology", category), explicitCategory.isPresent() ? "our-x-is" : "uses",
                    Json.map("category", category)));
        }
        return out;
    }

    private ExtractedMemory requirement(String text) {
        return semantic("requirement", "Requirement: " + capitalize(text), null, null, null, false, 4, 0.75,
                List.of("requirement"), "requirement", Map.of());
    }

    private static ExtractedMemory semantic(String category, String content, String subject, String predicate,
                                            String value, boolean userScoped, int importance, double confidence,
                                            List<String> tags, String rule, Map<String, Object> metadata) {
        return new ExtractedMemory(MemoryType.SEMANTIC, category, content, subject, predicate, value, userScoped,
                importance, confidence, tags, rule, metadata);
    }

    private static ExtractedMemory procedural(String category, String content, int importance, double confidence,
                                              String rule, Map<String, Object> metadata) {
        return new ExtractedMemory(MemoryType.PROCEDURAL, category, content, null, null, null, false, importance,
                confidence, List.of(category), rule, metadata);
    }

    private static boolean looksImperative(String action) {
        return action.matches("(?i)^(check|run|restart|verify|ensure|make sure|use|call|notify|roll back|rollback|retry|"
                + "inspect|look at|open|update|re-run|rerun|clear|delete|add|create|ask|escalate|review|read|try|start|stop|"
                + "page|ping|confirm|validate|reset|reload|revert|log|file|record|write|do not|don't|avoid|never|always)\\b.*");
    }

    private static List<String> tags(String first, String category) {
        return category == null ? List.of(first) : List.of(first, slug(category));
    }

    private static String clean(String raw) {
        return raw.trim().replaceAll("^[-*•\\d.)\\s]+(?=[A-Za-z])", "").replaceAll("[.!;]+$", "").trim();
    }

    static String trimValue(String s) {
        return s == null ? "" : s.trim().replaceAll("[.!,;:]+$", "").replaceAll("^(?i)(the|a|an)\\s+", "").trim();
    }

    static String slug(String s) {
        return TechVocabulary.normalize(s).replaceAll("[^a-z0-9#+]+", "-").replaceAll("^-|-$", "");
    }

    private static String capitalize(String s) {
        String t = s.trim();
        String out = t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
        return out.endsWith(".") ? out : out + ".";
    }

    private static String firstNonNull(String... values) {
        return Arrays.stream(values).filter(v -> v != null && !v.isBlank()).findFirst().orElse("");
    }
}
