package com.juancasimiro.mcpgateway.integration.rag;

import com.juancasimiro.mcpgateway.application.research.ResearchAnswer;
import com.juancasimiro.mcpgateway.application.research.ResearchGateway;
import com.juancasimiro.mcpgateway.application.research.ResearchQuestion;
import com.juancasimiro.mcpgateway.integration.rag.exception.RagCircuitOpenException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.stereotype.Component;

@Component
public class RagClient implements ResearchGateway {

    private final RagClientRequestExecutor requestExecutor;

    public RagClient(RagClientRequestExecutor requestExecutor) {
        this.requestExecutor = requestExecutor;
    }

    @Override
    public ResearchAnswer query(ResearchQuestion question) {
        try {
            return requestExecutor.query(question);
        } catch (CallNotPermittedException exception) {
            throw new RagCircuitOpenException(exception);
        }
    }
}
