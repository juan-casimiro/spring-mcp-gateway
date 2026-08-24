package com.juancasimiro.spring_mcp_gateway;

import com.juancasimiro.spring_mcp_gateway.model.RagQueryRequest;
import com.juancasimiro.spring_mcp_gateway.model.RagQueryResponse;
import com.juancasimiro.spring_mcp_gateway.services.RagClient;
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
        RagQueryResponse response = ragClient.query(
                new RagQueryRequest(
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
        RagQueryResponse response = ragClient.query(
                new RagQueryRequest(
           "Give me the percentage of diabetes found on wild jelly fish",
            8
        ));
        assertThat(response.answer()).isNotBlank();
        assertThat(response.contextSufficient()).isFalse();
        assertThat(response.insufficiencyReason()).isNotBlank();
    }
}
