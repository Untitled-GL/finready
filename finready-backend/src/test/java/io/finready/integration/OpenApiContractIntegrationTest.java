package io.finready.integration;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * TRD §17 — "springdoc 생성 스펙 vs openapi.yaml 대조, CI diff". §17 나머지 항목
 * (StateMachine·GateEvaluator 단위테스트, PDF SHA-256 검증 등)은 이미 구현돼 있고
 * 이 클래스가 마지막 한 줄을 채운다.
 *
 * <p><b>전체 필드 1:1 대조가 목표가 아니다.</b> {@code docs/openapi.yml}은 1,300줄대에
 * {@code description}이 100곳 넘게 있는 서술형 계약이라, 그 텍스트까지 자동 대조하는
 * 건 유지비 대비 비현실적이다. 여기서 보는 건 <b>경로 집합 · 메서드별 성공(2xx)
 * 상태코드 · 요청/응답 최상위 필드명</b>뿐이다 — "코드가 계약과 구조적으로 갈라졌다"를
 * 잡는 게 목적이지, 설명 문구가 바뀌었다고 실패하면 이 테스트를 아무도 안 믿게 된다.
 *
 * <p><b>오류(4xx/5xx) 상태코드는 별도 테스트로 분리했다.</b> {@code GlobalExceptionHandler}의
 * {@code @ExceptionHandler}에는 springdoc이 읽는 {@code @ApiResponse}가 없어서, 성공
 * 응답과 달리 이 부분은 자동 추론되지 않는다 — 실패한다면 그건 회귀가 아니라
 * "아직 어노테이션을 안 달았다"는 알려진 갭이다. 성공 케이스 실패와 섞으면 진짜 회귀가
 * 이 알려진 갭에 묻힌다.
 *
 * <p><b>{@code docs/openapi.yml}은 클래스패스 리소스가 아니다.</b> {@code finready-backend}
 * 밖(모노레포 루트)에 있어 파일시스템 경로로 읽는다 — {@code SeedEvalParityTest}가
 * 읽는 {@code eval/demo_seed.json}과 달리 컴파일된 test classpath에 포함되지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@DisplayName("springdoc 생성 스펙 ↔ openapi.yml 대조 (TRD §17)")
class OpenApiContractIntegrationTest extends AbstractPostgresIntegrationTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Set<String> HTTP_METHODS =
			Set.of("get", "post", "put", "delete", "patch", "options", "head", "trace");
	private static final Set<String> SUCCESS_CODES = Set.of("200", "201");

	@Autowired
	private MockMvc mockMvc;

	private static JsonNode contractSpec;
	private static JsonNode generatedSpec;

	@BeforeAll
	static void loadSpecs() {
		contractSpec = readContractSpec();
	}

	private JsonNode generatedSpec() {
		if (generatedSpec == null) {
			try {
				byte[] body = mockMvc.perform(get("/v3/api-docs"))
						.andReturn().getResponse().getContentAsByteArray();
				generatedSpec = MAPPER.readTree(body);
			}
			catch (Exception ex) {
				throw new IllegalStateException("/v3/api-docs 호출 실패", ex);
			}
		}
		return generatedSpec;
	}

	/**
	 * {@code docs/openapi.yml}은 {@code finready-backend} 밖에 있다. Gradle 테스트
	 * 워킹 디렉터리는 {@code finready-backend/}로 고정돼 있으므로(build.gradle.kts에
	 * workingDir 오버라이드 없음) {@code ../docs/openapi.yml}로 올라간다.
	 */
	private static JsonNode readContractSpec() {
		Path path = Path.of(System.getProperty("user.dir"), "..", "docs", "openapi.yml").normalize();
		if (!Files.isRegularFile(path)) {
			throw new IllegalStateException(
					"계약 파일을 못 찾았다: " + path.toAbsolutePath()
							+ " (user.dir=" + System.getProperty("user.dir")
							+ ") — Gradle 워킹 디렉터리가 finready-backend/가 아니면 상대경로를 조정할 것");
		}
		try (InputStream in = Files.newInputStream(path)) {
			Object yamlTree = new Yaml().load(in);
			return MAPPER.valueToTree(yamlTree);
		}
		catch (Exception ex) {
			throw new IllegalStateException(path + " 파싱 실패", ex);
		}
	}

	@Test
	@DisplayName("경로 집합이 일치한다 (springdoc의 /api 접두사만 벗겨내고 비교)")
	void pathsMatch() throws Exception {
		assertThat(pathSet(generatedSpec(), true))
				.containsExactlyInAnyOrderElementsOf(pathSet(contractSpec, false));
	}

	@Test
	@DisplayName("성공(2xx) 상태코드가 경로·메서드별로 일치한다")
	void successStatusCodesMatch() throws Exception {
		Map<Endpoint, Set<String>> expected = statusCodesByEndpoint(contractSpec, false, true);
		Map<Endpoint, Set<String>> actual = statusCodesByEndpoint(generatedSpec(), true, true);

		SoftAssertions softly = new SoftAssertions();
		for (Map.Entry<Endpoint, Set<String>> entry : expected.entrySet()) {
			softly.assertThat(actual.get(entry.getKey()))
					.as("성공 상태코드 — %s", entry.getKey())
					.isEqualTo(entry.getValue());
		}
		softly.assertAll();
	}

	/**
	 * 알려진 갭 — 컨트롤러에 {@code @ApiResponse}가 없어 springdoc이 4xx/5xx를
	 * 자동으로 못 뽑는다. 실패하면 "회귀"가 아니라 "아직 안 채워진 것"이다. 성공
	 * 케이스와 분리해둔 이유가 이것이다 — 여기서 실패해도 {@link #successStatusCodesMatch}·
	 * {@link #pathsMatch}·{@link #topLevelResponseFieldsMatch}는 계속 신뢰할 수 있다.
	 */
	@Test
	@DisplayName("오류(4xx/5xx) 상태코드가 경로·메서드별로 일치한다 (알려진 갭 — @ApiResponse 미도입)")
	void errorStatusCodesMatch() throws Exception {
		Map<Endpoint, Set<String>> expected = statusCodesByEndpoint(contractSpec, false, false);
		Map<Endpoint, Set<String>> actual = statusCodesByEndpoint(generatedSpec(), true, false);

		SoftAssertions softly = new SoftAssertions();
		for (Map.Entry<Endpoint, Set<String>> entry : expected.entrySet()) {
			softly.assertThat(actual.getOrDefault(entry.getKey(), Set.of()))
					.as("오류 상태코드 — %s", entry.getKey())
					.isEqualTo(entry.getValue());
		}
		softly.assertAll();
	}

	@Test
	@DisplayName("응답 바디 최상위 필드명이 경로별로 일치한다 (2xx만, 중첩 구조는 안 본다)")
	void topLevelResponseFieldsMatch() throws Exception {
		SoftAssertions softly = new SoftAssertions();
		forEachOperation(contractSpec, false, (path, method, contractOp) -> {
			JsonNode actualOp = findOperation(generatedSpec(), true, path, method);
			if (actualOp == null) {
				return; // pathsMatch/successStatusCodesMatch 가 이미 이 불일치를 보고한다
			}
			for (String code : SUCCESS_CODES) {
				JsonNode contractResponse = contractOp.path("responses").path(code);
				if (contractResponse.isMissingNode()) {
					continue;
				}
				Set<String> expectedFields = topLevelFieldNames(contractSpec, contractResponse);
				Set<String> actualFields =
						topLevelFieldNames(generatedSpec(), actualOp.path("responses").path(code));
				softly.assertThat(actualFields)
						.as("응답 필드 — %s %s [%s]", method.toUpperCase(), path, code)
						.isEqualTo(expectedFields);
			}
		});
		softly.assertAll();
	}

	@Test
	@DisplayName("요청 바디 최상위 필드명이 경로별로 일치한다 (requestBody가 있는 경로만)")
	void topLevelRequestBodyFieldsMatch() throws Exception {
		SoftAssertions softly = new SoftAssertions();
		forEachOperation(contractSpec, false, (path, method, contractOp) -> {
			JsonNode contractBody = contractOp.path("requestBody");
			if (contractBody.isMissingNode()) {
				return;
			}
			JsonNode actualOp = findOperation(generatedSpec(), true, path, method);
			if (actualOp == null) {
				return;
			}
			Set<String> expectedFields = topLevelFieldNames(contractSpec, contractBody);
			Set<String> actualFields = topLevelFieldNames(generatedSpec(), actualOp.path("requestBody"));
			softly.assertThat(actualFields)
					.as("요청 필드 — %s %s", method.toUpperCase(), path)
					.isEqualTo(expectedFields);
		});
		softly.assertAll();
	}

	@Test
	@DisplayName("공용 Error 스키마의 필드명이 일치한다")
	void errorSchemaFieldsMatch() throws Exception {
		JsonNode contractError = contractSpec.path("components").path("schemas").path("Error");
		JsonNode actualError = generatedSpec().path("components").path("schemas").path("Error");

		assertThat(fieldNames(generatedSpec(), actualError)).isEqualTo(fieldNames(contractSpec, contractError));
	}

	// ------------------------------------------------------------------

	private interface OperationVisitor {
		void visit(String path, String method, JsonNode operation);
	}

	private void forEachOperation(JsonNode spec, boolean stripApiPrefix, OperationVisitor visitor) {
		Iterator<Map.Entry<String, JsonNode>> paths = spec.path("paths").properties().iterator();
		while (paths.hasNext()) {
			Map.Entry<String, JsonNode> pathEntry = paths.next();
			String path = normalize(pathEntry.getKey(), stripApiPrefix);
			Iterator<Map.Entry<String, JsonNode>> methods = pathEntry.getValue().properties().iterator();
			while (methods.hasNext()) {
				Map.Entry<String, JsonNode> methodEntry = methods.next();
				if (HTTP_METHODS.contains(methodEntry.getKey())) {
					visitor.visit(path, methodEntry.getKey(), methodEntry.getValue());
				}
			}
		}
	}

	private JsonNode findOperation(JsonNode spec, boolean stripApiPrefix, String targetPath, String targetMethod) {
		JsonNode[] found = new JsonNode[1];
		forEachOperation(spec, stripApiPrefix, (path, method, operation) -> {
			if (path.equals(targetPath) && method.equals(targetMethod)) {
				found[0] = operation;
			}
		});
		return found[0];
	}

	private Set<String> pathSet(JsonNode spec, boolean stripApiPrefix) {
		Set<String> result = new TreeSet<>();
		spec.path("paths").propertyNames()
				.forEach(p -> result.add(normalize(p, stripApiPrefix)));
		return result;
	}

	private String normalize(String path, boolean stripApiPrefix) {
		return stripApiPrefix && path.startsWith("/api") ? path.substring(4) : path;
	}

	private record Endpoint(String path, String method) {
	}

	private Map<Endpoint, Set<String>> statusCodesByEndpoint(JsonNode spec, boolean stripApiPrefix,
	                                                         boolean successOnly) {
		Map<Endpoint, Set<String>> result = new java.util.LinkedHashMap<>();
		forEachOperation(spec, stripApiPrefix, (path, method, operation) -> {
			Set<String> codes = new TreeSet<>();
			operation.path("responses").propertyNames().forEach(code -> {
				boolean isSuccess = SUCCESS_CODES.contains(code);
				if (successOnly == isSuccess) {
					codes.add(code);
				}
			});
			result.put(new Endpoint(path, method), codes);
		});
		return result;
	}

	/** {@code $ref} 체인을 끝까지 따라간다. {@code #/a/b/c} 형태만 지원(이 계약이 쓰는 유일한 형태) */
	private JsonNode resolveRef(JsonNode root, JsonNode node) {
		int guard = 0;
		while (node.has("$ref") && guard++ < 10) {
			String ref = node.get("$ref").asString();
			JsonNode target = root;
			for (String segment : ref.substring(2).split("/")) {
				target = target.path(segment);
			}
			node = target;
		}
		return node;
	}

	/**
	 * response 노드({@code {description, content: {...}}} 또는 그걸 가리키는 {@code $ref})나
	 * requestBody 노드({@code {required, content: {...}}})를 받아 최상위 필드명만 뽑는다.
	 * 중첩 객체·배열의 하위 필드는 보지 않는다(범위 밖, 이 클래스 상단 Javadoc 참조).
	 *
	 * <p>미디어 타입 키가 양쪽에서 다르다 — 계약 파일은 항상 {@code application/json}인데,
	 * springdoc은 컨트롤러에 {@code produces}가 없으면 와일드카드({@code &#42;/&#42;})로
	 * 뽑는다(실측 확인, 예: {@code GET /products/demo}). 그래서 하나가 없으면 다른 걸 시도한다.
	 */
	private Set<String> topLevelFieldNames(JsonNode root, JsonNode responseOrRequestBodyNode) {
		JsonNode resolved = resolveRef(root, responseOrRequestBodyNode);
		JsonNode content = resolved.path("content");
		JsonNode mediaType = content.path("application/json");
		if (mediaType.isMissingNode()) {
			mediaType = content.path("*/*");
		}
		JsonNode schema = resolveRef(root, mediaType.path("schema"));
		return fieldNames(root, schema);
	}

	/**
	 * {@code allOf} 합성 스키마({@code SessionSnapshotResponse}가 이 형태 — 다른 스키마를
	 * 상속하듯 확장한다)를 만나면 각 분기를 펼쳐 필드명을 합친다. 이 계약에서 {@code allOf}는
	 * 최대 1단계만 쓰인다(중첩 합성 없음, 실측 확인) — 그 이상은 지원하지 않는다.
	 */
	private Set<String> fieldNames(JsonNode root, JsonNode schemaNode) {
		JsonNode resolved = resolveRef(root, schemaNode);
		Set<String> names = new LinkedHashSet<>();
		if (resolved.has("allOf")) {
			for (JsonNode branch : resolved.path("allOf")) {
				names.addAll(fieldNames(root, resolveRef(root, branch)));
			}
			return names;
		}
		resolved.path("properties").propertyNames().forEach(names::add);
		return names;
	}
}
