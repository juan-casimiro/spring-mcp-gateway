package com.juancasimiro.mcpgateway.integration.rag;

import com.juancasimiro.mcpgateway.application.research.ResearchAnswer;
import com.juancasimiro.mcpgateway.application.research.ResearchQuestion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EnabledIfSystemProperty(
        named = "live.rag.enabled",
        matches = "true"
)
@Tag("integration")
class RagClientIT {
    @Autowired
    private RagClient ragClient;

    @Test
    void queriesLiveRagService() {
        ResearchAnswer response = ragClient.query(
                new ResearchQuestion(
                        "What does CT-FFR measure in coronary artery disease?",
                        8
                )
        );

        assertThat(response).isNotNull();
        assertThat(response.answer()).isNotBlank();
        assertThat(response.sources()).isNotEmpty();
    }

    @Test
    void queriesLiveRagServiceInsufficientContext() {
        ResearchAnswer response = ragClient.query(
                new ResearchQuestion(
           "Give me the percentage of diabetes found on wild jelly fish",
            8
        ));
        assertThat(response.answer()).isNotBlank();
        assertThat(response.contextSufficient()).isFalse();
        assertThat(response.insufficiencyReason()).isNotBlank();
    }
}
