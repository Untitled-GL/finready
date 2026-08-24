package io.finready.product;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * TRD §18 Step 11 데모 preset. S02 '샘플 상담 내용 채우기' 와 S04 답변 채우기의 원문을
 * 서버가 갖는다.
 *
 * <p>이 문구가 프론트 상수로 있으면 데모 원문이 계약 밖에 있게 되고, 화면에서 문장을 다듬는 순간
 * 심사위원이 보는 상담문과 정확도를 실측한 상담문이 갈린다. 원문은 {@code eval/demo_seed.json}
 * 이고 {@code seed/demo_presets.json} 은 거기서 옮긴 사본이다 — 두 파일의 동일성은
 * {@code DemoPresetSeedParityTest} 가 강제한다.
 *
 * <p>기동 시 한 번 읽어 메모리에 둔다. DB 테이블을 만들지 않는 이유는
 * {@link DemoPresetSeedDocument} 에 적었다. {@code @Profile} 제한이 없어 test 프로파일에서도
 * 도는데, DB 를 건드리지 않으므로 {@code SeedLoader} 와 달리 안전하고 오히려 컨트롤러 테스트가
 * 실제 시드 값을 보게 된다.
 */
@Component
public class DemoPresetCatalog {

	private final List<DemoProductResponse.DemoPresetView> presets;
	private final List<DemoProductResponse.DemoAnswerView> answers;

	public DemoPresetCatalog(ResourceLoader resourceLoader,
	                         ObjectMapper objectMapper,
	                         @Value("${finready.seed.demo-preset-path}") String path) {
		DemoPresetSeedDocument seed = read(resourceLoader, objectMapper, path);

		this.presets = seed.presets() == null ? List.of() : seed.presets().stream()
				.map(data -> new DemoProductResponse.DemoPresetView(
						data.id(),
						data.label(),
						data.transcript(),
						data.supplementTranscript()))
				.toList();

		this.answers = seed.answers() == null ? List.of() : seed.answers().stream()
				.map(data -> new DemoProductResponse.DemoAnswerView(
						data.riskId(),
						data.expectedLabel(),
						data.answer()))
				.toList();
	}

	/** 이미 불변 리스트다. 방어 복사 없이 그대로 준다 */
	public List<DemoProductResponse.DemoPresetView> presets() {
		return presets;
	}

	public List<DemoProductResponse.DemoAnswerView> answers() {
		return answers;
	}

	private DemoPresetSeedDocument read(ResourceLoader resourceLoader, ObjectMapper objectMapper, String path) {
		Resource resource = resourceLoader.getResource(path);
		if (!resource.exists()) {
			throw new SeedValidationException("데모 preset 시드가 없다: " + path);
		}
		try (InputStream in = resource.getInputStream()) {
			return objectMapper.readValue(in, DemoPresetSeedDocument.class);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("데모 preset 시드를 읽지 못했다: " + path, ex);
		}
	}
}
