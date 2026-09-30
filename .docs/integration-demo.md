# 9단계 통합 검증과 재현 가능한 시연

자연어 전략 입력부터 저장, 백테스트, 결과 설명, 전략 비교, 모의매매까지 연결하고 정상·오류·재조회 경계를 확인한다. 발표에서는 같은 입력으로 다시 확인할 수 있는 합성 데이터와 저장된 실행 근거를 사용한다.

[README](../README.md) · [전체 계획](project-plan.md) · [Codex 웹 연결](codex-strategy-web.md) · [결과 차트와 설명](backtest-results-explanation.md) · [전략 비교](strategy-comparison.md) · [전략 모의매매](strategy-paper-trading.md)

## 검증 범위

자동 리허설은 실제 Java 검증기·실행 엔진·Spring API·H2와 실제 브라우저를 연결한다. 자연어 해석과 AI 설명은 고정 모델 응답을 사용하고, 모의매매 종목 정보·시세는 테스트 응답으로 대체한다. 실제 Codex 품질, 토스 인증·실시간 시세 수신을 검증한 것으로 해석하지 않는다.

검증 스크립트는 테스트 전용 메모리 H2와 임시 파일 H2를 사용하는 테스트를 실행한다. 운영 DB를 초기화하거나 개발 서버를 시작·종료하지 않는다. 브라우저 통합 테스트가 생성한 임시 HTTP 서버와 headless Edge는 해당 테스트가 끝나면 종료된다. 명령 종료 뒤 조작할 수 있는 상시 데모 서버는 제공하지 않는다.

## 수용 기준과 검증 연결

아래는 검증 대상과 이를 확인하는 테스트의 연결표다. 통과 여부와 실행일은 문서 마지막의 검증 결과에 기록한다.

| 구간 | 통과 조건 | 주요 검증 근거 |
|---|---|---|
| 자연어 → 구조화 조건 | 해석 결과를 검증하고 사용자 원문을 보존한다. 기간 누락·미지원 조건이 있으면 저장 가능한 초안으로 처리하지 않는다. | `StrategyAssistantTests`, `strategy-workbench.browser.cjs` |
| 조건 검토 → 저장 | 사용자가 검토한 조건을 버전으로 저장하고, 변경한 초안은 다시 확인해야 한다. | `StrategyWebWorkflowTests`, `strategy-workbench.test.cjs` |
| 동일 전략의 전체 연결 | 해석·검증·저장 후 같은 전략 ID와 버전으로 백테스트·분석·비교·모의매매를 연결한다. | `StrategyJourneyIntegrationTests` |
| 백테스트와 근거 | 고정 캔들에서 예상 거래와 수익률을 얻고 당시 조건·입력 데이터·해시를 저장한다. | `StrategyJourneyIntegrationTests`, `UserStrategyBacktestApiTests` |
| 요청 기간과 실제 적용 기간 | 요청 구간 전체를 실행한 것으로 오해하지 않도록 실제 적용 기간과 준비 부족 근거를 표시한다. | `strategy-composition.test.cjs`, `strategy-composition.browser.cjs` |
| 차트와 AI 설명 실패 | 설명 타임아웃에도 수치·차트·거래는 유지된다. 재시도한 설명은 저장된 실행 근거와 일치하고 다시 조회할 수 있다. | `StrategyWebWorkflowTests`, `strategy-results.browser.cjs` |
| 전략 비교 | 같은 유형의 전략이 같은 캔들을 사용한다. 조건 차이에 따른 결과 차이와 미청산 상태를 표시하고 동일 스냅샷을 재조회한다. | `ComparisonApiTests`, `ComparisonWebWorkflowTests` |
| 빈 데이터·잘못된 데이터 | `NO_DATA`, `INSUFFICIENT_DATA`, `FAILED`를 성공한 0% 성과로 표시하지 않는다. 실패한 비교 지표를 임의로 만들지 않는다. | `ComparisonApiTests`, `strategy-comparison.browser.cjs`, `strategy-results.browser.cjs` |
| 거래 없음 | 데이터 없음·지표 준비 부족과 실제 `NO_TRADES`를 구분하여 저장한다. 미청산 포지션이 있는 `COMPLETED`를 거래 없음으로 바꾸지 않는다. | `StrategyPersistenceApiTests.noDataInsufficientDataAndNoTradesAreSavedAsDistinctResults`, `StrategyJourneyIntegrationTests` |
| 모의매매 계좌 시작 | 저장 버전과 명시한 자금·비용으로 독립 계좌를 만든다. 같은 요청 식별자·같은 입력은 기존 실행을 반환한다. | `StrategyJourneyIntegrationTests`, `StrategyPaperApiTests` |
| 모의매매 중복·삭제 | 다른 요청 식별자로 같은 전략·종목의 미종료 실행을 만들면 409다. 미종료 실행이 있으면 전략 삭제도 거절한다. | `StrategyPaperApiTests` |
| 모의매매 갱신·중지 | 확정된 과거 봉만 처리하고 신규 매수 중지 후 대기 매수를 취소한다. 보유분은 원래 청산 조건으로 관리한다. | `PaperMarketDataTests`, `StrategyPaperEngineTests`, `StrategyPaperWebWorkflowTests` |
| 계좌 실패와 복구 | 시세 오류 원인을 구분하고 마지막 계좌를 보존한다. 같은 봉의 재처리·동시 처리로 중복 체결이 생기지 않는다. | `StrategyPaperPersistenceTests`, `strategy-paper.browser.cjs` |
| 파일 DB 재시작 | DB를 닫고 다시 열어도 저장 버전·입력·결과·설명이 유지된다. 원 전략 수정이나 공용 캔들 변경에 따라 과거 결과를 다시 계산하지 않는다. | `StrategyPersistenceRestartTests`, `BacktestExplanationPersistenceTests`, `ComparisonPersistenceTests`, `StrategyPaperPersistenceTests` |
| 회귀와 배포 파일 | 전체 Java·Node 회귀와 `bootJar`가 성공한다. 실제 외부 모델 테스트는 제외 여부를 집계에 명시한다. | Gradle HTML/XML, Node 실행 결과 |

`NO_DATA`는 요청 구간에 사용할 가격이 없는 경우다. `INSUFFICIENT_DATA`는 판단·선정에 필요한 준비 이력이 부족한 경우다. 단일 종목의 `NO_TRADES`는 청산 거래·미청산 포지션·미체결 신호가 모두 없는 경우다. 포지션이 열려 있으면 청산 거래 0건이어도 `COMPLETED`일 수 있다. HTTP 201은 이력 생성 성공을 뜻하며 비교 참여 전략 모두의 성공을 보장하지 않는다.

## 준비 사항

- Windows PowerShell, Java 17, 저장소의 Gradle wrapper.
- `node`가 `PATH`에 있고 `node:test`를 지원하는 Node.js.
- Node에서 불러올 수 있는 `playwright`와 설치된 Microsoft Edge. 기존 실제 서버 테스트는 `channel: 'msedge'`를 사용한다.
- Gradle 의존성. `-Offline`은 필요한 의존성이 캐시에 있을 때만 사용한다.
- 저장소 루트에서 실행한다. 테스트 결과·로그·스크린샷은 `build/`에 생성된다.

자동 리허설에는 Codex 로그인, OpenAI API 키, 토스 시세 인증 정보가 필요하지 않다. 스크립트가 `stage9-test` 프로필을 선택해 `application-local.yaml`을 로드하지 않고, OAuth 빈 생성에 필요한 두 값에는 사용하지 않는 테스트 문자열을 넣는다. 토스 API 기본 주소는 `http://127.0.0.1:1`로 제한하며 전략 모의매매 예약 실행도 끈다. 해석·설명은 고정 응답으로 대체되며 기존 계좌나 저장 전략을 테스트 입력으로 사용하지 않는다. 설정은 이 스크립트와 하위 프로세스에 적용되고 종료 시 복원된다.

## 자동 리허설 실행

의존성이 이미 준비된 환경:

```powershell
.\scripts\verify-stage9.ps1 -Offline
```

런타임 위치가 다른 환경:

```powershell
.\scripts\verify-stage9.ps1 `
    -JavaHome 'C:/Program Files/Java/jdk-17' `
    -NodeModules 'C:/path/to/node_modules' `
    -GradleUserHome "$env:USERPROFILE/.gradle" `
    -Offline
```

`-JavaHome` 기본값은 `C:/Program Files/Java/jdk-17`이다. `-NodeModules`는 `playwright` 폴더를 포함하는 `node_modules` 디렉터리를 받는다. 생략하면 사용 가능한 bundled 경로를 탐색하며, 환경마다 설치 위치가 다르면 명시한다. `-GradleUserHome`은 필요한 경우 지정한다. 캐시가 없으면 `-Offline`을 빼고 의존성을 받아야 한다.

스크립트는 다음 순서로 실행한다.

1. Java·Node·Playwright 등 실행 전제 조건을 확인한다.
2. `RUN_WEB_SMOKE=true`를 설정한다. `RUN_CODEX_LIVE`, `RUN_EXPLANATION_LIVE`, `RUN_STRATEGY_BATCH_LIVE`, `RUN_STRATEGY_EXPANSION_LIVE`, `RUN_STRATEGY_PORTFOLIO_LIVE`는 모두 `false`로 고정한다.
3. Gradle의 `test bootJar --rerun-tasks`를 실행한다. 환경변수만 달라졌을 때 이전 테스트 결과가 그대로 재사용되지 않도록 다시 실행한다.
4. `scripts/tests/`의 `*.test.cjs`와 `*.browser.cjs`만 Node로 실행한다. `*.server.cjs`는 Java 테스트가 임시 URL과 전략 ID를 전달하여 실행한다.
5. 각 단계의 종료 코드를 확인한다. 실패가 있으면 성공으로 마무리하지 않고 해당 로그와 테스트 보고서를 확인한다.

`node --test scripts/tests/*.cjs`처럼 모든 파일을 한 번에 실행하지 않는다. `*.server.cjs`는 Java가 만든 서버·데이터가 없으면 실행할 수 없다. 자동 리허설의 headless 실행은 발표 중 수동 조작을 기다리지 않는다.

## 고정 시나리오와 예상 결과

### A. 동일 저장 전략을 끝까지 연결

고정 입력은 [전략 A](../src/test/resources/demo/strategy-a.json), [전략 B](../src/test/resources/demo/strategy-b.json), [합성 일봉 CSV](../src/test/resources/demo/candles.csv)다. `StrategyJourneyIntegrationTests`가 이 파일을 읽어 검증한다.

| 항목 | 전략 A | 전략 B |
|---|---|---|
| 이름 | 시연 A · 거래량 1배 · 손절 5% | 시연 B · 거래량 1배 · 손절 20% |
| 형식 | `schemaVersion=1` | `schemaVersion=1` |
| 진입 | 당일 거래량 ≥ 직전 1봉 평균 거래량 × 1 | 동일 |
| 손절 | 진입가 대비 종가 하락률 5% | 진입가 대비 종가 하락률 20% |

종목은 `005930`이며 입력은 다음 네 봉이다. 각 봉의 시가·고가·저가·종가를 같은 가격으로 두었다.

| 날짜 | 가격 | 거래량 | 용도 |
|---|---:|---:|---|
| 2025-01-01 | 100 | 100 | 직전 거래량 준비 |
| 2025-01-02 | 100 | 100 | 진입 조건 충족 |
| 2025-01-03 | 94 | 100 | A의 손절 조건 충족 |
| 2025-01-04 | 93 | 100 | 남은 조건·포지션 처리 |

실행 기간은 `2025-01-02`부터 `2025-01-04`, 체결 방식은 `SAME_DAY_CLOSE`다. A의 청산 거래는 1건, 청산 거래 수익률 합계는 **-6%**다. B의 청산 거래는 0건이며 포지션은 미청산 상태다. B의 청산 거래 수익률 합계 **0%**를 계좌 손실이 없다는 뜻으로 설명하면 안 된다. 이 단일 종목 백테스트는 자금·수수료·세금·슬리피지를 모델링하지 않는다.

CSV는 테스트용 합성 가격이며 실제 삼성전자 시세가 아니다. 달력 날짜를 연속 사용하므로 실제 KRX 거래일 달력을 재현한 자료도 아니다. 테스트가 fixture를 전용 DB에 넣으며 운영 캔들 가져오기 기능으로 사용하지 않는다.

같은 저장 ID와 버전으로 분석·비교·모의매매 연결을 확인한다. 새 모의매매는 생성일 다음 한국 날짜부터 판단하므로 위 과거 CSV를 현재 계좌의 실시간 매매로 소급 처리하지 않는다. 모의매매 갱신은 준비 데이터 수신과 `WAITING_DATA`, 중복 요청 경계, 중지·재조회를 확인한다.

### B. 자연어 화면과 설명 실패·복구

`StrategyWebWorkflowTests`와 `strategy-workbench.server.cjs`는 다음 과정을 자동 조작한다.

1. `SMA 5일/20일 골든크로스에 매수하고 5% 손절한다.` 입력 → **Codex로 조건 해석**.
2. 원문·조건·위험 관리 설정 검토 체크 → **새 전략으로 저장**.
3. `005930`, `2025-01-01~2025-03-01`, `NEXT_DAY_OPEN` → **선택 버전 백테스트 실행**.
4. 합성 일봉 40개에서 청산 거래와 종가·체결점 등 결과 차트 3개를 확인한다.
5. 첫 **AI 설명 생성**은 고정 `CODEX_TIMEOUT` 응답이다. 숫자·차트가 그대로 남는지 확인한다.
6. **AI 설명 다시 시도**로 성공 응답을 저장한다. 거래 근거를 펼치고 새로고침 후 같은 실행·설명을 재조회한다.

이 시나리오는 A의 네 봉과 별도 데이터다. 그래프 형태나 거래 수를 A의 예상값과 섞지 않는다. 자연어를 실제 모델이 매번 같은 결과로 해석한다는 증거도 아니다.

### C. 비교 화면과 계좌 화면

`ComparisonWebWorkflowTests`는 A와 같은 네 봉·손절 조건으로 **통합 손절 5%**, **통합 손절 20%**라는 별도 저장 전략을 만든다. 각 버전에서 **선택 버전을 비교에 추가** → 공통 조건 입력 → **비교 실행 및 저장** → -6%/0%, 청산 1건/0건 확인 → 새로고침 후 비교 이력 재조회 순서다.

`StrategyPaperWebWorkflowTests`는 **일봉 손절** 버전을 선택하고 **이 버전으로 모의매매**로 이동한다. `005930`, `NEXT_DAY_OPEN`, 초기자금 1,000,000원, 매수 비중 50%, 수수료율 0.015%, 매도세율·슬리피지율 0%를 명시한다. 시작·갱신·거래 내역 조회 후 **신규 매수 중지**, 종료 이력 재조회를 확인한다.

페이퍼 화면의 고정 데이터는 어제 봉 한 개다. 첫 판단 가능일 전 자료이므로 즉시 거래가 없고 현금이 1,000,000원으로 유지되는 것이 정상이다. 이 테스트는 보유 포지션이 없어 바로 종료된다. 보유 중인 실제 실행의 중지는 청산 조건을 기다리며 `STOPPING` 상태를 유지할 수 있다.

### D. 오류·보존 경계

- 필요한 기간이 빠졌거나 미지원 조건이 있으면 보완 후 다시 해석한다. 부분 조건만 저장 가능한 전략으로 자동 확정하지 않는다.
- 가격 없음은 `NO_DATA`, 준비 부족은 `INSUFFICIENT_DATA`, 잘못된 가격은 `FAILED`와 `INVALID_DATA` 근거로 확인한다. 비교의 실패 지표·곡선을 0%로 채우지 않는다.
- 같은 모의매매 요청 식별자에 같은 숫자 값을 소수 자릿수만 달리 보내도 기존 실행을 반환한다. 같은 식별자에 다른 설정, 또는 다른 식별자로 같은 전략·종목의 미종료 실행을 만들면 409다.
- 모의매매 시세 HTTP 400, 401/403, 429, 5xx를 각각 요청·인증·호출 제한·연결/서버 오류로 구분하고 계좌를 보존한다.
- 전략 수정·공용 캔들 삭제 이후에도 저장 결과가 같아야 한다. 파일 H2 종료·재연결 검증은 테스트 임시 디렉터리에서 수행하며 실제 앱을 재시작하지 않는다.
- 비교 이력 단독 삭제는 전략을 보존한다. 참여 전략 삭제는 관련 비교 이력을 삭제한다. 종료된 모의매매는 원 전략 삭제 뒤에도 이력을 보존한다.

## 발표 진행안: 9분

발표 전에 자동 리허설을 실행하고 보고서·스크린샷을 열어 둔다. 아래 진행안은 재현된 화면과 증거를 설명하는 순서이며 실행 중인 수동 데모 서버를 전제로 하지 않는다.

| 시간 | 제시할 자료 | 설명할 내용 |
|---|---|---|
| 0:00~0:45 | 이 문서의 검증 범위 | “자연어를 구조화 조건으로 검증하고, 매매 판단은 Java 엔진이 수행합니다. 오늘의 입력은 합성 데이터이며 외부 모델·시세 응답을 고정했습니다.” |
| 0:45~2:00 | 전략 화면·결과 화면 캡처 | 원문, 해석된 조건, 사용자 확인, 저장 버전을 짚는다. 단일 전략의 저장→실행→이력 연결을 설명한다. |
| 2:00~3:15 | 고정 CSV와 비교 곡선 | 가격 100→94에서 5% 손절 조건이 충족되어 -6%에 청산된다는 계산을 보여 준다. 요청 기간·실제 데이터 범위와 체결 방식을 확인한다. 40봉을 쓰는 별도 자연어 화면의 수익률과 섞지 않는다. |
| 3:15~4:00 | `strategy-web-smoke.log`, 설명 캡처 | AI 설명이 실패해도 수치가 사라지지 않으며 재시도 결과를 저장한다. 저장 설명 재조회와 새 모델 호출을 구분한다. |
| 4:00~5:15 | 비교 데스크톱 캡처 | 같은 네 봉에서 조건만 다른 두 전략을 비교한다. -6%/0%는 청산 거래 합계이며 미청산 포지션을 별도로 확인한다고 설명한다. |
| 5:15~6:30 | 페이퍼 화면 캡처 | 버전·계좌·비용을 명시하고 첫 판단 가능일을 보여 준다. 거래 없음이 정상인 이유, 신규 매수 중지와 전량 즉시 매도의 차이를 설명한다. |
| 6:30~8:00 | 수용 기준 표·Gradle 보고서 | 빈 데이터, 잘못된 입력, 중복 계좌, 파일 DB 재시작·동시 갱신을 확인한 테스트를 짚는다. GUI 재조회와 실제 파일 DB 재연결은 별도 검증임을 밝힌다. |
| 8:00~9:00 | 검증 결과·제한 | 이번 실행의 통과·실패·건너뜀 수와 날짜를 읽는다. 실제 모델·시세·장기 예약 실행의 미검증 범위를 설명한다. |

발표 직전에 새 전체 빌드를 시작해 완료를 기다리는 구성은 피한다. 최신 성공 결과를 준비하고, 필요하면 `verify-stage9.ps1`의 동일 명령과 테스트 입력 파일을 보여 주어 재현 방법을 설명한다.

## 발표 질문: 실제로 무엇을 저장하며 어떻게 실행하는가?

### 1. 실제 저장하는 입력 정보

사용자가 입력한 문장과 최종 실행 조건을 함께 보존한다. 원문은 의도를 설명하는 자료이고, 실제 매매 판단에는 사용자가 검토한 구조화 조건을 사용한다.

| 구분 | 저장 항목 | 저장 위치 |
|---|---|---|
| 전략 식별 | 전략 ID, 현재 버전, 최초 생성·최근 변경 시각 | `saved_strategy` |
| 버전별 입력 | 이름 `name`, 원문 `originalPrompt`, 전략 형식 `schemaVersion`, 진입 `entry`, 청산 `exit`, 위험 관리 `risk` | `saved_strategy_version.definition_json` |
| 확장 전략 입력 | 포트폴리오 `portfolio`, 조합 `composition`의 출처 전략·조건 결합·배분·보완 근거 등 해당 형식의 데이터 | 같은 버전의 `definition_json` |
| 단일 종목 백테스트 실행 입력 | 선택 전략 ID·버전과 정의 사본, 종목·시작일·종료일·체결 방식 | `saved_backtest.snapshot_json` 내부 `strategy`, `execution` |
| 단일 종목 재현용 데이터 | 실제로 엔진에 전달한 지표 준비 봉을 포함한 일봉 OHLCV 사본, 출처·주기·시간대·SHA-256 | 같은 스냅샷의 `data` |
| 단일 종목 실행 결과 | 실행 상태·시각, 엔진 버전, 비용·자금 적용 여부, 지표·거래·판정 근거·미청산 포지션·오류 | 같은 스냅샷과 실행 행 |
| 다른 유형의 실행 이력 | 해당 유형의 실행 당시 입력·데이터·결과 스냅샷 | 포트폴리오 `portfolio_run.snapshot_json`, 조합 `composite_run.snapshot_json` |

`schemaVersion`은 JSON 전략 형식의 버전이고, 저장 전략의 `version`은 사용자가 수정해 저장한 이력 번호다. 예를 들어 형식 4의 전략도 최초 저장 버전은 v1이다. 조건 수정 저장 시 기존 버전을 덮어쓰지 않고 새 버전을 추가한다.

일반 자연어 해석에서 보완 입력이 있으면 서버가 원문 뒤에 `[보완 입력]` 구분자로 합쳐 `originalPrompt`에 넣는다. 여러 전략 입력·조합은 해당 초안의 원문과 출처를 보존한다. 일반 화면의 검토 체크(`confirmed`)는 저장 버튼을 제어하는 화면 상태이며, 확인자·확인 시각을 별도 감사 이력으로 저장하는 기능은 없다. 조합 형식 안의 `riskConfirmed`, `exitConfirmed`는 전략 정의에 속한 별도 확인 값이다.

단일 종목 백테스트의 비용은 `NOT_MODELED`, 수수료·세금·슬리피지 수치는 0, 초기자금은 null로 기록한다. 이는 비용이 실제로 없다는 뜻이 아니라 계산 모델에 반영하지 않았다는 뜻이다. 자금과 비용을 명시하는 포트폴리오·조합·모의매매와 구분한다.

**현재 앱에서 확인한 예시(2026-09-30, 읽기 전용 조회):**

- 저장 전략 **#8 / v1**, 이름 **[화면 검증] 초안8 · 20일/52주/SMA OR 조합**: 형식 4, 출처 전략 3개, 공통 손절 -5%, 최고 종가 대비 추적손절 -20%가 저장돼 있다.
- 저장 전략 **#9 / v1**, 이름 **초안8 비교용 · 추적손절 10%**: 원 전략의 입력과 변경 이유를 `originalPrompt`에 보존하고, 최종 공통 추적손절을 -10%로 저장했다. 원문에 등장하는 과거 -20%와 최종 실행 조건 -10%를 구분할 수 있다.
- 확인 요청은 `GET /api/strategies?page=0&size=2`이며 생성·수정·백테스트·모의매매 실행 요청은 보내지 않았다. 위 ID는 해당 날짜 로컬 앱의 예시이고 자동 시연 fixture의 ID가 아니다.

구현 근거: [전략 모델](../src/main/java/com/tossinvest/tossinvestbackend/strategy/StrategyDefinition.java), [버전 저장](../src/main/java/com/tossinvest/tossinvestbackend/strategy/SavedStrategyService.java), [백테스트 스냅샷](../src/main/java/com/tossinvest/tossinvestbackend/backtest/SavedBacktestService.java).

### 2. AI 해석 → 검토 → 저장 → 백테스트

1. **입력:** 사용자가 매수·매도 조건을 문장으로 입력한다. 예: “종가 기준 SMA 5일선이 SMA 20일선을 상향 돌파하면 매수하고 5% 손실이면 손절한다.”
2. **AI 해석:** Codex가 문장을 전략 JSON 초안으로 변환한다. 이 단계에서는 전략을 DB에 저장하거나 백테스트하지 않는다.
3. **서버 검증:** 조건 종류·기간·값 범위·지원 여부를 검사한다. “골든크로스”처럼 기간이 빠진 입력은 보완 질문을 반환하며 실행 가능한 전략으로 자동 확정하지 않는다.
4. **사용자 검토:** 화면에서 원문·진입·청산·위험 관리를 확인한다. 수정하면 확인 상태가 해제되며 다시 조건 검증과 확인을 거쳐야 저장할 수 있다.
5. **저장:** 새 전략은 v1로 저장하고, 기존 전략 수정은 새 버전으로 저장한다. 서버도 저장 직전에 조건을 검증한다. 일반 검토 체크 자체를 서버의 인증·감사 기록으로 간주하지 않는다.
6. **실행:** 저장 버전, 종목, 기간, 체결 방식을 선택한다. 해당 형식의 Java 엔진과 공통 전략 판정기가 일봉을 평가하고 진입·청산 근거를 기록한다. AI를 호출해 매수·매도 여부를 결정하지 않는다.
7. **조회:** 실행 당시 입력·가격·결과를 스냅샷으로 저장한다. 이력 조회는 이 사본을 읽으므로 현재 전략이나 캔들이 달라져도 과거 결과는 바뀌지 않는다. 별도로 요청한 AI 설명이 실패해도 숫자·거래는 조회할 수 있다.

**발표용 답변:**

> 사용자가 입력한 원문과 이름, AI 해석 후 검토한 매수·매도·위험 관리 조건을 버전별로 저장합니다. AI는 문장을 조건 데이터로 바꾸고, 서버가 이를 검증한 뒤 사용자가 확인해야 화면에서 저장할 수 있습니다. 백테스트는 선택한 버전과 종목·기간·체결 방식으로 Java 엔진이 실행합니다. 실행 당시의 전략과 가격 데이터, 거래 근거를 함께 저장하기 때문에 나중에 전략을 수정해도 과거 결과를 설명할 수 있습니다.

## 증거 파일

| 증거 | 경로 |
|---|---|
| 이번 실행의 UTC 시각·최종 상태·Java/Node 집계 | `build/stage9-verification.json` |
| 전체 Java·JAR 빌드 로그 | `build/stage9-java.log` |
| 전체 Node 회귀 로그 | `build/stage9-node.log` |
| 전체 Java HTML 결과 | `build/reports/tests/test/index.html` |
| 테스트별 실제 실행·실패·건너뜀 수 | `build/test-results/test/TEST-*.xml` |
| 전체 연결 테스트 XML | `build/test-results/test/TEST-com.tossinvest.tossinvestbackend.integration.StrategyJourneyIntegrationTests.xml` |
| 자연어·백테스트·설명 웹 로그 | `build/strategy-web-smoke.log` |
| 자연어 화면 캡처 | `build/strategy-workbench-desktop.png`, `build/strategy-workbench-mobile.png` |
| 결과 차트·설명 캡처 | `build/strategy-results-desktop.png` |
| 조합 실행 로그·화면 | `build/strategy-composition-web.log`, `build/strategy-composition-server.png` |
| 비교 웹 로그·화면 | `build/strategy-comparison-web.log`, `build/strategy-comparison-server-desktop.png`, `build/strategy-comparison-server-mobile.png` |
| 페이퍼 웹 로그·화면 | `build/strategy-paper-web.log`, `build/strategy-paper-desktop.png`, `build/strategy-paper-mobile.png` |
| 실행 가능한 JAR | `build/libs/toss-invest-backend-0.0.1-SNAPSHOT.jar` |

`build/` 파일은 재실행 때 갱신된다. 파일이 존재한다는 사실만으로 이번 실행이 통과한 것은 아니다. 명령의 종료 코드, 실행 시각, XML 집계와 함께 확인한다. 실패한 실행에서는 이전 스크린샷이 남을 수 있으며, 스크린샷만으로 API·재시작 검증 통과를 주장하지 않는다.

## 선택 사항: 실제 로컬 앱에서 직접 시연

자동 리허설과 별개다. 본인의 앱에 이미 준비된 데이터·저장 전략·인증을 사용하며, 저장·실행·시작 버튼은 해당 앱의 DB에 기록을 만든다. 자동 검증 스크립트는 이 절차를 실행하지 않는다.

1. [Codex 웹 연결 안내](codex-strategy-web.md)에 따라 본인 PC의 서버·브라우저를 준비한다. 실제 해석·설명에는 ChatGPT 로그인된 Codex와 구독 사용 한도가 적용된다. OpenAI API 키로 전환하지 않는다.
2. 자연어를 입력하고 해석 결과의 조건을 직접 검토한다. 저장할 전략 이름과 버전을 확인한다.
3. 본인 DB에 일봉이 있는 종목·기간으로 백테스트한다. 실제 데이터가 없으면 `NO_DATA`가 정상이며 이 문서의 -6% 결과를 기대하지 않는다.
4. [비교 안내](strategy-comparison.md)에 따라 같은 유형의 저장 전략 2개 이상을 선택한다. 공통 기간·체결 방식과 실제 적용 범위를 확인한다.
5. [모의매매 안내](strategy-paper-trading.md)에 따라 국내 종목·자금·비중·비용을 명시한다. 시작에는 종목 정보 확인, 갱신에는 시세 제공업체 접근이 필요하다.
6. 시작 직후에는 첫 판단 가능일과 데이터 대기 상태를 보여 준다. 당일·미래 봉을 확정된 거래처럼 표시하거나 기다림을 없애기 위해 시작일·DB를 수정하지 않는다.

실제 앱의 숫자·전략 ID·상태는 데이터와 실행 시각에 따라 달라진다. 저장 이력만 재조회하는 단계는 새 설명 생성·해석과 달리 모델 호출이 필요 없다.

## 미검증 범위와 남은 제한

- 이번 고정 리허설은 실제 Codex 응답 품질·지연·구독 제한과 실제 토스 인증·제공업체 페이지 응답을 확인하지 않는다.
- 장기간 예약 실행, 휴장·거래정지·시세 수정·주식 분할에 따른 실환경 계좌 조정은 이 시연만으로 보증하지 않는다.
- 모의매매는 형식 1·2의 국내 단일 종목 확정 일봉 범위다. 실제 주문, 포트폴리오·조합의 모의매매는 포함하지 않는다.
- 단일 종목 백테스트의 수익률 단순 합계·낙폭(%p)와 계좌형 결과의 수익률·일별 최대 낙폭은 다른 지표다. 유형 간 자동 순위·추천은 제공하지 않는다.
- 자동 브라우저 검증은 원격 CDN·계좌 등 관련 없는 요청을 차단한다. 전체 운영 대시보드의 모든 외부 연결을 검증한 결과가 아니다.
- 합성 데이터 테스트의 성공은 투자 수익성이나 실제 체결 가능성을 증명하지 않는다.

## 검증 결과

2026-09-30, Java 17·Node 24.13.0·설치된 Playwright/Edge 환경에서 아래 명령을 실행했다. 최종 실행은 `stage9-test` 프로필과 사용하지 않는 OAuth 값으로 로컬 인증 설정 없이 검증했다.

```powershell
.\scripts\verify-stage9.ps1 -Offline
```

| 검증 | 결과 |
|---|---|
| 전체 Java | **195개 중 189 통과, 6 건너뜀, 실패·오류 0** |
| 같은 저장 ID·버전의 전체 연결 | 신규 `StrategyJourneyIntegrationTests` 1개 통과(위 Java 집계에 포함) |
| 실제 HTTP·H2·브라우저 | 자연어·결과, 조합, 비교, 모의매매의 4개 `WebWorkflowTests` 모두 실행·통과(위 Java 집계에 포함) |
| Node 단위·화면 회귀 | **32개 통과, 실패·건너뜀 0** |
| 배포 JAR | `bootJar` 성공, Gradle **BUILD SUCCESSFUL**, 7개 작업 재실행 |
| 형식·문서 확인 | PowerShell 구문 검사, `git diff --check`, README·상세 문서·기획서의 상대 링크 확인 통과 |
| 화면 확인 | 비교 데스크톱의 -6%/0%·청산 1/0건, 결과 차트·설명·거래 근거, 모바일 모의매매의 계좌·대기·종료 표시 캡처 확인 |

건너뛴 6개는 실제 Codex를 호출하는 `CodexLiveTests` 2개와 `BacktestExplanationLiveTests`, `StrategyBatchLiveTests`, `StrategyExpansionLiveTests`, `StrategyPortfolioLiveTests` 각 1개다. 외부 연동을 통과한 수에 포함하지 않는다. 최종 스크립트 종료 코드는 0이며 `build/stage9-verification.json`의 `status`는 `PASSED`다.

첫 스크립트 실행에서는 `PATH`에 Node 실행 파일이 두 개 있을 때 경로 배열이 만들어지는 문제를 확인해 첫 실행 파일을 선택하도록 수정했다. 최종 명령 전체를 다시 실행해 위 결과를 얻었다. 실제 Codex·토스 연동과 장기간 예약 실행은 이번 단계에서 재검증하지 않았다.

## 이번 단계의 변경 파일

- `README.md`: 실제 설정 이름, 8·9단계 사용법과 통합 검증 명령·문서 연결.
- `.docs/project-plan.md`: 현재 검증 범위에 맞춘 완료 점검표와 9단계 진행 기록.
- `.docs/integration-demo.md`: 수용 기준, 입력 자료, 발표 순서, 저장 구조·처리 흐름 질의응답과 검증 결과.
- `scripts/verify-stage9.ps1`: 실행 전제 확인, 격리 설정, 전체 검증과 결과 집계, 환경변수 복원.
- `src/test/java/com/tossinvest/tossinvestbackend/integration/StrategyJourneyIntegrationTests.java`: 하나의 저장 전략이 기능 사이를 이동하는 전체 연결 검증.
- `src/test/resources/demo/strategy-a.json`, `strategy-b.json`, `candles.csv`: 위 테스트가 직접 읽는 고정 시연 입력.

기존 8단계 변경은 유지했으며 이 단계에서 제품 실행 코드는 수정하지 않았다.
