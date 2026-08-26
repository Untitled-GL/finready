plugins {
	java
	id("org.springframework.boot") version "4.0.7"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "io.finready"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-actuator")

	// 스키마 변경은 Flyway로만. JPA는 validate만 한다 (TRD §1)
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.flywaydb:flyway-database-postgresql")
	runtimeOnly("org.postgresql:postgresql")

	// openapi.yaml v1.4.2와 구현을 CI에서 diff한다 (TRD §17 계약 테스트)
	// Boot 4는 springdoc 3.x 라인이다. 2.x는 Boot 3 전용이므로 쓰면 안 된다.
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0")

	// LLM 클라이언트 — Claude Sonnet 4.6 (TRD D-02 결정, 2026-08-18).
	// 공식 SDK를 쓴다. 직접 HTTP를 짜면 재시도·스트리밍·오류 타입을 다시 만들어야 한다.
	//
	// swagger-annotations(비-jakarta, 2.2.31)를 뺀다 — SDK가 구조화 출력 스키마 생성용으로
	// 전이 의존한다(jsonschema-module-swagger-2 경유). Sonnet 4.6은 구조화 출력을 지원하지
	// 않아(주석 참조, ai/AiGateway) 이 경로를 안 쓰는데, 클래스명이 springdoc이 쓰는
	// swagger-annotations-jakarta(2.2.52)와 같은 패키지라 클래스패스에 둘 다 있으면 구버전이
	// 먼저 로드될 때 NoSuchMethodError가 난다(TRD §17 계약 테스트 도입 중 실제로 걸림,
	// 2026-08-26 — org.springdoc...NoSuchMethodError: Schema.$dynamicRef()).
	implementation("com.anthropic:anthropic-java:2.34.0") {
		exclude(group = "io.swagger.core.v3", module = "swagger-annotations")
	}

	// 시드 sourceText ↔ PDF 대조. 런타임이 아니라 테스트에서만 쓴다 (TRD §5.4)
	testImplementation("org.apache.pdfbox:pdfbox:3.0.3")

	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")

	testImplementation("org.springframework.boot:spring-boot-starter-test")

	// Boot 4는 테스트 슬라이스도 모듈로 쪼갰다. @WebMvcTest는 starter-test에 없다.
	// 이걸 빼면 컨트롤러 계약 테스트를 아예 못 쓴다 (모듈화 주의 — CLAUDE.md)
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")

	// F03부터 필요한 실제 Postgres 통합 테스트 (ck_*, append-only 트리거, ddl-auto:validate 회귀).
	// 버전은 spring-boot-dependencies BOM이 관리한다 — 여기서 직접 안 박는다.
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	// Testcontainers 2.x부터 아티팩트명이 testcontainers-* 로 바뀌었다 (junit-jupiter, postgresql 단독 이름 아님)
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")

	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 기본 test: 실제 LLM을 호출하는 평가 모듈 + Docker가 필요한 통합 테스트는 제외한다 (TRD §17)
tasks.named<Test>("test") {
	systemProperty("spring.profiles.active", "test")
	useJUnitPlatform {
		excludeTags("evaluation", "integration")
	}
}

// 통합 테스트 실행: ./gradlew integrationTest (Docker Desktop 필요)
tasks.register<Test>("integrationTest") {
	description = "Testcontainers PostgreSQL 통합 테스트. 실제 DB 제약·트리거를 검증한다"
	group = "verification"
	testClassesDirs = sourceSets["test"].output.classesDirs
	classpath = sourceSets["test"].runtimeClasspath
	systemProperty("spring.profiles.active", "test")
	useJUnitPlatform {
		includeTags("integration")
	}
	shouldRunAfter("test")
}

// 오프라인 평가 실행: ./gradlew evaluate
//
// 키는 ~/.gradle/gradle.properties 의 llmApiKey 에 둔다(권장). 저장소 밖이라 커밋될 수
// 없고, 한 번 넣으면 IntelliJ 초록 버튼으로도 계속 먹는다.
//
// -PllmApiKey=... 도 동작하지만 권장하지 않는다 — 셸 히스토리와 ps 출력에 키가 남는다.
//
// ⚠️ IntelliJ 의 "애플리케이션" Run Configuration 에 넣은 환경변수는 Gradle 태스크에
//    적용되지 않는다. 그 설정은 Spring Boot 실행용이고 Gradle 데몬은 별도 프로세스다.
//    이걸 몰라 키를 넣고도 평가가 통째로 skip 된 적이 있다 (2026-08-19).
tasks.register<Test>("evaluate") {
	description = "Hold-out / Rule baseline 오프라인 평가. 실제 LLM을 호출한다"
	group = "verification"
	testClassesDirs = sourceSets["test"].output.classesDirs
	classpath = sourceSets["test"].runtimeClasspath
	systemProperty("spring.profiles.active", "eval")
	useJUnitPlatform {
		includeTags("evaluation")
	}

	// 데몬 환경 상속에 기대지 않고 명시적으로 넘긴다. 상속에 기대면 "어디에 넣어야
	// 하는가"가 실행 방식마다 달라지고, 틀렸을 때 아무 신호가 없다
	val llmApiKey = providers.gradleProperty("llmApiKey")
		.orElse(providers.environmentVariable("LLM_API_KEY"))
	environment("LLM_API_KEY", llmApiKey.getOrElse(""))

	// 이 태스크의 산출물은 단언이 아니라 콘솔에 찍히는 보고서다. 안 보이면 목적이 없다
	testLogging {
		showStandardStreams = true
	}

	outputs.upToDateWhen { false }
}