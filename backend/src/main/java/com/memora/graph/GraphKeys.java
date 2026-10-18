package com.memora.graph;

import com.memora.extraction.TechVocabulary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical entity keys. A known technology maps to its {@link TechVocabulary} canonical name ("Postgres",
 * "PostgreSQL" and "postgres" are all {@code postgresql}); anything else is slugged ("Billing Service" is
 * {@code billing-service}). Projection and retrieval use the same function, which is what lets a query
 * term land on the entity a memory was attached to.
 */
public final class GraphKeys {

    /** Hyphenated names such as billing-service or auth-api are treated as component names. */
    private static final Pattern COMPONENT = Pattern.compile("\\b[a-z0-9]+(?:-[a-z0-9]+)+\\b");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}#+._-]*");

    public record EntityRef(String key, String name, String kind) {
    }

    private GraphKeys() {
    }

    public static String entityKey(String phrase) {
        return TechVocabulary.exact(phrase).map(TechVocabulary.Term::canonical).orElseGet(() -> slug(phrase));
    }

    public static EntityRef entity(String phrase) {
        return TechVocabulary.exact(phrase)
                .map(t -> new EntityRef(t.canonical(), t.display(), t.category()))
                .orElseGet(() -> new EntityRef(slug(phrase), phrase.trim(), "component"));
    }

    /** Entities a text mentions: known technologies plus hyphenated component names. */
    public static List<EntityRef> mentions(String text) {
        Map<String, EntityRef> out = new LinkedHashMap<>();
        if (text == null) {
            return List.of();
        }
        for (TechVocabulary.Term t : TechVocabulary.termsIn(text)) {
            out.putIfAbsent(t.canonical(), new EntityRef(t.canonical(), t.display(), t.category()));
        }
        Matcher m = COMPONENT.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            out.putIfAbsent(m.group(), new EntityRef(m.group(), m.group(), "component"));
        }
        return new ArrayList<>(out.values());
    }

    /** Candidate entity keys for a free-text query: every word, 2- and 3-word phrase, canonicalised. */
    public static List<String> queryKeys(String query) {
        List<String> keys = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return keys;
        }
        List<String> words = new ArrayList<>();
        Matcher m = WORD.matcher(query.toLowerCase(Locale.ROOT));
        while (m.find() && words.size() < 40) {
            words.add(m.group().replaceAll("[.]+$", ""));
        }
        for (int i = 0; i < words.size(); i++) {
            for (int n = 1; n <= 3 && i + n <= words.size(); n++) {
                String phrase = String.join(" ", words.subList(i, i + n));
                String key = entityKey(phrase);
                if (!key.isBlank() && !keys.contains(key)) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    static String slug(String s) {
        return s.toLowerCase(Locale.ROOT).trim().replaceAll("^(the|a|an)\\s+", "")
                .replaceAll("[^a-z0-9#+]+", "-").replaceAll("^-|-$", "");
    }
}
