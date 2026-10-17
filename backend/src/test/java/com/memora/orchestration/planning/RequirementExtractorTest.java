package com.memora.orchestration.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RequirementExtractorTest {

    @Test
    void splitsTheDemoGoalIntoCategorisedRequirements() {
        RequirementExtractor.Result r = RequirementExtractor.extract("Build a full-stack e-commerce website with responsive design, "
                + "authentication, product search, a shopping cart, order management, database persistence, and automated tests.", 30);
        assertThat(r.subject()).isEqualTo("full-stack e-commerce website");
        assertThat(r.requirements()).extracting(RequirementExtractor.Requirement::feature).containsExactly("responsive design",
                "authentication", "product search", "shopping cart", "order management", "database persistence", "automated tests");
        assertThat(r.requirements()).extracting(RequirementExtractor.Requirement::category).containsExactly("interface",
                "authentication", "search", "cart", "orders", "persistence", "testing");
        assertThat(r.requirements().get(1).acceptance()).contains("Users can register, sign in and sign out");
        assertThat(r.requirements().get(0).id()).isEqualTo("R1");
        assertThat(r.missingInformation()).anyMatch(m -> m.contains("sign-in methods"));
    }

    @Test
    void bulletListsModalSentencesAndVagueItems() {
        RequirementExtractor.Result r = RequirementExtractor.extract("""
                - Export reports as CSV
                - Nice to have: dark mode
                - stuff etc
                The system must log every admin action.""", 30);
        assertThat(r.requirements()).extracting(RequirementExtractor.Requirement::feature)
                .contains("Export reports as CSV", "system must log every admin action");
        assertThat(r.requirements()).filteredOn(q -> q.feature().contains("dark mode"))
                .extracting(RequirementExtractor.Requirement::priority).containsExactly("SHOULD");
        assertThat(r.unclear()).contains("stuff etc");
    }

    @Test
    void keywordsMatchAtWordStartsOnly() {
        assertThat(RequirementExtractor.categorize("show the latest news")).isEqualTo("general");
        assertThat(RequirementExtractor.categorize("automated tests")).isEqualTo("testing");
        assertThat(RequirementExtractor.categorize("build pipeline")).isNotEqualTo("interface");
        assertThat(RequirementExtractor.extract("Hello", 10).requirements()).isEmpty();
        assertThat(List.of(RequirementExtractor.extract("Research vector databases for semantic search", 10).verb())).containsExactly("research");
    }
}
