package com.memora.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.memora.memory.MemoryType;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleBasedMemoryExtractorTest {

    private final RuleBasedMemoryExtractor extractor = new RuleBasedMemoryExtractor();

    private List<ExtractedMemory> run(String text) {
        return extractor.extract(new ExtractionInput(text, true));
    }

    private ExtractedMemory only(String text) {
        List<ExtractedMemory> out = run(text);
        assertThat(out).as("extractions for: %s", text).hasSize(1);
        return out.get(0);
    }

    @Test
    void usesStatementBecomesCategorizedFact() {
        ExtractedMemory m = only("We use PostgreSQL.");
        assertThat(m.type()).isEqualTo(MemoryType.SEMANTIC);
        assertThat(m.subject()).isEqualTo("project");
        assertThat(m.predicate()).isEqualTo("uses.database");
        assertThat(m.objectValue()).isEqualTo("postgresql");
        assertThat(m.content()).isEqualTo("The project uses PostgreSQL as its database.");
    }

    @Test
    void aliasesNormalizeToTheSameValue() {
        assertThat(only("We use Postgres for persistence.").objectValue()).isEqualTo("postgresql");
    }

    @Test
    void listOfTechnologiesBecomesOneFactEach() {
        List<ExtractedMemory> out = run("We use Java and Spring Boot for the backend.");
        assertThat(out).extracting(ExtractedMemory::predicate).containsExactly("uses.language", "uses.backend framework");
        assertThat(out).extracting(ExtractedMemory::objectValue).containsExactly("java", "spring boot");
    }

    @Test
    void switchProducesFactWithSameKeyAsUsesPlusAnEvent() {
        List<ExtractedMemory> out = run("We switched from MySQL to PostgreSQL.");
        assertThat(out).hasSize(2);
        ExtractedMemory fact = out.get(0);
        assertThat(fact.type()).isEqualTo(MemoryType.SEMANTIC);
        assertThat(fact.predicate()).isEqualTo(only("We use MySQL.").predicate());
        assertThat(fact.objectValue()).isEqualTo("postgresql");
        assertThat(fact.metadata()).containsEntry("previous", "mysql");
        assertThat(out.get(1).type()).isEqualTo(MemoryType.EPISODIC);
    }

    @Test
    void switchBetweenUnknownToolsKeysOnTheReplacedTool() {
        ExtractedMemory before = only("We use Trello.");
        ExtractedMemory after = run("We moved from Trello to Notion.").get(0);
        assertThat(after.predicate()).isEqualTo(before.predicate());
        assertThat(after.objectValue()).isEqualTo("notion");
    }

    @Test
    void personalPreferenceIsUserScopedAndOnlyFromHumans() {
        ExtractedMemory m = only("I prefer Java.");
        assertThat(m.userScoped()).isTrue();
        assertThat(m.predicate()).isEqualTo("prefers.language");
        assertThat(m.objectValue()).isEqualTo("java");
        assertThat(extractor.extract(new ExtractionInput("I prefer Java.", false))).isEmpty();
    }

    @Test
    void oppositePreferencesShareAKeySoTheNewerOneWins() {
        ExtractedMemory a = only("I prefer tabs over spaces.");
        ExtractedMemory b = only("I prefer spaces over tabs.");
        assertThat(a.predicate()).isEqualTo(b.predicate());
        assertThat(a.objectValue()).isNotEqualTo(b.objectValue());
    }

    @Test
    void unrelatedFactsGetDifferentKeys() {
        assertThat(only("We use PostgreSQL.").predicate()).isNotEqualTo(only("We use React.").predicate());
    }

    @Test
    void whenThenIsProcedural() {
        ExtractedMemory m = only("When an integration test fails, check database migrations first.");
        assertThat(m.type()).isEqualTo(MemoryType.PROCEDURAL);
        assertThat(m.metadata()).containsEntry("action", "check database migrations first");
    }

    @Test
    void alwaysNeverAndLessonsAreProcedural() {
        assertThat(only("Never log patient identifiers.").type()).isEqualTo(MemoryType.PROCEDURAL);
        assertThat(only("Lesson learned: pin dependency versions in CI.").category()).isEqualTo("lesson");
    }

    @Test
    void pastEventsAreEpisodicWithOutcome() {
        ExtractedMemory m = only("Yesterday the staging deploy failed because a migration was missing.");
        assertThat(m.type()).isEqualTo(MemoryType.EPISODIC);
        assertThat(m.metadata()).containsEntry("outcome", "failure");
        assertThat(only("We deployed version 2.3 to production.").metadata()).containsEntry("outcome", "success");
    }

    @Test
    void requirementsAndDecisionsAreSemanticWithoutConflictKeys() {
        ExtractedMemory req = only("The API must return JSON errors.");
        assertThat(req.category()).isEqualTo("requirement");
        assertThat(req.isFact()).isFalse();
        ExtractedMemory decision = only("We decided to use Flyway for migrations.");
        assertThat(decision.category()).isEqualTo("decision");
        assertThat(decision.isFact()).isFalse();
    }

    @Test
    void negationsQuestionsAndSmallTalkAreIgnored() {
        assertThat(run("We don't use MySQL anymore.")).isEmpty();
        assertThat(run("We are not using Kafka.")).isEmpty();
        assertThat(run("Do we use PostgreSQL?")).isEmpty();
        assertThat(run("Thanks, sounds good!")).isEmpty();
        assertThat(run("ok")).isEmpty();
    }

    @Test
    void credentialsAreNeverExtracted() {
        assertThat(run("Our database password is hunter2-super-secret.")).isEmpty();
        assertThat(run("We use the API key sk-abcdefghijklmnopqrstuvwxyz123456.")).isEmpty();
    }

    @Test
    void multipleSentencesAreHandledIndependently() {
        List<ExtractedMemory> out = run("We use Kotlin. When the build breaks, notify the on-call engineer. Great!");
        assertThat(out).extracting(ExtractedMemory::type).containsExactly(MemoryType.SEMANTIC, MemoryType.PROCEDURAL);
    }

    @Test
    void ourXIsUsesTheNamedCategory() {
        ExtractedMemory m = only("Our database is MongoDB.");
        assertThat(m.predicate()).isEqualTo("uses.database");
        assertThat(m.objectValue()).isEqualTo("mongodb");
    }
}
