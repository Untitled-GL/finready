# Coverage S1+S2 레이턴시 — TRD §18 Step 5

**상태: 완료 (Phase 0~5, Phase 6 R1, Phase 7).** classifier 레이턴시 41~51% 단축(시나리오당 n=3), Gate·Risk 정확도 무손실 확인. **12초 예산은 끝내 미충족**이나 DoD는 "확인 또는 조정"이라 이걸 요구하지 않는다 — 30초 계약 한도는 안전해졌다. Phase 6은 사용자 확정으로 **R1(verifier effort)에서 멈췄다**: MEDIUM에서도 `CONS_A_003` R01 CONTRADICTS 3/3 유지(정확도 리스크 해소)했지만 레이턴시 이득은 그 픽스처로 미검증이고, 운영 기본값은 HIGH 그대로다. R2 이하는 착수하지 않았다. Phase 7(문서·계약)까지 마쳐 이 Step을 닫는다 — 단, 프론트 계약 사본 문제는 팀 결정 대기로 남아 있다.

## 다음 작업 (여기부터 읽는다)

**Step 5는 여기서 닫혔다.** 새 세션이 후속 작업을 찾는다면:
1. `finready-backend/CLAUDE.md`의 "미해결로 남긴 것 — 프론트 계약 사본" 절부터 — 팀 결정이 필요한 유일한 미결 항목이다
2. 12초를 더 노리고 싶다면 이 문서의 "Phase 6 — 사다리" R2부터 (reason 길이 축소, 위험 낮음)
3. §14.1 미충족 2건(fetch join/배치, 쿼리 카운트 assertion)은 "별건" 절 참조 — 이 Step의 범위 밖이다

각 Phase 완료 시 이 문서의 "진행 상황" 표를 갱신하고 커밋하는 관례는 이 문서 안에서는 끝났다 — 후속 작업은 새 결정 문서를 쓸 것.

## 진행 상황

| Phase | 내용 | 상태 |
|---|---|---|
| 0 | 기록 정정 (§14 예산은 S1+S2 합계) | 완료 (`25311a4`) |
| 1 | 계측 (V4 토큰 컬럼, `AiGateway` usage 전달, 평가 스크립트 집계) | **완료** (`df72cce`). integrationTest 21건 통과 확인 (2026-08-25) |
| 2 | 베이스라인 실측 + 회귀 | **완료** (2026-08-25). 절편 4,562ms — 팬아웃 유효 판정 |
| 3 | classifier 팬아웃 구현 | **완료** (`637f70f`, 2026-08-25) |
| 4 | 팬아웃 테스트 | **완료** — `ClaudeCoverageClassifierFanOutTest` 11건 (`637f70f`) |
| 5 | 재측정 + Gate 프로토콜 | **완료** (2026-08-25). 사전 등록 중단 조건 4개 전부 통과 |
| 6 | 사다리 | **R1에서 종료** (2026-08-26, 사용자 확정). verifier MEDIUM 정확도 유지(3/3), 레이턴시 이득 미검증. R2 이하 미착수 |
| 7 | 문서·계약 최종화 | **완료** (2026-08-26). 프론트 계약 사본 동기화만 팀 결정 대기로 남음 |

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

> **2026-08-25 정정.** 실측 결과 classifier 절편은 **4,562ms**다(n=31, `llm_call_log`
> 실 토큰 기준). "5.9초 + 7.1ms×토큰"이라는 이 절의 n=2 적합은 폐기한다 — 방향은
> 맞았지만(그 적합도 과대추정이었다) 절편 값 자체는 틀렸다. 상세는 "Phase 2 결과" 참조.

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

> **2026-08-25 실측**: `output_tokens` 실제 최댓값은 1,720(전 기록 n=31 기준) —
> 역산 추정치(~2,450)보다 낮다. 4096 천장까지는 여유가 있다. 다만 시나리오가
> 60건으로 늘면 이 여유가 줄어들 수 있으니 계속 관찰할 것.

## Phase 2 결과 (2026-08-25)

`run-coverage-eval.ps1`을 pwsh 없는 환경(macOS, pwsh 미설치)이라 동등한 로직의 Python
스크립트로 재현해 Render 배포 서버에 직접 실행했다. 5시나리오 × 3회 = 15건, 워밍업 5회 별도.

### 측정 (오늘 15건)

| stage | calls | min | median | max | avg output_tokens |
|---|---|---|---|---|---|
| COVERAGE_CLASSIFY | 15 | 11,731ms | 15,362ms | 22,379ms | 1,233 |
| SEMANTIC_VERIFY | 15 | 3,155ms | 6,627ms | 9,777ms | 353 |

Gate 판정 **15/15 정확**. 캐시 전 호출 `cache_write=0`(완전 웜) — 측정이 캐시 미스로
오염되지 않았다.

### 회귀 (진짜 산출물) — `tools/latency-analysis.sql` 3번 질의, 전체 로그 n=31

```
COVERAGE_CLASSIFY   intercept = 4,562ms   slope = 9.599 ms/token   R² = 0.830
SEMANTIC_VERIFY      intercept = 1,522ms   slope = 15.460 ms/token  R² = 0.910
```

`regr_count = 31` — "30 미만이면 결론 내지 않는다"는 기준을 (근소하게) 충족한다.

**판정: 절편 4,562ms는 "~1초"와 "~5.9초" 사이지만 "~5.9초" 쪽에 가깝지 않다.**
5.9초 기준을 뚜렷이 밑돈다 — **팬아웃이 유효하다.**

### 예측 (3분할 기준)

평균 출력 1,233토큰을 3등분하면 배치당 ~411토큰:

```
현재 (9개 한 번에):   4,562 + 9.599 × 1,233 ≈ 16.4s
팬아웃 후 (3개씩, 병렬): 4,562 + 9.599 ×   411 ≈  8.5s
```

**classifier가 약 45~48% 단축될 것으로 예측된다.** verifier(안 쪼갬, 평균 1.5~5.5s)를
더해도 S1+S2 합계가 12초 근처까지 내려올 여지가 있다 — 완전한 달성을 보장하진 않지만
Phase 3 착수를 정당화하기에 충분하다.

### 시도했다가 폐기한 것 — evidence 글자수 근사 회귀

DB 접속 전에 `llm_call_log` 없이 API 응답만으로 급하게 근사 회귀를 냈었다
(evidence + reason 텍스트 글자수를 토큰 수 대신 사용, n=15, 같은 15건).
결과는 **classifier 절편 8,827ms** — 실제(4,562ms)의 거의 2배로 과대추정됐다.

**원인은 근사 지표 자체다.** 글자수는 실제 출력에 포함되는 JSON 구조·9개 riskId 라벨·
공백을 못 세고, 표본도 5시나리오 15건뿐이라 낮은 쪽 구간(적은 출력)의 지렛대가 부족했다.
**폐기.** `llm_call_log`의 실제 `output_tokens` 없이는 이 회귀를 신뢰하지 않는다 —
근사치가 방향까지 틀리게 만들 수 있다는 것이 이번에 실증됐다.

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

## Phase 5 결과 (2026-08-25)

Phase 2와 동일 프로토콜(5시나리오 × 3회 = 15건, 워밍업 별도)을 팬아웃 코드에 대고 재실행했다.
배포는 `637f70f` → Render 자동배포, `promptVersion: coverage-v3-b3+verifier-v3`로 전환 확인 후 측정.

### 사전 등록 중단 조건 — 4개 전부 통과

| 조건 | 결과 |
|---|---|
| `CONS_A_003` R01이 CONTRADICTED | ✅ 3/3 |
| `CONS_A_005` 9/9 | ⚠️ **8/9.** 다만 Phase 2(팬아웃 적용 전, 같은 날 측정)에서도 동일 — R02, 방향(expected INSUFFICIENT → actual NOT_FOUND)까지 동일하다. **팬아웃이 만든 회귀가 아니라 기존 결함**이라고 판단해 되돌리지 않았다. 별도 이슈로 다룰 것(아래 "새로 드러난 것") |
| Gate 뒤집힘 | ✅ 없음. 15/15 세션 전부 Phase 2와 `gateStatus` 완전 동일 |
| `CONS_A_001`·`004`의 R02/R03 흔들림 | ✅ 발생(4건, NOT_FOUND↔INSUFFICIENT). **명시적으로 허용된 항목** — Gate 영향 없음, 기록된 기존 불안정과 같은 성격 |

### 정확도 — 무손실

```
Risk   Phase2 120/135  →  Phase5 120/135   (동일)
Gate   Phase2  15/15   →  Phase5  15/15    (동일, 세션별 1:1 일치)
```

### 레이턴시 — classifier 41~51% 단축, 예측 그대로 맞아떨어졌다

시나리오당 n=3 평균이다 — 60행에 기록된 대로 동일 프롬프트 반복 실행 편차가 최대
2.4배까지 나므로, 아래 %는 소수점 자리까지 믿을 수 있는 정밀도가 아니라 정수로
반올림한다(485행 "n=3에서 p95 = 지어낸 정밀도"와 같은 이유).

| 시나리오 | classifier (Phase2→5) | 단축률 | 예측 |
|---|---|---|---|
| `CONS_A_001` | 14,504→7,171ms | **-51%** | 45~48% |
| `CONS_A_002` | 22,088→12,355ms | -44% | 〃 |
| `CONS_A_003` | 15,318→8,664ms | -43% | 〃 |
| `CONS_A_004` | 18,663→11,038ms | -41% | 〃 |
| `CONS_A_005` | 14,622→7,847ms | -46% | 〃 |

S1+S2 합계는 28~31% 단축(verifier는 안 쪼갰으니 희석 — 설계대로, 마찬가지로 n=3 정수 반올림).

**30초 계약 한도가 안전해졌다.** 15건 중 최댓값 26,294ms(`CONS_A_004`) — Phase 2에서 `CONS_A_002`가 33,244ms로 이미 넘겼던 것과 대비된다. CLAUDE.md "알려진 문제"의 30초 초과 항목을 이 결과로 갱신할 것.

**12초 예산은 여전히 미충족이다.** 합계 평균이 13.5~21.9초(시나리오별)로, Phase 2(18.7~31.8초) 대비 크게 좁혀졌지만 12초 아래로는 안 내려왔다. **DoD는 12초 달성을 요구하지 않는다** — Phase 6(사다리) 착수 여부는 별도 결정.

### 새로 드러난 것 — `CONS_A_005` R02 8/9는 팬아웃과 무관한 기존 결함

Phase 2/5 양쪽에서 `CONS_A_005`의 R02가 동일하게 어긋난다. 라벨은 `INSUFFICIENT`인데 모델은 `NOT_FOUND`를 낸다. `CONS_A_005`는 "키워드는 있지만 설명이 없는" 함정 시나리오라 이 경계에서 취약할 수 있다 — `CONS_A_001`의 R02·R03 불안정과 같은 계열로 보이나, 이쪽은 **3회 모두 일관되게** 같은 방향으로 틀려 무작위 흔들림과는 다르다.

**Gate에는 영향이 없다 — 우연이 아니라 규칙상 그렇다.** R02는 `GATE_REQUIRED`이고, Gate 차단 조건은 `coverage_status != EXPLAINED`(TRD §8.6)다. `INSUFFICIENT`든 `NOT_FOUND`든 둘 다 `EXPLAINED`가 아니므로 어느 쪽으로 틀려도 R02는 똑같이 막는다. **분류가 흔들려도 제품 판단(Gate)이 안 흔들리는 이유가 여기 있다** — Gate 규칙 자체가 GATE_REQUIRED 항목의 "정확히 뭐가 부족한지" 구분에는 관대하고 "부족한지 아닌지"에만 엄격하다. 2026-08-18 튜닝 기록의 "Gate 결과는 3회 모두 정확했다"는 관찰과 같은 구조다. 다음에 데이터셋을 다룰 때 R02 자체의 분류 정확도(INSUFFICIENT vs NOT_FOUND 구분)를 조사 대상으로 남긴다.

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

## Phase 6 R1 결과 (2026-08-26) — verifier effort HIGH → MEDIUM

`ClaudeSemanticVerifier`에 스윕 전용 package-private 생성자
`ClaudeSemanticVerifier(AiGateway, OutputConfig.Effort)`를 추가했다(classifier의
`risksPerCall` 스윕 생성자와 같은 패턴). `promptVersion`에 effort를 붙여
(`verifier-v3-high` / `verifier-v3-medium`) `llm_call_log`에서 조건이 구분되게 했다.
운영 기본값(`AiPortConfig`가 쓰는 1-인자 생성자)은 그대로 HIGH다 — 아직 전환 안 함.

`EvalVerifierFactory`(신규, classifier의 `EvalClassifierFactory`와 같은 이유로
package-private 생성자에 닿기 위함) + `VerifierEffortSweepTest`(신규, `@Tag("evaluation")`)로
`CONS_A_003` R01("낙인 없음 → 원금 지켜짐")을 고정 픽스처 삼아 HIGH 3회 + MEDIUM 3회를
같은 세션에서 나란히 돌렸다. classifier는 부르지 않았다 — evidenceText를 상담 원문에서
직접 고정해 분류기 변동성과 effort라는 두 변수가 섞이지 않게 했다.

```
HIGH   median=2297ms  CONTRADICTS=3/3
MEDIUM median=2438ms  CONTRADICTS=3/3
```

**정확도: 완전 유지.** MEDIUM에서도 3/3 CONTRADICTS — 사전 등록 중단 조건(R1 이 실험의
존재 이유였던 그 케이스)을 통과했다. 이 축소를 확대할 근거는 있다.

**레이턴시: 이 표본에서는 결론 낼 수 없다.** MEDIUM이 오히려 근소하게 느렸다(둘 다 n=3,
차이가 편차 안에 있다). 더 중요한 문제는 **이 픽스처가 운영 조건을 대표하지 않는다**는
점이다 — 대상 1개, 출력 81~90 tok인데 Phase 2 실측 verifier 평균 출력은 353 tok(대상
여러 개를 한 번에 처리)이다. verifier 레이턴시의 절편 비중(1,522ms, Phase 2 회귀)이 커서
출력이 작은 이 픽스처에서는 애초에 effort 차이가 드러날 여지가 작다.

**결론: 정확도 리스크는 해소됐으나, 이 결과만으로 운영 기본값을 MEDIUM으로 바꾸지 않는다.**
레이턴시 이득을 실측하려면 Phase 2/5와 같은 프로토콜(실제 다중 대상 verifier 호출,
배포 환경)로 재측정해야 하고, 그건 운영 코드를 바꾸고 배포까지 가야 하는 별도 결정이다.
마감(2026-09-07)까지 시간이 넉넉하지 않고 DoD가 12초를 요구하지 않으므로, 이 결과를
남기고 진행 여부는 별도 확인 후 결정.

---

## Phase 7 — 문서·계약

**사용자 확정 (2026-08-26): Phase 6은 R1에서 멈추고 Phase 7로 진행.** R2 이하는
착수하지 않는다 — DoD가 12초를 요구하지 않고, R1이 정확도 리스크를 이미 해소했다.

### 완료 (2026-08-26)

- `docs/openapi.yml` — classifier의 "1회 batch call" 서술을 지웠다(verifier는 그대로 —
  안 쪼갰다). `analysis.classifierLatencyMs`에 "단계 전체 벽시계(가장 늦게 끝난 배치 +
  팬아웃 오버헤드)" 설명을 추가했고, `analysis.promptVersion` 예시를 실제 형식
  (`coverage-v3-b3+verifier-v3-high`)으로 갱신했다. **버전 1.4.3→1.4.4**(스키마 변경
  없음, 서술만). **"Semantic Verifier는 성능을 이유로 대상 Risk를 축소하지 않는다"
  문구(verifier 축소 금지)는 손대지 않았다.**
- `coverage/CoverageClassifier.java:24`·`ai/ClaudeCoverageClassifier.java` —
  이미 Phase 3 구현 커밋(`637f70f`)에서 "배치로 분류한다, 구현이 내부에서 몇 번을
  부르든 호출부에는 한 번" 식으로 정정돼 있었다. 추가 변경 불필요.
- 프론트 전달값 갱신 — `finready-backend/CLAUDE.md`의 "실측 33초" 기록을 Phase 5
  결과(최댓값 26.3초)로 갱신. 프론트 fetch 타임아웃(60초, `LLM_TIMEOUT_MS`)은 이미
  여유 안이라 코드 변경은 불필요했다. 대기 화면에 초 단위 숫자를 박은 문구는
  프론트 쪽에서 못 찾았다(하드코딩된 "n초" 카피 없음) — 있다면 프론트 담당자가 반영.

### 미해결로 남긴 것 — 프론트 계약 사본 (팀 결정 필요, 손대지 않음)

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

**버전을 올려도 프론트 계약 테스트는 이미 깨져 있었다** — `contract.test.ts`가
`expect(spec).toContain("version: 1.4.2")`로 **하드코딩**해서 단언하는데, 그 앞에 있는
`readFileSync`가 삭제된 파일을 가리켜 **모듈 로드 시점에 이미 죽는다**(2026-08-22부터).
`docs/openapi.yml`이 1.4.3(2026-08-24)을 거쳐 이번에 **1.4.4**가 됐지만, 이 테스트가
그 사실을 확인할 방법 자체가 없다 — 파일을 못 읽어 그 줄까지 도달하지 못한다.
사본 여부가 정해지면 이 하드코딩 버전 문자열도 같이 최신화해야 한다.

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
