package com.juancasimiro.mcpgateway.integration.rag;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.juancasimiro.mcpgateway.application.research.ResearchQuestion;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "management.tracing.export.otlp.enabled=false",
        "management.otlp.metrics.export.enabled=false"
})
// Boot disables propagation with global tracing export; enable it while keeping OTLP off.
@AutoConfigureTracing
@EnableWireMock(@ConfigureWireMock(name = "rag-service", baseUrlProperties = "rag.base-url"))
class RagClientTracingTest {

    @Autowired
    private RagClient ragClient;

    @Autowired
    private Tracer tracer;

    @InjectWireMock("rag-service")
    private WireMockServer ragWireMock;

    @Test
    void propagatesCurrentTraceThroughTheConfiguredHttpClient() {
        ragWireMock.stubFor(post(urlEqualTo("/query")).willReturn(okJson("""
                {"answer":"test traced answer","sources":[],"context_sufficient":true}
                """)));
        Span parent = tracer.nextSpan().name("test-parent").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(parent)) {
            assertThat(ragClient.query(new ResearchQuestion("test trace", 8)).answer())
                    .isEqualTo("test traced answer");
        } finally {
            parent.end();
        }

        var requests = ragWireMock.findAll(postRequestedFor(urlEqualTo("/query")));
        assertThat(requests).singleElement().satisfies(request -> {
            String traceparent = request.getHeader("traceparent");
            assertThat(traceparent).matches("00-" + parent.context().traceId() + "-[0-9a-f]{16}-[0-9a-f]{2}");
            assertThat(Integer.parseInt(traceparent.split("-")[3], 16) & 1).isEqualTo(1);
            assertThat(traceparent.split("-")[2]).isNotEqualTo(parent.context().spanId());
        });
    }
}
