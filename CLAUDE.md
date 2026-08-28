# FinReady — 저장소 공통 컨텍스트

2026 금융 AI Challenge 출품작. 팀 '앞과뒤' 2인(백엔드 1, 프론트엔드 1).

ELS 상담에서 **어떤 Risk가 충분히 설명되지 않았는지**와 **고객이 어떤 Risk를 반대로
이해했는지**를 항목 단위로 드러내는 상담 보조 서비스. 법적 판정이 아니라 보조 수단이다.

> 이 파일은 백엔드·프론트엔드가 함께 지키는 내용만 둔다.
> 백엔드 작업 규칙과 진행 상황은 `finready-backend/CLAUDE.md` 에 있다.

## 마감

| 일정 | 내용 |
|---|---|
| 2026-09-07 10:00 | 기획서·기능명세서·배포 URL 제출 마감 |
| 2026-09-07 11:00 ~ 09-11 23:59 | 심사 URL 상시 가용 필요 |
| 2026-09-06 | **배포 동결.** 이후 긴급 수정 외 push 금지 |

## 저장소 구조

```
finready/
├── docs/
│   ├── FinReady_PRD_DEV_FREEZE_v1.3.1.pdf
│   ├── FinReady Backend TRD v1_2_3.pdf
│   └── openapi.yml                    ← API 계약 원본 (v1.4.6)
├── finready-backend/                  ← Spring Boot. CLAUDE.md 별도
└── finready-frontend/                 ← Next.js. 프론트 담당자 영역
```

## 문서 우선순위

**PRD > TRD > 코드.** 충돌하면 상위 문서가 이긴다.

- PRD v1.3.1 — 제품 요구사항. DEV FREEZE 상태
- Backend TRD v1.2.3 — 기술 결정. 데이터 모델·상태머신·검증 절차
- `docs/openapi.yml` v1.4.6 — API 계약

**작업 전에 TRD의 해당 절을 먼저 읽을 것.** 특히 §4(데이터 모델), §6(Enum 계약),
§8(Evidence 검증)은 값 하나가 어긋나면 세 문서 대조에서 걸린다.

두 문서 모두 PDF다. TRD는 폰트가 커스텀 인코딩이라 텍스트 추출 시 **한글 본문이
깨진다.** enum·SQL·표·영문 식별자는 정상이므로 구조 파악은 되지만, 한글 서술이
중요한 절은 원본을 직접 볼 것.

## 계약 파일 규칙

`docs/openapi.yml`은 **백엔드만 수정**한다. 바꿀 때는:
- `info.version`을 올린다
- `description`의 변경 이력 블록에 요약을 적는다
- 커밋 메시지 앞에 `contract:`를 붙인다

**사본을 두지 않는다** (2026-08-26, 팀 결정). `finready-frontend/contracts/openapi.yml`
사본은 삭제됐고 되살리지 않는다. 프론트는 루트 `docs/openapi.yml`을 직접 참조한다 —
사본이 갈라질 걱정 자체가 없다.

## 커밋 컨벤션

```
feat(be): F03 Coverage 4상태 분류 + Gate 판정
fix(be): F05 attempt 상한이 서버에서 안 걸리던 문제
contract: openapi v1.4.3 — recheckQuestion 추가
docs: TRD §4.6 session_question 신설
chore: .gitignore 패턴 기반으로 변경
```

범위는 `be` / `fe` / `contract` / `docs` / `chore`.
기능 작업은 PRD의 F01~F08, S01~S08 ID를 제목에 넣는다.

## 공통 보안 규칙

**API 키·DB 자격증명을 코드·응답·로그·커밋에 넣지 않는다.** 환경변수만 쓴다.
로컬 설정 파일은 `.gitignore` 대상이며, `.yml`과 `.yaml` 양쪽 확장자를 모두 막는다.
