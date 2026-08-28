# Known Issue — revisionNo 채번 경쟁 상태

| | |
|---|---|
| **발견** | 2026-08-14, 테스트 전략 논의 중 |
| **상태** | **수정 보류 (의도적)** — 단 실패 모양은 2026-08-18에 (1)안 적용, 아래 각주 참조 |
| **영향 범위** | `POST /api/sessions/{sessionId}/revisions` |
| **재검토 시점** | P0 데모 범위를 벗어날 때 / 동시 사용자가 생길 때 |

> **(2026-08-18) (1)안 적용.** 경쟁 자체는 그대로 두고 실패의 모양만 고쳤다 —
> `uq_revision` 위반이 500 `INTERNAL_ERROR`(재시도 불가)로 나가던 것을 409
> `CONCURRENT_SESSION_UPDATE`(recoverable)로 바꿨다. `RevisionConcurrencyIntegrationTest`가
> 실 Postgres로 재현한다. 상세는 `finready-backend/CLAUDE.md`.

---

## 무엇이 문제인가

`SessionService.createRevision`이 revision 번호를 **읽고 나서 +1** 하는 방식으로 채번한다.

```java
Optional<ConsultationRevision> latest =
        revisionRepository.findTopBySessionIdOrderByRevisionNoDesc(sessionId);
int nextRevisionNo = latest.map(r -> r.getRevisionNo() + 1).orElse(1);
```

읽기와 쓰기 사이가 원자적이지 않다. 같은 세션에 동시 요청 두 개가 들어오면:

1. 요청 A가 `revisionNo=1`을 읽고 `2`를 계산
2. 요청 B가 `revisionNo=1`을 읽고 `2`를 계산 (A가 아직 커밋 전)
3. A가 `revisionNo=2` INSERT — 성공
4. B가 `revisionNo=2` INSERT — `uq_revision(session_id, revision_no)` 위반

## 어떻게 드러나는가

`DataIntegrityViolationException`이 `GlobalExceptionHandler`의 일반 핸들러로 흘러
**HTTP 500 `INTERNAL_ERROR`** 로 나간다.

클라이언트 입장에서는 "저장 실패"인데 재시도 버튼도 안 뜬다(`recoverable=false`).
실제로는 재시도하면 성공하는 상황이므로 **응답이 실제 상황을 잘못 전달한다.**

## 왜 지금 고치지 않는가

**1. P0는 단일 사용자 데모다.**

PRD 기준 P0에는 인증이 없고(TRD §13.1), 심사 시나리오는 직원 한 명이 한 세션을
순차적으로 진행하는 흐름이다. 같은 세션에 동시 쓰기가 발생하려면 두 사람이
같은 `sessionId`를 동시에 열고 동시에 저장 버튼을 눌러야 한다.

**2. 프론트가 순차 흐름을 강제한다.**

상담 입력(S02)은 저장 후 다음 화면으로 넘어가는 단선 흐름이다.
같은 화면에서 동시에 두 번 저장할 경로가 UI에 없다.

**3. 더블 클릭은 이 경로에서 무해에 가깝다.**

같은 텍스트를 두 번 보내면 계약상 **새 revision을 만들지 않고 기존 것을 반환**한다
(TRD §5.2). 따라서 더블 클릭으로 이 문제가 재현되려면 두 클릭 사이에 텍스트가
바뀌어야 하는데, 그건 사람이 두 번 타이핑한 경우다.

**4. 제대로 고치려면 비용이 든다.**

DB 락(`SELECT ... FOR UPDATE`)은 커넥션 풀이 5개뿐인 환경(`maximum-pool-size: 5`)에서
부담이 크다. 시퀀스/트리거로 옮기면 마이그레이션이 필요하고, `revisionNo`가
"세션 안에서의 순번"이라는 의미가 흐려진다.

**5. 지금 남은 기간에 더 값싼 위험이 많다.**

배포 동결이 2026-09-06이고 F03~F08이 통째로 남아 있다.

## 위험이 현실화되는 조건

아래 중 하나라도 생기면 재검토한다.

- 인증이 붙고 여러 직원이 같은 세션에 접근할 수 있게 될 때
- 프론트가 자동 저장(autosave)을 도입할 때 — 사용자 조작 없이 요청이 겹칠 수 있다
- 재시도 로직이 클라이언트에 들어갈 때 — 실패한 요청이 뒤늦게 도착할 수 있다

## 고칠 때의 선택지

| 안 | 내용 | 비용 |
|---|---|---|
| **(1) 실패를 우아하게** | `DataIntegrityViolationException` → `CONCURRENT_SESSION_UPDATE`(409, `recoverable=true`) 매핑 | 핸들러 한 개. **경쟁 자체는 남지만** 프론트가 재시도할 수 있게 된다 |
| (2) 재시도 루프 | 유니크 위반 시 서버가 번호를 다시 읽고 1회 재시도 | 중간. 트랜잭션 경계 설계 필요 |
| (3) 행 락 | 세션 행을 `SELECT ... FOR UPDATE` | 커넥션 5개 환경에서 부담 |
| (4) DB 채번 | 시퀀스/트리거로 `revisionNo` 생성 | 마이그레이션 필요. 순번 의미가 흐려짐 |

**(1)을 먼저 권한다.** 근본 해결은 아니지만 잘못된 응답(500)을 올바른 응답(409 재시도 가능)으로
바꾸는 것만으로 사용자 영향이 거의 사라진다. 계약에 이미 있는 코드를 쓰므로
openapi 변경도 필요 없다.

## 검증 방법

이 문제는 **mock 기반 테스트로는 드러나지 않는다.** `uq_revision` 제약이 실제 Postgres에
있어야 재현된다. F03에서 Testcontainers를 붙일 때 동시성 테스트로 재현 가능하다.

## 관련

- `finready-backend/src/main/java/io/finready/session/SessionService.java` — `createRevision`
- `V1__init.sql` — `constraint uq_revision unique (session_id, revision_no)`
- TRD §5.2 (Revision immutable), §5.3 (낙관적 락)
- `docs/backend-notes.md` §4.4 (확인이 필요한 것)
