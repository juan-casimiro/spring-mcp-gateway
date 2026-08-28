package com.juancasimiro.mcpgateway.application.research;

import com.juancasimiro.mcpgateway.application.research.exception.InvalidResearchQuestionException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResearchQuestionTest {

    @Test
    void trimsQuestion() {
        ResearchQuestion question = new ResearchQuestion("  test question  ", 8);

        assertThat(question.question()).isEqualTo("test question");
    }

    @Test
    void acceptsBoundaryValues() {
        assertThat(new ResearchQuestion("a", 1).question()).isEqualTo("a");
        assertThat(new ResearchQuestion("a".repeat(1_000), 20).question()).hasSize(1_000);
    }

    @Test
    void rejectsNullQuestion() {
        assertThatThrownBy(() -> new ResearchQuestion(null, 8))
                .isInstanceOf(InvalidResearchQuestionException.class)
                .hasMessage("The research question is required.");
    }

    @Test
    void rejectsEmptyQuestion() {
        assertThatThrownBy(() -> new ResearchQuestion("", 8))
                .isInstanceOf(InvalidResearchQuestionException.class);
    }

    @Test
    void rejectsWhitespaceOnlyQuestion() {
        assertThatThrownBy(() -> new ResearchQuestion("   ", 8))
                .isInstanceOf(InvalidResearchQuestionException.class);
    }

    @Test
    void rejectsQuestionOverOneThousandCharacters() {
        assertThatThrownBy(() -> new ResearchQuestion("a".repeat(1_001), 8))
                .isInstanceOf(InvalidResearchQuestionException.class);
    }

    @Test
    void rejectsResultCountBelowOne() {
        assertThatThrownBy(() -> new ResearchQuestion("test question", 0))
                .isInstanceOf(InvalidResearchQuestionException.class);
    }

    @Test
    void rejectsResultCountAboveTwenty() {
        assertThatThrownBy(() -> new ResearchQuestion("test question", 21))
                .isInstanceOf(InvalidResearchQuestionException.class);
    }
}
