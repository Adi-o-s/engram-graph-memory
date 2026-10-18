package com.memora.orchestration.expr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExpressionsAndConditionsTest {

    private final Map<String, Object> ctx = Map.of(
            "run", Map.of("goal", "Ship it", "inputs", Map.of("mode", "go")),
            "steps", Map.of("qa", Map.of("output", Map.of("passed", false, "missing", List.of("R5")), "status", "SUCCEEDED")),
            "items", List.of(Map.of("id", "R1"), Map.of("id", "R2")));

    @Test
    void wholePlaceholdersKeepTypesAndEmbeddedOnesInterpolate() {
        assertThat(Expressions.resolve("${steps.qa.output.missing}", ctx)).isEqualTo(List.of("R5"));
        assertThat(Expressions.resolve("${steps.qa.output.passed}", ctx)).isEqualTo(false);
        assertThat(Expressions.resolve("Goal: ${run.goal} (${run.inputs.mode})", ctx)).isEqualTo("Goal: Ship it (go)");
        assertThat(Expressions.resolve("${items.1.id}", ctx)).isEqualTo("R2");
        assertThat(Expressions.resolve("${items.size}", ctx)).isEqualTo(2);
        assertThat(Expressions.resolve("${nothing.here}", ctx)).isNull();
        assertThat(Expressions.resolve("[${nothing.here}]", ctx)).isEqualTo("[]");
        assertThat(Expressions.resolve(Map.of("a", List.of("${run.goal}")), ctx)).isEqualTo(Map.of("a", List.of("Ship it")));
    }

    @Test
    void fallbacksApplyToMissingAndEmptyValues() {
        assertThat(Expressions.resolve("${nothing | postgresql}", ctx)).isEqualTo("postgresql");
        assertThat(Expressions.resolve("db=${nothing | postgresql}", ctx)).isEqualTo("db=postgresql");
        assertThat(Expressions.resolve("${run.goal | other}", ctx)).isEqualTo("Ship it");
        assertThat(Expressions.paths("${run.goal | x} and ${steps.qa.status}")).containsExactly("run.goal", "steps.qa.status");
    }

    @Test
    void syntaxErrorsAreReported() {
        assertThat(Expressions.syntaxErrors("${run.goal")).hasSize(1);
        assertThat(Expressions.syntaxErrors("${run goal}")).hasSize(1);
        assertThat(Expressions.syntaxErrors("${ok.path} ${also_ok | fallback}")).isEmpty();
    }

    @Test
    void conditionsEvaluateAndExplain() {
        assertThat(Conditions.evaluate(Map.of("path", "steps.qa.output.passed", "op", "eq", "value", true), ctx)).isFalse();
        assertThat(Conditions.evaluate(Map.of("path", "steps.qa.output.missing", "op", "contains", "value", "R5"), ctx)).isTrue();
        assertThat(Conditions.evaluate(Map.of("path", "steps.qa.status", "op", "in", "value", List.of("SUCCEEDED", "SKIPPED")), ctx)).isTrue();
        assertThat(Conditions.evaluate(Map.of("path", "items.size", "op", "gte", "value", 2), ctx)).isTrue();
        assertThat(Conditions.evaluate(Map.of("any", List.of(Map.of("path", "x", "op", "exists"),
                Map.of("path", "run.goal", "op", "notEmpty"))), ctx)).isTrue();
        assertThat(Conditions.evaluate(Map.of("not", Map.of("path", "run.inputs.mode", "op", "eq", "value", "go")), ctx)).isFalse();
        assertThat(Conditions.evaluate(Map.of("path", "run.inputs.mode", "op", "eq", "value", "${run.inputs.mode}"), ctx)).isTrue();
        assertThat(Conditions.evaluate(Map.of(), ctx)).isTrue();
        Conditions.Evaluation ev = Conditions.explain(Map.of("path", "steps.qa.output.passed", "op", "eq", "value", true), ctx);
        assertThat(ev.trace().get(0)).contains("actual false").endsWith("false");
    }

    @Test
    void conditionValidationCatchesUnknownOperatorsAndMissingValues() {
        List<String> errors = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        Conditions.validate(Map.of("all", List.of(Map.of("path", "a.b", "op", "matches", "value", 1),
                Map.of("path", "c", "op", "gt"))), "when", errors, paths);
        assertThat(errors).hasSize(2);
        assertThat(errors.get(0)).contains("unknown operator 'matches'");
        assertThat(errors.get(1)).contains("needs a 'value'");
        assertThat(paths).containsExactly("a.b", "c");
    }

    @Test
    void miniSchemaValidatesTypesRequiredAndBounds() {
        Map<String, Object> schema = Map.of("type", "object", "required", List.of("items"), "properties", Map.of(
                "items", Map.of("type", "array", "minItems", 1, "items", Map.of("type", "string")),
                "count", Map.of("type", "integer", "minimum", 0)));
        assertThat(MiniSchema.validate(schema, Map.of("items", List.of("a")))).isEmpty();
        assertThat(MiniSchema.validate(schema, Map.of("items", List.of()))).first().asString().contains("at least 1");
        assertThat(MiniSchema.validate(schema, Map.of("items", List.of(1)))).first().asString().contains("expected string");
        assertThat(MiniSchema.validate(schema, Map.of("count", -1))).hasSize(2);
        assertThat(MiniSchema.checkSchema(Map.of("type", "object", "pattern", "x"), "s")).first().asString()
                .contains("unsupported schema keyword 'pattern'");
        assertThat(MiniSchema.validate(Map.of(), "anything")).isEmpty();
    }
}
