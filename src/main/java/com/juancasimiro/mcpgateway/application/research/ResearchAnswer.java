package com.juancasimiro.mcpgateway.application.research;

import java.util.List;

public record ResearchAnswer(
        String answer,
        List<String> sources,
        boolean contextSufficient,
        String insufficiencyReason) {
}
