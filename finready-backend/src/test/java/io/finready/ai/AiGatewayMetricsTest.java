package io.finready.ai;

import com.anthropic.models.messages.OutputConfig;
import com.sun.net.httpserver.HttpServer;
import io.finready.common.ApiException;
import io.finready.common.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Real SDK -> local HTTP fixture; no Docker or actual Anthropic credentials. */
class AiGatewayMetricsTest {

	@Test
	void successfulCallRecordsOneLogicalCallAndReleasesActiveGauge() throws Exception {
		try (Fixture fixture = new Fixture()) {
			String result = fixture.gateway.call(call(), text -> {
				assertThat(fixture.registry.get("finready.ai.active").gauge().value()).isEqualTo(1);
				return text;
			});
			assertThat(result).isEqualTo("fixture-response");
			assertThat(fixture.requests.get()).isEqualTo(1);
			assertThat(fixture.registry.get("finready.ai.call").tag("outcome", "success").timer().count()).isEqualTo(1);
			assertThat(fixture.registry.get("finready.ai.active").gauge().value()).isZero();
			verify(fixture.recorder).record(any());
		}
	}

	@Test
	void parsingRetriesRemainTwoAttemptsButOneFailedLogicalCall() throws Exception {
		try (Fixture fixture = new Fixture()) {
			ApiException failure = catchThrowableOfType(ApiException.class, () ->
					fixture.gateway.call(call(), text -> { throw new AiGateway.ResponseParseException("fixture"); }));
			assertThat(failure.code()).isEqualTo(ErrorCode.AI_PARSING_FAILED);
			assertThat(fixture.requests.get()).isEqualTo(2);
			assertThat(fixture.registry.get("finready.ai.call").tag("outcome", "parse_error").timer().count()).isEqualTo(1);
			assertThat(fixture.registry.get("finready.ai.active").gauge().value()).isZero();
			verify(fixture.recorder, times(2)).record(any());
		}
	}

	private static AiGateway.AiCall call() {
		return new AiGateway.AiCall("S-LOCAL", "COVERAGE_CLASSIFY", "test", "system", "user", "fixture", 100L,
				OutputConfig.Effort.MEDIUM);
	}

	private static class Fixture implements AutoCloseable {
		final SimpleMeterRegistry registry = new SimpleMeterRegistry();
		final LlmCallRecorder recorder = mock(LlmCallRecorder.class);
		final AtomicInteger requests = new AtomicInteger();
		final HttpServer server;
		final AiGateway gateway;

		Fixture() throws Exception {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/v1/messages", exchange -> {
				requests.incrementAndGet();
				exchange.getRequestBody().readAllBytes();
				byte[] body = """
						{"id":"msg_test","type":"message","role":"assistant","model":"claude-sonnet-4-6",
						"content":[{"type":"text","text":"fixture-response"}],"stop_reason":"end_turn",
						"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":10}}
						""".getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
				exchange.close();
			});
			server.start();
			gateway = new AiGateway(new AiProperties("local-only", "claude-sonnet-4-6",
					"http://127.0.0.1:" + server.getAddress().getPort(), 5, 1, null), recorder, registry);
		}

		@Override
		public void close() {
			server.stop(0);
			registry.close();
		}
	}
}
