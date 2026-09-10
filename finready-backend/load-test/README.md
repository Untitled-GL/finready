# FinReady 로컬 부하 테스트

가짜 Claude + PostgreSQL + 백엔드 + k6 + Prometheus + Grafana를 로컬 Docker Compose로 실행한다.
브랜치: `feat/load-testing-observability`. Docker Desktop의 **Linux containers**를 사용한다.
Java 25와 k6는 이미지에 들어 있어 호스트 설치가 필요 없다. 처음 실행할 때 이미지와 Gradle 의존성 다운로드가 필요하다.

## 집에서 처음 실행하기

저장소 루트 `D:\dev\finready`에서 PowerShell로 실행한다.

```powershell
git switch feat/load-testing-observability
docker compose -f finready-backend/load-test/compose.yaml up -d --build
docker compose -f finready-backend/load-test/compose.yaml logs -f backend
```

백엔드의 `Started` 로그를 확인한 후 Ctrl+C로 로그 보기만 종료한다. 실행 중인 컨테이너는 유지된다.

```powershell
# 최초 1명 × 1회: 준비 상태를 기다리고 실제 Coverage 전체 경로 확인
docker compose -f finready-backend/load-test/compose.yaml run --rm -e MODE=smoke k6

# smoke 성공 후: 1 → 3 → 5 → 10명, 약 4분 + 진행 중 요청 종료 대기
docker compose -f finready-backend/load-test/compose.yaml run --rm -e MODE=load k6
```

- Grafana: http://localhost:13000 → Dashboards → FinReady → FinReady — Local Load Test (열람 로그인 불필요)
- Prometheus: http://localhost:19090/targets → `finready`, `mock-claude`가 UP인지 확인
- 백엔드 health: http://localhost:18080/actuator/health
- Spring 메트릭: http://localhost:18080/actuator/prometheus

한 번에 하나의 k6 실행만 사용한다. Grafana `k6 run` 필터로 실행을 선택할 수 있다. 서버 메트릭은 전체 서버 기준이므로 해당 실행의 시간 구간도 맞춘다. 1회 smoke의 p95는 성능 근거가 아니며 기능·설정 확인용이다.

## 어떤 경로를 측정하나

```text
k6 → 세션 생성 → revision 생성 → Coverage 요청
                                 ├─ classifier 3개 병렬 HTTP 호출 (각 8초)
                                 ├─ 실제 provenance 검사
                                 ├─ verifier 1개 HTTP 호출 (5초)
                                 └─ 실제 DB 저장 및 Gate 계산

k6 → Prometheus remote write ← Spring /actuator/prometheus + mock /metrics
                 ↑
              Grafana
```

매 iteration은 새로운 세션을 만든다. 완료된 revision을 반복 호출해 저장된 결과만 읽는 실수를 방지한다.
가짜 AI는 지정된 항목을 EXPLAINED/SUPPORTS로 고정 반환한다. 이는 금융 판정 정확도 테스트가 아니라 HTTP·동시성·실패 전파·DB 성능 테스트다.
정상 응답의 evidence는 상담 원문에 실제로 있는 120자 구간을 사용해 verifier까지 실행되게 한다. 가짜 토큰 usage는 성능·비용 추정에 쓰지 않는다.

정상 부하 테스트는 HTTP 오류율 <1%, Coverage p95 <30초, 응답 및 provenance 검증 전부 통과를 요구한다. 기본 지연 합계가 약 13초라 제품의 12초 목표 충족을 증명하는 테스트는 아니다.
가상 스레드와 일반 스레드의 A/B 벤치마크는 포함하지 않는다. 현재 운영과 같은 3배치 가상 스레드 실행을 관측한다. 로컬 PC의 CPU/DB/네트워크 성능도 배포 환경과 다르다.

## 한 배치 실패 테스트

다른 k6 실행이 끝난 상태에서 진행한다. 기본 실패 배치는 R04~R06이다.

```powershell
# 잘못된 JSON: 실패 배치의 gateway 재시도 1회를 확인
$env:FAILURE_MODE = 'malformed'
docker compose -f finready-backend/load-test/compose.yaml up -d --force-recreate mock-claude
docker compose -f finready-backend/load-test/compose.yaml run --rm -e MODE=failure k6
docker compose -f finready-backend/load-test/compose.yaml run --rm verify-db

# HTTP 500: SDK 재시도 + gateway 재시도를 확인
$env:FAILURE_MODE = 'http500'
docker compose -f finready-backend/load-test/compose.yaml up -d --force-recreate mock-claude
docker compose -f finready-backend/load-test/compose.yaml run --rm -e MODE=failure k6
docker compose -f finready-backend/load-test/compose.yaml run --rm verify-db
```

`verify-db`는 직전 최신 세션의 Coverage 결과 0행, gateway 시도 로그 4행(정상 배치 1+1, 실패 배치 2)을 확인한다. 반드시 **failure 테스트 직후, 다른 테스트가 없는 상태**에서 실행한다.

HTTP 500/timeout은 SDK의 1회 재시도에 더해 `AiGateway`도 다시 시도하므로 실패 배치에 물리 HTTP 호출이 최대 4회 발생한다. 파싱 오류는 HTTP 자체는 성공이므로 2회다. 나머지 정상 배치는 각 1회 완료하고 verifier는 호출되지 않는다. `finready_ai_call_seconds_count`는 재시도를 포함한 논리 호출 단위여서 세 classifier 배치로 센다. `/metrics`의 mock 호출 수는 물리 HTTP 호출이다.

```powershell
# 타임아웃 테스트: SDK 대기시간을 로컬에서만 5초로 단축 (mock은 15초)
$env:FAILURE_MODE = 'timeout'
$env:LOADTEST_AI_TIMEOUT_SECONDS = '5'
$env:CLASSIFIER_DELAY_MS = '100'
$env:VERIFIER_DELAY_MS = '100'
$env:TIMEOUT_DELAY_MS = '15000'
docker compose -f finready-backend/load-test/compose.yaml up -d --force-recreate backend mock-claude
docker compose -f finready-backend/load-test/compose.yaml run --rm -e MODE=failure k6
docker compose -f finready-backend/load-test/compose.yaml run --rm verify-db

# 정상 설정으로 복구
Remove-Item Env:FAILURE_MODE,Env:LOADTEST_AI_TIMEOUT_SECONDS,Env:CLASSIFIER_DELAY_MS,Env:VERIFIER_DELAY_MS,Env:TIMEOUT_DELAY_MS -ErrorAction SilentlyContinue
docker compose -f finready-backend/load-test/compose.yaml up -d --force-recreate backend mock-claude
```

failure 모드에서는 의도한 503을 정상적인 테스트 결과로 취급하되 `coverage_503` 메트릭에는 그대로 남긴다. 오류 코드도 검사한다: 파싱은 `AI_PARSING_FAILED`, HTTP 실패/timeout은 `AI_TIMEOUT`. 기대하지 않은 200/500 등은 실패다.

## 대시보드 읽기

- k6 p95·503 비율·처리량: 사용자가 경험하는 요청 결과
- Hikari active/pending: DB 커넥션 점유와 대기. 기본 풀 크기는 기존과 같은 5개
- AI active·시간·outcome: 배치 단위 호출, 재시도와 호출 로그 저장까지 포함
- Mock active/peak·배치별 호출 수: 가짜 외부 서버에 실제로 들어온 HTTP 동시성
- JVM CPU·heap·GC: 동일 시간대 서버 자원 사용량

`peak`는 mock 프로세스 시작 이후 최대값이다. smoke의 동시성 검증을 독립적으로 다시 하려면 mock을 재생성한 뒤 실행한다. Spring AI 메트릭은 사용자 코드에 고정된 stage/outcome만 태그로 쓰고 k6는 session URL 태그를 제외한다. 짧은 smoke에서는 1분 rate 그래프가 비어 있을 수 있다. load에서 확인한다.

## 종료와 데이터

```powershell
docker compose -f finready-backend/load-test/compose.yaml down
```

PostgreSQL 데이터는 임시 메모리 파일시스템이며 DB 컨테이너 중지/삭제 시 사라진다. Prometheus와 Grafana에도 영구 볼륨을 두지 않아 `down` 후 측정 기록은 유지되지 않는다. 대시보드와 설정은 저장소 파일로 재생성된다. 기존 개발 DB·Supabase는 사용하지 않는다.
모든 서비스는 `internal` 네트워크로 연결되고 포트는 localhost에만 공개한다. 가짜 키·로컬 DB 주소를 Compose에 고정해 호스트의 실제 API/DB 환경변수를 사용하지 않는다. 이미지 다운로드·빌드가 끝난 후 런타임 서비스는 외부 Claude로 나갈 필요가 없다.

## 구현 검증

```powershell
# Docker 데몬 없이도 가능
docker compose -f finready-backend/load-test/compose.yaml config --quiet
node --test finready-backend/load-test/mock/server.test.mjs

# Java 25가 설치된 환경 (백엔드 디렉터리에서)
cd finready-backend
.\gradlew.bat test
# Docker가 켜져 있어야 통합 테스트 실행 가능
.\gradlew.bat integrationTest
```

현재 작성 환경에서는 Java 25로 `gradlew test`(컴파일 및 신규 SDK/계측 테스트 포함), mock 테스트 6건, Compose 정적 검사, k6 JavaScript 문법 검사, 대시보드 JSON 검사를 통과했다. Docker 컨테이너 기동, DB 통합 테스트, k6 실측 및 Grafana 메트릭 수신은 집의 Docker 환경에서 검증해야 한다. 서버의 기본 프로파일은 기존처럼 health만 노출하며 `prometheus` 노출은 `loadtest` 프로파일에 한정된다.

설정 참고: [k6 Prometheus remote write](https://grafana.com/docs/k6/latest/results-output/real-time/prometheus-remote-write/), [Spring Boot metrics](https://docs.spring.io/spring-boot/reference/actuator/metrics.html). k6의 `experimental-prometheus-rw` 출력은 experimental API이므로 이미지 버전을 고정했다.
