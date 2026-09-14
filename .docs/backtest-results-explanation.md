# 결과 차트와 AI 설명 (6단계)

## 승인 설계와 구현 계획

2026-09-11 사용자가 결과 차트, 거래 번호와 근거, 실행별 AI 설명 생성·저장, 실패 시 수치 조회 유지 설계를 승인했다.

**Goal:** 저장한 실행의 차트·집계·거래 근거와 검증된 AI 설명을 결과 화면에서 조회한다.

**Architecture:** 기존 불변 실행 스냅샷에서 분석 데이터를 만든다. 기존 Codex 구독 연결로 설명 근거를 선택하고 서버에서 수치·거래 참조를 검증해 문장으로 표시한다. 설명은 실행에 종속된 별도 H2 행으로 저장하며 실행 스냅샷과 엔진을 변경하지 않는다.

**Tech Stack:** Java 17, Spring Boot, Jackson, H2, 기존 정적 JavaScript/CSS, 로컬 SVG.

**Spec:** 이 문서의 승인 설계 및 `.docs/project-plan.md` FR-03/04/07. 실행은 `subagent-driven-development`와 TDD를 적용한다.

### 공통 기준

- 거래 수익률 단순 합산·비용/자금 미반영·샤프 비연율화라는 기존 계산 기준을 유지한다.
- 월별 성과는 한국 시간 청산월 기준 완료 거래 수익률 합계다. 일별 계좌 성과로 표현하지 않는다.
- 미청산 포지션은 청산 거래 통계·차트에서 분리한다. 현재 캔들 캐시로 과거 실행을 다시 계산하지 않는다.
- AI가 수치나 시장 원인을 창작하지 못하도록 서버가 만든 근거 ID, 수치, 거래 번호를 선택하는 구조화 응답만 받는다. 화면의 설명 문장은 서버 템플릿과 검증된 근거로 구성한다.
- 뉴스·시장 사건·투자 권유·원화 손익은 생성하지 않는다. 설명 생성 실패가 수치 결과를 차단하지 않는다.
- 기존 Codex 인증·동일 출처·한도 정책을 재사용한다. 새 제공업체/라이브러리/결제 경로를 추가하지 않는다.
- 현재 승인된 체크아웃에서 작업하며 브랜치 전환·커밋·배포는 하지 않는다.

### 1. 실행 분석 API

파일: `backtest/BacktestAnalysis.java`, `backtest/BacktestAnalysisController.java`, `src/test/.../backtest/BacktestAnalysisApiTests.java`.

- [x] 저장 실행으로 `GET /api/backtest/runs/{id}/analysis`를 호출하는 실패 테스트 작성·실행. 손절 -6% 거래, 한국 시간 월 경계, 동일 청산 시점, 거래 없음과 부족 데이터를 확인한다.
- [x] `BacktestAnalysis.from(RunDetail)` 구현. `runId/status/metrics/prices/curve/trades/months/exitReasons/facts` 반환.
- [x] 스냅샷의 캔들/전략이 원본 저장소 변경 후에도 재현되는지 검증한다.

계약: `prices[{timestamp,close}]`, `curve[{tradeNumber,timestamp,sumReturnRate,drawdown}]`, `trades[{number,trade}]`, 집계 `months/exitReasons[{key,closedTrades,winRate,sumReturnRate,tradeNumbers}]`, `facts[{id,value,unit,text,tradeNumbers}]`. 비율은 소수. `curve`는 0번 시작점과 청산 거래마다 한 점을 갖는다.

### 2. 설명 생성·저장 API

파일: `backtest/BacktestExplanation{Service,Entity,Repository}.java`, `strategy/assistant/CodexClient.java`, `strategy/assistant/StrategyAssistantController.java`, `strategy/assistant/StrategyAssistantErrors.java`, `codex/backtest-explanation.schema.json`, 관련 Java 테스트.

- [x] API 테스트에서 `GET/POST /api/backtest/runs/{id}/explanation` 실패 확인.
- [x] AI 응답 `{"highlights":[{"factId":"reason:STOP_LOSS","value":-0.06,"tradeNumbers":[1]}]}`를 해당 실행의 facts와 정확히 대조한다. 존재하지 않는 ID, 다른 수치/거래, 중복/빈 항목, 잘못된 타입/추가 필드는 거절한다.
- [x] 검증된 설명과 모델·생성 시각·데이터 해시를 실행별 한 번 저장한다. 재요청은 저장본을 반환한다. 모델 호출 중 DB 트랜잭션을 유지하지 않는다.
- [x] `NOT_GENERATED/READY/NOT_APPLICABLE` 상태를 제공한다. 실패 응답은 기존 Codex 오류 계약을 따른다. 설명 입력이 너무 크면 명시적 오류를 반환한다.
- [x] 호출 실패·동시 요청·삭제 중 생성·기존 실행/전략 삭제·파일 H2 재시작 검증.

설명 응답: `{runId,status,generatedAt,model,dataSha256,highlights:[{id,value,unit,text,tradeNumbers}]}`. 미생성/적용불가에는 생성 메타데이터가 null이다. POST만 모델 호출, 동일 출처와 `X-Strategy-Local: 1` 필수.

### 3. 결과 화면

파일: `static/js/strategy-results.js`, `static/js/strategy-workbench.js`, `static/css/strategy-workbench.css`, `static/index.html`, `scripts/tests/strategy-results.browser.cjs`.

- [x] 브라우저 실패 테스트로 수치 조회, 차트, 거래 근거, 설명 실패·재시도, 느린 응답 후 실행 전환을 검증한다.
- [x] 로컬 SVG 종가/체결점·누적 수익률·낙폭 차트를 그린다. 거래 번호별 근거 상세, 월별/청산 사유 집계, AI 설명 참조 링크를 제공한다.
- [x] `StrategyResults.mount(container,item)`을 결과 화면에서 호출한다. 기존 수치와 거래 조회를 유지하고 새 요청 실패는 자체 영역에만 표시한다.
- [x] 설명 요청은 전체 작업 화면을 잠그지 않으며 버튼 중복 호출을 막고 이전 실행 응답을 새 실행에 표시하지 않는다.
- [x] 모바일/키보드/HTML 이스케이프와 원격 차트 CDN 실패 시 표시를 확인한다.

### 4. 통합 검증과 문서

- [x] `JAVA_HOME=C:/Program Files/Java/jdk-17`로 `./gradlew.bat test bootJar --offline --console=plain` 실행.
- [x] 기존 Node 테스트와 새 브라우저 테스트, 테스트 H2를 사용하는 실제 HTTP 화면 흐름을 실행한다.
- [x] 가능하면 실제 Codex 설명 생성 프로토콜을 별도 검증하고 결과를 구분해 기록한다.
- [x] README·기획서 진행 기록·이 문서에 API, 계산 기준, 검증 결과와 제한을 기록한다.

## 화면 사용 방법

1. [로컬 대시보드](http://localhost:8090/)에서 저장한 전략 버전으로 백테스트를 실행하거나 이전 실행을 연다.
2. 종가·체결점, 거래 수익률 누적 합계·낙폭과 청산월/청산 사유 집계를 확인한다. 초록 점은 진입, 주황 점은 청산이다.
3. 차트 점 또는 `거래 #번호 근거 보기` 버튼으로 거래 상세를 연다. 신호/체결 시각과 지표의 현재·이전 실제 값, 기준 값, 조건 충족 여부가 표시된다.
4. `AI 설명 생성`을 누른다. 생성 중에도 기존 지표를 읽거나 다른 실행을 조회할 수 있다. 실패하면 설명 영역에서 다시 시도한다.
5. 설명은 실행별 한 번 저장한다. 화면 새로고침과 서버 재시작 후에는 저장본을 읽으며, 모델 설정을 바꾸어도 기존 설명은 보존한다.

## API 계약

- `GET /api/backtest/runs/{id}/analysis`: 해당 실행 스냅샷의 지표·차트·집계·거래 근거. 모델 호출 없음.
- `GET /api/backtest/runs/{id}/explanation`: 저장된 설명 또는 `NOT_GENERATED`, 청산 거래가 없으면 `NOT_APPLICABLE`. 모델 호출 없음.
- `POST /api/backtest/runs/{id}/explanation`: 본문 없이 설명 생성. 이미 저장되었으면 저장본 반환. `X-Strategy-Local: 1` 필수.
- 설명 GET/POST에는 기존 로컬·동일 출처 검사를 적용한다. 존재하지 않거나 삭제한 실행은 `404 NOT_FOUND`다.
- 실행 또는 전략 삭제 시 해당 설명도 DB 외래키의 삭제 연쇄로 제거된다. 생성 중 실행을 삭제하면 응답이 돌아와도 실행이나 설명을 복구하지 않는다.

```powershell
# 1을 실제 저장된 실행 번호로 변경한다.
$runId = 1
Invoke-RestMethod "http://localhost:8090/api/backtest/runs/$runId/analysis"
Invoke-RestMethod "http://localhost:8090/api/backtest/runs/$runId/explanation"
Invoke-RestMethod -Method Post -Headers @{ 'X-Strategy-Local' = '1' } `
  "http://localhost:8090/api/backtest/runs/$runId/explanation"
```

설명 저장 응답의 `highlights`는 모델의 자유 서술이 아니라 **AI가 선택하고 서버가 검증한 근거와 문장**이다. 다음은 손절 거래 1건, 수익률 -6%인 경우의 항목 예시다.

```json
{
  "id": "reason:STOP_LOSS",
  "value": -0.06,
  "unit": "RATE",
  "text": "손절 사유로 청산한 1건의 거래 수익률 합계는 -6.00%입니다. 기록된 청산 사유에 따른 집계입니다.",
  "tradeNumbers": [1]
}
```

## 계산과 설명의 범위

- 수익률 합계는 완료 거래 수익률을 그대로 더한다. 예를 들어 +10%, -20% 거래의 합계는 -10%다.
- 누적 합계의 고점은 0에서 시작한다. 위 예제의 최대 낙폭은 -20%p다. 미실현 손익·복리·투자 비중을 반영한 계좌 낙폭이 아니다.
- 승률은 양수 수익률 거래 수 / 완료 거래 수다. 0% 거래는 승리에 포함하지 않는다.
- 기존 샤프 계산은 거래별 평균(소수 6자리 반올림)을 모집단 표준편차로 나누며 무위험수익률은 0, 연율화는 생략한다. 표준편차가 0이면 기존 엔진은 0을 반환한다.
- 월별 집계는 **한국 시간 청산월**에 해당하는 완료 거래를 묶는다. 보유기간의 일별 손익을 월별로 나눈 값이 아니다.
- 손절 등 청산 사유별 집계는 실제 완료 거래의 사유를 사용한다. 손절 설정값과 체결 수익률이 항상 같다고 가정하지 않는다.
- 가격 차트에는 요청 기간의 저장 캔들만 사용한다. 체결점은 `executionBarTimestamp`로 해당 일봉과 가로 위치를 맞추고 툴팁에는 실제 `executionTimestamp`를 표시한다. 준비 기간 데이터, 미청산 포지션, 대기 주문을 완료 거래 곡선에 섞지 않는다.
- AI 입력에는 실행 조건·실제 거래·조건 관측값·서버 집계를 보낸다. 원문 프롬프트와 전략 이름은 보내지 않는다. AI는 1~8개의 기존 근거 ID를 고르고 수치·거래 번호를 그대로 반환한다. 서버는 참조, 정확한 수치와 거래 번호 목록, 중복 여부를 검사한다.
- AI가 사실의 중요도를 선택할 수는 있지만 자유로운 원인 추론은 제공하지 않는다. 전체 거래와 집계는 AI가 선택하지 않아도 화면에 남는다.

## 오류와 제한

- 잘못된 수치·거래 번호·추가 필드·빈 설명·중복 근거는 `502 CODEX_INVALID_RESPONSE`로 거절하며 저장하지 않는다.
- Codex 미설치·미로그인·한도·시간 초과는 기존 5단계 오류 계약을 재사용한다. API 키나 유료 API로 전환하지 않는다.
- 동시에 하나의 설명 생성 요청을 처리하며 다른 요청은 `429 CODEX_BUSY`를 반환한다. 기존 Codex 연결과도 호출 슬롯을 공유한다. 저장된 설명 조회에는 이 제한이 없다.
- 모델 입력은 최대 160,000자, 응답은 최대 64,000자다. 입력을 넘으면 일부 거래를 숨기지 않고 `422 EXPLANATION_TOO_LARGE`를 반환한다. 전체 수치와 거래 조회는 가능하다.
- `NO_DATA`, `INSUFFICIENT_DATA`, `NO_TRADES` 및 미청산 포지션만 있는 실행은 청산 거래 설명을 생성하지 않는다. 적용 불가 상태를 표시한다.
- 기존 H2 파일 DB에 `backtest_explanation` 테이블을 추가한다. 실행 스냅샷은 수정하지 않는다. 서버 재시작이 필요하며 운영 DB를 테스트 데이터로 변경하지 않는다.

## 검증 실행

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
# Playwright가 설치된 node_modules 경로. 프로젝트에 의존성을 추가하지 않는다.
$env:NODE_PATH = 'C:/Users/EM_NB171/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules'
$env:RUN_WEB_SMOKE = 'true'
$env:RUN_CODEX_LIVE = 'false'
$env:RUN_EXPLANATION_LIVE = 'false'
.\gradlew.bat --gradle-user-home "$env:USERPROFILE/.gradle" test bootJar --offline --console=plain
node --test scripts/tests/strategy-workbench.test.cjs scripts/tests/strategy-workbench.browser.cjs scripts/tests/strategy-results.browser.cjs

# 선택: 합성 가격과 테스트 H2로 실제 Codex 설명 호출. 구독 사용량을 사용한다.
$env:RUN_EXPLANATION_LIVE = 'true'
.\gradlew.bat --gradle-user-home "$env:USERPROFILE/.gradle" test --tests '*BacktestExplanationLiveTests' --offline --console=plain
$env:RUN_EXPLANATION_LIVE = 'false'
$env:RUN_WEB_SMOKE = 'false'
```

화면 테스트는 원격 차트 CDN을 차단한다. 실제 HTTP/DB/엔진을 사용하는 `StrategyWebWorkflowTests`에서 모델 응답만 대체해 실패→재시도→저장→새로고침을 확인한다. 실모델 검증은 `BacktestExplanationLiveTests`로 구분한다.

## 변경 파일

- `src/main/java/com/tossinvest/tossinvestbackend/backtest/BacktestAnalysis.java`, `BacktestAnalysisController.java`: 저장 실행 분석과 결과 API.
- 같은 패키지의 `BacktestExplanationService.java`, `BacktestExplanationEntity.java`, `BacktestExplanationRepository.java`, `SavedBacktestRepository.java`: 검증·저장·조회 및 삭제 경쟁 처리.
- `src/main/java/com/tossinvest/tossinvestbackend/strategy/StrategyApiErrors.java`: 결과 API 오류 계약.
- `src/main/java/com/tossinvest/tossinvestbackend/strategy/assistant/CodexClient.java`, `StrategyAssistantController.java`, `StrategyAssistantErrors.java`, `AssistantException.java`: 기존 구독 연결과 로컬 검사·오류 처리 재사용.
- `src/main/resources/codex/backtest-explanation.schema.json`: 설명 근거 선택 스키마.
- `src/main/resources/static/js/strategy-results.js`, `js/strategy-workbench.js`, `css/strategy-workbench.css`, `index.html`: 차트·집계·근거·설명과 캐시 버전.
- `src/test/java/com/tossinvest/tossinvestbackend/backtest/BacktestAnalysisApiTests.java`, `BacktestAnalysisTests.java`, `BacktestExplanationApiTests.java`, `BacktestExplanationPersistenceTests.java`, `BacktestExplanationLiveTests.java`: 계산·검증·저장·재시작·동시 요청 및 선택 실모델 테스트.
- `src/test/java/com/tossinvest/tossinvestbackend/strategy/assistant/StrategyWebWorkflowTests.java`, `scripts/tests/strategy-workbench.server.cjs`, `scripts/tests/strategy-results.browser.cjs`: 실제 HTTP와 브라우저 검증.
- `README.md`, `.docs/project-plan.md`, `.docs/codex-strategy-web.md`, 이 문서: 사용 방법과 진행 기록.

## 검증 결과 (2026-09-14)

- 전체 Java 테스트: 103개 중 **100개 통과**, 실패·오류 0개. 명시적으로 선택 실행하는 실모델 테스트 3개는 이 실행에서 제외했다. 실제 HTTP/브라우저/H2 통합 테스트는 포함했다. `bootJar` 성공.
- Node/Edge 테스트: **11개 통과**. 기존 전략 화면 동작과 새 차트·근거 참조, AI 실패/재시도, 늦은 응답 후 실행 전환, 빈 데이터, HTML 문자열 처리, 모바일 폭, 일봉과 체결점 정렬을 확인했다.
- 실제 Codex 설명 테스트: 별도로 **1개 통과**. `gpt-5.6-terra`가 합성 손절 거래의 전체 합계·손절 사유·청산월·거래 #1을 선택했다. 수익률 -0.06과 거래 참조를 검증하고 저장·재조회를 확인했다. 원본 결과는 `build/codex-live-explanation.json`에 있다.
- 자동 검색 경로를 사용한 첫 실모델 실행은 `CODEX_NOT_AVAILABLE`이었다. 확인된 설치 경로의 `codex.exe`를 `STRATEGY_CODEX_EXECUTABLE`에 명시한 재검증은 통과했다. 이는 시스템 설정을 변경하지 않은 테스트 프로세스의 환경변수 설정이다.
- 파일 H2를 종료하고 `ddl-auto=validate`로 다시 열어 전략 수정·원본 캔들 삭제 후에도 과거 설명과 근거가 유지되는 것을 확인했다. 모델 응답 대기 중 실행 삭제, 동시 요청 거절, 삭제 후 잠금 해제와 다음 생성도 확인했다.
- 최초 설명 저장 시 `@MapsId`의 새 엔티티 인식 문제와 실제 HTTP 요청에서 불변 엔티티 잠금 승격 문제를 재현·수정했다. DB의 ID 행만 잠가 원본 스냅샷의 불변성을 유지한다.
- 독립 리뷰에서 지적한 일봉/체결 시각의 가로 위치 차이는 회귀 테스트에서 실패를 확인한 뒤 수정했다. 재검토에서 해소되었으며 다른 중대한 지적은 없었다.
- 화면 산출물: `build/strategy-results-desktop.png`, `build/strategy-workbench-desktop.png`, `build/strategy-workbench-mobile.png`, `build/strategy-web-smoke.log`. 데스크톱 차트·집계·설명·거래 근거 화면을 직접 확인했다.

**완료 범위:** 승인된 6단계 결과 차트·근거와 검증된 AI 설명 생성·저장·조회. 실제 금융시장 성과나 투자 수익을 검증한 것이 아니라 합성 거래와 테스트 DB로 기능을 검증했다. 운영 서버 재시작·운영 DB 변경·커밋·배포는 수행하지 않았다. 전략 비교와 전략별 페이퍼 트레이딩은 후속 단계다.
