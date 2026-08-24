# Coverage S1+S2 레이턴시 — TRD §18 Step 5

**상태: 진행 중.** Phase 0·1 완료, Phase 2(베이스라인 실측)부터 이어간다.

## 다음 작업 (여기부터 읽는다)

1. **Docker Desktop 켜고 `./gradlew integrationTest`** — 직전 세션에서 Docker가 꺼져 있어
   V4 마이그레이션(`llm_call_log` 토큰 컬럼)이 실제 Postgres로 아직 검증되지 않았다.
   `SchemaConstraintIntegrationTest`가 `ddl-auto: validate`로 엔티티↔스키마 일치를 잡아준다
2. **서버 기동** (`LLM_API_KEY` 필요) 후 베이스라인 실측:
   ```
   ./tools/run-coverage-eval.ps1 -Scenarios CONS_A_001,CONS_A_002,CONS_A_003,CONS_A_004,CONS_A_005 `
       -Repeat 3 -WarmUp -OutFile baseline.json
   ./gradlew evaluate > baseline-eval.txt
   ```
   15회 ≈ $0.45. `CONS_A_006`은 `evaluate`가 덮는다(실 LLM 이력이 없던 시나리오)
3. **`tools/latency-analysis.sql`의 3번 질의(회귀)를 돌린다.** `intercept_ms`가 이후 전부를
   정한다 — 작으면(~1000ms) 병렬화만으로 12초가 사정권, 크면(~6000ms) 모델 교체까지 필요.
   **이 숫자 없이 Phase 3(팬아웃 코드)을 시작하지 않는다.**
4. 그다음 아래 "Phase 3~7 설계"를 그대로 구현한다

각 Phase 완료 시 이 문서의 "진행 상황" 표를 갱신하고 커밋할 것.

## 진행 상황

| Phase | 내용 | 상태 |
|---|---|---|
| 0 | 기록 정정 (§14 예산은 S1+S2 합계) | 완료 (`25311a4`) |
| 1 | 계측 (V4 토큰 컬럼, `AiGateway` usage 전달, 평가 스크립트 집계) | 완료 (`df72cce`), **integrationTest 미검증** |
| 2 | 베이스라인 실측 + 회귀 | 다음 작업 |
| 3 | classifier 팬아웃 구현 | 대기 |
| 4 | 팬아웃 테스트 | 대기 |
| 5 | 재측정 + Gate 프로토콜 | 대기 |
| 6 | 사다리 (필요시) | 대기 |
| 7 | 문서·계약 최종화 | 대기 |

---

## Context

TRD §18 Step 5의 DoD는 **"S1+S2 레이턴시 실측 — 12초 이내 확인 *또는* 프롬프트/모델 조정
(검증 축소 금지)"**. F03~F08을 먼저 하면서 건너뛰어졌고 지금 미충족이다.

### 예산 범위가 지금까지 잘못 기록돼 있었다 (Phase 0에서 정정)

TRD §14 원문:

| 구간 | 예산 | 초과 시 대응 |
|---|---|---|
| **Coverage (S1+S2)** | **12초** | 프롬프트 축약 / 모델 교체 / **부분 병렬화**. 검증 범위 축소 금지(ADR004) |

**12초는 classifier 단독이 아니라 S1+S2 합계다.** CLAUDE.md는 이를 per-stage로 읽어
"classifier 10.8s → 예산 충족"이라 기록해왔다. 합계 기준으로는 **측정된 6회 전부 미달이며,
한 번도 충족한 적이 없다.** 돌아갈 베이스라인이 없다. (정정 상세는
`2026-08-18-coverage-prompt-tuning.md`의 "정정 (2026-08-20)" 절.)

TRD가 **"부분 병렬화"를 대응 수단으로 직접 명시**한 점이 중요하다 — 이전 세션이
정확도 위험을 이유로 보류한 그 수단이다.

### 현재 실측치

| 시나리오 | classifier | verifier | 합계 | wall |
|---|---|---|---|---|
| `CONS_A_001` 웜 | 10,808 | 4,699 | 15,507 | 15.5s |
| `CONS_A_005` | 15,120 | 7,613 | 22,733 | 23.0s |
| `CONS_A_004` | 17,476 | 8,584 | 26,060 | 26.4s |
| `CONS_A_002` | 23,268 | 9,609 | 32,877 | **33.2s** |

- 비-AI 오버헤드 약 367ms — 시간의 사실상 100%가 LLM이다
- 동일 프롬프트 실행 편차 최대 2.4배(verifier 5,024 ↔ 12,048)
- `002`·`004`·`005`는 각각 **n=1**이고 캐시 상태 기록도 없다
- **`CONS_A_002`의 33.2초는 계약 한도 30초를 이미 넘겼다** — CLAUDE.md에 등록됨(미해결)

### 두 단계의 성질이 정반대다

|  | classifier (S1) | verifier (S2) |
|---|---|---|
| 출력량 | Risk 9개 × evidence 인용 → 역산 ~2,450 tok | relation + reason(60자) → ~300 tok |
| 성질 | **출력 지배적** | **고정 오버헤드 지배적** (effort=HIGH) |
| 항목 간 의존 | **있다** — "한 문장을 여러 항목의 근거로 재사용하지 않는다" | **없다** — "인용 구간과 사실의 의미 관계만" 보는 per-item 함수 |
| 분할 이득 | 크다 | 거의 없다 |
| 분할 위험 | 경계 규칙 저하 (완화 설계 있음, 아래) | 구조적으로 없다 |

### 이 계획이 전제하지 않는 것

토큰이 기록된 두 점으로 회귀하면 "5.9초 + 7.1ms×토큰"이 나오고, 그렇다면 직렬 2호출의
바닥이 11.8초라 12초는 산술적으로 불가능하다. **그러나 이것은 자유도 0인 n=2 적합이고,
5.9초 절편은 물리적으로도 의심스럽다** — 2,969토큰 캐시 prefix의 TTFT가 5.9초일 리 없다.
같은 이유로 기존 표의 "웜 10.8s vs 콜드 15~18s" 5초 격차도 캐시로 설명되지 않는다
(**캐시는 비용 레버이지 레이턴시 레버가 아니다**). 콜드/웜 축은 출력 토큰 수와 교란돼 있다.

**그래서 절편의 실재를 먼저 측정으로 확정한다**(Phase 2). 절편이 ~1초면 병렬화만으로
12초가 사정권이고, ~5.9초면 모델 교체까지 가야 한다. 이 숫자 없이 사다리를 오르지 않는다.

### 합격 기준 (사용자 확정, 2026-08-20)

- **Gate 판정 유지가 절대조건.** 개별 Risk 정확도는 소폭 흔들려도 허용
  (경계 케이스는 원래 실행마다 뒤집힌다 — `CONS_A_001`의 R02·R03)
- 검증 범위 축소 금지(ADR004) — Verifier 대상 축소·Provenance 생략 금지
- 측정 규모: 5시나리오 × 3회 전·후 (약 30회 ≈ $0.90)
- `llm_call_log`에 토큰 컬럼 추가 (Phase 1에서 완료)

**DoD는 12초 달성을 요구하지 않는다.** "확인 또는 조정"이 접속사다. 사다리를 어디까지
밟았고 왜 멈췄는지가 남으면 단계는 닫힌다. 반대로 검증을 줄여 11초를 만들면 실패다.

---

## Phase 0 — 기록 정정 (완료, `25311a4`)

`finready-backend/CLAUDE.md`에 §14 표 원문 이식 + 오독 5곳 정정. `README.md` 동일 오독 정정.
30초 계약 한도 초과를 `알려진 문제`에 신규 등록. `2026-08-18-coverage-prompt-tuning.md`에
날짜 붙여 정정 추가(덮어쓰지 않음 — 저장소 관례).

## Phase 1 — 계측 (완료, `df72cce`, integrationTest 미검증)

- `V4__llm_call_log_token_columns.sql` — `input_tokens`/`output_tokens`/`cache_read_tokens`/
  `cache_write_tokens`/`effort`, 전부 nullable
- `LlmCallLog.java` — 중첩 레코드 `TokenUsage(input, output, cacheRead, cacheWrite)`로
  위치 인자 폭증을 피함
- `AiGateway.java` — `send()`가 `private record Sent(String text, TokenUsage usage)`를
  돌려주도록 바꿔 `call()`의 `finally`까지 usage를 들고 나옴
- `tools/run-coverage-eval.ps1` — S1+S2 합계 명시 출력, 시나리오별 min/median/max +
  12초·30초 초과 횟수 집계(p95는 안 씀 — n≤5에서 지어낸 정밀도), `-WarmUp` 스위치,
  실행 후 `llm_call_log` 대조 SQL 안내. BOM 유지 확인됨
- `tools/latency-analysis.sql` (신규) — 단계별 통계, 세션별 합계, **토큰 회귀**(핵심),
  캐시 적중 확인, 팬아웃 동시성 확인(created_at 겹침)
- `application-test.yaml`의 `timeout-seconds`를 운영과 같은 60으로 정렬

**검증: `./gradlew test` 330건 통과. `integrationTest`는 Docker Desktop이 꺼져 있어
21건 전부 컨테이너 기동 단계에서 실패 — 코드 문제 아님, 재실행 필요.**

---

## Phase 2 — 베이스라인 측정 (파이프라인 미변경, 계측만 얹은 상태)

```
./tools/run-coverage-eval.ps1 -Scenarios CONS_A_001,CONS_A_002,CONS_A_003,CONS_A_004,CONS_A_005 `
    -Repeat 3 -WarmUp -OutFile baseline.json
./gradlew evaluate > baseline-eval.txt
```

**판정 통계는 max(classifier + verifier) 대 12,000ms, max(wallMs) 대 30,000ms.**
예산은 평균이 아니라 천장이다.

### 이 단계의 진짜 산출물: 회귀

`tools/latency-analysis.sql` 3번 질의로 latency_ms ~ output_tokens 회귀.
**절편이 ~1초인지 ~5.9초인지가 이후 전부를 정한다.** regr_count가 30 미만이면
결론 내지 않는다. 이 숫자가 나오기 전에 Phase 5 스윕을 돌리지 않는다.

### 부수적으로 확인되는 것

maxTokens: 4096인데 CONS_A_002 역산이 ~2,450 tok — 천장의 60%. 8,000자 상담문이면
절단 → 파싱 실패 → 재시도(+23초) 위험. **팬아웃은 레이턴시와 무관하게 이것만으로도
정당화된다** (배치마다 4096을 따로 갖는다).

---

## Phase 3 — classifier 팬아웃 (`ai/ClaudeCoverageClassifier.java` 내부)

### 어디를 쪼개는가: 포트 뒤

| | 어댑터 내부 (채택) | `CoverageAnalysisService` (기각) |
|---|---|---|
| 기존 테스트 | 안 깨진다 | `notApplicableRisksAreExcluded:404` 암묵 times(1) 파손 + 스텁 replay로 ~10건 조용한 오염 |
| 정확도 하네스 | `CoverageBaselineComparisonTest`가 포트를 직접 부르므로 **수정 없이 새 동작 측정** | **하네스가 옛 경로를 계속 재고 운영만 바뀐다 — Gate 유지를 증명할 바로 그 계기에 조용한 공백** |

포트 시그니처는 **바꾸지 않는다** — 바꾸면 `evaluation/RuleBasedClassifier`가 컴파일
실패하고 `RuleBaselineTest`·`CoverageScorer`·`CoverageBaselineComparisonTest`가 연쇄 파손.

### 분할: 결정적·연속·정렬 순서

[R01,R02,R03] [R04,R05,R06] [R07,R08,R09]. **정책·난이도·상담문 기반 분할 금지** —
분할 자체가 통제해야 할 변수가 된다.

### 배치 크기는 상수. `AiProperties` 필드로 빼지 않는다

```java
private static final int RISKS_PER_CALL = 3;
private static final String PROMPT_VERSION = "coverage-v3";   // promptVersion() -> "coverage-v3-b3"
```
프로퍼티로 빼면 호출부 6곳(`EvalClassifierFactory`, `AiPropertiesTest`)이 깨지고,
TRD §7.2가 prompt_version을 재현 조건으로 규정하는데 런타임 가변 배치는
llm_call_log로 재현 불가능한 실험을 운영에서 돌릴 수 있게 한다.

스윕용으로는 **package-private 생성자** `ClaudeCoverageClassifier(AiGateway, int risksPerCall)` +
`EvalClassifierFactory.claude(apiKey, risksPerCall)` 오버로드. 테스트 전용, 운영 호출부 0.

### 캐시 prefix와 경계 규칙을 동시에 지키는 배치

**system (모든 서브배치에서 바이트 동일):** INSTRUCTIONS + **9개 Risk 카탈로그 전체**
(정렬 순서 그대로) + 상수 문단 하나:

> ## 이번 호출의 판정 대상
> 사용자 메시지에 "판정 대상" 목록이 주어진다. **그 목록에 있는 항목만** 결과로 낸다.
> 목록에 없는 항목도 위 카탈로그에 그대로 있다 — **항목 사이의 경계를 가르기 위한 것**이며
> 결과에 포함하지 않는다.

**user (배치마다 다름):** "## 판정 대상\n\nR01, R02, R03\n\n## 상담 내용\n\n<transcript>"

모델은 여전히 9개 정의를 전부 본다 — "이 문장은 다른 항목에 더 가깝다 → NOT_FOUND"에
필요한 건 다른 항목의 정의이지 판정 결과가 아니다.

**비용은 콜드에서 오른다.** 서브배치가 동시에 나가므로 콜드면 셋 다 miss → 쓰기 3회
(2,969 × 3 × 1.25 ≈ $0.033 vs 현재 $0.011). 웜이면 읽기 3회로 더 싸다.

### 검증: 배치별은 파서 안에서, union은 그대로

```java
return gateway.call(call, text -> parseAndValidate(text, batchRiskIds));
```
parseAndValidate는 개수 불일치·중복·배치 밖 riskId·누락에 ResponseParseException을
던진다 — 그러면 AiGateway의 재시도 1회가 실패한 서브배치에만 적용된다.

`CoverageAnalysisService.indexExactly`는 **손대지 않는다.** union에 대해 이미 검증하므로
배치 간 중복·누락을 잡아준다.

### 실패 처리

- 한 배치라도 실패하면 전체 실패(`parsingFailureWritesNothing` 계약)
- **CompletionException을 반드시 벗겨** 원래 ApiException을 던진다
- 형제 취소를 시도하지 않는다(OkHttp execute()는 중단 안 됨). 첫 번째 실패를 보존한다

### 동시성

`Executors.newVirtualThreadPerTaskExecutor()`를 classify() 호출마다
try-with-resources로 연다. StructuredTaskScope는 쓰지 않는다(JDK 25 preview).

놓치기 쉬운 두 가지:
- **MDC** — RequestIdFilter가 넣은 requestId를 워커 스레드가 상속하지 않는다.
  getCopyOfContextMap() → setContextMap → finally에서 clear()
- **Hikari 풀 5개** — AiGateway.call()의 finally가 LlmCallRecorder.record()
  (REQUIRES_NEW)를 부르므로 한 요청이 순간 커넥션 3개를 쓴다. 고갈되면 record()가
  WARN으로 삼킨다. **배치 3을 넘기지 않는다**, N+1행을 프로토콜 점검 항목으로 둔다
- OkHttp Dispatcher.maxRequestsPerHost는 비동기에만 적용돼 문제없어야 하지만,
  N=9를 고려하기 전에 llm_call_log 타임스탬프가 실제로 겹치는지 확인한다

### verifier는 이번에 쪼개지 않는다

출력 ~300토큰이라 거의 전부가 고정 오버헤드이고, buildUserMessage가 매 호출 전체
상담문을 다시 보내 캐시 밖이라 N분할이면 입력 토큰이 N배가 된다. verifier의 레버는
분할이 아니라 effort다(Phase 6).

---

## Phase 4 — 테스트

**`src/test/java/io/finready/ai/ClaudeCoverageClassifierFanOutTest.java`** (신규, LLM 없음).
AiGateway 목이 넘어온 파서를 실제로 호출하게 해서 파싱·검증이 실제로 돌게 한다.

| # | 테스트 | 없으면 |
|---|---|---|
| 1 | 9개가 3x3으로 갈린다 | 분할 표류 |
| 2 | **모든 서브배치 system 프롬프트가 바이트 동일, 9개 riskId 전부 포함** | **가장 중요.** 캐시 파괴는 오류·로그·테스트 실패 없이 청구서로만 나타난다 |
| 3 | 배치 멤버십은 user 메시지에만 | 누가 목록을 system으로 "정리"해 캐시를 죽인다 |
| 4 | 분할이 결정적 | 재현성 |
| 5 | 병합 결과가 riskId 오름차순 | 전후 표 대조 가능성 |
| 6 | 배치가 요청 밖 riskId를 뱉으면 파싱 실패 | 배치 간 유출이 union 우연으로 가려짐 |
| 7 | 배치에 riskId 누락이면 파싱 실패 | 규칙 9 |
| 8 | **서브배치 실패가 CompletionException 아닌 ApiException으로** | 503 계약 파손 |
| 9 | risk 수 <= 배치 크기면 1회 호출 | 서비스 단위 테스트 픽스처(3개) |
| 10 | 빈 목록이면 호출 0회 | `CoverageWiringIntegrationTest:74` |

`CoverageAnalysisServiceTest`는 건드리지 않는다. `CoverageBaselineComparisonTest`에는
단언을 넣지 않고 **회귀 감시** 출력 절만 추가한다.

---

## Phase 5 — 재측정과 Gate 프로토콜

Phase 2와 **동일 프로토콜** 15회. 대조군(N=1)은 **같은 세션에서 다시 돌린다**
(모델 쪽 용량 변동을 분리하려면).

### 사전 등록 중단 조건 (결과를 보기 전에 적는다)

| 조건 | 조치 |
|---|---|
| `CONS_A_003` R01이 어느 실행에서든 CONTRADICTED가 아님 | **되돌린다** — Rule baseline 대비 LLM 우위의 유일한 근거 |
| `CONS_A_005`가 9/9 아래로 | **되돌린다** — 키워드 매칭 실패를 보이는 유일한 실증 |
| 어느 시나리오든 Gate가 뒤집힘 | **되돌린다** |
| gateStatus는 같은데 blockingRiskIds가 바뀜 | 진행 전 조사 |
| `CONS_A_001`·`004`의 R02·R03 흔들림 | **허용** — 기록된 기존 불안정 |

합격은 전 실행 일치다(3회 중 2회 맞는 Gate는 집계가 가리는 회귀).

---

## Phase 6 — 사다리 (Phase 5가 12초 미달일 때, TRD가 허용한 순서대로)

1. **verifier HIGH → MEDIUM.** 한 번도 시험되지 않음. R1 착지 즉시 S2가 구속조건이 됨.
   CONS_A_003으로 먼저(n=3, $0.09)
2. reason 60자→30자, NOT_FOUND 생략 (무조건 함, 위험 낮음)
3. evidence 인용 300자→150자 (위험 중간, AMBIGUOUS 분포 확인)
4. classifier MEDIUM→LOW 재시험 (R1 이후 전제가 달라짐 — 9개가 아니라 3개를 봄)
5. **모델 교체** — claude-sonnet-5 먼저(코드 변경 0, yaml 한 줄, 단 토크나이저가 달라
   토큰 재기준화 필요). Opus 5 + fast mode는 "돈으로 사는" 최후 수단

**S1/S2 파이프라이닝은 기각** — classifierLatencyMs/verifierLatencyMs가 겹치는
구간이 되어 합이 wallMs를 넘어 통계가 무의미해진다.

---

## Phase 7 — 문서·계약

- `docs/openapi.yml` — "1회 batch call" 서술 수정, **버전 1.4.2→1.4.3**.
  L227-228(verifier 축소 금지 문구)는 손대지 않는다
- 이 문서를 최종 결과로 갱신 (상태를 "진행 중"→"완료"로)
- 프론트 전달값: "실측 33초"를 Phase 5 결과로 갱신

### ⚠️ 계약 사본 동기화 단계가 지금은 성립하지 않는다 (2026-08-24 확인)

원래 여기 "`finready-frontend/contracts/openapi.yml` 동기화까지가 한 작업"이라 적어뒀는데,
**그 파일이 2026-08-22에 삭제됐다** (`fdbbcf5` "fix: openapi.yml 파일 삭제", 프론트 담당자).
`finready-frontend/contracts/` 디렉터리 자체가 없고 대체 파일도 없다.

그 커밋은 **yml 하나만 지웠고 그 파일에 의존하는 두 곳을 같이 고치지 않았다**:
- `finready-frontend/package.json:10` — `gen:api` 스크립트가 `contracts/openapi.yml`을
  가리킨다. 타입 재생성이 불가능하다 (생성물 `generated/openapi.ts`는 커밋돼 있어 앱은 돈다)
- `finready-frontend/src/shared/api/contract.test.ts:14` — 최상위에서 `readFileSync` 한다.
  테스트 함수 안이 아니라 모듈 로드 시점이라 **파일이 없으면 스위트 전체가 에러**다

**팀 결정이 필요하다.** 되돌릴지(`git checkout 3c95a38 -- finready-frontend/contracts/openapi.yml`),
아니면 사본을 두지 않는 방식으로 갈지(그렇다면 `package.json`·`contract.test.ts`가
`docs/openapi.yml`을 직접 보도록 고쳐야 한다). 정해지기 전에는 이 항목을 건너뛴다.

**그리고 버전을 올리면 프론트 계약 테스트가 깨진다** — 삭제와 무관하게. `contract.test.ts`가
`expect(spec).toContain("version: 1.4.2")`로 버전을 **하드코딩**해서 단언한다. Phase 7에서
1.4.3으로 올리는 순간 그 단언이 실패하므로, 프론트와 함께 올려야 한다.

---

## 하지 말 것

| | 이유 |
|---|---|
| 서비스 계층에서 분할 | 테스트 파손 + 하네스가 안 쓰이는 코드를 잰다 |
| Risk 카탈로그를 배치별 system으로 쪼개기 | 캐시 prefix N개(조용히 죽음) + 경계 규칙 근거 상실 |
| S1·S2를 한 호출로 합치기 | 이름만 다른 검증 축소 |
| verifier 대상 축소 | TRD §14·openapi 명시 금지 |
| 배치 크기를 AiProperties로 | 재현 불가능한 실험을 운영에서 돌리게 됨 |
| StructuredTaskScope | JDK 25 preview |
| n=3에서 p95 | 지어낸 정밀도 |
| run-coverage-eval.ps1을 BOM 없이 저장 | 구문이 조용히 사라진다 (전례 있음) |

## 별건 (범위 밖, 기록만)

§14.1 미충족 2건 — GET /sessions/{id}·GET /report의 fetch join/배치 없음,
통합 테스트 쿼리 카운트 assertion 없음. Step 5 이후 별도 항목. 상세는
`finready-backend/CLAUDE.md`의 "성능 예산 (TRD §14)" 절.
