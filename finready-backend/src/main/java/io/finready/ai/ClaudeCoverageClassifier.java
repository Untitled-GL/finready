package io.finready.ai;

import com.anthropic.models.messages.OutputConfig;
import io.finready.coverage.CoverageClassifier;
import io.finready.coverage.CoverageStatus;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * F03 Coverage 분류기 — Claude 구현.
 *
 * <p>Risk 를 <b>{@value #RISKS_PER_CALL} 개씩 나눠 동시에</b> 분류한다. v2 까지는 9개를 한 번에
 * 물었는데, 실측 회귀에서 이 단계의 레이턴시가 <b>출력 토큰에 비례</b>하고 고정 오버헤드는
 * 4,562ms 뿐임이 확인됐다(n=31). 출력을 3등분하면 세 배치가 병렬로 도는 동안 각자의 출력이
 * 1/3 이 되어 16.4s → 8.5s 가 예측된다 (TRD §14 의 "부분 병렬화").
 *
 * <p><b>Risk 마다 부르지는 않는다.</b> 9회로 쪼개면 고정 오버헤드 4.5초를 9번 물고, 캐시 쓰기도
 * 9번이다. 분할의 이득은 출력 토큰에서 나오지 호출 수에서 나오지 않는다.
 *
 * <p>evidence 의 offset 을 <b>받지 않는다</b>. 포트가 아예 필드를 두지 않았고(규칙 4),
 * 서버가 원문에서 다시 찾는다. 모델에게 위치를 묻지 않으면 틀린 위치를 받을 일도 없다.
 *
 * <p><b>Risk 카탈로그는 전부 system 에 둔다 — 배치마다 자르지 않는다.</b> 이유가 둘이다.
 * ① 캐시는 prefix 일치라 배치마다 다른 카탈로그를 넣으면 <b>캐시 prefix 가 배치 수만큼 갈라져</b>
 * 전부 miss 가 된다. 그 사고는 오류도 로그도 테스트 실패도 없이 <b>청구서로만</b> 나타난다.
 * ② "인용하려는 문장이 다른 항목의 주제에 더 가깝다면 NOT_FOUND" 라는 경계 규칙은 다른 항목의
 * <b>정의</b>를 필요로 한다 — 판정 결과가 아니라. 정의를 빼면 그 규칙이 근거를 잃는다.
 *
 * <p>배치 멤버십은 {@link #buildUserMessage} 에만 들어간다. system 으로 올리는 순간 위 ①이 터진다.
 */
class ClaudeCoverageClassifier implements CoverageClassifier {

	/**
	 * 프롬프트를 고치면 반드시 올린다 — Hold-out 재현 조건이다 (TRD §7.2).
	 *
	 * <p>v1 → v2: Risk 카탈로그를 system 으로 이동(캐싱), 핵심/부연 구분 원칙 추가,
	 * NOT_FOUND 정당화 + Risk 경계 규칙 추가, reason 길이 제한.
	 *
	 * <p>v2 → v3: <b>팬아웃.</b> {@link #BATCH_SCOPE} 문단이 system 에 붙었고, user 메시지에
	 * "판정 대상" 절이 생겼다. 프롬프트 본문이 달라졌으므로 버전을 올린다.
	 *
	 * <p>실제로 기록·노출되는 값은 여기에 배치 크기를 붙인 {@link #promptVersion} 이다 —
	 * 같은 프롬프트라도 <b>배치 크기가 다르면 다른 실험</b>이고, 그것이 문자열에 남지 않으면
	 * {@code llm_call_log} 만 보고는 어느 조건의 행인지 복원할 수 없다.
	 */
	private static final String PROMPT_VERSION = "coverage-v3";
	private static final String STAGE = "COVERAGE_CLASSIFY";

	/**
	 * 한 호출이 판정하는 Risk 수. <b>{@code AiProperties} 로 빼지 않는다.</b>
	 *
	 * <p>프로퍼티가 되면 ① 그 record 의 구성요소가 6→7 이 되어 생성 지점 6곳이 깨지고,
	 * ② TRD §7.2 가 {@code prompt_version} 을 재현 조건으로 규정하는데 <b>런타임에 바뀌는
	 * 배치 크기</b>는 재현 불가능한 실험을 운영에서 돌릴 수 있게 한다.
	 *
	 * <p>스윕은 테스트 전용 생성자({@link #ClaudeCoverageClassifier(AiGateway, int)}) 로 한다.
	 *
	 * <p><b>3을 넘기지 않는다.</b> {@code AiGateway.call()} 의 finally 가 REQUIRES_NEW 로
	 * {@code LlmCallRecorder.record()} 를 부르므로 한 요청이 순간 커넥션을 배치 수만큼 쓴다.
	 * Hikari 풀이 5(DB role 한도와 맞춤)라 3을 넘기면 다른 요청과 경합한다.
	 */
	private static final int RISKS_PER_CALL = 3;

	/** 분류는 구조화 추출에 가깝다. 다만 CONTRADICTED 판별에 의미 추론이 필요해 low 로는 내리지 않는다 */
	private static final OutputConfig.Effort EFFORT = OutputConfig.Effort.MEDIUM;

	private static final String INSTRUCTIONS = """
			당신은 ELS(주가연계증권) 상담 내용을 검토하는 보조 도구다.
			상담원이 각 위험 항목을 고객에게 설명했는지를 항목 단위로 판정한다.

			판정은 법적 효력이 없으며 상담원을 돕기 위한 참고 자료다.

			## 판정 기준

			각 위험 항목에 대해 다음 4가지 중 하나로 판정한다.

			- EXPLAINED: 해당 위험의 핵심이 상담 내용에 전달되었다.
			- INSUFFICIENT: 해당 위험을 다루기는 했으나 핵심이 빠졌다.
			- NOT_FOUND: 해당 위험을 다룬 내용이 상담에 없다.
			- CONTRADICTED: 해당 위험을 사실과 반대로 설명했거나 오해를 유발했다.

			## 무엇이 "핵심"인가

			각 위험 항목의 설명에는 핵심과 부연이 섞여 있다. 판정 기준은
			**고객이 이 위험 때문에 무엇을 잃을 수 있는지 알게 되었는가**다.

			- 핵심: 그 위험이 실제로 존재한다는 것, 그리고 고객이 입을 수 있는 불이익
			- 부연: 그 판정을 계산하는 방법 — 기준 수치, 산식, 차수, 배리어 값 등

			**부연이 빠졌다고 INSUFFICIENT로 내리지 않는다.** 핵심이 전달되었으면 EXPLAINED다.

			예시:
			- "원금이 보장되지 않고 손실이 날 수 있다"까지 말했다면 원금 손실 위험은 EXPLAINED다.
			  손실 판정 기준선(예: 65%)을 말하지 않았다는 이유로 내리지 않는다.
			- 반대로 손실 **범위**가 핵심인 항목이라면, 범위를 말하지 않은 것은 핵심 누락이다.
			  "손실이 날 수도 있다"만으로는 부족하다.

			판단이 서면 그 항목의 설명 첫 문장이 무엇을 말하는지 보라. 대체로 그것이 핵심이다.

			## 언급이 없으면 NOT_FOUND다

			NOT_FOUND는 흔하고 정상적인 판정이다. 상담에서 다루지 않은 위험이 있는 것이
			이 도구가 찾으려는 것이므로, 억지로 근거를 만들지 않는다.

			- 해당 위험을 다룬 문장이 없으면 NOT_FOUND로 판정하고 evidenceText를 null로 둔다.
			- 관련 있어 보이는 문장을 찾아내려 애쓰지 않는다.

			## 위험 항목 사이의 경계

			각 위험 항목은 서로 다른 것을 다룬다. 한 문장을 여러 항목의 근거로 재사용하지 않는다.

			- 인용하려는 문장이 **다른 항목의 주제에 더 가깝다면**, 이 항목에 대해서는
			  근거가 없는 것이다. NOT_FOUND로 판정한다.
			- 단어가 겹친다고 같은 주제가 아니다. 예를 들어 "조건이 까다롭다"는 표현은
			  기초자산 개수를 말하는 것일 수도, 상환 조건을 말하는 것일 수도 있다.
			  문장이 실제로 무엇을 설명하는지 보고 판단한다.

			## 그 밖의 판정 원칙

			1. 단어가 등장한다고 설명된 것이 아니다. 어떤 항목의 주제를 다루는 문장이 있지만
			   핵심을 설명하지 않았다면 INSUFFICIENT다. 주제 자체가 없으면 NOT_FOUND다.
			2. 위험을 축소하거나 반대 의미로 전달한 경우 CONTRADICTED다. 예를 들어
			   "낙인이 없다"는 사실을 "원금이 지켜진다"는 의미로 전달했다면 CONTRADICTED다.
			   다만 원금 손실 가능성을 이미 명확히 고지한 뒤 구조를 부연한 것은 오해 유발이 아니다.
			3. 상담 내용에 없는 것을 추측해서 채우지 않는다.

			## 근거 인용 규칙

			판정의 근거가 되는 구간을 상담 내용에서 **그대로 복사**해 evidenceText에 넣는다.

			- 반드시 상담 내용에 있는 문자열을 글자 그대로 옮긴다. 요약하거나 다듬지 않는다.
			- 15자 이상 300자 이하로 인용한다.
			- 상담 내용 전체에서 그 구간이 한 번만 나타나도록 충분히 길게 인용한다.
			- 근거가 없으면(NOT_FOUND 등) evidenceText를 null로 둔다.

			## 출력 형식

			다른 설명 없이 JSON만 출력한다.

			{"results":[{"riskId":"R01","status":"EXPLAINED","reason":"판정 근거","evidenceText":"상담 내용에서 그대로 복사한 구간"}]}

			- 요청받은 모든 riskId에 대해 정확히 하나씩 결과를 낸다. 빠뜨리거나 중복하지 않는다.
			- status는 위 4가지 값 중 하나만 쓴다. 다른 문자열을 만들지 않는다.
			- reason은 한 문장, 60자 이내로 쓴다.
			""";

	/**
	 * <b>상수다.</b> 배치마다 달라지는 값이 한 글자라도 들어가면 캐시 prefix 가 배치 수만큼
	 * 갈라지고, 그 사고는 오류 없이 청구서로만 드러난다. 실제 목록은 user 메시지에 있다.
	 *
	 * <p>이 문단이 없으면 모델은 카탈로그에 있는 9개 전부에 대해 결과를 내려 하고,
	 * 그러면 배치 밖 riskId 가 섞여 {@link #parseBatch} 가 매번 파싱 실패로 되돌린다.
	 */
	private static final String BATCH_SCOPE = """
			## 이번 호출의 판정 대상

			사용자 메시지에 "판정 대상" 목록이 주어진다. **그 목록에 있는 riskId만** 결과로 낸다.

			- 목록에 없는 항목도 위 카탈로그에 그대로 실려 있다. **항목 사이의 경계를 가르라고**
			  둔 것이며 결과에는 포함하지 않는다.
			- 목록에 없는 항목이 상담에서 잘 설명되었더라도 이번 결과에 넣지 않는다.
			  그 항목은 다른 호출에서 판정한다.
			- 결과의 개수는 판정 대상 목록의 개수와 정확히 같아야 한다.
			""";

	private final AiGateway gateway;
	private final int risksPerCall;

	/** 배치 크기를 붙인 실제 버전 문자열. {@code llm_call_log.prompt_version}(varchar(32)) 에 들어간다 */
	private final String promptVersion;

	ClaudeCoverageClassifier(AiGateway gateway) {
		this(gateway, RISKS_PER_CALL);
	}

	/**
	 * <b>배치 크기 스윕 전용</b>이다. 운영 호출부는 없다 — {@code AiPortConfig} 는 1-인자
	 * 생성자를 쓴다.
	 *
	 * <p>설정이 아니라 생성자인 이유: 프로퍼티로 열면 재현 불가능한 조건이 운영에서 돌 수 있다.
	 * 여기로 열면 그 조건을 쓸 수 있는 것은 {@code EvalClassifierFactory} 뿐이고, 그렇게 돌린
	 * 행은 {@link #promptVersion} 에 배치 크기가 박혀 나중에도 구분된다.
	 */
	ClaudeCoverageClassifier(AiGateway gateway, int risksPerCall) {
		if (risksPerCall < 1) {
			throw new IllegalArgumentException("risksPerCall 은 1 이상이어야 한다: " + risksPerCall);
		}
		this.gateway = gateway;
		this.risksPerCall = risksPerCall;
		this.promptVersion = "%s-b%d".formatted(PROMPT_VERSION, risksPerCall);
	}

	@Override
	public String promptVersion() {
		return promptVersion;
	}

	@Override
	public List<RiskVerdict> classify(String sessionId, String transcript, List<RiskPrompt> risks) {
		if (risks.isEmpty()) {
			// 판정할 것이 없는 호출에 요금과 4.5초를 물지 않는다. v2 는 빈 목록으로도 한 번 나갔다
			return List.of();
		}

		// 배치마다 다시 만들지 않는다. 같은 문자열 참조를 세 호출이 공유해야
		// "바이트 동일" 이 실수로 깨질 여지가 없다
		String systemPrompt = buildSystemPrompt(risks);
		List<List<RiskPrompt>> batches = split(risks);

		if (batches.size() == 1) {
			// 스레드·MDC 복사·executor 를 만들 이유가 없다. 프롬프트 모양은 위와 완전히 같다
			return classifyBatch(sessionId, transcript, systemPrompt, batches.getFirst(), risks.size());
		}

		// 가상 스레드는 MDC 를 상속하지 않는다. 지금 복사해 두지 않으면 워커에서 읽을 방법이 없다
		Map<String, String> callerContext = MDC.getCopyOfContextMap();

		// try-with-resources 의 close() 가 종료를 기다린다. 한 배치가 먼저 실패해도 형제를
		// 취소하지 않고 기다리는데 이건 의도한 것이다 — OkHttp 의 동기 execute() 는 중단되지
		// 않아 취소는 흉내에 그치고, 기다리면 형제의 llm_call_log 행이 온전히 남는다.
		// 대기 시간은 SDK 타임아웃 60초 × 재시도 2회로 이미 묶여 있고, 이는 v2 의 최악값과 같다.
		//
		// StructuredTaskScope 를 쓰지 않는다 — JDK 25 에서 preview 다
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			List<CompletableFuture<List<RiskVerdict>>> futures = batches.stream()
					.map(batch -> CompletableFuture.supplyAsync(
							() -> withMdc(callerContext, () -> classifyBatch(
									sessionId, transcript, systemPrompt, batch, risks.size())),
							executor))
					.toList();

			// 배치 순서대로 join 한다. 완료 순서가 아니라 배치 순서로 모아야 병합 결과가
			// 실행마다 같고, 실패했을 때 보고되는 것도 "먼저 죽은 배치"가 아니라
			// "앞선 배치"로 정해져 재현이 된다
			List<RiskVerdict> merged = new ArrayList<>(risks.size());
			for (CompletableFuture<List<RiskVerdict>> future : futures) {
				merged.addAll(joinUnwrapped(future));
			}
			return merged;
		}
	}

	/**
	 * 주어진 순서(riskId 오름차순) 그대로 연속해서 자른다.
	 *
	 * <p>정책·난이도·상담문 길이로 나누지 않는다 — 분할 방식 자체가 통제해야 할 변수가 되어,
	 * 판정이 달라졌을 때 프롬프트 때문인지 분할 때문인지 갈라낼 수 없게 된다.
	 */
	private List<List<RiskPrompt>> split(List<RiskPrompt> risks) {
		List<List<RiskPrompt>> batches = new ArrayList<>();
		for (int from = 0; from < risks.size(); from += risksPerCall) {
			int to = Math.min(from + risksPerCall, risks.size());
			// subList 는 뷰다. 다른 스레드가 읽으므로 스냅샷으로 끊는다
			batches.add(List.copyOf(risks.subList(from, to)));
		}
		return batches;
	}

	private List<RiskVerdict> classifyBatch(String sessionId,
	                                        String transcript,
	                                        String systemPrompt,
	                                        List<RiskPrompt> batch,
	                                        int totalRisks) {
		List<String> batchRiskIds = batch.stream().map(RiskPrompt::riskId).toList();

		AiGateway.AiCall call = new AiGateway.AiCall(
				sessionId,
				STAGE,
				promptVersion,
				systemPrompt,
				buildUserMessage(transcript, batchRiskIds),
				// 배치 멤버십을 요약에 남긴다. 이제 한 세션에 COVERAGE_CLASSIFY 행이 여러 개라,
				// 어느 행이 어느 배치인지 없으면 llm_call_log 만으로 재현이 안 된다 (TRD §7.2)
				"risks=%d/%d, batch=%s, transcriptChars=%d".formatted(
						batch.size(), totalRisks, String.join(",", batchRiskIds), transcript.length()),
				// 배치가 작아졌다고 줄이지 않는다. 잘림 여유가 이 분할의 이득 중 하나다
				4096L,
				EFFORT);

		// 검증을 파서 안에 두는 것이 핵심이다 — 여기서 던진 ResponseParseException 은
		// AiGateway 의 재시도 1회를 그 배치에만 태운다. 병합한 뒤에 검사하면
		// 배치 하나가 어긋났다고 세 배치를 통째로 다시 부르게 된다
		return gateway.call(call, text -> parseBatch(text, batchRiskIds));
	}

	/**
	 * 지시문 + Risk 카탈로그 <b>전체</b> + 배치 범위 문단.
	 *
	 * <p><b>모든 서브배치에서 바이트 단위로 같아야 한다.</b> 여기 배치별 값이 섞이면 캐시
	 * prefix 가 갈라지고, 그 사고는 오류·로그·테스트 실패 없이 청구서로만 나타난다.
	 * 호출부가 Risk 를 항상 같은 순서로 넘기므로(riskId 정렬) 세션 간에도 같은 바이트가 된다.
	 */
	private String buildSystemPrompt(List<RiskPrompt> risks) {
		StringBuilder builder = new StringBuilder(INSTRUCTIONS);
		builder.append("\n## 위험 항목\n\n");
		for (RiskPrompt risk : risks) {
			builder.append("### ").append(risk.riskId()).append(" — ").append(risk.title()).append('\n');
			builder.append(risk.fact()).append("\n\n");
		}
		builder.append(BATCH_SCOPE);
		return builder.toString();
	}

	/**
	 * 호출마다 달라지는 것은 <b>판정 대상 목록과 상담 원문</b>뿐이다. 둘 다 캐시 prefix 뒤에 온다.
	 *
	 * <p>목록을 system 으로 올리고 싶어지는 순간이 온다("프롬프트가 한 곳에 모여 깔끔하다").
	 * 올리면 캐시가 조용히 죽는다.
	 */
	private String buildUserMessage(String transcript, List<String> batchRiskIds) {
		return "## 판정 대상\n\n" + String.join(", ", batchRiskIds)
				+ "\n\n## 상담 내용\n\n" + transcript + "\n";
	}

	/**
	 * 배치 단위 파싱 + 검증.
	 *
	 * <p>{@code CoverageAnalysisService.indexExactly} 가 union 을 이미 검사하지만 그것으로는
	 * 부족하다. ① 그 검사는 재시도를 못 태운다(게이트웨이 밖이다). ② 배치 A 가 B 의 riskId 를
	 * 내고 B 가 자기 것을 내면 <b>union 은 개수도 집합도 맞아</b> 유출이 그냥 통과한다.
	 */
	private List<RiskVerdict> parseBatch(String responseText, List<String> batchRiskIds) {
		JsonNode results = JsonResponses.requireArray(JsonResponses.parse(responseText), "results");

		Map<String, RiskVerdict> byRiskId = new LinkedHashMap<>();
		for (JsonNode item : results) {
			RiskVerdict verdict = new RiskVerdict(
					JsonResponses.requireText(item, "riskId"),
					JsonResponses.requireEnum(item, "status", CoverageStatus.class),
					JsonResponses.optionalText(item, "reason"),
					JsonResponses.optionalText(item, "evidenceText"));

			if (!batchRiskIds.contains(verdict.riskId())) {
				throw new AiGateway.ResponseParseException(
						"이번 배치가 요청하지 않은 riskId 다: %s (요청=%s)"
								.formatted(verdict.riskId(), batchRiskIds));
			}
			if (byRiskId.put(verdict.riskId(), verdict) != null) {
				throw new AiGateway.ResponseParseException("riskId 가 중복됐다: " + verdict.riskId());
			}
		}

		List<String> missing = batchRiskIds.stream()
				.filter(riskId -> !byRiskId.containsKey(riskId))
				.toList();
		if (!missing.isEmpty()) {
			throw new AiGateway.ResponseParseException(
					"요청한 riskId 가 빠졌다: %s (요청=%s)".formatted(missing, batchRiskIds));
		}

		// 모델이 낸 순서가 아니라 요청 순서로 되돌린다. 배치 안 순서가 응답에 좌우되면
		// 병합 결과의 오름차순이 모델 출력에 의존하게 되고, 전후 비교표가 어긋난다
		return batchRiskIds.stream().map(byRiskId::get).toList();
	}

	/**
	 * MDC 를 워커 스레드로 옮긴다.
	 *
	 * <p>안 옮기면 배치 3개의 로그가 {@code [%X{requestId}]} 없이 찍혀 어느 요청의 것인지
	 * 사후에 이을 수 없다. 관측을 잃는 것이 팬아웃에서 가장 조용한 손실이다.
	 *
	 * <p>{@code finally} 의 {@code clear()} 를 빼면 안 된다 — 가상 스레드는 태스크마다
	 * 새로 뜨지만 캐리어 스레드는 재사용되고, 여기서 지우지 않으면 남은 값이 다음 태스크로
	 * 샐 여지를 남긴다.
	 */
	private <T> T withMdc(Map<String, String> context, Supplier<T> action) {
		if (context != null) {
			// setContextMap(null) 은 예외다. 요청 스레드 밖(평가·배치)에서 부르면 null 이 온다
			MDC.setContextMap(context);
		}
		try {
			return action.get();
		} finally {
			MDC.clear();
		}
	}

	/**
	 * {@code CompletionException} 을 <b>반드시 벗긴다.</b>
	 *
	 * <p>벗기지 않으면 {@link AiGateway} 가 만든 {@code ApiException}(503) 이 그 안에 숨어
	 * {@code GlobalExceptionHandler} 를 못 만나고 500 {@code INTERNAL_ERROR} 로 나간다 —
	 * 프론트에는 "재시도하면 될지도"가 "서버가 깨졌다"로 보인다.
	 */
	private <T> T joinUnwrapped(CompletableFuture<T> future) {
		try {
			return future.join();
		} catch (CompletionException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			throw ex;
		}
	}
}
