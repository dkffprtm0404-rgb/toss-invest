# 백테스트 실행 전 데이터 준비 상태

## 목적과 범위

사용자가 실행 전에 요청 기간, 저장 데이터 보유 기간과 봉 수, 전략의 진입 판단 준비 여부를 확인하고 부족한 데이터를 준비할 수 있게 한다. 대표의 2026-11-11 진행 지시에 따른 구현이다.

첫 적용 대상은 저장된 단일 종목 전략(`schemaVersion` 1·2)의 백테스트다. 포트폴리오·조합의 구성 종목·거래일 입력 템플릿과 사전 검증은 이 작업에 포함하지 않는다.

## 구현 계획과 책임

- [x] 서버: 기존 일봉 검증과 `StrategyEvaluator.Evaluation.entryReady`를 재사용하는 읽기 전용 조회를 추가한다.
- [x] 서버: 전체 저장 범위와 요청 범위, 준비용 이력, 최초 판단 가능일을 구분한다. 달력 조건에서 고정 부족 봉 수를 추정하지 않는다.
- [x] 화면: 데이터 준비 상태 확인, 상태별 한국어 안내, 기간·대상 수정 및 준비 안내 동선을 연결한다.
- [x] 화면: 입력 변경 시 결과 무효화, 역순 응답 무시, 조회 실패 시 실행 차단을 구현한다.
- [x] 검토: 충분·없음·부족·부분 준비·달력 조건·OR/AND·잘못된 가격·조회 경합과 부작용 부재를 소스·테스트 작성 내용으로 대조했다. 실행 확인은 미완료다.
- [x] 검증: 허용된 정적 점검 결과와 실행하지 못한 자동 검증을 분리해 기록한다. 실행 검증은 미완료다.

서버 개발 담당은 Java/API와 이 문서, 화면 개발 담당은 workbench 화면과 관련 테스트, 품질 확인 담당과 코드 검토 담당은 최종 검증·검토를 맡는다. 기존 기획·조사 문서 3개는 보존한다. 작업 브랜치는 `feat/data-readiness-20261111`이다.

## API

```http
GET /api/strategies/12/data-readiness?version=1&symbol=005930&startDate=2025-01-02&endDate=2025-01-04&executionMode=SAME_DAY_CLOSE
```

전략 ID와 저장 버전, 종목, 시작일·종료일, 체결 방식이 필요하다. 기본 버전·종목·기간·방식을 임의로 정하지 않는다. 기존 단일 종목 실행과 동일한 입력 검증을 사용한다. 응답은 `Cache-Control: no-store`로 반환한다.

다음은 API 형식을 설명하기 위한 합성 데이터 예시이며 실제 DB 현황이 아니다.

```json
{
  "status": "READY",
  "symbol": "005930",
  "startDate": "2025-01-02",
  "endDate": "2025-01-04",
  "totalCandleCount": 4,
  "availableStartDate": "2025-01-01",
  "availableEndDate": "2025-01-04",
  "candleCount": 3,
  "actualStartDate": "2025-01-02",
  "actualEndDate": "2025-01-04",
  "warmupBars": 1,
  "requiredWarmupBars": 1,
  "calendarBasedWarmup": false,
  "firstReadyDate": "2025-01-02",
  "readyCandleCount": 3,
  "invalidDataDate": null,
  "warnings": ["ENTRY_READINESS_ONLY", "TRADING_CALENDAR_NOT_CHECKED", "CACHE_CAN_CHANGE"]
}
```

`totalCandleCount`와 `availableStartDate`·`availableEndDate`는 해당 종목의 전체 저장 현황이다. `candleCount`와 `actualStartDate`·`actualEndDate`는 요청 구간에서 읽은 봉 현황이다. `warmupBars`는 요청 시작일보다 앞선 저장 봉 수이며, 종료일 뒤의 봉은 지표 준비 계산에 사용하지 않는다.

`requiredWarmupBars`는 기존 판정기가 사용하는 진입 준비 기준이다. 이동평균 추세의 달력 개월 조건, 달력 주 단위 가격 범위 조건이 있으면 `calendarBasedWarmup=true`이고 이 값은 `null`이다. 달력 조건의 부족량을 거래일 봉 수로 임의 환산하지 않는다.

## 상태와 안내

- `READY`: 요청 구간의 저장 봉 모두에서 진입 판단이 가능하고, 요청 날짜가 저장 범위 안에 있다.
- `PARTIAL`: 일부 봉에서만 진입 판단이 가능하거나 요청 날짜가 전체 저장 범위를 벗어난다. 최초 판단 가능일·봉 수와 저장 범위를 함께 확인한다.
- `NO_DATA`: 요청 구간에 사용할 봉이 없다. 다른 기간의 저장 데이터가 있어도 이 상태일 수 있다.
- `INSUFFICIENT_DATA`: 요청 구간에 봉이 있으나 진입 판단이 가능한 봉이 없다.
- `INVALID_DATA`: 기존 엔진 검증에서 일자·순서·OHLC·거래량 오류를 발견했다. 계산하지 못한 구간/준비 수치는 `null`이며 0으로 표시하지 않는다.

HTTP 조회 실패와 잘못된 실행 입력은 위 데이터 상태로 바꾸지 않는다. 입력을 수정하거나 조회를 다시 시도하도록 안내한다. 데이터가 없거나 준비가 부족하거나 잘못된 경우, 그리고 조회에 실패한 경우에는 단일 종목 실행을 진행하지 않는다. `PARTIAL`은 제한 범위를 안내하고 사용자가 현황을 확인한 뒤 실행할 수 있다.

날짜 공백이 휴장일인지 데이터 누락인지는 이 API가 판정하지 않는다. `READY`도 진입 지표 준비에 대한 설명이다. 매도 지표 전체의 준비, 주문 체결, 실제 거래일 완전성, 거래 발생이나 수익을 보장하지 않는다. OR 조건은 한 갈래만 준비되어도 기존 엔진과 동일하게 진입 판단이 가능할 수 있다.

## 데이터 보존과 복구

조회의 트랜잭션은 읽기 전용이다. 저장 버전을 읽고 저장 캔들을 검사하며 백테스트 이력·모의매매 계좌를 생성하지 않는다. 캔들 수집, CSV 가져오기, Toss API, Codex를 호출하지 않는다. 기존 실행 결과를 조회할 때는 이 API로 덮어쓰지 않고 저장 스냅샷을 계속 사용한다.

화면 복구는 기간·대상을 수정하거나 [README의 데이터 준비 절차](../README.md#5-캔들-데이터-준비--csv-수집-사용-시)를 확인하는 동선이다. 실제 수집·가져오기는 별도 명시적 동작이며 사전 점검에서 자동 실행하지 않는다. 조회 후 데이터가 바뀔 수 있으므로 저장된 실행의 최종 근거는 해당 실행 스냅샷이다.

## 검증 기록

아래는 소스 구현과 정적 점검 기록이다. 컴파일·자동 테스트·실제 화면 동작을 통과했다는 기록이 아니다.

- Java 17.0.12 실행 환경을 조회했다.
- `git diff --check`는 현재 변경에 대해 종료 코드 0이었다. README의 LF→CRLF 예고 경고만 출력됐다.
- 신규 DTO·Java 테스트·브라우저 테스트·이 문서는 별도 `rg` 검색에서 후행 공백·병합 충돌 표식이 발견되지 않았다(일치 없음, 종료 코드 1). 파싱·컴파일 검사는 아니다.
- `DataReadinessTests`에 범위·준비 상태·OR/AND·달력·미래 데이터·가격 오류·일자 공백, 체결 방식별 ATR 준비/다음 봉 부재와 UTC/KST 자정 경계를 작성했다. 최종 취합에서 52주 최초 준비일과 윤년 월말의 MA_TREND 경계 2개를 추가해 총 16개다.
- `StrategyPersistenceApiTests`의 이번 기능 검사 3개는 조회 부작용 없음, 저장 버전 고정, 입력 검증, 데이터 없음과 달력 조건이 미준비인 짧은 VOLUME OR의 API 결과를 다룬다. OR 검사는 캔들 수·내용, 저장 전략·버전 목록, 실행 이력의 불변 단언도 포함한다.
- 화면 상태 단위 검사 3개, 새 브라우저 검사 8개를 작성하고 기존 저장→실행 브라우저/격리 서버 사례에 준비 상태 확인 단계를 추가했다. 선언 수이며 통과 수가 아니다.
- QA와 코드 검토 담당이 새 브라우저 fixture의 전략 상세 GET 응답 누락을 찾았다. 실제 선택 코드가 요청하는 `/api/strategies/7`·`/api/strategies/8` 응답을 보완했고, 소스에서 두 경로를 확인했다. 실행 재현·수정 후 통과는 확인하지 않았다.
- QA의 최종 보완 보고와 독립 코드 검토에서 위 소스 수정 반영을 확인했다. 독립 정적 검토에서 남아 있는 실제 중대 결함은 발견하지 못했으며, 이는 컴파일·자동 테스트·동작 성공 판정이 아니다.
- 프로젝트 스크립트 실행 금지에 따라 테스트의 실패→통과 확인과 자동 통합 검증은 실행하지 않았다.
- 직접 `javac` 컴파일과 `node --check src/main/resources/static/js/strategy-workbench.js`도 프로세스 생성 단계에서 `Rejected("rejected by user")`로 차단됐다. 상세 사유는 제공되지 않았다. 컴파일·구문 검사·테스트 통과를 주장할 근거가 없다.
- 현재 변경을 제공하는 실행 환경을 확인하지 못해 새 결과 화면 캡처는 0장이다. 서버·브라우저 검사 스크립트를 기동하지 않았고 과거 PNG를 현재 결과로 재사용하지 않았다. 모바일·키보드·HTTP 직렬화와 화면/API 결합은 실행 검증 대상이다.
- 하위 업무 보고·현재 소스와 추가 회귀를 대조한 [최종 취합 보고](data-readiness-final-report.md)에 변경 범위·미완료 검증·후속 업무 카드를 기록했다. 초기 인계의 Java 14개·API 2개는 추가 보완 전 수치다. 모든 검사 수는 선언 수이며 통과 수가 아니다.

## 변경 파일

- 서버: `backtest/DataReadiness.java`(신규 DTO), `backtest/UserStrategyBacktestEngine.java`, `backtest/SavedBacktestService.java`, `strategy/SavedStrategyController.java`. Java 경로 기준은 `src/main/java/com/tossinvest/tossinvestbackend/`다.
- 화면: `src/main/resources/static/js/strategy-workbench.js`, `src/main/resources/static/css/strategy-workbench.css`.
- Java 회귀: `src/test/java/com/tossinvest/tossinvestbackend/backtest/DataReadinessTests.java`(신규), 같은 폴더의 `StrategyPersistenceApiTests.java`.
- 화면 회귀: `scripts/tests/strategy-workbench.test.cjs`, `strategy-workbench.browser.cjs`, `strategy-workbench.server.cjs`, 신규 `strategy-readiness.browser.cjs`(모두 `scripts/tests/` 안).
- 문서: `README.md`, `.docs/data-readiness.md`(이 문서, 신규).

원래 있던 미추적 `.docs/2026-11-11-follow-up-cards-b5ea6f8d.md`, `.docs/product-audit-2026-11-10.md`, `.docs/research-standby-2026-11-10.md`는 이 작업의 변경 파일에 포함하지 않는다. 팀별 검토 인계는 Git 제외 경로인 `build/data-readiness-frontend-review.md`, `build/data-readiness-qa-review.md`, `build/data-readiness-code-review.md`에 있다.

## 가정

- 이번 승인 범위는 로컬 코드 구현이며 배포·push·실제 데이터 수집은 포함하지 않는다.
- 첫 적용 범위는 단일 종목 전략 1·2이며 다른 전략 유형을 준비 완료로 표시하지 않는다.
- '현황 확인 후 실행'은 화면의 확인란으로 명시했다. `PARTIAL`도 제한 범위와 주의사항을 확인한 뒤 기존 백테스트를 실행할 수 있다.
- 화면·서버의 준비 상태는 현재 캐시 조회 시점의 참고 정보다.
- 위험 작업 및 프로젝트 스크립트 실행 금지는 유지한다.

## 대표 확인 필요

실행하지 않은 검증 명령이다. Java 17과 기존 의존성 캐시, 격리된 테스트 설정을 사용해 수행해야 한다.

```powershell
node --check src/main/resources/static/js/strategy-workbench.js
node --test --test-concurrency=1 scripts/tests/strategy-workbench.test.cjs scripts/tests/strategy-readiness.browser.cjs scripts/tests/strategy-workbench.browser.cjs
.\scripts\verify-stage9.ps1 -Offline
```

마지막 명령은 기존 `stage9-test` 프로필·고정 외부 응답·테스트 DB를 사용해 Java 테스트와 `bootJar`, 브라우저/격리 서버 회귀를 포함한다. 개별 Gradle 실행은 이 스크립트의 격리 환경 설정을 먼저 재현해야 한다. `*.server.cjs`는 준비된 Java 테스트 서버 없이 독립 실행하지 않는다.

삭제·배포·push·실제 외부 전송·수집 명령은 이번 작업에 필요하지 않아 실행하지 않았다.
