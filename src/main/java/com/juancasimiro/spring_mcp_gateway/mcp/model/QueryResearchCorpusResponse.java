package com.juancasimiro.spring_mcp_gateway.mcp.model;

import java.util.List;

public record QueryResearchCorpusResponse(
        String answer,
        List<String> sources,
        boolean contextSufficient,
        String insufficiencyReason) {
}
