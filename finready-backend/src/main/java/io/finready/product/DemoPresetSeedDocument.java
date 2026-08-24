package io.finready.product;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * seed/demo_presets.json 의 루트.
 *
 * <p>DB 테이블이 없다. TRD §4 데이터 모델에 없는 표를 하나 더 만드는 대신 리소스로 두고
 * 기동 시 메모리에 올린다 — 런타임에 바뀌지 않는 데모 원문이고, 감사 대상 도메인 상태가 아니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DemoPresetSeedDocument(
		List<PresetSeedData> presets,
		List<AnswerSeedData> answers
) {

	/**
	 * {@code supplementTranscript} 는 null 일 수 있다. 채점된 보완문이 있는 시나리오만 갖는다 —
	 * 없는 시나리오에 그럴듯한 문장을 지어 넣으면 데모가 측정 밖으로 나간다.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record PresetSeedData(
			String id,
			String sourceConsultationId,
			String label,
			String transcript,
			String supplementTranscript
	) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record AnswerSeedData(
			String riskId,
			String expectedLabel,
			String answer,
			String sourceAnswerId
	) {
	}
}
