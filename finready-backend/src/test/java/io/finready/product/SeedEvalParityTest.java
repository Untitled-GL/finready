package io.finready.product;

import io.finready.understanding.UnderstandingStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * main 시드와 평가 데이터셋이 <b>같은 문자열</b>을 들고 있는지 본다. LLM 을 호출하지 않는다.
 *
 * <p>이 테스트가 있는 이유: 데모에서 심사위원이 보는 판정 결과가 곧 정확도를 실측한 그 상담문의
 * 결과여야 한다. 화면에 맞춰 문구를 한 글자만 다듬어도 데모는 측정되지 않은 산출물이 되는데,
 * 그 사고는 아무 오류도 내지 않고 조용히 통과한다 — 그래서 사람 눈이 아니라 여기서 막는다.
 *
 * <p>Jackson 으로 읽어 비교하므로 파일의 들여쓰기·키 순서는 자유롭고 <b>값만</b> 비교된다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("시드 ↔ 평가 데이터셋 동일성")
class SeedEvalParityTest {

	private static final String EVAL_PATH = "/eval/demo_seed.json";
	private static final String PRESET_PATH = "/seed/demo_presets.json";
	private static final String CUSTOMER_PATH = "/seed/customer_profiles.json";
	private static final String RISK_PATH = "/seed/product_a_risk_schema.json";

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final JsonNode eval = read(EVAL_PATH);
	private final JsonNode presetSeed = read(PRESET_PATH);
	private final JsonNode customerSeed = read(CUSTOMER_PATH);
	private final JsonNode riskSeed = read(RISK_PATH);

	@Nested
	@DisplayName("데모 preset")
	class Presets {

		@Test
		@DisplayName("상담문이 원본 시나리오와 한 글자도 다르지 않다")
		void transcriptsMatchTheirSourceConsultation() {
			Map<String, JsonNode> consultations = byId(eval.get("consultations"), "id");

			for (JsonNode preset : presetSeed.get("presets")) {
				String sourceId = preset.get("sourceConsultationId").asString();
				JsonNode source = consultations.get(sourceId);

				assertThat(source)
						.as("%s 가 가리키는 %s 가 평가 데이터셋에 있다", preset.get("id").asString(), sourceId)
						.isNotNull();

				assertThat(preset.get("transcript").asString())
						.as("%s transcript", sourceId)
						.isEqualTo(source.get("text").asString());

				assertThat(text(preset, "supplementTranscript"))
						.as("%s 보완문 — 원본에 없으면 preset 도 null 이어야 한다", sourceId)
						.isEqualTo(text(source, "staffSupplementText"));
			}
		}

		@Test
		@DisplayName("id 가 유일하고 상담문이 비어 있지 않다")
		void presetsAreWellFormed() {
			List<String> ids = strings(presetSeed.get("presets"), "id");

			assertThat(ids).doesNotHaveDuplicates().isNotEmpty();

			for (JsonNode preset : presetSeed.get("presets")) {
				assertThat(preset.get("transcript").asString())
						.as("%s transcript", preset.get("id").asString())
						.isNotBlank();
			}
		}
	}

	@Nested
	@DisplayName("데모 답변")
	class Answers {

		@Test
		@DisplayName("답변 문장이 평가 데이터셋과 같고 라벨도 같다")
		void answersMatchTheirSourceRecord() {
			Map<String, JsonNode> sources = byId(eval.get("understandingAnswers"), "id");

			for (JsonNode answer : presetSeed.get("answers")) {
				String sourceId = answer.get("sourceAnswerId").asString();
				JsonNode source = sources.get(sourceId);

				assertThat(source).as("%s 가 평가 데이터셋에 있다", sourceId).isNotNull();
				assertThat(answer.get("answer").asString()).as("%s 본문", sourceId)
						.isEqualTo(source.get("answer").asString());
				assertThat(answer.get("expectedLabel").asString()).as("%s 라벨", sourceId)
						.isEqualTo(source.get("goldLabel").asString());
				assertThat(answer.get("riskId").asString()).as("%s riskId", sourceId)
						.isEqualTo(source.get("riskId").asString());
			}
		}

		/**
		 * 시드는 빌드 타임 고정 데이터라 런타임에 valueOf 로 검증하지 않는다. 그 대신 여기서 막는다.
		 * 규칙 9 — enum 밖의 값을 임의 매핑하지 않는다.
		 */
		@Test
		@DisplayName("expectedLabel 이 UnderstandingStatus 안의 값이다")
		void labelsAreContractEnumValues() {
			Set<String> allowed = java.util.Arrays.stream(UnderstandingStatus.values())
					.map(Enum::name)
					.collect(Collectors.toSet());

			assertThat(strings(presetSeed.get("answers"), "expectedLabel"))
					.isSubsetOf(allowed);
		}

		/**
		 * 프론트는 정답/오해/애매 세 버튼을 그린다. 한 조합이라도 비면 그 버튼이 아무것도 채우지
		 * 않는데, 화면에서는 그냥 반응이 없는 것처럼 보여 원인을 찾기 어렵다.
		 */
		@Test
		@DisplayName("이해확인 대상 Risk 마다 세 라벨이 모두 있다")
		void everyUnderstandingCheckRiskHasAllThreeLabels() {
			Set<String> targets = StreamSupport.stream(riskSeed.get("risks").spliterator(), false)
					.filter(risk -> risk.path("understandingCheck").asBoolean(false))
					.map(risk -> risk.get("riskId").asString())
					.collect(Collectors.toCollection(LinkedHashSet::new));

			assertThat(targets).as("이해확인 대상").isNotEmpty();

			for (String riskId : targets) {
				Set<String> labels = StreamSupport.stream(presetSeed.get("answers").spliterator(), false)
						.filter(answer -> riskId.equals(answer.get("riskId").asString()))
						.map(answer -> answer.get("expectedLabel").asString())
						.collect(Collectors.toSet());

				assertThat(labels).as("%s 의 샘플 답변 라벨", riskId)
						.containsExactlyInAnyOrder("UNDERSTOOD", "MISUNDERSTOOD", "UNCERTAIN");
			}
		}
	}

	/**
	 * customer_profiles.json 의 note 가 "eval 과 같은 값을 쓴다"고 선언한다. 그 문장을 지킬
	 * 방법이 없으면 선언이 아니라 희망이라, 여기서 강제한다.
	 */
	@Test
	@DisplayName("고객 preset 이 평가 데이터셋과 같다")
	void customerProfilesMatchEvalDataset() {
		assertThat(customerSeed.get("customerProfiles"))
				.isEqualTo(eval.get("customerProfiles"));
	}

	private Map<String, JsonNode> byId(JsonNode array, String idField) {
		return StreamSupport.stream(array.spliterator(), false)
				.collect(Collectors.toMap(node -> node.get(idField).asString(), node -> node));
	}

	private List<String> strings(JsonNode array, String field) {
		List<String> values = new ArrayList<>();
		array.forEach(node -> values.add(node.get(field).asString()));
		return values;
	}

	/** 없는 키와 명시적 null 을 똑같이 null 로 본다 */
	private String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isMissingNode() || value.isNull() ? null : value.asString();
	}

	private JsonNode read(String path) {
		try (InputStream in = SeedEvalParityTest.class.getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalStateException("클래스패스에 없다: " + path);
			}
			return objectMapper.readTree(in);
		}
		catch (Exception ex) {
			throw new IllegalStateException(path + " 를 읽지 못했다", ex);
		}
	}
}
