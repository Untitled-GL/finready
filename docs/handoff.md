# 작업 인수인계: 로컬 부하 테스트

마지막 갱신: 2026-09-10
작업 브랜치: `feat/load-testing-observability` (메인 브랜치: `master`)
구현 기준 커밋: `4bec5e2` — `feat: add isolated k6 load testing and observability stack`

## 목적과 결정

회사와 집에서 같은 브랜치의 작업을 이어가기 위한 메모다. 새 작업자는 이 문서와
[로컬 부하 테스트 README](../finready-backend/load-test/README.md)를 먼저 읽고 실제 Git 상태를 확인한다.

- 실제 Claude 호출 비용 없이 k6 부하 테스트와 Prometheus·Grafana 관측을 경험한다.
- Docker Compose에 가짜 Claude, 임시 PostgreSQL, 백엔드, k6, Prometheus, Grafana를 구성했다.
- 위험도 9개를 3개씩 나눠 병렬 호출하는 기존 경로를 사용하고, 이후 provenance 검사와 verifier 호출 및 DB 저장까지 실행한다.
- mock은 고정된 판정과 지연을 반환한다. 금융 판정 정확도, 실제 Claude 성능, 가상 스레드 대 플랫폼 스레드의 우열을 증명하는 테스트는 아니다.

## 완료 및 검증 상태

구현 커밋 `4bec5e2`는 원격 작업 브랜치에 푸시되어 있다. 아래는 구현 당시 회사 환경에서 확인한 결과이며, Docker 실행 결과와 구분한다.

- Java 25로 백엔드 단위 테스트 357개 통과(신규 AI 호출 계측 테스트 포함).
- Node mock 테스트 6개 통과.
- Compose 설정 정적 검사, k6 JavaScript 문법 검사, Grafana 대시보드 JSON 검사 통과.
- Docker 컨테이너 기동, DB 통합 테스트, k6 실제 실행, Prometheus 수집 및 Grafana 표시 여부는 **아직 미검증**이다.

## 집에서 다음에 할 일

1. 로컬 변경이 있는지 `git status`로 확인한다. 변경이 있으면 보존한 뒤 브랜치를 전환한다. 저장소가 없다면 먼저 clone한다.
2. 원격 브랜치의 최신 코드와 이 문서를 받는다. 아래 명령은 저장소 루트에서 실행한다.

   ```powershell
   git fetch origin
   git switch feat/load-testing-observability
   git pull --ff-only
   ```

   로컬 브랜치가 없고 자동 추적이 되지 않으면 `git switch --track origin/feat/load-testing-observability`를 사용한다.
   pull이 실패하면 강제 초기화하지 말고 로컬 변경이나 브랜치 분기를 확인한다.

3. Docker Desktop을 Linux containers 모드로 켜고 README의 명령으로 Compose를 빌드·기동한다. 백엔드 시작 로그를 확인한다.
4. `smoke`를 실행해 정상 응답과 3배치 동시 호출을 확인한다. Prometheus targets가 UP인지, Grafana에 메트릭이 표시되는지도 확인한다.
5. smoke 성공 후 `load`를 실행한다. 오류율·Coverage p95·Hikari 대기·AI 동시 호출 수를 함께 관찰한다.
6. README의 설정 변경 절차에 따라 `malformed`, `http500`, `timeout` 실패 시나리오를 각각 실행한다. 각 failure 실행 직후 다른 테스트 없이 `verify-db`로 결과 미저장과 호출 로그를 검증한다.
7. 실패 설정을 기본값으로 복구한다. 필요하면 Java 25 환경에서 백엔드 `integrationTest`도 실행하고, 결과와 남은 문제를 이 문서에 갱신한다.

실행 명령, 환경변수, 재시도 횟수와 임계값의 상세 기준은 README에 있다. 테스트가 실패하면 실행 모드·설정·오류·관련 로그를 기록하고, 실제 원인을 확인하기 전에 통과 기준부터 완화하지 않는다.

## 주의사항과 작업 종료 규칙

- 이 부하 테스트는 로컬 전용이다. 운영 DB·Supabase·실제 Claude를 대상으로 실행하지 않는다. 실제 API 키나 `.env`를 커밋하지 않는다.
- k6는 한 번에 하나씩 실행한다. `verify-db`는 최신 세션을 검사하므로 반드시 단일 failure 실행 직후 수행한다.
- DB는 임시 저장소이고 관측 도구에도 영구 볼륨이 없다. 컨테이너 종료·삭제 전에 필요한 결과를 별도로 기록한다.
- 1회 smoke의 p95나 로컬 mock 결과를 운영 성능 측정값으로 해석하지 않는다.
- 기존 사용자 파일인 루트와 백엔드의 `.claude/`는 이번 작업 범위가 아니므로 임의로 수정하거나 커밋하지 않는다.
- 작업을 마칠 때 이 문서의 날짜·완료 항목·실제 검증 결과·미완료 항목·다음 작업을 갱신하고, 관련 코드와 함께 커밋·푸시한다. 미실행 검증을 완료로 표시하지 않는다.

다른 PC의 새 AI 대화에서는 다음과 같이 요청하면 된다.

> `docs/handoff.md`와 `finready-backend/load-test/README.md`를 읽고 현재 브랜치 및 코드 상태를 확인해서, 남아 있는 로컬 부하 테스트 검증을 이어가줘.
