package com.juancasimiro.spring_mcp_gateway.application.research;

import java.util.List;

public record ResearchAnswer(
        String answer,
        List<String> sources,
        boolean contextSufficient,
        String insufficiencyReason) {
}
