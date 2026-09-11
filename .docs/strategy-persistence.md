# 전략과 실행 결과 저장 (4단계)

기준: [기획서](project-plan.md)의 FR-02, FR-03 및 사용자 지정 4단계.

## 설계와 구현 계획

기존 Java 17, Spring Data JPA, Jackson, H2를 사용한다. 기존 파일 DB와 캔들 테이블을 유지하며 의존성을 추가하지 않는다.

- [x] API 통합 테스트로 저장 → 버전 수정 → 버전 지정 실행 → 결과 재조회 흐름의 실패를 먼저 확인한다.
- [x] `strategy/SavedStrategy*`에 전략 식별자와 불변 버전별 JSON을 저장한다. 이름은 필수, 원문 프롬프트는 선택이며 입력 그대로 보존한다. `schemaVersion`과 수정 버전은 구분한다.
- [x] 수정은 `expectedVersion`을 확인하고 DB 행 잠금으로 직렬화한다. 오래된 수정 요청은 409로 거절한다.
- [x] `backtest/SavedBacktest*`에서 명시한 전략 버전으로 실행한다. 실제 조회한 준비 구간 포함 캔들, 데이터 SHA-256, 전략, 기간, 체결 방식, 비용 미적용 설정, 결과와 거래 근거를 불변 JSON으로 함께 보존한다.
- [x] 검증 오류는 저장하지 않는다. 유효한 실행의 데이터 오류는 `FAILED` 이력으로 남기며, 데이터 없음/부족/거래 없음은 기존 엔진 상태를 그대로 저장한다.
- [x] 목록·상세·버전별 조회 API와 사용 예제를 제공한다. 기존 직접 실행 API는 유지한다.
- [x] 별도 파일 H2를 사용하는 애플리케이션 컨텍스트를 닫고 다시 열어 버전·실행 결과·캔들 스냅샷 보존을 확인한다. 전체 테스트와 실행 JAR 빌드를 확인한다.

4단계 최초 구현에는 삭제, LLM, 웹 화면, 전략 비교, 모의매매, 비용 계산 엔진 확장을 포함하지 않았다. 이후 추가한 삭제 계약은 아래에 설명한다. 기존 엔진의 비용·자금 미지원 상태를 실행 스냅샷에 명시한다.

## API 사용 계약

- `DELETE /api/strategies/{id}`: 전략의 모든 버전과 그 버전들에 속한 실행 이력을 한 트랜잭션에서 영구 삭제한다. 다른 전략과 공용 캔들 캐시는 유지한다. 실행·수정과 같은 전략 행 잠금을 사용한다. 성공은 본문 없는 204, 없는 전략은 404다.
- `DELETE /api/backtest/runs/{id}`: 실행 결과와 당시 입력 스냅샷 한 건을 영구 삭제한다. 저장 전략·버전과 다른 실행은 유지한다. 성공은 본문 없는 204, 없는 이력은 404다.
- 웹 화면의 삭제 버튼은 대상과 범위를 확인한 후 요청한다. 전략의 개별 버전 삭제와 실행 이력 일괄 삭제는 제공하지 않는다.
- `POST /api/strategies`: `StrategyDefinition` JSON 자체를 전송한다. 이름은 공백이 아닌 1~200자다. `originalPrompt`는 선택이며 원문 그대로 저장한다. 201과 `Location`, `id`, `version: 1`, `createdAt`, `versionCreatedAt`, `strategy`를 반환한다.
- `PUT /api/strategies/{id}`: `{ "expectedVersion": 1, "strategy": { ... } }`. 최신 버전과 일치할 때만 전체 전략을 새 버전으로 저장한다. 200과 새 버전을 반환한다. 기존 버전을 덮어쓰지 않는다. 같은 내용의 재저장도 새 버전이다.
- `GET /api/strategies`: 최신 전략 목록. 수정 시각 내림차순, 동일 시각은 ID 내림차순.
- `GET /api/strategies/{id}`: 최신 버전 상세.
- `GET /api/strategies/{id}/versions`: 버전 내림차순 이력.
- `GET /api/strategies/{id}/versions/{version}`: 특정 버전 상세.
- `POST /api/strategies/{id}/backtests`: `{ "version": 1, "symbol": "005930", "startDate": "2025-01-02", "endDate": "2025-01-04", "executionMode": "SAME_DAY_CLOSE" }`. 모든 항목 필수다. 최신 버전 자동 선택은 하지 않는다. 201과 실행 상세 및 `/api/backtest/runs/{runId}` 형태의 `Location`을 반환한다.
- `GET /api/strategies/{id}/backtests`: 실행 ID 내림차순 요약(`id`, `version`, `createdAt`, `status`). 큰 캔들·거래 JSON은 읽지 않는다.
- `GET /api/backtest/runs/{runId}`: 저장된 실행 상세(`id`, `createdAt`, `status`, `snapshot`). 현재 전략·캔들로 다시 계산하지 않는다.

목록 API는 `page=0&size=20`이 기본값이며, `page >= 0`, `1 <= size <= 100`이다. 응답은 배열이며 빈 배열이 목록 끝을 뜻한다. 존재하지 않는 전략·버전·실행은 404 `NOT_FOUND`, 최신 버전 불일치는 409 `VERSION_CONFLICT`다. 잘못된 JSON은 400 `INVALID_REQUEST`, 범위·필수값 오류는 400 `VALIDATION_ERROR`와 필드별 `issues`를 반환한다. 중복 JSON 키, 미지원 필드·조건, 숫자 문자열, 소수 기간을 거절하는 기존 엄격한 파싱을 재사용한다.

기존 `POST /api/backtest/run-strategy`는 저장하지 않는 직접 실행 경로다. 저장 이력이 필요하면 위의 저장 전략 실행 경로를 사용한다. 베이스라인·그리드서치 실행 및 기존 API 응답 형식은 유지한다.

## 실행 스냅샷

- `schemaVersion: 1`: 저장 형식의 버전. 전략 표현의 `strategy.strategy.schemaVersion`, 수정 번호 `strategy.version`과 별개다.
- `engineVersion: USER_STRATEGY_DAILY_V1`: 현재 사용자 전략 엔진의 동작 기준 식별자. 엔진 동작을 변경할 때 갱신해야 한다.
- `capturedAt`: 실행 입력을 읽은 시각(UTC).
- `strategy`: 실행한 전략 ID, 수정 버전, 생성 시각, 해당 버전 생성 시각, 이름·원문·조건이 포함된 전략 전체.
- `execution`: 실제 엔진에 전달한 전략·종목·시작일·종료일·체결 방식.
- `data`: `LOCAL_CANDLE_CACHE`, 일봉 `1d`, `Asia/Seoul`, 준비 구간을 포함한 실제 입력 캔들 전체 및 SHA-256. 캔들은 `symbol`, `timestamp`, `open`, `high`, `low`, `close`, `volume`을 저장한다. 종료일 다음 날 이후 데이터는 읽거나 보존하지 않는다.
- SHA-256은 서버가 직렬화한 캔들 배열 JSON의 UTF-8 바이트 기준이다. 외부 공급원 원본 파일 해시가 아니다. 현재 캐시에는 공급원·수정주가 여부가 없으므로 이를 추정해 기록하지 않는다.
- `costs`: `model: NOT_MODELED`, `commissionRate/taxRate/slippageRate: 0`, `capitalModel: NOT_MODELED`, `initialCapital: null`. **현실 비용이 0이라는 의미가 아니라 이번 엔진이 비용·자금·수량을 계산하지 않았다는 기록이다.** 사용자 지정 비용 필드는 지원하지 않으며 조용히 무시하지 않는다.
- `result`: 기존 사용자 엔진 결과 전체. 지표, 거래별 진입·청산 사유와 조건 근거, 보유 포지션, 미체결 주문, 해석 가정을 보존한다. 계산 기준은 [3단계 API 문서](user-strategy-backtest.md)를 따른다.
- `error`: 데이터 오류 시 `code`, `message`, 원본 봉 `timestamp`. 이 경우 `status: FAILED`, `result: null`이다.

`NO_DATA`, `INSUFFICIENT_DATA`, `NO_TRADES`, `COMPLETED`도 각각 결과 이력으로 저장한다. 201은 이력 생성 성공을 뜻하며 실행 성과 여부는 `status`를 확인한다. 요청 검증 오류는 이력을 만들지 않는다. 예상하지 못한 서버 오류·DB 실패는 트랜잭션을 취소하고 성공 이력을 만들지 않는다. 실행은 동기 방식이며, 서버 강제 종료 중이던 요청의 진행 상태를 복구하는 작업 큐는 제공하지 않는다.

전략 버전과 실행 테이블은 애플리케이션에서 불변으로 다루며 외래 키로 연결한다. 원본 캔들 캐시 수정·삭제는 실행 JSON을 바꾸지 않는다. 같은 실행 요청을 다시 보내면 별도 실행 이력이 생성된다.

## PowerShell 예제

서버가 기본 포트 8090에서 실행 중이라는 가정이다. 저장 캔들이 없으면 실행은 `NO_DATA`로 저장된다. 예제의 종목·기간·이동평균 값은 사용자가 확인할 명시적 예시다.

```powershell
$apiBase = 'http://localhost:8090'
$example = Get-Content -Raw -Encoding UTF8 .docs/examples/golden-cross-backtest.json | ConvertFrom-Json
$strategyBody = $example.strategy | ConvertTo-Json -Depth 30
$saved = Invoke-RestMethod -Method Post -Uri "$apiBase/api/strategies" `
  -ContentType 'application/json; charset=utf-8' `
  -Body ([System.Text.Encoding]::UTF8.GetBytes($strategyBody))

$runBody = @{
  version = $saved.version
  symbol = $example.symbol
  startDate = $example.startDate
  endDate = $example.endDate
  executionMode = $example.executionMode
} | ConvertTo-Json
$run = Invoke-RestMethod -Method Post -Uri "$apiBase/api/strategies/$($saved.id)/backtests" `
  -ContentType 'application/json; charset=utf-8' `
  -Body ([System.Text.Encoding]::UTF8.GetBytes($runBody))

# 수정 전 결과는 변경되지 않는다.
$example.strategy.name = '골든크로스 손절 8%'
$example.strategy.risk.stopLoss.rate = -0.08
$updateBody = @{ expectedVersion = $saved.version; strategy = $example.strategy } | ConvertTo-Json -Depth 30
$updated = Invoke-RestMethod -Method Put -Uri "$apiBase/api/strategies/$($saved.id)" `
  -ContentType 'application/json; charset=utf-8' `
  -Body ([System.Text.Encoding]::UTF8.GetBytes($updateBody))

Invoke-RestMethod "$apiBase/api/strategies/$($saved.id)/versions"
Invoke-RestMethod "$apiBase/api/strategies/$($saved.id)/backtests"
$pastRun = Invoke-RestMethod "$apiBase/api/backtest/runs/$($run.id)"
$pastRun.snapshot.result | ConvertTo-Json -Depth 30
```

## DB 유지와 재시작

기존 `application.yaml`의 `jdbc:h2:file:./data/tossinvest;AUTO_SERVER=TRUE`, `ddl-auto: update`를 그대로 사용한다. 스키마 변경은 `saved_strategy`, `saved_strategy_version`, `saved_backtest` 추가다. 기존 캔들·모의매매 테이블은 변경하지 않는다.

프로젝트 루트처럼 **같은 작업 디렉터리에서 서버를 재시작**해야 동일한 상대 경로의 DB를 사용한다. 실행 위치를 바꿔야 한다면 `SPRING_DATASOURCE_URL`에 고정한 절대 파일 경로를 지정한다. `data/`를 삭제하거나 메모리 DB·`create`·`create-drop` 설정으로 바꾸면 이력 유지 조건을 충족하지 못한다. DB 백업은 서버를 종료한 상태에서 `data/tossinvest.mv.db`를 복사한다. DB 파일은 Git 제외 대상이다.

서버 종료 후 동일 설정으로 다시 시작하여 위 예제의 전략·실행 ID로 조회한다. 자동 검증은 운영 파일 대신 임시 파일 H2에서 컨텍스트 종료 → `ddl-auto: validate`로 재시작 → 이전 전략·거래·데이터 조회 및 저장 캔들로 결과 재현을 수행한다. 전략·실행 스냅샷을 누적하므로 DB 용량은 실행 횟수와 입력 데이터 양에 따라 증가한다. 초기 로컬 H2 범위이며 사용자별 소유권·인증 기능은 아직 없다.

## 변경 파일

Java 경로 기준은 `src/main/java/com/tossinvest/tossinvestbackend/`다.

- 추가: `strategy/SavedStrategyEntity.java`, `SavedStrategyVersionEntity.java`, `SavedStrategyRepository.java`, `SavedStrategyVersionRepository.java`, `SavedStrategyService.java`, `SavedStrategyController.java` — 식별자·버전 저장, 충돌 검사, 조회 및 실행 API.
- 추가: `strategy/StrategyJson.java`, `StrategyApiErrors.java` — 기존 사용자 API의 엄격한 파싱·오류 처리를 공통으로 재사용.
- 추가: `backtest/SavedBacktestEntity.java`, `SavedBacktestRepository.java`, `SavedBacktestService.java` — 실행 스냅샷 저장과 조회.
- 수정: `backtest/UserStrategyBacktestController.java` — 파서·오류 처리 공통화. 기존 요청·응답 계약 유지.
- 추가 테스트: `src/test/java/com/tossinvest/tossinvestbackend/backtest/StrategyPersistenceApiTests.java`, `StrategyPersistenceRestartTests.java`.
- 문서: `.docs/strategy-persistence.md` 추가, `.docs/user-strategy-backtest.md`와 `README.md`의 저장 경로 안내 및 H2 설명 보완.

## 검증 방법

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
.\gradlew.bat test bootJar --offline --console=plain --rerun-tasks
```

로컬 의존성 캐시가 없다면 `--offline`을 제거하고 의존성을 내려받아야 한다. 테스트는 메모리 H2와 임시 파일 H2를 사용한다. 파일 DB 재시작 테스트는 웹·스케줄러가 없는 별도 컨텍스트로 실제 JPA 저장 서비스를 실행한다. 기존 운영 DB나 외부 시세·주문 API를 사용하지 않는다.

2026-09-09 최종 검증: 위 명령으로 **전체 73개 테스트 통과, 실패·오류·건너뜀 0개**, `bootJar` 빌드 성공. 기존 63개와 신규 10개다. 신규 API 테스트 8개는 전략 메타데이터·버전, 실행 스냅샷 보존, 충돌, 누락·검증 오류, 엄격한 JSON, 데이터 상태 구분, 실패 이력, 소수 정밀도를 검증한다. 파일 DB 테스트 2개는 컨텍스트 재시작·캐시 삭제 후 과거 결과 조회 및 재현, 동시 수정 요청의 단일 반영을 확인한다. 구현 전에는 신규 API 테스트 8개가 미구현 엔드포인트의 404 응답으로 실패하는 것을 확인했다.

별도 코드 리뷰에서 수정이 필요한 문제는 발견되지 않았다. `git diff --check` 공백 오류 없음. 테스트 보고서는 `build/reports/tests/test/index.html`, 실행 JAR는 `build/libs/toss-invest-backend-0.0.1-SNAPSHOT.jar`다. 실제 운영 DB 파일을 사용한 서버 재시작이나 외부 API 연동은 이번 검증에 포함하지 않았다.
