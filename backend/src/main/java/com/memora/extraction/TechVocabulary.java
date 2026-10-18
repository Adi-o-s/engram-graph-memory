package com.memora.extraction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A small, explicit vocabulary that tells the extractor which technologies are alternatives for
 * the same role. "We use PostgreSQL" and "We use MySQL" conflict because both are databases;
 * "We use PostgreSQL" and "We use React" do not.
 */
public final class TechVocabulary {

    public record Term(String canonical, String display, String category) {
    }

    private static final Map<String, Term> TERMS = new LinkedHashMap<>();
    /** Aliases that are ordinary English words; they only count as a mention when written as a fact value. */
    private static final java.util.Set<String> AMBIGUOUS = java.util.Set.of("go", "express", "linear", "swift", "spring", "rust");

    static {
        add("database", "PostgreSQL", "postgresql", "postgres", "postgre sql");
        add("database", "MySQL", "mysql");
        add("database", "MariaDB", "mariadb");
        add("database", "MongoDB", "mongodb", "mongo");
        add("database", "SQLite", "sqlite");
        add("database", "Oracle Database", "oracle", "oracle database");
        add("database", "SQL Server", "sql server", "mssql");
        add("database", "Cassandra", "cassandra");
        add("database", "DynamoDB", "dynamodb");
        add("database", "CockroachDB", "cockroachdb");
        add("language", "Java", "java");
        add("language", "Python", "python");
        add("language", "Go", "go", "golang");
        add("language", "Kotlin", "kotlin");
        add("language", "TypeScript", "typescript");
        add("language", "JavaScript", "javascript");
        add("language", "Rust", "rust");
        add("language", "C#", "c#", "csharp");
        add("language", "C++", "c++");
        add("language", "Ruby", "ruby");
        add("language", "PHP", "php");
        add("language", "Scala", "scala");
        add("language", "Swift", "swift");
        add("backend framework", "Spring Boot", "spring boot", "spring");
        add("backend framework", "Django", "django");
        add("backend framework", "Flask", "flask");
        add("backend framework", "FastAPI", "fastapi");
        add("backend framework", "Express", "express", "express.js");
        add("backend framework", "Ruby on Rails", "rails", "ruby on rails");
        add("backend framework", "Quarkus", "quarkus");
        add("backend framework", "NestJS", "nestjs");
        add("frontend framework", "React", "react", "react.js");
        add("frontend framework", "Vue", "vue", "vue.js");
        add("frontend framework", "Angular", "angular");
        add("frontend framework", "Svelte", "svelte");
        add("build tool", "Maven", "maven");
        add("build tool", "Gradle", "gradle");
        add("package manager", "npm", "npm");
        add("package manager", "pnpm", "pnpm");
        add("package manager", "Yarn", "yarn");
        add("message broker", "Kafka", "kafka", "apache kafka");
        add("message broker", "RabbitMQ", "rabbitmq");
        add("cache", "Redis", "redis");
        add("cache", "Memcached", "memcached");
        add("ci", "GitHub Actions", "github actions");
        add("ci", "Jenkins", "jenkins");
        add("ci", "GitLab CI", "gitlab ci");
        add("ci", "CircleCI", "circleci");
        add("cloud", "AWS", "aws", "amazon web services");
        add("cloud", "Google Cloud", "gcp", "google cloud");
        add("cloud", "Azure", "azure");
        add("issue tracker", "Jira", "jira");
        add("issue tracker", "Linear", "linear");
        add("test framework", "JUnit", "junit", "junit 5");
        add("test framework", "pytest", "pytest");
        add("test framework", "Jest", "jest");
        add("test framework", "Vitest", "vitest");
    }

    private TechVocabulary() {
    }

    private static void add(String category, String display, String... aliases) {
        String canonical = aliases[0];
        for (String alias : aliases) {
            TERMS.put(alias, new Term(canonical, display, category));
        }
    }

    /** Looks up a phrase such as "PostgreSQL" or "postgres 16"; matches the longest known prefix. */
    static Optional<Term> lookup(String phrase) {
        String p = normalize(phrase);
        Term best = null;
        int bestLength = 0;
        for (Map.Entry<String, Term> e : TERMS.entrySet()) {
            String alias = e.getKey();
            boolean matches = p.equals(alias) || p.startsWith(alias + " ");
            if (matches && alias.length() > bestLength) {
                best = e.getValue();
                bestLength = alias.length();
            }
        }
        return Optional.ofNullable(best);
    }

    /** The term a whole phrase names exactly ("Postgres" -> postgresql), used for graph entity keys. */
    public static Optional<Term> exact(String phrase) {
        return phrase == null ? Optional.empty() : Optional.ofNullable(TERMS.get(normalize(phrase)));
    }

    /** Every known technology mentioned anywhere in a text (1- to 3-word phrases), in order of appearance. */
    public static List<Term> termsIn(String text) {
        List<Term> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        String[] words = normalize(text).replaceAll("[^a-z0-9#+.\\s-]", " ").split("\\s+");
        for (int i = 0; i < words.length; i++) {
            for (int n = 3; n >= 1; n--) {
                if (i + n > words.length) {
                    continue;
                }
                String phrase = String.join(" ", java.util.Arrays.copyOfRange(words, i, i + n)).replaceAll("[.]+$", "");
                Term t = AMBIGUOUS.contains(phrase) ? null : TERMS.get(phrase);
                if (t != null) {
                    if (!out.contains(t)) {
                        out.add(t);
                    }
                    i += n - 1;
                    break;
                }
            }
        }
        return out;
    }

    static Optional<String> categoryFromNoun(String noun) {
        String n = normalize(noun);
        return switch (n) {
            case "database", "db", "datastore", "data store" -> Optional.of("database");
            case "language", "programming language", "main language", "primary language" -> Optional.of("language");
            case "framework", "backend framework", "web framework" -> Optional.of("backend framework");
            case "frontend", "frontend framework", "ui framework" -> Optional.of("frontend framework");
            case "build tool" -> Optional.of("build tool");
            case "cache" -> Optional.of("cache");
            case "cloud", "cloud provider" -> Optional.of("cloud");
            case "ci", "ci system", "ci provider" -> Optional.of("ci");
            case "issue tracker", "tracker" -> Optional.of("issue tracker");
            case "message broker", "queue" -> Optional.of("message broker");
            default -> Optional.empty();
        };
    }

    static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ").replaceAll("^(the|a|an)\\s+", "");
    }
}
