package io.finready.coverage;

import java.util.List;

/**
 * Coverage 분류기 포트. <b>구현체가 아직 없다</b> — LLM 모델·요금제가 미결정이기 때문이다
 * (TRD D-02).
 *
 * <p>인터페이스를 먼저 두는 이유는 나머지 F03(provenance·결정표·Gate)이 모델 선정을
 * 기다릴 필요가 없기 때문이다. 모델이 정해지면 이 포트의 구현체 하나만 추가하면 된다.
 *
 * <p>구현할 때 지킬 것:
 * <ul>
 *   <li>반환된 {@code evidenceText} 의 offset 은 <b>쓰지 않는다.</b> 서버가
 *       {@link ProvenanceVerifier} 로 재계산한다(규칙 4). 그래서 이 포트는 offset 을
 *       아예 받지 않는다 — 받을 수 없으면 잘못 쓸 수도 없다.</li>
 *   <li>enum 밖의 문자열이 오면 파싱 실패로 처리한다. 임의 매핑 금지(규칙 9).</li>
 *   <li>호출은 트랜잭션 밖에서 한다(규칙 6).</li>
 * </ul>
 */
public interface CoverageClassifier {

	/**
	 * {@code coveragePolicy != NOT_APPLICABLE} 인 Risk 전체를 배치로 분류한다. 구현이 내부에서
	 * 몇 번을 부르든 호출부에는 한 번이다. <b>Risk 마다 부르지는 않는다</b> — 고정 오버헤드를
	 * 9번 물고 캐시도 9번 쓴다.
	 */
	List<RiskVerdict> classify(String sessionId, String transcript, List<RiskPrompt> risks);

	/**
	 * 이 구현이 쓰는 프롬프트 버전. <b>Hold-out 재현 조건</b>이라(TRD §7.2) 응답의
	 * {@code analysis.promptVersion} 으로 나가야 실험할 때마다 DB 를 뒤지지 않는다.
	 *
	 * <p>{@code default} 인 이유는 스텁을 람다로 두기 위해서다 — 추상 메서드가 둘이면
	 * 함수형 인터페이스가 아니게 되어 {@code AiPortConfig} 의 스텁이 전부 익명 클래스가 된다.
	 */
	default String promptVersion() {
		return null;
	}

	/**
	 * @param riskId Risk 식별자 (R01~R09)
	 * @param title  화면·프롬프트에 쓰는 제목
	 * @param fact   이 Risk 가 설명됐다고 볼 수 있는 사실. 검수된 값이다
	 */
	record RiskPrompt(String riskId, String title, String fact) {
	}

	/**
	 * @param riskId       요청한 riskId 와 같아야 한다. 다르면 파싱 실패로 다룬다
	 * @param status       4상태 원판정
	 * @param reason       판정 근거 서술. {@code classifier_reason} 에 그대로 들어간다
	 * @param evidenceText 원문에서 인용한 구간. 없으면 null
	 */
	record RiskVerdict(String riskId, CoverageStatus status, String reason, String evidenceText) {
	}
}
