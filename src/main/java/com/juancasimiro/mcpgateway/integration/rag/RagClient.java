package com.juancasimiro.mcpgateway.integration.rag;

import com.juancasimiro.mcpgateway.application.research.ResearchAnswer;
import com.juancasimiro.mcpgateway.application.research.ResearchGateway;
import com.juancasimiro.mcpgateway.application.research.ResearchQuestion;
import com.juancasimiro.mcpgateway.integration.rag.dto.RagQueryRequest;
import com.juancasimiro.mcpgateway.integration.rag.dto.RagQueryResponse;
import com.juancasimiro.mcpgateway.integration.rag.exception.RagContractException;
import com.juancasimiro.mcpgateway.integration.rag.exception.RagTimeoutException;
import com.juancasimiro.mcpgateway.integration.rag.exception.RagUnavailableException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;

@Component
public class RagClient implements ResearchGateway {

    private final RestClient ragRestClient;

    public RagClient(@Qualifier("ragRestClient") RestClient ragRestClient) {
        this.ragRestClient = ragRestClient;
    }

    @Override
    public ResearchAnswer query(ResearchQuestion question) {
        RagQueryRequest request = toRequest(question);

        RagQueryResponse response;
        try {
            response = ragRestClient.post()
                    .uri("/query")
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (httpRequest, httpResponse) -> {
                        HttpStatusCode statusCode = httpResponse.getStatusCode();
                        if (statusCode.isSameCodeAs(HttpStatus.GATEWAY_TIMEOUT)) {
                            throw new RagTimeoutException();
                        }
                        if (statusCode.is5xxServerError()) {
                            throw new RagUnavailableException();
                        }
                        throw new RagContractException();
                    })
                    .body(RagQueryResponse.class);
        } catch (ResourceAccessException exception) {
            if (hasCause(exception, SocketTimeoutException.class)) {
                throw new RagTimeoutException(exception);
            }
            throw new RagUnavailableException(exception);
        } catch (RestClientException exception) {
            throw new RagContractException(exception);
        }

        if (response == null) {
            throw new RagContractException();
        }

        return toResearchAnswer(response);
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> causeType) {
        Throwable current = throwable;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private RagQueryRequest toRequest(ResearchQuestion question) {
        return new RagQueryRequest(
                question.question(),
                question.resultCount(),
                false,
                false
        );
    }

    private ResearchAnswer toResearchAnswer(RagQueryResponse response) {
        return new ResearchAnswer(
                response.answer(),
                response.sources(),
                response.contextSufficient(),
                response.insufficiencyReason()
        );
    }
}
