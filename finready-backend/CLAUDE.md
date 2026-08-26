# FinReady Backend

Spring Boot 백엔드. 저장소 공통 규칙(마감·문서 우선순위·계약 파일·커밋 컨벤션)은
루트 `CLAUDE.md`에 있다. 이 파일은 백엔드 작업 규칙만 다룬다.

## 기술 스택

Java 25 / Spring Boot 4.0.7 / Gradle Kotlin DSL / Spring Data JPA /
Flyway / PostgreSQL(Supabase, 스키마 `finready`) / Render 배포(Singapore)

Boot 4는 Jackson 3(`tools.jackson`)를 쓴다. Boot 3 예제를 그대로 가져오면 안 된다.
springdoc은 3.x 라인이다. 2.x는 Boot 3 전용이다.

> **Boot 4 모듈화 주의.** Boot 4는 자동설정을 기술별 모듈로 쪼갰다.
> 라이브러리(`flyway-core` 등)만 넣으면 자동설정이 **조용히 안 걸린다.**
> 반드시 `spring-boot-starter-*` 형태로 넣을 것. 앞으로 Redis·Kafka 등을
> 추가할 때도 동일하다.
>
> 판별법: 기동 시 `debug: true`로 CONDITIONS EVALUATION REPORT를 찍었을 때
> 해당 기능 이름이 **Positive에도 Negative에도 없으면 = 전용 스타터 누락**이다.
> Negative에 있으면 조건 문제다.

> **TRD §1 기술스택 표는 `Java 21 (LTS) / Spring Boot 3.x`로 적혀 있다.**
> 코드·Dockerfile·이 문서가 Java 25 / Boot 4.0.7이므로 **TRD 쪽이 낡았다.**
> 결정: 코드를 유지하고 TRD §1을 정정한다(→ v1.2.4). 아직 반영 전이므로
> 세 문서 대조 전에 처리할 것.

## 절대 어기면 안 되는 규칙

1. **AI 원판정을 덮어쓰지 않는다.** `coverage_result.classifier_status`와
   `understanding_result.ai_status`는 어떤 경로로도 UPDATE되지 않는다.
   Override/Resolution은 별도 테이블 INSERT다. 리포지토리에 UPDATE 메서드를 만들지 말 것.

2. **합성 상태를 저장하지 않는다.** `effectiveStatus` 같은 필드를 만들지 않는다.
   `classifierStatus`(AI 원판정)와 `coverageStatus`(검증 후)를 별도 컬럼으로 둔다.

3. **EXPLAINED는 provenance + semantic을 모두 통과해야 성립한다.**
   DB check 제약으로도 강제돼 있다. 애플리케이션에서 우회하지 말 것.

4. **LLM이 반환한 offset을 쓰지 않는다.** 서버가 원문에서 재계산한다.
   응답 offset은 항상 원문 UTF-16 code unit 기준.

5. **스키마 변경은 Flyway로만.** `ddl-auto: validate` 고정. 엔티티를 고쳤으면
   마이그레이션 파일을 추가한다. 기존 마이그레이션을 수정하지 않는다.

6. **LLM 호출은 트랜잭션 밖에서.** 30초짜리 커넥션 점유를 만들지 않는다.
   DB role에 `idle_in_transaction_session_timeout=30s`가 걸려 있어 어기면 런타임에 터진다.

7. **상태 전이는 `common.StateMachine` 단일 지점을 통과한다.** 서비스 코드에
   상태 분기를 흩뿌리지 않는다. 미허용 전이는 `INVALID_STATE_TRANSITION`(409).

8. **프론트 분기는 서버가 결정한다.** 흐름을 진전시키는 응답은 `nextAction`을 싣는다.
   산출 규칙은 TRD §6.6.

9. **enum 문자열은 TRD §6이 전부다.** 목록에 없는 값을 만들지 않는다.
   LLM이 enum 밖의 값을 반환하면 파싱 실패로 처리한다. 임의 매핑 금지.

10. **API 키·DB 자격증명을 코드·응답·로그에 넣지 않는다.** 환경변수만 쓴다.

## 작업할 때

- **저장소를 한글·공백 경로에 두지 말 것.** Windows에서 `gradlew test`가 통째로 깨진다.
  Gradle이 테스트 워커 클래스패스를 `@argfile`로 넘기는데, Gradle은 UTF-8로 쓰고
  JVM 런처는 네이티브 인코딩(cp949)으로 읽어서 경로가 깨진다. 증상은
  모든 테스트 클래스에 `ClassNotFoundException`이며, **컴파일은 멀쩡히 통과한다.**
  워커 명령줄의 `-Dfile.encoding=UTF-8`은 argfile을 읽은 뒤 적용돼 소용없다.
  이 문제로 `D:\공부\finready` → `D:\dev\finready`로 옮겼다 (2026-08-14).
  F03의 Testcontainers도 Docker 볼륨 마운트에서 같은 계열 문제를 겪는다.
- **한글이 든 `.ps1`은 반드시 UTF-8 *with BOM*으로 저장할 것.** Windows PowerShell 5.1은
  BOM이 없으면 스크립트를 ANSI 코드페이지(여기선 cp949)로 읽는다. 한글의 마지막 UTF-8
  바이트가 cp949 선행 바이트인 경우(예: `대조`의 `B0`) **뒤따르는 LF 개행을 삼켜서
  다음 줄이 위 주석에 흡수된다.** 문법 오류가 아니라 **문장이 조용히 사라진다.**
  `tools/run-coverage-eval.ps1`에서 `$groundTruth`·`$gateExpected` 두 대입문이 이렇게
  없어져 평가 결과가 전부 불일치로 찍혔다 (2026-08-19).
  CRLF 파일은 `\r`이 대신 먹혀서 안 걸린다 — **LF 파일에서만 터진다.**
  앞의 argfile 문제와 같은 계열(UTF-8로 쓰고 cp949로 읽음)이다.
- **로컬 실행 전 JAVA_HOME을 JDK 25로 잡을 것.** 셸 기본값이 존재하지 않는
  openjdk@17 경로라 gradlew가 즉시 죽는다.
  ```bash
  export JAVA_HOME=$(/usr/libexec/java_home -v 25)   # macOS
  ./gradlew build
  ```
  Windows는 `.\gradlew.bat build`.
- 로컬 실행은 `local` 프로파일. 접속 정보는 IntelliJ Run Configuration
  환경변수로 관리한다. 참고용 템플릿은 `application-local.yaml.example`.
  Run Configuration에 `SPRING_PROFILES_ACTIVE=local` + `DB_URL` /
  `DB_USERNAME` / `DB_PASSWORD`를 한 번 넣어두면 이후 초록 버튼으로 그냥 실행된다.
- **앱을 띄우는 데는 Docker가 필요 없다.** DB가 원격 Supabase라 띄울 컨테이너가 없다.
  `Dockerfile`은 Render 배포 전용이다(아래 참조).
  다만 **F03부터는 통합 테스트에 Docker가 필요하다** — DB 제약(`ck_*`)·append-only 트리거·
  `updatable=false`·`@Version` 락은 실제 Postgres 없이는 검증되지 않는다.
  Testcontainers를 그때 붙인다. Render 빌드는 `Dockerfile`이 `-x test`라 영향 없다.
- **IntelliJ Run Configuration의 환경변수는 Gradle 태스크에 적용되지 않는다.**
  그 설정은 Spring Boot 애플리케이션 실행용이고 Gradle은 별도 프로세스다.
  `LLM_API_KEY`를 거기 넣고 `gradlew evaluate`를 돌려 **평가가 통째로 skip됐는데
  4초 만에 BUILD SUCCESSFUL이 떴다.** 결과 XML에도 `<skipped/>`만 남아 신호가 없었다
  (2026-08-19). 평가용 키는 **`~/.gradle/gradle.properties`의 `llmApiKey`**에 둔다 —
  저장소 밖이라 커밋될 수 없고 초록 버튼으로도 먹는다. `-PllmApiKey=`는 셸 히스토리와
  `ps`에 남으므로 쓰지 않는다. 프로젝트 `gradle.properties`는 `.gitignore` 대상이다.
- 테스트에서 실제 LLM을 호출하지 않는다. 평가 모듈만 `@Tag("evaluation")`으로 분리
- 큰 변경 전에는 계획을 먼저 제시하고 승인을 받을 것
- **`columnDefinition`은 `ddl-auto: validate`에 영향을 주지 않는다.** DDL 생성용이라
  JDBC 타입 코드를 바꾸지 못한다. DB 타입 코드가 Java 기본 매핑과 다른 컬럼은
  `@JdbcTypeCode`로 지정해야 한다. 실제로 `product.document_sha256`의 `char(64)`에서
  `found [bpchar (Types#CHAR)], but expecting [char(64) (Types#VARCHAR)]`로 걸렸고
  `@JdbcTypeCode(SqlTypes.CHAR)`로 해결했다 (2026-08-13)
- **엔티티 검증은 기동으로만 된다.** `gradlew build`는 컴파일만 확인한다.
  Hibernate 검증기는 불일치를 만나면 예외를 던져 **한 번에 하나만** 보여주므로,
  엔티티를 몰아서 쓰면 고치고 재기동을 반복하게 된다. DDL 섹션 단위로 나눠 진행할 것
- **Boot 4는 테스트 슬라이스도 모듈로 쪼갰다.** `@WebMvcTest`가
  `spring-boot-starter-test`에 없다. `spring-boot-starter-webmvc-test`를 따로 넣어야 한다.
  패키지도 `org.springframework.boot.webmvc.test.autoconfigure`로 옮겼다(Boot 3과 다름).
  `@MockBean`은 없어졌고 `@MockitoBean`
  (`org.springframework.test.context.bean.override.mockito`)을 쓴다.

## 성능 예산 (TRD §14)

원문 그대로 옮긴다. **이 표를 안 보고 기억으로 판단하다 한 번 틀렸다** — Coverage 예산을
classifier 단독 기준으로 읽어 "충족"으로 기록해뒀는데, 판정 단위는 **S1+S2 합계**다.

| 구간 | 예산 | 초과 시 대응 |
|---|---|---|
| **Coverage (S1+S2)** | **12초** | 프롬프트 축약 / 모델 교체 / **부분 병렬화**. 검증 범위 축소 금지(ADR004) |
| 질문 생성 (S3) | 6초 | 표현 조정 생략, fallbackQuestion 직행 |
| 답변 판정 (S4) | 6초 | — |
| 재설명 (S5) | 8초 | — |
| 비-AI API | p95 300ms | — |
| DB 왕복 (앱 SG ↔ DB ICN) | 요청당 쿼리 15회 이하 | 조회를 배치로 묶고 N+1 제거 |

> "Step 5에 S1+S2 실측을 반드시 수행한다. 4주차에 발견하면 손쓸 시간이 없다.
> 질문 3개는 세션 진입 시 미리 생성해 캐시한다."

**Step 5 — 완료** (2026-08-26, 사용자 확정으로 Phase 6은 R1에서 종료 후 Phase 7 마무리).
전체 설계·진행 상황은
`docs/decisions/2026-08-20-coverage-latency-fanout.md` — 새 세션은 이 파일부터 읽을 것.
(2026-08-20) Phase 0(기록 정정)·Phase 1(계측: `llm_call_log` 토큰 컬럼) 완료.
(2026-08-25) `integrationTest` 21건 통과로 V4 마이그레이션 검증 완료.
(2026-08-25) Phase 2(베이스라인 실측 + 회귀) 완료 — classifier 절편 **4,562ms**(n=31, 실 토큰
기준), 팬아웃 유효 판정.
(2026-08-25) **Phase 3~5 완료** (`637f70f`). `ai/ClaudeCoverageClassifier`가 9개 Risk를
3개씩 3배치로 나눠 가상 스레드로 동시 호출한다(`RISKS_PER_CALL=3`, `promptVersion`은
`coverage-v3-b3`로 배치 크기까지 인코딩). 배포·실측 결과:
- **classifier 레이턴시 43~51% 단축**, 예측(45~48%)과 일치. S1+S2 합계는 28~31% 단축
  (verifier는 안 쪼갬)
- **Risk 정확도 120/135, Gate 15/15 — Phase 2와 완전 동일.** 정확도 손실 없음
- **30초 계약 한도가 안전해졌다.** 15건 중 최댓값 26.3s(이전엔 `CONS_A_002`가 33.2s로
  초과했었음)
- **12초 예산은 여전히 미충족**(합계 평균 13.5~21.9s). DoD는 12초 달성을 요구하지
  않으므로 Phase 6(사다리) 착수는 별도 결정
- 사전 등록된 중단 조건 4개 전부 통과(`CONS_A_005` 8/9는 팬아웃 전 Phase 2에서도 동일해
  회귀 아님으로 판정, 상세는 결정 문서)

(2026-08-26) **Phase 6 R1(verifier effort HIGH→MEDIUM) 완료.** `CONS_A_003` R01 고정
픽스처로 HIGH 3회 + MEDIUM 3회 스윕 — **MEDIUM에서도 CONTRADICTS 3/3 유지**(정확도 리스크
해소). 다만 대상 1개짜리 픽스처라 레이턴시 이득은 이 실험으로 드러나지 않았다(운영 평균
출력 353 tok 대비 이 픽스처는 81~90 tok). **운영 기본값은 여전히 HIGH** — 스윕 전용
package-private 생성자(`ClaudeSemanticVerifier(AiGateway, Effort)`)만 추가했다.
**사용자가 여기서 사다리를 멈추기로 확정** — R2 이하는 미착수.

(2026-08-26) **Phase 7(문서·계약) 완료로 Step 5 종료.** `docs/openapi.yml`
1.4.3→1.4.4(스키마 변경 없음, classifier "1회 batch call" 서술 정정 +
`analysis.classifierLatencyMs`/`promptVersion` 설명 갱신). "실측 33초" 프론트 전달값을
26.3초로 갱신(코드 변경 불필요 — fetch 타임아웃 60초가 이미 여유 안).

**프론트 계약 사본 — 해소** (2026-08-26, 팀 결정). `finready-frontend/contracts/openapi.yml`
사본을 되살리지 않고, 프론트가 루트 `docs/openapi.yml`을 직접 참조하는 방식으로 정리하기로
했다. 사본이 갈라질 걱정 자체가 없어진다. `package.json`의 `gen:api`·`contract.test.ts`
경로 수정은 프론트 쪽 작업이라 여기서는 손대지 않았다.

상세는 `docs/decisions/2026-08-20-coverage-latency-fanout.md`

**12초와 별개로 Coverage 엔드포인트에는 30초 계약 한도가 있다.** 둘을 섞지 말 것.

§14.1(리전 교차)이 요구하는 세 가지 중 **1번은 부분적으로, 3번은 충족했다** (2026-08-26,
TRD §18 Step 9).
① `GET /sessions/{id}`·`GET /report`를 fetch join/배치로 한 번에 읽기 — **여전히
`join fetch`·`@EntityGraph`는 없다.** 대신 확인된 **중복 쿼리 2건을 제거**했다:
`SessionService.getSnapshot`이 최신 revision을 조회한 뒤 그 값을
`CoverageQueryService.latestFor(session, currentRevision)`(신규 오버로드)에 넘겨 같은
쿼리를 두 번 안 날리게 했고, `ReportService.getReport`는 `gate_override`를 두 번
읽던 것(`latestFor` 내부 1회 + `overrideRecordsOf` 1회)을 `CoverageQueryService
.reportSectionsOf(session)` 하나로 합쳐 한 번만 읽는다. 실측: `GET /sessions/{id}`
11→**9쿼리**, `GET /report` 14→**11쿼리**(둘 다 TRD 상한 15 이내). Coverage·Understanding·
Override·Revision·Audit 다섯 컬렉션을 각각 한 번씩 순차로 읽는 구조 자체는 그대로다 —
진짜 fetch join으로 왕복 자체를 줄이는 건 더 큰 리팩터라 이번엔 안 건드렸다.
② `default_batch_fetch_size: 100` — **충족**(`application.yaml`).
③ 통합 테스트에 쿼리 카운트 assertion — **충족**.
`ReportQueryCountIntegrationTest`(Hibernate `Statistics`, Testcontainers)가 두 엔드포인트의
쿼리 수를 실측치(9·11)로 고정한다. TRD가 "로컬에서는 N+1이 안 보이고 배포 후 심사에서만
느려지는 게 리전 교차의 전형적 실패 방식"이라 경고한 바로 그 회귀를 이 테스트가 잡는다.
`risk_workflow_state`가 비어 있으면 `UnderstandingQueryService.statesOf`가 조기 반환해
쿼리 4개가 숨는 함정이 있어, 픽스처에 Coverage·Override·Understanding 세 섹션 모두
최소 1행씩 채워 실제 운영 경로를 그대로 태웠다.

**실 배포(Render Singapore ↔ Supabase Seoul)에서 실측** (2026-08-26). 로컬/Testcontainers는
같은 리전이라 이 비용이 안 보인다 — 실제 배포 URL로 직접 재야 하는 이유다.
`https://finready-backend.onrender.com`에 임시 세션(`9c9b50a7-e4d1-42c8-96d6-137f349a4ffe`,
DRAFT, 심사에 노출 안 됨 — 목록 조회 API가 없다)을 만들어 4개 엔드포인트를 재서
쿼리당 비용을 역산했다.

| 엔드포인트 | 쿼리 수 | ttfb (warm, n=4~5 평균) |
|---|---|---|
| `/actuator/health` (DB 사실상 미사용) | ~0 | 213ms |
| `GET /products/demo` | 3 | 447ms |
| `GET /sessions/{id}` (DRAFT, coverage 전이라 4개로 단축) | 4 | 521ms |
| `GET /report` (DRAFT, 위와 동일 이유로 8개) | 8 | 732ms |

네 점을 선형회귀하면 **쿼리당 51~80ms** — TRD §14.1의 문서화된 70~90ms와 같은 자릿수로
수렴한다(측정마다 오차 있음, 위 표는 워밍업 후 값). 이 기울기로 **실제 데이터가 찬**
9·11쿼리를 투영하면:

- `GET /sessions/{id}` (9쿼리): **약 695~920ms**
- `GET /report` (11쿼리): **약 805~1,080ms**

**둘 다 TRD §14 "비-AI API p95 300ms" 예산을 2.3~3.6배 초과한다.** Coverage처럼 "12초
넘지만 30초 한도는 지킨다"는 여유가 이쪽엔 없다 — 심사 중 리포트·세션 화면을 열 때마다
매번 이 비용이 걷힌다. 이번 작업(쿼리 중복 제거)은 **회귀를 하나 막은 것이지 예산을
충족시킨 게 아니다.** 진짜 해소는 위 ①에서 보류한 fetch join/배치 리팩터, 또는 다섯
컬렉션 조회를 병렬화하는 것(커넥션 풀이 5개뿐이라 단일 사용자 P0 데모 전제에서만
안전) 중 하나가 필요하다.

**결정 (2026-08-26, 사용자 확정): 지금은 보류한다.** 화면이 안 뜨는 문제가 아니라
느린 문제이고, fetch join은 손이 많이 가고 병렬화는 커넥션 풀 5개를 다 쓰는 리스크가
있다 — Coverage 12초 예산과 같은 논리로 우선순위를 낮춘다. 배포 동결(09-06) 전 남은
시간은 Step 6(dev set 확장)·Step 9 잔여·Step 12·Step 13처럼 아직 착수 전인 항목에 쓴다.
재검토하려면 이 문서의 실측치(9·11쿼리, 695~920ms·805~1,080ms)부터 다시 읽을 것.

## 테스트 전략

**하이브리드.** 순수 단위/`@WebMvcTest`는 기본 `test` 태스크, 실 Postgres가 필요한 검증은
`integrationTest` 태스크로 분리했다(둘 다 2026-08-14 기준 도입 완료, F03 기능 코드는 아직).

| 단계 | 방식 | 검증 범위 |
|---|---|---|
| `./gradlew test` (기본) | 순수 단위 + `@WebMvcTest` | 로직·계약 JSON 필드명·오류 코드 매핑 |
| `./gradlew integrationTest` (Docker 필요) | Testcontainers PostgreSQL | `ck_*` 제약, append-only 트리거, `updatable=false`, `@Immutable`, `@Version` 락, `ddl-auto: validate` 회귀, Flyway 맨바닥 실행 |

지금 순수 단위 방식으로는 **DB에 걸린 규칙을 검증할 수 없다.** 특히 규칙 1의 `updatable=false`는
Hibernate가 실제 SQL을 만들어야 확인되므로, `@WebMvcTest` 쪽은 "코드에 그렇게 적어놨다"까지만
검증한다. F03에서 규칙 3(`ck_explained_requires_verification`)이 실제로 쓰이기 시작하면
`integrationTest` 쪽에 케이스를 추가한다.

- `test`·`integrationTest` 둘 다 `test` 프로파일로 돈다. `SeedLoader`는 `@Profile("!test")`라
  안 돈다
- `src/test/resources/application-test.yaml`이 가짜 datasource를 박아둔다.
  `integrationTest`는 `io.finready.integration.AbstractPostgresIntegrationTest`가
  `@DynamicPropertySource`로 이 값을 컨테이너 실접속 정보로 덮어쓴다 — 별도 프로파일 파일 없이
  같은 `test` 프로파일 안에서 통합 테스트만 실 DB를 쓴다. 이 장치 덕분에 실수로
  `@SpringBootTest`를 붙여도 운영 Supabase에는 붙지 않는다
- Testcontainers 쪽 datasource URL엔 `currentSchema=finready`가 필요하다.
  `application.yaml`의 `flyway.schemas: finready` 때문에 테이블이 `public`이 아니라
  `finready` 스키마에 생기는데, 컨테이너 JDBC URL은 기본이 `public`이라 안 붙이면
  방금 만든 테이블이 안 보인다
- 신규 통합 테스트는 `@Tag("integration")` — `AbstractPostgresIntegrationTest`가 클래스에
  붙여두므로 상속만 하면 자동으로 붙는다. `test` 태스크가 이 태그를 제외한다
- 평가 모듈만 `@Tag("evaluation")`으로 분리해 `./gradlew evaluate`로 돌린다

## Docker

용도가 둘이다. **배포용 `Dockerfile`과 테스트용 Testcontainers는 별개다.**

- `Dockerfile` — Render 빌드 전용. 로컬에서 이걸 직접 실행할 일은 없다
- Testcontainers — `integrationTest` 태스크가 PostgreSQL 컨테이너를 띄운다. Docker Desktop이
  떠 있어야 한다. 아티팩트명이 Testcontainers 2.x부터 `testcontainers-junit-jupiter` /
  `testcontainers-postgresql`로 바뀌었다(단독 `junit-jupiter`/`postgresql` 아님) — Boot
  4.0.7 BOM이 관리하는 `testcontainers-bom` 버전이 2.0.5라 구버전 아티팩트명 예제를
  그대로 쓰면 `Could not find` 로 걸린다 (2026-08-14)

### 배포용 Dockerfile

`finready-backend/Dockerfile`이 Render 빌드에 쓰인다. 로컬에서는 실행할 일이 없다.

- 빌드 스테이지: `eclipse-temurin:25-jdk` + **Gradle Wrapper**
  (gradle 공식 이미지를 안 쓴 이유: 래퍼가 Gradle 버전을 저장소에 고정하므로
  로컬과 컨테이너 빌드가 항상 일치한다)
- 런타임 스테이지: `eclipse-temurin:25-jre`, 비-root 사용자
- Render가 주입하는 `PORT` 환경변수를 사용
- **모노레포 주의**: Render 서비스 설정에서 Root Directory를 `finready-backend`로
  지정해야 `COPY` 경로가 맞는다

## 현재 진행 상황

### 완료
- Supabase `finready` 스키마 + `finready_backend` role + search_path/타임아웃/커넥션 한도
- 프로젝트 스캐폴딩, `gradlew build` 성공 (Java 25 / Boot 4.0.7 / Gradle 9.5.1)
- `application.yaml`, `V1__init.sql`, `V2__audit_append_only.sql` 배치
- `seed/product_a_risk_schema.json` — PRD v1.3.1 정책표 반영본
- `static/documents/PROD_A/v1.0` — SHA-256 `5d355381abe028eb492f3c277236ee35a774150f4dbb24c289d2612ca8c5c47e`
  (파일명에 `.pdf` 확장자가 없다. 시드의 `documentFileName`과 로더에서 맞출 것)
- `src/test/resources/eval/demo_seed.json` — Gate 시나리오 6건 검증 완료
- 시드 sourceText 9건이 PDF 지정 페이지에 정확히 1회 존재함을 확인 (2026-08-12)
- **로컬 DB 연결 성공** — Supavisor Session Pooler,
  user는 `finready_backend.{project-ref}` 형식 (접두사만 전용 role로 교체) (2026-08-12)
- **Flyway 마이그레이션 v1·v2 적용 완료** — 테이블 14개 + `flyway_schema_history` 생성 (2026-08-12)
- **IntelliJ 2026.2.1 Community로 교체** — 이전 2023.2.5의 번들 Kotlin 1.9.24가
  Gradle 9.5.1의 Kotlin 2.3.20 메타데이터를 못 읽어 `build.gradle.kts` 전체가
  빨간줄이었다. Boot 4가 요구하는 최소 Gradle(8.14)조차 Kotlin 2.0.21이라
  다운그레이드로는 해결 불가였다 (2026-08-12)
- **Render 배포 완료** — https://finready-backend.onrender.com
  (Singapore / Docker / Starter, Root Directory `finready-backend`).
  `/actuator/health` 200 확인. 배포 로그에서 Flyway `Current version: 2, up to date` —
  로컬에서 적용한 v1·v2를 그대로 인식했다는 뜻이라 스키마가 하나임이 확인됐다.
  `SPRING_PROFILES_ACTIVE`를 넣지 않아 **default 프로파일로 뜬다(의도한 동작)**.
  `application.yaml`의 `local` 문서가 안 걸리므로 로깅은 INFO/WARN이다 (2026-08-13)
- **JPA 엔티티 14개 + enum 16개** — 패키지 구조는 TRD §2.1
  (`product`·`session`·`coverage`·`understanding`·`explanation`·`audit`·`ai`·`common`).
  `local` 기동으로 `ddl-auto: validate` 통과 확인 = V1 DDL과 컬럼·타입·nullable 일치.
  `customer_profile`은 TRD §2.1 목록에 없어 시드라는 성격을 따라 `product/`에 뒀다 (2026-08-13)
- **시드 로더 + 검증기** (TRD §4.5) — `CommandLineRunner`가 검증 후 upsert.
  기동 로그 `시드 적재 완료 — product=PROD_A (A-2026-08-12-01), risk 9건, customerProfile 6건`.
  고객 preset은 `seed/customer_profiles.json` 별도 파일로 뒀다(`$schema`가 다르므로).
  `static/documents/PROD_A/v1.0` → **`v1.0.pdf`로 이름 변경** — `documentUrl`이
  `/documents/PROD_A/v1.0.pdf`라 그대로 두면 프론트의 PDF 요청이 404다 (2026-08-14)
- **F01 `GET /api/products/demo` + `common/` 오류 규약** — `ErrorCode`(계약 18값,
  HTTP 상태·`recoverable`을 코드마다 보유) / `ErrorResponse` / `ApiException` /
  `GlobalExceptionHandler` / `RequestIdFilter` / `WebConfig`(CORS).
  응답은 엔티티가 아니라 `DemoProductResponse`로 변환한다 — `document_sha256` 같은
  계약 밖 컬럼이 새지 않게 (2026-08-14)
- **데모 preset 서버 이관** (TRD §18 Step 11, openapi v1.4.3) — `DemoPresetCatalog`가
  `seed/demo_presets.json`을 기동 시 읽어 `demoPresets`/`demoAnswers`로 내려준다.
  DB 테이블을 만들지 않았다(TRD §4에 없는 표 + 런타임 불변 + 감사 대상 아님).
  파일은 `eval/demo_seed.json`의 `CONS_A_001`(main)·`CONS_A_003`(safety) 사본이고
  `SeedEvalParityTest`가 동일성을 빌드 타임에 강제한다 — **데모에서 보이는 판정 결과가
  정확도를 실측한 그 상담문의 결과여야 하기 때문**이다. 이전에는 프론트 상수
  `constants/demo.ts`에 채점 이력 없는 별개 텍스트가 있었다.
  `safety`의 `supplementTranscript`는 `null`이다 — CONS_A_003에 채점된 보완문이 없다 (2026-08-24)
- **고객 preset 3→6건** — `CUST_D`(HIGH/ADVANCED)·`CUST_E`·`CUST_F` 추가.
  ⚠️ 세 enum 중 **동작에 영향을 주는 것은 `explanationLevel` 하나뿐**이다
  (`ClaudeQuestionGenerator`, `ReExplanationService`). 나머지 둘은 어디서도 읽히지 않아
  `CUST_B`와 `CUST_C`는 지금 동작상 구별되지 않는다. `InvestmentExperience.HIGH`와
  `FinancialLiteracy.ADVANCED`는 `CUST_D` 전까지 시드에 한 번도 없었다 (2026-08-24)
- **세션 / Revision / StateMachine** (TRD §5.1~5.3) — `common/StateMachine`에 전이표 전체.
  `POST /api/sessions`, `POST /api/sessions/{id}/revisions`(F02), `GET /api/sessions/{id}`.
  상태 변경은 `session.transitionTo(to, stateMachine)` 하나뿐이다 — StateMachine을 인자로
  받게 해서 전이표를 건너뛸 수 없게 만들었다(규칙 7).
  `GET`의 `coverage`·`nextAction`·`understanding`은 계약이 null/빈 배열을 허용해
  지금은 그대로 내보낸다. F03·F04에서 채운다 (2026-08-14)
- **테스트 3종 도입** — `StateMachineTest`(전이표 52+15조합 전수),
  `SessionServiceTest`(revision 채번·중복·검증 순서), `SessionControllerTest`(계약 필드명·오류 스키마).
  `D:\dev\finready`에서 `gradlew test` 재실행 — 6개 스위트 101건 전수 통과,
  argfile 인코딩 문제 재발 없음 확인 (2026-08-14)
- **Testcontainers 스캐폴딩** — `./gradlew integrationTest` 신설 태스크(기본 `test`와 분리,
  `@Tag("integration")` excludeTags로 격리). `AbstractPostgresIntegrationTest` 베이스 +
  `SchemaConstraintIntegrationTest` 스모크 2건: ① Flyway V1+V2가 실 Postgres에 적용된
  스키마로 `ddl-auto: validate` 통과, ② `audit_event` INSERT 성공 / UPDATE는
  `[23001]`로 트리거가 차단 — 둘 다 지금까지 로컬 기동으로 수기 확인했던 것을
  CI 회귀로 전환. F03 리포지토리·서비스 코드는 아직 없음, 이건 인프라만 (2026-08-14)
- **`revisionNo` 경쟁 상태 — (1)안 적용** (`docs/decisions/2026-08-14-...`). 경쟁 자체는
  그대로 두고 실패의 모양만 고쳤다: `uq_revision` 위반이 500 `INTERNAL_ERROR`(재시도 불가)로
  나가던 것을 409 `CONCURRENT_SESSION_UPDATE`(recoverable)로. **제약 위반을 뭉뚱그리지
  않는다** — `ck_*` 위반은 애플리케이션이 DB 규칙을 우회했다는 뜻이라 500으로 남긴다.
  제약 이름 추출은 `common/ConstraintNames`(JPA는 Hibernate 예외, JdbcTemplate은 메시지 폴백).
  `RevisionConcurrencyIntegrationTest`가 실 Postgres로 재현 (2026-08-18)
- **F03 Coverage 코어 (LLM 비의존 부분)** — 모델 선정(D-02)을 기다리지 않아도 되는 것만 먼저.
  `OffsetMapper`(정규화↔원문 UTF-16 인덱스 맵, 규칙 4의 재계산 지점) /
  `ProvenanceVerifier`(EMPTY·TOO_SHORT·TOO_LONG·NOT_FOUND·AMBIGUOUS) /
  `CoverageStatusResolver`(openapi 결정표) / `CoverageResultFactory`(엔티티 생성자를
  직접 부르지 않게 하는 유일한 경로) / `GateEvaluator`+`GateStatus` /
  `CoverageClassifier`(포트만, 구현체 없음) (2026-08-18)
- **`ck_explained_requires_verification`의 NULL 구멍 수정 (V3)** —
  `semantic_relation IS NULL`인 EXPLAINED를 막지 못하고 있었다. Postgres CHECK는 결과가
  FALSE일 때만 거부하는데 `NULL = 'SUPPORTS'`가 NULL이라 제약 전체가 NULL로 평가됐다.
  **규칙 3의 "DB check 제약으로도 강제돼 있다"가 이 경우 사실이 아니었다.**
  `is not distinct from`으로 교체. 경위는 `docs/decisions/2026-08-18-explained-constraint-null-hole.md`
  (2026-08-18)
- **평가 데이터셋 `CONS_A_002`~`006` 본문 작성** — 라벨(`coverageGroundTruth`)에 맞춰
  9개 Risk의 `fact` 요소를 넣고 뺐다. `DemoSeedGateConsistencyTest`가 라벨 ↔
  `expectedGateResult`를 `GateEvaluator`로 재계산해 대조한다 — 시나리오가 60건까지
  늘어날 때 사람 눈으로는 유지되지 않는 검증이다 (2026-08-18)
- **F03 파이프라인 (LLM 무관 부분 전체)** — `CoverageAnalysisService` /
  `CoverageWriter` / `CoverageController`(`POST /sessions/{id}/coverage`,
  `POST /sessions/{id}/gate-override`) / `CoverageResponse` /
  `CoverageResultRepository`·`GateOverrideRepository` / `SemanticVerifier` 포트.
  **규칙 6 때문에 서비스에 `@Transactional`이 없다** — 읽기 → LLM 호출(트랜잭션 밖) → 쓰기이고,
  쓰기만 `CoverageWriter`가 묶는다. 같은 클래스 안에서 `@Transactional` 메서드를 자기 호출하면
  프록시를 안 타 트랜잭션이 아예 안 걸리므로 별도 빈으로 뺐다.
  멱등: 같은 revision에 결과가 있으면 LLM을 다시 부르지 않는다(새로고침이 요금을 다시 물지 않게).
  `ai/AiPortConfig`가 `@ConditionalOnMissingBean` 스텁을 등록해 **LLM 없이도 기동**하되,
  호출되면 설정 누락을 명시적으로 알린다 — 빈 결과를 돌려주면 전 Risk가 "설명 안 됨"으로
  읽혀 Gate가 잠긴다 (2026-08-18)
- **F04 질문 발급 + F05 답변 판정(attempt 1)** — `POST /sessions/{id}/questions`,
  `POST /sessions/{id}/understanding`. F03과 같은 트랜잭션 구조(`UnderstandingWriter`).
  · `understanding/WorkflowStateMachine` — Risk 단위 전이표. TRD §4.2 "갱신 일원화"를
  `ConsultationSession.transitionTo`와 같은 패턴으로 강제한다(상태머신을 인자로 받게 해
  전이표를 건너뛸 수 없게). 세션 상태머신과 **합치지 않았다** — 축이 다르다
  · `understanding/NextActionResolver` — 계약 표(TRD §6.6)를 그대로. 프론트가 이 값만 보고
  분기하므로(규칙 8) 한 곳에만 둔다. **UNCERTAIN은 REEXPLAIN으로 가지 않는다**(PRD §7.5) —
  헷갈리기 쉬워 테스트로 고정
  · attempt는 **경로가 정한다**(`/understanding`=1, `/recheck`=2). 클라이언트가 보내지
  않으므로 2를 1로 바꿔 재시도할 수 없다
  · 질문 생성 실패는 **정상 경로**다 — 검수 `fallbackQuestion`으로 대체 + `source: FALLBACK`.
  그래서 `QuestionGenerator` 스텁만 예외적으로 빈 결과를 돌려준다(던지면 F04를 못 돌려본다)
  · Override로 제외된 Risk도 `COMPLETE/SKIPPED_BY_OVERRIDE`로 기록한다 — 리포트에서
  "왜 안 물었나"가 보여야 한다 (2026-08-18)
- **F06 재설명 + F07 recheck·직원 처리** — `POST /sessions/{id}/reexplain`,
  `POST /sessions/{id}/recheck`, `POST /sessions/{id}/risks/{riskId}/staff-resolution`.
  · **F07은 거의 공짜였다** — `judge(...)`가 이미 attempt를 인자로 받아서
  `submitRecheckAnswer`가 `RECHECK_ATTEMPT`로 부르기만 하면 됐다. 설계가 값을 한 셈이다
  · `explanation/Guardrail` — TRD D-04 해소. **금칙어 목록 방식**(LLM 자가검증 아님).
  결정적이라 테스트로 고정되고 실행마다 흔들리지 않는다 — Coverage 실측에서 경계 판정이
  실행마다 뒤집히는 걸 봤으므로 그 성질을 안전장치에 넣지 않았다
  · **부정형 예외가 이 클래스의 핵심이다.** "보장"·"확정"·"안전"을 단순 `contains`로 막으면
  **맞는 설명이 걸린다** — 이 상품의 검수된 사실 자체가 부정형이다("원금이 보장되지 않습니다").
  그대로 구현했다면 가장 정확한 재설명이 매번 fallback으로 떨어졌을 것이다. 같은 절 안에서만
  부정어를 찾는다 — 절 경계를 안 끊으면 뒷문장 "없"이 앞문장 "보장"을 면제해 위반을 놓친다
  · 숫자는 **값으로 비교**한다(`BigDecimal.stripTrailingZeros`). 문자열 비교면 `0.80` vs `0.8`
  처럼 표기만 다른 정확한 숫자가 "지어낸 숫자"로 걸린다
  · 재설명은 **멱등**하다 — 이미 있으면 LLM을 다시 부르지 않는다(Coverage와 같은 이유).
  `re_explanation`에는 unique 제약이 없어(§4.2 append 정책) 애플리케이션이 조회로 판단한다
  · 진입 조건 확인(`requireMisunderstoodAnswer`)과 후속 질문 발급(`issueRecheckQuestion`)은
  **understanding 모듈에 뒀다.** explanation이 직접 `session_question`을 쓰면 발급 규칙과
  멱등성이 두 곳이 된다(TRD §4.2·§4.6)
  · 직원 처리는 `ai_status`를 건드리지 않는다(규칙 1). `StaffResolutionHandling` 테스트가
  `resultRepository.save`가 호출되지 않음을 고정한다
  · 신규 테스트 38건 (Guardrail 15 / ReExplanation 12 / F07 11). 전체 268 + 18 통과 (2026-08-19)
- **F08 리포트 + 종료 + 감사** — `GET /sessions/{id}/report`, `POST /sessions/{id}/close`.
  · `audit/` 신설 — `AuditEventType`(9값) / `AuditEntry` / `AuditRecorder` / `AuditEventRepository`.
  기록 지점 9개: 세션 생성·revision 저장·Coverage 분석·Gate Override·질문 발급·답변 판정·
  재설명 생성·직원 처리·세션 종료
  · **`AuditRecorder` 는 `Propagation.MANDATORY`** — 감사 기록이 자기 트랜잭션을 새로 열면
  변경은 롤백됐는데 기록만 남는 조합이 생긴다. 규칙 6 때문에 서비스에는 트랜잭션이 없으므로,
  요약 문자열은 서비스가 `AuditEntry` 로 만들어 Writer 에 넘긴다
  · **`AuditEventRepository` 가 `JpaRepository` 를 상속하지 않는다** — append-only 테이블에
  `delete` 가 자동완성으로 노출되지 않게 필요한 두 메서드만 선언했다. 규칙 1이
  "리포지토리에 UPDATE 메서드를 만들지 말 것"이라 한 것과 같은 이유
  · `session/CloseEligibilityEvaluator` — 리포트의 `closeEligibility` 와 종료 요청 검증이
  **같은 판정을 쓴다.** `canClose` 는 따로 정의하지 않고 `StateMachine.canTransition` 에 묻는다
  (규칙 7 — 조건을 복제하면 전이표를 고쳐도 이쪽이 안 따라온다)
  · 경고 확인은 **개수가 아니라 riskId 로 대조**한다. 개수만 세면 다른 Risk 를 같은 개수만큼
  보내도 통과한다
  · 미해결이 없으면 사유를 **저장하지 않는다** — 정상 종료인데 사유가 남으면 리포트에서
  "무언가 미해결이었다"로 읽힌다
  · 종료는 **멱등**하다. 버튼 두 번 누르기·새로고침 후 재시도가 409 로 끝나면 안 된다
  · `report/ReportService` 는 아무것도 계산하지 않는다 — Coverage·Understanding 조회 서비스와
  `CloseEligibilityEvaluator` 의 결과를 담기만 한다
  · 신규 테스트 38건. 전체 **345건**(단위 324 + 통합 21) 통과 (2026-08-19)

### 검증한 것 (2026-08-12)
- V1 테이블 14개가 TRD §4.1 목록과 이름 일치
- 시드 risk 9건 정책이 PRD §5 정책표와 일치
  (R01–R03 GATE_REQUIRED+understandingCheck, R04·R08 GATE_REQUIRED, R05–R07·R09 WARN_ONLY)
- PDF SHA-256이 기재값과 일치
- V2 트리거가 `before update or delete`만 잡고 INSERT를 넣지 않음 (TRD §4.4가 경고한 사고 회피됨)
- JDK 25로 `gradlew build` BUILD SUCCESSFUL
- **append-only 트리거 실검증**: `audit_event` INSERT 성공 /
  UPDATE는 `[23001] append-only table: audit_event`로 차단됨 (TRD §4.4 충족)
- **스키마 격리**: 마이그레이션·`flyway_schema_history` 모두 `finready` 스키마에 생성.
  `public`의 기존 앱 테이블은 건드리지 않음

### 검증한 것 (2026-08-14)
- **F01 응답 ↔ openapi v1.4.2 대조**: `product` 7필드 일치,
  `understandingCheckRiskIds`=`["R01","R02","R03"]`, `customers` 3건(현재 6건),
  risks 9건의 정책 분포가 PRD §5 정책표와 일치
- **계약 밖 컬럼 미노출 확인**: 응답에 `documentSha256`·`isLiveDemo`가 없다.
  엔티티 직렬화였으면 그대로 샜다
- `X-Request-Id` 헤더 존재(RequestIdFilter 작동), `Vary: Origin`(CORS 활성)
- **PDF 서빙**: `/documents/PROD_A/v1.0.pdf` 200 + `Content-Type: application/pdf`.
  확장자 없는 `v1.0`이었으면 `application/octet-stream`으로 나가 브라우저가
  다운로드로 처리했을 것
- 미검증 경로: 시드에 `NOT_APPLICABLE` Risk가 없어 `ProductQueryService`의
  해당 필터가 실제로 걸러낸 적이 없다

### 실측한 것 (2026-08-18, 실 LLM 호출)

상세는 `docs/decisions/2026-08-18-coverage-prompt-tuning.md`. **다시 헤매지 않으려면
프롬프트를 손대기 전에 그 문서부터 볼 것.**

- **비용**: Coverage 2호출 웜 캐시 **$0.019** / 콜드 $0.035. 세션 전체 추정 ~$0.047 →
  **$5 크레딧으로 약 100세션.** 착수 전 추정($0.2~0.4)이 자릿수로 틀렸다 — 한국어 토큰을
  과대평가했다(실측 대략 1글자=1토큰). **실험을 아낄 이유가 없다**
- **캐시 작동 확인**: `cacheWrite=2969 → cacheRead=2969` 완전 적중. 44% 절감.
  Risk 카탈로그를 정렬 순서로 system에 둔 설계가 유효하다.
  ⚠️ **기본 TTL 5분** — 심사처럼 띄엄띄엄 오면 매번 쓰기만 물 수 있다(배포 전 결정)
- **웜 상태 비용의 70%가 출력 토큰**이다. 입력은 캐시로 거의 사라졌고, 줄일 곳은 evidence 인용문이다
- **레이턴시**: 웜에서 classifier 10.8s, verifier 4.7s, **합계 15.5s → §14 예산 12초 초과**.
  (2026-08-20 정정: 위 "## 성능 예산 (TRD §14)" 참조. 이 줄은 원래
  "classifier 10.8s로 예산 충족"이라 적혀 있었고 그것이 오독의 출처였다.)
  단 **실행 간 편차가 프롬프트 효과보다 크다**(같은 프롬프트로 verifier 5.0~12.0s).
  **n=1로 레이턴시를 판단하지 말 것** — 이 함정에 한 번 빠져 잘못된 원인 진단을 했다
- **판정 안정성**: `temperature: 0`인데도 **경계 케이스는 실행마다 바뀐다**(R02·R03).
  명확한 판정(R01·R04·R05·R07~R09)은 3회 안정. 무작위가 아니라 경계에 집중된 흔들림이다
- **Gate 결과는 3회 모두 정확**했다. 개별 Risk가 어긋나도 제품 판단은 맞았다 —
  **평가 지표를 개별 Risk 정확도만으로 잡으면 이 사실이 안 보인다**
- **`CONS_A_003` 함정 검출 확인** — 오도 설명("노낙인이라 사실상 원금은 지켜진다")에서
  R01을 `CONTRADICTED`로 판정하고 해당 문장을 정확히 인용했다. **2회 실행 결과 9개 Risk 전부 동일.**
  라벨(`INSUFFICIENT`)보다 정확해서 **라벨을 `CONTRADICTED`로 정정**했다
- **불안정성은 모델이 아니라 입력이 모호할 때 나타난다.** `CONS_A_003`은 완전히 안정적이고
  `CONS_A_001`만 흔들렸다 — 후자는 "언급이 아예 없는" 항목이 많아 모델이 매번
  "이 애매한 문장을 근거로 볼 것인가"를 다시 판단해야 했다
- **레이턴시·비용은 출력 토큰에 비례한다.** EXPLAINED가 많을수록 인용문이 길어진다.
  `CONS_A_001` classifier 10.8s / $0.019 vs `CONS_A_003` 14.3s / $0.030 —
  상담문 내용이 출력 토큰을 정하고 그것이 레이턴시를 정한다.
  (2026-08-20 정정: 원래 "§14 예산 충족 여부가 상담문에 달렸다"였으나,
  합계 기준으로는 **어떤 상담문에서도 충족한 적이 없다**)

### 실측한 것 (2026-08-19, `CONS_A_002`·`004`·`005`)

`tools/run-coverage-eval.ps1`로 일괄 실행. 상세는 위 결정 문서.

- **Risk 25/27, Gate 3/3.** `002` 9/9 · `004` 7/9 · `005` 9/9
- **`CONS_A_005` 키워드 함정 9/9 통과** — "조기상환 조건은 투자설명서에 다 나와 있고"처럼
  단어만 있고 설명이 없는 문장에서 INSUFFICIENT를 냈다.
  **단순 키워드 매칭이 실패하는 지점을 통과한다는 유일한 실증이다**
- **`CONS_A_002`에서 Gate가 처음 열렸다** — `001`·`003`은 모두 BLOCKED라 통과 경로가
  실제로 동작한 적이 없었다
- **Verifier 대상 확대가 7건으로 뒷받침됐다** — WARN_ONLY+EXPLAINED가 전부 `SUPPORTS`.
  확대 전이었다면 `CONS_A_002`는 **완벽한 상담인데 경고 3개**가 떴다
- **어긋난 2건은 둘 다 `CONS_A_004`이고 Gate 영향 없음.** R02는 NOT_FOUND/INSUFFICIENT
  경계(둘 다 Gate를 막아 동작 동일), **R09는 모델 오류** — 다요소 fact를 근거 인용
  하나로 판정하기 불리하다는 가설(미검증)
- ⚠️ **레이턴시가 세 건 모두 12초 예산 초과** — classifier 15.1~23.3s,
  **합계(S1+S2) 22.7~32.9s**, wall 23.0~33.2s. (2026-08-20: 원래 classifier 단독을
  예산과 비교했는데, 판정 단위는 합계다. 결론은 같고 격차가 더 크다.)
  `CONS_A_002`의 23.3s는 30초 한도에 여유가 6.7초인데 그 상담문은 1,400자이고
  **계약 상한은 8,000자**다. → **`timeout-seconds` 30 → 60 으로 올렸다**(실패 모양만 변경).
  근본 해결인 classifier 배치 병렬화는 **항목 간 경계 규칙이 나빠질 위험**이 있어
  재측정이 필요하므로 F06~F08 이후로 미뤘다
- ~~**프론트에 실측 33초를 전달해야 한다**~~ — **갱신** (2026-08-26, Step 5 Phase 7).
  팬아웃 이후 재측정 최댓값은 **26.3초**(`CONS_A_004`, Phase 5)로 낮아졌다. 프론트
  fetch 타임아웃은 이미 60초(`LLM_TIMEOUT_MS`, `spring-api.ts`)라 33초든 26초든 여유
  안이었으므로 타임아웃 자체를 바꿀 필요는 없다 — 대기 화면 문구에 구체적인 초 단위
  숫자를 박아뒀다면 그 값만 26초대로 낮출 것

### 실측한 것 (2026-08-19, F04~F07 전 구간 실 LLM)

`tools/run-understanding-eval.ps1` — `CONS_A_002`로 1회. **검증 19/19 통과.**
Guardrail 상세는 `docs/decisions/2026-08-19-guardrail-negation.md`.

- **부정형 예외가 실전에서 작동했다.** R01 재설명이 *"'원금이 보장되지 않는다'고 명시"*로
  나왔고 통과했다(`retried=false`). 단순 `contains`였다면 fallback으로 떨어졌을 문장이다
- **Guardrail이 한 번 걸리고 한 번 통과했다.** R02 1차 `UNSUPPORTED_NUMBER` → 재생성 통과.
  2차 본문은 `50%` 대신 "절반"이라는 **단어**로 바꿨다 — 재생성이 실제로 교정한다.
  **너무 빡빡하지도 느슨하지도 않다는 첫 신호지만 n=2다**
- ⚠️ **F04~F07 세 단계는 prompt caching이 전혀 안 붙는다** — `cacheWrite=0`.
  시스템 프롬프트가 **Sonnet 4.6의 1024토큰 최소치 미만**이다. 오류 없이 조용히 안 걸리므로
  `cacheWrite=0`이 유일한 신호다(`logUsage`를 넣어둔 값을 했다).
  **고치지 말 것** — 캐시 태우려고 프롬프트를 늘리면 호출당 $0.003 아끼자고 매번 입력을 늘린다
- **레이턴시는 Coverage만 문제다.** QUESTION_PHRASE 3s / ANSWER_JUDGE 2~5s /
  RE_EXPLANATION 4~6s(재생성 시 11s). 전부 예산 안이다. classifier 23s만 튄다
- **비용 정정: 세션당 ~$0.11**(콜드, Risk 2건 처리 기준) → **$5로 약 45세션.**
  앞의 "$0.047 / 100세션"은 **F03만** 계산한 값이었다
- `QuestionsResponse`의 필드명은 `generationSource`가 아니라 **`source`**다(계약과 일치).
  평가 스크립트가 이걸 잘못 읽어 빈 값이 찍혔던 적이 있다

### 실측한 것 (2026-08-19, 2회차 — 같은 입력으로 다른 결과)

스냅샷 복구 검증을 붙여 같은 스크립트를 다시 돌렸다. **27/27 통과**(복구 8건 추가).
그런데 **R01이 이번엔 `FALLBACK`으로 떨어졌다 — 1차와 같은 입력, 같은 프롬프트다.**

- **Guardrail 부정형 목록이 좁았다.** 두 시도 모두 *"중간에 팔지 않는 것과 원금 보장은
  별개입니다"*에서 걸렸다. `보장` 뒤 같은 절에 `않`·`아니`·`없`·`못`이 없어서다.
  **맞는 문장인데 걸렸다** — 한국어는 부정 어미 말고 **거리를 두는 표현**으로도 같은 뜻을
  만든다. `별개`·`다르`·`다릅`·`다른`·`달라`·`무관` 추가.
  `다르`만 넣으면 **르 불규칙이라 `다릅니다`를 못 잡는다**
- **1차의 "부정형 예외가 작동했다"는 과대 해석이었다.** 작동한 건 목록에 있던 한 형태
  (`보장되지 않는다`)뿐이다. **한 번 통과를 목록 전체의 검증으로 읽으면 안 된다** —
  같은 입력 2회 실행이 이걸 드러냈다
- **원인 특정이 가능했던 건 `Inspection.matches()` 덕분이다.** 위반 유형만 남겼다면
  "완곡 표현에 걸렸다"까지만 알았다. **안전장치는 자기가 왜 걸렸는지 말할 수 있어야 한다**
- **프롬프트 `reexplain-v1` → `v2`** — R02가 걸린 숫자는 **고객이 답변에서 말한 숫자**였다.
  오해를 반박하려고 인용한 것이라 의도는 맞지만 검수 근거에는 없다. "고객이 말한 숫자를
  되풀이하지 말고 말로 바꿔 써라"를 규칙에 추가.
  **Guardrail 허용 숫자에 `customerAnswer`를 더하지 않았다** — 그쪽이 쉽지만 고객이 틀린
  숫자를 말했을 때 모델이 그대로 단언해도 통과한다.
  fallback은 안전한 실패고, 근거 없는 숫자 단언은 아니다
- ⚠️ **`llm_call_log.session_id`가 지금까지 전 행 NULL이었다.** 포트 5개가 전부 `null`을
  넘겼고 컬럼이 nullable이라 **조용히 실패했다.** TRD §7.2의 "세션 단위 평가 재현"이
  처음부터 불가능했던 것이다. 진단 SQL이 아무 행도 안 뽑아서 발견했다.
  → **포트 시그니처에 `sessionId`를 필수 인자로 넣어 컴파일러가 강제**하게 고쳤다
  (ThreadLocal 안 씀 — 조용히 실패하는 관측 장치는 없느니만 못하다)

### 실측한 것 (2026-08-19, F04~F08 전 구간 실 LLM)

`run-understanding-eval.ps1` — `CONS_A_002` 2회 실행. 1차는 close 에서 중단, 2차 **54/56 통과.**
어긋난 2건 중 **서버 결함은 0건**이다.

**F08 이 실제로 작동한다**

- **감사 이벤트 13건이 예상 지점에 전부 남았다.** `actorRole` 이 의도대로 갈린다 —
  SESSION_CREATED·REVISION_SAVED·QUESTIONS_ISSUED는 `SYSTEM`,
  판정·재설명은 `AI`, 직원 처리·종료는 `STAFF`.
  `Propagation.MANDATORY` 는 전 경로에서 예외 없이 통과했다
- **멱등 경로는 감사 로그를 부풀리지 않는다.** 재설명 재호출·종료 재호출 어느 쪽도
  `audit_event` 를 추가하지 않았다 — `persist()` 를 멱등 분기 **안쪽**에 둔 판단이 맞았다.
  밖에 뒀다면 새로고침 횟수가 "재설명을 몇 번 만들었나"로 기록됐을 것이다
- **`canClose` 가 양방향으로 맞았다.** 이해확인 미완료 상태(1차)에서는
  `INVALID_STATE_TRANSITION`, 완료 후에는 `canClose=true` → 종료 성공
- **경고 확인 요구가 실제로 걸렸다.** `CONS_A_002` 의 `warningRiskIds=[R09]` 를 빠뜨린
  close 가 `WARNING_ACKNOWLEDGEMENT_REQUIRED` 로 거부됐다.
  **WARN_ONLY 가 Gate 는 통과시키되 종료는 막는다는 설계가 처음으로 실증됐다**
- 종료 후 `resumePoint=S08`, `nextAction=null` — 끝난 상담에 다음 행동 버튼이 안 그려진다

**어긋난 2건**

- ⚠️ **`ANS_R03_002`(gold=UNDERSTOOD) → `UNCERTAIN`. 2회 연속, 같은 이유다.**
  판정 근거: *"'조건이 맞아야'의 구체적 내용(3개 기초자산 모두 배리어 이상)을 언급하지 않아
  핵심 조건을 이해했는지 확인할 수 없다."* 무작위 흔들림이 아니라 **일관된 불일치**다.
  → **질문이 매번 LLM 생성인데 라벨은 답변에만 붙어 있다.** 이 실행의 질문은
  *"…어떤 조건이 필요하다고 이해하셨나요?"* 로 조건을 직접 물었고, 그 질문에 대해
  *"조건이 맞아야"* 는 순환적인 답이다. **같은 답변이 질문 문구에 따라 다른 라벨이 될 수 있다** —
  데이터셋 설계의 구조적 문제이지 판정기 오류가 아니다
  → **해소: 판정기가 옳다고 보고 데이터셋을 고쳤다** (2026-08-19). 문제의 문구는
  `ANS_R03_004`(gold=UNCERTAIN, AMBIGUOUS)로 옮기고, `ANS_R03_002` 는 조건을 명시하는
  문장으로 바꿨다. **라벨을 지우지 않고 옮겼다** — 판정기가 두 번 정확히 짚은 경계 사례는
  버릴 게 아니라 UNCERTAIN 정답이 있는 케이스가 없던 자리를 메운다.
  답변 12 → 13건, 라벨 분포 MISUNDERSTOOD 5 / UNDERSTOOD 4 / UNCERTAIN 4
- ⚠️ **`close 멱등` 은 검사가 틀렸다.** 서버는 정상이다
  ```
  expected=2026-08-19T14:40:19.7885642+09:00   ← 첫 응답: 방금 만든 엔티티, 나노초
  actual  =2026-08-19T05:40:19.788564Z         ← 재호출: DB 재조회, UTC·마이크로초
  ```
  **같은 순간이다.** `timestamptz` 왕복에서 표기(+09:00→Z)와 정밀도(나노→마이크로)가 바뀐다.
  → 검사를 **감사 기록 1건 확인**으로 바꿨다. 멱등이 뜻하는 것은 "재호출이 세션을 다시
  닫지 않는다"이고 타임스탬프 문자열 동일성이 아니다
  → **알려진 잔여 사항**: 첫 close 응답의 `closedAt` 은 DB 에 저장된 값보다 정밀하다
  (100ns 자리가 잘린다). 같은 문제가 모든 `OffsetDateTime.now()` 에 있어
  `closedAt` 만 고치면 오히려 불일치다. 화면 영향이 없어 **보류**

**스크립트 결함 4건을 고쳤다** — 전부 서버가 아니라 평가 장치의 문제였다

- **F08 도달을 모델 판정 하나에 걸어뒀다** — R03 이 한 번에 UNDERSTOOD 로 나와야만 세션이
  `AWAITING_STAFF_REVIEW` 가 됐다. 판정이 흔들리는 날엔 **F08 검증이 통째로 사라진다**(1차가 그랬다).
  이제 판정이 무엇이든 R03 을 COMPLETE 로 몬다(재설명→recheck→직원 처리, 경로가 유한하다).
  2차에서 attempt 2 에 풀려 직원 처리까지 가지 않았다
- **중단되면 검증 요약이 안 찍혔다** — 실 LLM 비용을 물고도 "어딘가에서 죽었다"만 남았다.
  `trap` + `Show-Summary` 로 중간까지의 결과를 항상 출력한다
- **"거부됐다"만 보는 검사는 거짓말을 한다** — close 경고 검사가
  `WARNING_ACKNOWLEDGEMENT_REQUIRED` 가 아니라 `INVALID_STATE_TRANSITION` 으로 막혔는데
  초록으로 찍혔다. 이제 **오류 code 까지 대조**한다
- **타임스탬프 문자열 비교** — 위 참조

**비용·레이턴시** (2차, 콜드 캐시)

- LLM 호출 12회 / wall 약 63초. classifier 23s·verifier 10s 가 대부분이고
  QUESTION_PHRASE 3s / ANSWER_JUDGE 2~4s / RE_EXPLANATION 4~5s 는 전부 예산 안이다
- Coverage 만 §14 예산을 넘는다 — 기존 관측과 같다. **Coverage 예산 12초는 S1+S2 합계**이고
  다른 단계는 각자의 행(S3 6초 / S4 6초 / S5 8초)과 비교한다

### 다음 순서
1. ~~F03~~ **완료** (2026-08-18). 파이프라인 + 구현체 4개 + 프롬프트 튜닝 + 실 LLM 검증(`CONS_A_001`·`CONS_A_003`)
   → **모델 결정됨: `claude-sonnet-4-6`** (2026-08-18, TRD D-02 해소). SDK는
   `com.anthropic:anthropic-java`, 설정은 `ai/AiProperties`(`finready.ai.*`)
   → **Sonnet 4.6은 structured outputs를 지원하지 않는다** (지원: Fable 5 / Opus 5 /
   Opus 4.8 / Sonnet 5 / Haiku 4.5). `output_config.format`으로 enum을 API 계층에서
   강제할 수 없으므로 **프롬프트로 JSON을 유도하고 직접 파싱 + 방어적 검증**해야 한다.
   규칙 9의 보장이 API가 아니라 우리 코드에 있다 — `indexExactly`·`validate`가 이미
   `AI_PARSING_FAILED`를 던지므로 구조는 그대로 쓴다
   → `temperature: 0`은 4.6에서 유효하다. **Opus 4.7+ / Sonnet 5로 올리면 400이므로
   그때 지울 것**
   → prompt caching·effort 모두 적용 완료. 비용·레이턴시 실측은 위 "실측한 것" 참조
   → **Verifier 대상은 계약 문구("GATE_REQUIRED + CONTRADICTED")보다 넓다** —
   `EXPLAINED` 후보도 돌린다. 규칙 3 때문에 EXPLAINED는 `semantic = SUPPORTS` 없이
   성립하지 않아서, 안 돌리면 잘 설명한 WARN_ONLY Risk가 INSUFFICIENT로 접혀 경고로 둔갑한다
   (`CONS_A_003`의 R06에서 실측). `CoverageAnalysisServiceTest`가 이 동작을 고정하므로
   **계약 문구만 보고 되돌리면 테스트가 깨진다.** 경위는
   `docs/decisions/2026-08-18-explained-constraint-null-hole.md` "해소됨"
2. ~~F06 재설명 + F07 recheck·직원 처리~~ **완료 + 실 LLM 검증** (2026-08-19).
   Guardrail D-04도 해소. `tools/run-understanding-eval.ps1`로 전 구간 **19/19 통과**
3. ~~**`GET /sessions/{id}` 스냅샷 채우기**~~ **완료 + 실 LLM 검증** (2026-08-19).
   `coverage`·`understanding`·`nextAction`을 실제로 채운다. 전 구간 **27/27 통과**
   (F04~F07 19건 + 새로고침 복구 8건)
   → `coverage/CoverageQueryService`(읽기 전용, LLM 없음)가 `respond(...)`를
   `CoverageAnalysisService`의 멱등 경로와 **공유**한다 — GET과 POST 응답이 갈라질 수 없다
   → `understanding/UnderstandingQueryService` + `RiskUnderstandingState`.
   `pendingQuestion`은 **개수가 아니라 attempt로 매칭**한다
   → `NextActionResolver.resume(...)` 신설 — 저장된 상태만으로 재개 지점을 다시 계산한다.
   규칙 8(서버가 분기 결정)을 새로고침 경로에도 적용한 것
4. ~~Report + Close + Audit (F08)~~ **완료 + 실 LLM 검증** (2026-08-19).
   `run-understanding-eval.ps1` F04~F08 전 구간 **54/56 통과**(어긋난 2건 모두 서버 결함 아님).
   `GET /sessions/{id}/report`, `POST /sessions/{id}/close`, `audit_event` 기록 9개 지점
   → `session/CloseEligibilityEvaluator` — **리포트의 버튼 상태와 종료 요청 검증이 같은 판정을
   쓴다.** 갈라지면 버튼은 활성화됐는데 눌러보면 400 이 나온다
   → `report/ReportService` 는 **아무것도 계산하지 않는다.** Coverage·Understanding·종료 조건을
   각 모듈에서 받아 담기만 한다. 여기서 다시 조립하면 리포트의 Gate 판정이 화면과 달라진다
   → `audit/AuditRecorder` 는 **`Propagation.MANDATORY`** 다. 감사 기록은 자기가 기록하는
   변경과 같은 트랜잭션이어야 한다 — 새 트랜잭션을 열면 상태 전이는 롤백됐는데 "종료했다"는
   기록만 남는 조합이 생긴다. 그래서 요약 문자열은 서비스가 만들어 `AuditEntry` 로 Writer 에
   넘긴다(규칙 6 때문에 서비스에는 트랜잭션이 없다)
   → `AuditEventRepository` 는 **`JpaRepository` 를 상속하지 않는다.** append-only 테이블에
   `delete` 가 자동완성에 뜨지 않게 필요한 두 메서드만 선언했다
   → **감사 기록이 세션을 고정한다.** `audit_event` 가 `consultation_session` 을 참조하고
   append-only 라 세션 행을 지울 수 없다. `RevisionConcurrencyIntegrationTest` 의 정리 로직이
   이것 때문에 깨져서 revision 만 비우도록 고쳤다 — **의도된 동작이다**
   → 감사 요약에 **상담 원문·고객 답변·미해결 사유 본문을 넣지 않는다.** 원본 테이블에 이미
   있고, append-only 라 한 번 들어가면 지울 수 없다
   → `actorRole` 이 리포트의 핵심 필드다. 세션 생성·revision 저장은 **SYSTEM** 이다 —
   인증이 없어 조작자가 누구인지 모르는데 STAFF 로 적으면 없는 신원을 지어내는 것이다
5. ~~오프라인 평가 모듈 + Rule baseline~~ **완료 + 실측** (2026-08-19).
   `RuleBasedClassifier`(`CoverageClassifier` 포트의 규칙 기반 구현, 서로 다른 키워드
   0개=NOT_FOUND/1개=INSUFFICIENT/2개 이상=EXPLAINED, `CONTRADICTED`는 구조적으로 낼 수 없음) +
   `CoverageBaselineComparisonTest`(`@Tag("evaluation")`, `./gradlew evaluate`) +
   결정적인 `RuleBaselineTest`(기본 `test`에 포함, LLM·Docker 불필요)
   → **실측: baseline이 Risk 49/54(90.7%)·Gate 6/6.** LLM을 측정된 3개 시나리오로 좁히면
   25/27로 기록된 LLM 실측치와 **동점** — 집계 정확도만으로는 LLM 도입을 정당화할 수 없다
   → 차이는 한 자리(`CONS_A_003` R01)뿐이다. 직원이 사실과 반대로 말했는데 baseline은
   키워드가 있다는 이유로 EXPLAINED로 통과시킨다 — **없는 설명을 놓치는 것보다 틀린 설명을
   인증하는 게 이 상품에서 더 나쁜 실패**라 이 한 자리가 LLM을 쓰는 근거다.
   그 시나리오의 Gate가 맞은 것도 우연이다(막은 건 R02·R08이지 R01이 아니다)
   → 그래서 리포트를 집계 점수 하나로 접지 않았다: `CONTRADICTED` 전용 절 분리(안 그러면
   1/54에 묻힘), Gate는 막은 Risk 목록과 함께 찍음(우연을 우연으로 보이게), 단언은 점수가
   아니라 성질에 건다(`CONTRADICTED` 불가·결정성·바닥값). **"LLM이 baseline보다 높다"고
   단언하지 않는다** — 동점이 이 평가의 산출물이다
   → **함정**: `evaluate` 태스크가 `LLM_API_KEY`를 워커에 넘기지 않아 IntelliJ Run
   Configuration에만 넣으면 조용히 skip됐다(결과 XML도 `<skipped/>`뿐, 이유 기록 없음).
   `build.gradle.kts`에서 `~/.gradle/gradle.properties`의 `llmApiKey` 또는 환경변수를
   워커로 명시 전달하도록 고쳤고, 키가 아예 없으면 **skip이 아니라 FAILED + 조치 배너**로
   바꿨다 — 사람이 명시적으로 타이핑해서 도는 태스크에 조용한 초록을 돌려주는 건 안전장치가
   아니라 거짓말이라서. 프로젝트 `gradle.properties`는 `.gitignore` 대상(실행법을 문서화하는
   순간 누가 거기 키를 커밋한다)

리포지토리는 `product`·`product_risk`·`customer_profile`·`consultation_session`·
`consultation_revision`·`coverage_result`·`gate_override`·`session_question`·
`understanding_result`·`risk_workflow_state` 10개가 있다.
나머지(`staff_resolution`·`re_explanation`·`audit_event`·`llm_call_log`)는 해당 기능 작업에서 만든다.

> **병행(배포 연동)**: 프론트 배포 URL 확정 — `https://finready-rho.vercel.app` (2026-08-22).
> Render `CORS_ALLOWED_ORIGINS`에 이 도메인 추가 필요(끝 슬래시 없이). 프론트는
> `NEXT_PUBLIC_API_BASE_URL=https://finready-backend.onrender.com/api`를 Vercel 환경변수에
> 넣고 재배포해야 반영된다. 둘 다 Render/Vercel 대시보드에서 직접 설정 — 코드로는 안 됨.
> CORS는 `common/WebConfig`가 이 설정을 실제로 읽는다. 기본값이 `http://localhost:3000`이라
> 위 값을 안 넣으면 배포 프론트에서 막힌다.

> **TRD §18 Step 0~5, 7·8·10·11 완료. Step 6·9는 부분 진행, Step 12·13 미착수**
> (2026-08-26, PDF §18 표를 직접 읽어 전체 Step 목록 확인함 — 더 이상 "PDF 봐야 함"
> 상태 아님).
> - 0~5(인프라~Coverage 레이턴시), 7·8(F04~F08), 10(평가모듈), 11(데모 preset 이관) — 완료
> - 6(dev set 확장) — PROD_A 6/60·답변 13/180, `CONS_A_006` 실 LLM 검증 완료.
>   **목표 절반은 Step 13 선행 필요**(아래 "데이터셋 현황" 참조), 지금은 보류
> - 9(append-only·evidence·§17 계약테스트) — 쿼리 카운트 회귀 테스트·중복 쿼리 제거는
>   완료, §17 계약 테스트(springdoc 주석)·fetch join은 미착수. p95 300ms 예산도
>   실 배포에서 미충족 확인했으나 **사용자 확정으로 보류**(위 §14.1 절 참조)
> - 12(Prompt Freeze + Hold-out), 13(Product B/C) — 미착수

### 데이터셋 현황 (별도 작업, 코드와 병행) — TRD §18 Step 6

⚠️ **목표(60시나리오·180답변)의 절반이 PROD_B/PROD_C 없이는 구조적으로 못 채워진다**
(2026-08-26 확인). `eval/demo_seed.json`의 `datasetPlan`이 이미 이렇게 적어뒀다 —
`consultations.byProduct = {PROD_A:30, PROD_B:15, PROD_C:15}`,
`answers.perRisk=20`(R01~R03 × 3개 상품 = 180). **PROD_B/C는 Step 13이 아직
미착수라 상품·Risk 시드·검수 근거·PDF 자체가 없다** — Step 6을 목표치대로 끝내려면
Step 13(새 금융상품 2개 기획·검수)이 선행돼야 한다. 배포 동결(09-06) 전 완료는
비현실적이라고 판단, **사용자 확정으로 지금은 저비용 항목(`CONS_A_006` 실행)만
먼저 처리하고 PROD_A 확장·PROD_B/C 착수는 보류한다.**

- 상담 시나리오 6 / 목표 60 (PROD_A만, 위 이유로 목표 미달 확정적) — **6건 모두 본문
  작성 완료** (2026-08-18). `DemoSeedGateConsistencyTest`가 라벨↔기대 Gate 정합성을
  자동 검증하므로 시나리오를 추가할 때 라벨만 맞으면 어긋남이 바로 걸린다
- **실 LLM 실행 6/6 완료** (2026-08-26). `006`(장황한 상담)을 배포된 Render 백엔드에
  대고 `tools/run-coverage-eval.ps1 -Scenarios CONS_A_006 -BaseUrl
  https://finready-backend.onrender.com` 로 2회 실행 — **Risk 9/9·Gate 1/1, 라벨
  수정 불필요.** 합계 16.0~16.5s(12초 예산 여전히 미충족, 기존 관측과 같음), wall
  18.3~18.5s(30초 한도 안전). 로컬 DB·LLM 자격증명 없이도 `-BaseUrl`로 배포 서버를
  직접 겨냥하면 이 스크립트를 돌릴 수 있다 — 자격증명은 Render 쪽에 이미 있다.
  이 스크립트는 시드에서 본문을 그대로 읽어 쓴다 — Swagger로 손으로 붙여넣으면
  **본문이 한 글자만 달라져도 provenance가 전부 실패해 측정이 오염된다**
  → **스크립트 결함 발견·수정**: 레이턴시 집계 표 출력부(`{1,>3}` 류 포맷 문자열)가
  .NET 컴포지트 포맷에 없는 `>` 정렬 기호를 써서 **이 구간이 한 번도 성공한 적
  없이 매번 크래시했었다** — Risk/Gate 대조까지는 항상 정상 출력되고 그 아래
  요약 표만 죽어서 지금까지 안 걸렸다. `{1,3}` 형태로 고쳤다(TODO 아님, 이미 수정 완료)
- 고객 답변 **13** / 목표 180. `ANS_R03_004` 추가 (2026-08-19) —
  실 LLM 실행에서 라벨과 2회 연속 어긋난 문구를 **버리지 않고 UNCERTAIN 사례로 옮겼다.**
  판정기가 정확한 근거로 두 번 짚은 경계 사례는 데이터셋에 없던 자리를 메운다
- ⚠️ **답변에만 라벨을 붙이는데 질문은 매번 LLM 이 만든다.** 같은 답변이 질문 문구에 따라
  다른 라벨이 될 수 있다 — `ANS_R03_002` 가 그 사례였다. 답변을 쓸 때
  **"어떤 질문에도 이 라벨인가"** 를 확인할 것
- 라벨을 먼저 정하고 상담문을 생성하는 방식. 사후 라벨링 비용이 0이다

## 미결정

- ~~LLM 모델·요금제~~ — **`claude-sonnet-4-6` 결정** (2026-08-18).
  실측 결과 $5로 약 100세션 — **quota는 빠듯하지 않다**(위 "실측한 것")
- **캐시 TTL을 5분에서 1시간으로 올릴지** — 심사처럼 세션이 띄엄띄엄 오면 5분 TTL은
  매번 쓰기(1.25배)만 물고 읽기 혜택이 없다. 1시간은 쓰기 2배지만 유지된다.
  배포 전 결정 (`docs/decisions/2026-08-18-coverage-prompt-tuning.md`)
- ~~`CONS_A_001`의 R06 라벨~~ — **라벨이 아니라 코드 문제였다** (2026-08-18).
  Verifier를 안 돌린 EXPLAINED가 규칙 3 때문에 접히던 것 → Verifier 대상에 EXPLAINED 추가로 해소
- **`CONS_A_001`의 R02·R03 불안정** — 실행마다 뒤집힌다. 입력이 모호한 케이스의 한계로 보이며
  프롬프트로 더 잡을 수 있을지 불확실하다. **Gate 결과는 3회 모두 정확했으므로 우선순위가 낮다**
- ~~Guardrail 금칙어 최종 목록~~ — **해소** (2026-08-19, TRD D-04). 금칙어 목록 방식.
  `explanation/Guardrail`에 있고 `GuardrailTest`가 고정한다.
  **목록에 단어를 더할 때는 부정형으로 쓰이는 단어인지 먼저 볼 것** — 아니면 맞는 설명이 걸린다
- **Guardrail 임계값 표본이 아직 작다** — 2회 실측(각 Risk 2건)에서 매번 한 건이 걸렸다.
  2회차는 **부정형 목록의 구멍**이었고 고쳤다. 시나리오를 늘릴 때 `retried` 비율과
  `matches()` WARN 로그를 같이 볼 것. **목록에 단어를 더할 땐 실제 생성물에서 근거를
  확보하고 넣는다** — 짐작으로 넣으면 이번엔 위반을 놓치는 쪽으로 틀린다
- **캐시 TTL 결정 시 F04~F07은 고려 대상이 아니다** — 애초에 캐시가 안 걸린다(1024토큰 미만).
  TTL 논의는 Coverage 2단계에만 해당한다
- `customerProfile` 프로덕션 시드 배치 방식 (별도 파일 vs risk schema에 병합)
- **`resumePoint` 매핑을 프론트 화면 정의와 대조할 것.** TRD에 규정이 없다 —
  §6.6은 Understanding 단계의 `nextAction` → 화면만 정한다.
  현재 매핑(DRAFT→S02 / COVERAGE_ANALYZED·GATE_BLOCKED→S03 / UNDERSTANDING_IN_PROGRESS→S04 /
  AWAITING_STAFF_REVIEW→S07 / CLOSED_*→S08)은 `SessionService.resumePointOf`에 있고
  `SessionServiceTest`가 고정해뒀다. 프론트가 API 연결을 시작하기 전에 맞출 것
- `CoverageResult`·`SessionQuestion`에 `@Immutable`을 붙일지.
  TRD §4.2("정정 시 이전 행을 지우지 않는다")와 §4.6("멱등 발급")을 그대로 읽으면
  행 전체가 append-only다. 다만 규칙 1은 `classifier_status`·`ai_status` 두 컬럼만
  명시하므로 지금은 `updatable = false`까지만 걸어뒀다.
  `ConsultationRevision`·`AuditEvent`는 근거가 명확해 이미 `@Immutable`이다

## 알려진 문제

- **`revisionNo` 채번 경쟁 상태** — `docs/decisions/2026-08-14-revision-no-race-condition.md`.
  **경쟁 자체는 여전히 남아 있다**(의도적 보류. P0가 단일 사용자 데모라 실질 위험이 낮다).
  다만 (1)안을 적용해 실패가 409 `CONCURRENT_SESSION_UPDATE`(recoverable)로 나가므로
  프론트가 재시도할 수 있다. 근본 해결(재시도 루프·행 락·DB 채번)은 결정 문서의
  "위험이 현실화되는 조건"에 해당할 때 재검토
- ~~**Verifier 대상 범위 미정**~~ — **해소** (2026-08-18, 실측 뒷받침 2026-08-19).
  EXPLAINED 후보도 Verifier를 돌린다.
  `docs/decisions/2026-08-18-explained-constraint-null-hole.md` "해소됨"
- **Coverage 레이턴시가 TRD §14 예산(12초)을 넘는다** — 예산은 **S1+S2 합계**다.
  (2026-08-25) **팬아웃(Step 5 Phase 3~5) 적용 후 합계 평균 13.5~21.9s로 크게 줄었으나
  여전히 12초는 미충족.** classifier만 보면 43~51% 단축돼 예산 안쪽까지 왔지만, 안 쪼갠
  verifier가 이제 상대적으로 더 큰 비중을 차지한다.
  (2026-08-26) **Step 5 종료 — 12초는 끝내 미충족인 채로 남는다.** Phase 6에서 verifier
  effort HIGH→MEDIUM을 시험해 정확도 리스크는 해소했지만(`CONS_A_003` R01 CONTRADICTS
  3/3 유지), 운영 기본값을 바꿔 레이턴시를 재측정하는 건 별도 결정으로 미루고 사용자
  확정으로 여기서 멈췄다 — DoD가 "확인 또는 조정"이라 12초 달성을 요구하지 않는다.
  더 밟으려면 `docs/decisions/2026-08-20-coverage-latency-fanout.md` "Phase 6 — 사다리"
  R2부터.
- **30초 계약 한도 — 팬아웃 후 5개 시나리오에서 안전권으로 확인** (2026-08-25).
  최댓값 26,294ms(`CONS_A_004`), 이전엔 `CONS_A_002`가 33,244ms(2026-08-19)로 실제 초과했었음.
  다만 **8,000자 상담문(계약 상한)이나 60개 시나리오로 늘었을 때는 미검증**이다.
  `timeout-seconds: 60`은 SDK 타임아웃 모양만 바꿀 뿐 엔드포인트 한도와 무관하므로,
  프론트 fetch 타임아웃과 Render 프록시 한도는 배포 전 별도 확인할 것
- **LLM이 떨어져 있는 두 문장을 이어붙여 인용한다** — `docs/decisions/2026-08-22-evidence-stitching.md`.
  R04에서 312자 떨어진 두 문장을 합쳐 내 `NOT_FOUND`로 무효화됐다(접합부에 `직원: `까지 지어냈다).
  **분류기 판정 자체는 EXPLAINED로 맞았는데 근거를 대는 방식에서 걸린 것**이다.
  2026-08-19에 R09에서 세운 **다요소 fact 가설의 실증**이며, `fact`가 두 요소이고 상담문에서
  떨어져 있으면 연속 한 구간 제약상 빠져나갈 길이 없다. **조치는 (0) 현행 유지** —
  Gate 영향이 없고, Step 5 팬아웃이 `coverage-v3`를 건드릴 예정이라 프롬프트를 두 갈래로
  동시에 고치면 효과가 분리되지 않는다
- **재분석은 텍스트 추가가 아니라 수정이다** — Coverage는 revision 단위 멱등이라 재분석하려면
  새 revision이 필요한데, 같은 보완 문장을 또 이어붙이면 근거가 원문에 2회 매칭돼
  `AMBIGUOUS`로 무효가 된다. 실제로 발생했다(위 문서 (a)). 또한 **보완 문장이 다른 주제의
  배경정보를 흘리면 기존 문장의 완전성 기준이 올라가 판정이 되레 내려갈 수 있다**(같은 문서 (b))

## 처리 대기 (문서 동기화)

- TRD §1 기술스택 표 정정 (Java 21/Boot 3.x → Java 25/Boot 4.0.7) → v1.2.4 — PDF 원본,
  코드로 처리 불가. 다음에 TRD 직접 열 때 반영할 것
- ~~`finready-frontend/contracts/openapi.yml` v1.4.1 → v1.4.2 동기화~~ — 완료 (2026-08-14).
  `docs/openapi.yml`을 그대로 덮어썼다. 두 파일 diff 없음 확인
- PRD §12에 `POST /api/sessions/:id/recheck` 추가 (TRD §22-1) — PDF 원본, 코드로 처리 불가
- PRD §17-3 "Coverage Hold-out" → "Coverage dev set" 정정 (TRD §22-2) — PDF 원본, 코드로 처리 불가