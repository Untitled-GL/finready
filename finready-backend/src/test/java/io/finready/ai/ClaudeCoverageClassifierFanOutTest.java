package io.finready.ai;

import io.finready.common.ApiException;
import io.finready.common.ErrorCode;
import io.finready.coverage.CoverageClassifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 팬아웃(9개 → 3개씩 3회 동시)의 계약을 고정한다. <b>LLM 을 부르지 않는다.</b>
 *
 * <p>게이트웨이 목이 <b>넘어온 파서를 실제로 호출</b>한다. 그냥 값을 돌려주게 하면 파싱과
 * 배치 검증이 한 줄도 안 돌아서, 이 테스트가 지키려는 것의 절반이 사라진다.
 *
 * <p><b>가장 중요한 것은 {@code CachePrefix} 절이다.</b> 서브배치마다 system 프롬프트가
 * 갈라지면 prompt caching 이 통째로 죽는데, 그 사고는 예외도 로그도 테스트 실패도 없이
 * <b>청구서로만</b> 드러난다 — 여기서 안 잡으면 배포 후에도 안 잡힌다.
 *
 * <p>판정 품질은 여기서 보지 않는다. 그건 {@code CoverageBaselineComparisonTest} 가
 * 실제 모델로 잰다.
 */
@DisplayName("ClaudeCoverageClassifier — 배치 팬아웃")
class ClaudeCoverageClassifierFanOutTest {

	private static final String SESSION_ID = "S-1";
	private static final String TRANSCRIPT =
			"이 상품은 원금이 보장되지 않습니다. 최대 손실률은 마이너스 100%입니다.";

	private static final List<String> BATCH_1 = List.of("R01", "R02", "R03");
	private static final List<String> BATCH_2 = List.of("R04", "R05", "R06");
	private static final List<String> BATCH_3 = List.of("R07", "R08", "R09");

	private final AiGateway gateway = mock(AiGateway.class);
	private final ClaudeCoverageClassifier classifier = new ClaudeCoverageClassifier(gateway);

	/** 배치가 동시에 나가므로 기록도 동시에 들어온다 */
	private final List<AiGateway.AiCall> calls = Collections.synchronizedList(new ArrayList<>());

	@Nested
	@DisplayName("분할")
	class Splitting {

		@Test
		@DisplayName("9개가 3개씩 3회로 갈린다")
		void nineRisksSplitIntoThreeCalls() {
			gatewayEchoesRequestedIds();

			classifier.classify(SESSION_ID, TRANSCRIPT, risks(9));

			verify(gateway, times(3)).call(any(), any());
			assertThat(batchesInOrder()).containsExactly(BATCH_1, BATCH_2, BATCH_3);
		}

		@Test
		@DisplayName("같은 입력이면 분할도 같다 — 재현 조건이다")
		void splitIsDeterministic() {
			gatewayEchoesRequestedIds();

			classifier.classify(SESSION_ID, TRANSCRIPT, risks(9));
			List<List<String>> first = batchesInOrder();

			calls.clear();
			classifier.classify("S-2", TRANSCRIPT, risks(9));
			List<List<String>> second = batchesInOrder();

			assertThat(second).isEqualTo(first);
			assertThat(first).containsExactly(BATCH_1, BATCH_2, BATCH_3);
		}

		@Test
		@DisplayName("배치 크기 이하면 1회만 부른다 — 쪼갤 이유가 없다")
		void singleBatchWhenRisksFitInOneCall() {
			gatewayEchoesRequestedIds();

			List<CoverageClassifier.RiskVerdict> verdicts =
					classifier.classify(SESSION_ID, TRANSCRIPT, risks(3));

			verify(gateway, times(1)).call(any(), any());
			assertThat(verdicts).hasSize(3);
			assertThat(calls.getFirst().userMessage()).contains("## 판정 대상", "R01, R02, R03");
			assertThat(calls.getFirst().systemPrompt()).contains("## 이번 호출의 판정 대상");
		}

		@Test
		@DisplayName("빈 목록이면 아예 부르지 않는다 — 판정할 것이 없는 호출에 요금을 물지 않는다")
		void emptyRiskListMakesNoCall() {
			List<CoverageClassifier.RiskVerdict> verdicts =
					classifier.classify(SESSION_ID, TRANSCRIPT, List.of());

			assertThat(verdicts).isEmpty();
			verify(gateway, never()).call(any(), any());
		}
	}

	@Nested
	@DisplayName("캐시 prefix")
	class CachePrefix {

		/** 이 테스트가 이 파일에서 가장 중요하다. 깨진 채로 배포되면 신호가 청구서뿐이다 */
		@Test
		@DisplayName("모든 서브배치의 system 프롬프트가 바이트 단위로 같고 9개 riskId 를 전부 담는다")
		void systemPromptIsByteIdenticalAndCarriesEveryRisk() {
			gatewayEchoesRequestedIds();

			classifier.classify(SESSION_ID, TRANSCRIPT, risks(9));

			Set<String> systemPrompts = calls.stream()
					.map(AiGateway.AiCall::systemPrompt)
					.collect(Collectors.toSet());

			assertThat(calls).hasSize(3);
			assertThat(systemPrompts)
					.as("서브배치마다 system 이 다르면 캐시 prefix 가 셋으로 갈라진다")
					.hasSize(1);

			assertThat(systemPrompts.iterator().next())
					.contains("R01", "R02", "R03", "R04", "R05", "R06", "R07", "R08", "R09")
					.contains("## 이번 호출의 판정 대상");

			assertThat(calls).extracting(AiGateway.AiCall::promptVersion).containsOnly("coverage-v3-b3");
			assertThat(classifier.promptVersion()).isEqualTo("coverage-v3-b3");
		}

		@Test
		@DisplayName("배치 멤버십은 user 메시지에만 있다 — system 으로 올리면 캐시가 죽는다")
		void batchMembershipLivesOnlyInUserMessage() {
			gatewayEchoesRequestedIds();

			classifier.classify(SESSION_ID, TRANSCRIPT, risks(9));

			for (String membership : List.of("R01, R02, R03", "R04, R05, R06", "R07, R08, R09")) {
				assertThat(calls)
						.as("배치 %s 를 요청한 호출이 있어야 한다", membership)
						.anySatisfy(call -> assertThat(call.userMessage()).contains(membership));
				assertThat(calls)
						.as("배치 %s 가 system 에 들어가면 캐시 prefix 가 갈라진다", membership)
						.allSatisfy(call -> assertThat(call.systemPrompt()).doesNotContain(membership));
			}
		}
	}

	@Nested
	@DisplayName("병합")
	class Merging {

		@Test
		@DisplayName("배치가 순서를 흔들어 답해도 병합 결과는 riskId 오름차순이다")
		void mergedVerdictsAreRiskIdAscending() {
			gatewayRespondsWith(call -> {
				List<String> reversed = new ArrayList<>(requestedIds(call));
				Collections.reverse(reversed);
				return resultsJson(reversed);
			});

			List<CoverageClassifier.RiskVerdict> verdicts =
					classifier.classify(SESSION_ID, TRANSCRIPT, risks(9));

			assertThat(verdicts).extracting(CoverageClassifier.RiskVerdict::riskId)
					.containsExactly("R01", "R02", "R03", "R04", "R05", "R06", "R07", "R08", "R09");
		}
	}

	@Nested
	@DisplayName("배치별 검증")
	class PerBatchValidation {

		@Test
		@DisplayName("배치 밖 riskId 를 내면 파싱 실패다 — union 으로는 안 걸린다")
		void verdictOutsideBatchIsParseFailure() {
			gatewayRespondsWith(call -> {
				List<String> requested = requestedIds(call);
				return requested.contains("R04")
						? resultsJson(List.of("R04", "R05", "R09"))
						: resultsJson(requested);
			});

			Throwable thrown = catchThrowable(() -> classifier.classify(SESSION_ID, TRANSCRIPT, risks(9)));

			assertThat(thrown)
					.isInstanceOf(AiGateway.ResponseParseException.class)
					.hasMessageContaining("R09");
		}

		@Test
		@DisplayName("요청한 riskId 를 빠뜨리면 파싱 실패다 (규칙 9)")
		void missingVerdictIsParseFailure() {
			gatewayRespondsWith(call -> {
				List<String> requested = requestedIds(call);
				return requested.contains("R04")
						? resultsJson(List.of("R04", "R05"))
						: resultsJson(requested);
			});

			Throwable thrown = catchThrowable(() -> classifier.classify(SESSION_ID, TRANSCRIPT, risks(9)));

			assertThat(thrown)
					.isInstanceOf(AiGateway.ResponseParseException.class)
					.hasMessageContaining("R06");
		}
	}

	@Nested
	@DisplayName("실패 전파")
	class FailurePropagation {

		@Test
		@DisplayName("서브배치 실패는 ApiException 그대로 올라온다 — CompletionException 에 싸이지 않는다")
		void subBatchFailureSurfacesAsApiException() {
			when(gateway.call(any(), any())).thenAnswer(invocation -> {
				AiGateway.AiCall call = invocation.getArgument(0);
				AiGateway.ResponseParser<?> parser = invocation.getArgument(1);
				calls.add(call);
				if (requestedIds(call).contains("R04")) {
					throw new ApiException(ErrorCode.AI_PARSING_FAILED,
							"AI 분석이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.");
				}
				return parser.parse(resultsJson(requestedIds(call)));
			});

			Throwable thrown = catchThrowable(() -> classifier.classify(SESSION_ID, TRANSCRIPT, risks(9)));

			assertThat(thrown)
					.isInstanceOf(ApiException.class)
					.isNotInstanceOf(CompletionException.class);
			assertThat(((ApiException) thrown).code()).isEqualTo(ErrorCode.AI_PARSING_FAILED);
		}
	}

	@Nested
	@DisplayName("관측")
	class Observability {

		/**
		 * 11번째 테스트(설계 검증에서 추가) — MDC 전파에 대한 유일한 안전망이다.
		 * 없으면 배치 3개의 로그 줄이 requestId 없이 찍혀도 아무것도 실패하지 않는다.
		 */
		@Test
		@DisplayName("워커 스레드가 호출 스레드의 MDC(requestId)를 물려받고, 끝나면 캐리어를 더럽히지 않는다")
		void mdcPropagatesToWorkersAndIsClearedAfter() {
			Set<String> seenOnWorkers = new CopyOnWriteArraySet<>();
			gatewayRespondsWith(call -> {
				seenOnWorkers.add(MDC.get("requestId"));
				return resultsJson(requestedIds(call));
			});

			MDC.put("requestId", "REQ-1");
			try {
				classifier.classify(SESSION_ID, TRANSCRIPT, risks(9));
			} finally {
				assertThat(MDC.get("requestId"))
						.as("호출 스레드 자신의 MDC 는 이 메서드가 건드리면 안 된다")
						.isEqualTo("REQ-1");
				MDC.remove("requestId");
			}

			assertThat(seenOnWorkers).containsExactly("REQ-1");
		}
	}

	// ------------------------------------------------------------------
	// 픽스처
	// ------------------------------------------------------------------

	private static List<CoverageClassifier.RiskPrompt> risks(int count) {
		return IntStream.rangeClosed(1, count)
				.mapToObj(index -> new CoverageClassifier.RiskPrompt(
						"R%02d".formatted(index),
						"위험 %d".formatted(index),
						"위험 %d 의 검수된 사실".formatted(index)))
				.toList();
	}

	private void gatewayRespondsWith(Function<AiGateway.AiCall, String> responder) {
		when(gateway.call(any(), any())).thenAnswer(invocation -> {
			AiGateway.AiCall call = invocation.getArgument(0);
			AiGateway.ResponseParser<?> parser = invocation.getArgument(1);
			calls.add(call);
			return parser.parse(responder.apply(call));
		});
	}

	private void gatewayEchoesRequestedIds() {
		gatewayRespondsWith(call -> resultsJson(requestedIds(call)));
	}

	private static String resultsJson(List<String> riskIds) {
		return riskIds.stream()
				.map(riskId -> ("{\"riskId\":\"%s\",\"status\":\"NOT_FOUND\","
						+ "\"reason\":\"언급 없음\",\"evidenceText\":null}").formatted(riskId))
				.collect(Collectors.joining(",", "{\"results\":[", "]}"));
	}

	private static List<String> requestedIds(AiGateway.AiCall call) {
		String marker = "## 판정 대상\n\n";
		int start = call.userMessage().indexOf(marker);
		assertThat(start).as("user 메시지에 판정 대상 절이 있어야 한다").isNotNegative();
		int from = start + marker.length();
		int to = call.userMessage().indexOf('\n', from);
		return Arrays.stream(call.userMessage().substring(from, to).split(","))
				.map(String::trim)
				.toList();
	}

	private List<List<String>> batchesInOrder() {
		return calls.stream()
				.map(ClaudeCoverageClassifierFanOutTest::requestedIds)
				.sorted(Comparator.comparing((List<String> batch) -> batch.getFirst()))
				.toList();
	}
}
