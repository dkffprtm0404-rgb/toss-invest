# 국내주식 상대강도 포트폴리오 — 3단계

2026-09-15 사용자의 남은 단계 계속 진행 요청에 따른 구현 명세. 기존 형식 1/2 개별 종목 전략과 이력을 유지하며 형식 3의 상대강도 포트폴리오를 추가한다.

## 계약

- 자연어 → StrategyDefinition → 서버 검증 → 저장 버전 → Java 실행 경계를 유지한다. API 과금 전환, 실제 주문, 기존 서버 재시작은 하지 않는다.
- portfolio에는 대상 시장(KOSPI/KOSDAQ/통합), 수익률 달력 개월 수, 상위 종목 수, SMA 필터 기간, 필터/순위 적용 순서, 주간 첫/마지막 거래일, 동일 비중 방식을 명시한다. 누락값은 보완 대상이다. 개별 조건 entry/exit와 혼용하지 않으며 포트폴리오 위험 관리는 고정 손절만 지원한다.
- 일별 거래 캘린더와 종목별 시장·유효 시작/종료일, 데이터 출처를 실행 입력으로 받는다. 로컬 캔들에 시장 이력이 없으므로 현재 목록을 과거 전체 시장으로 자동 간주하지 않는다. 입력 자료의 완전성은 사용자 자료에 달려 있으며 범위와 제외 근거를 결과에 명시한다.
- 순위는 해당 날짜까지의 종가 수익률이다. N개월 전 날짜 이하 마지막 입력 거래일의 종가를 분모로 사용한다. SMA는 해당 날짜까지 최근 N거래일 종가 평균이다. 필요한 거래일 캔들이 없거나 현재 거래량이 0이면 후보에서 제외하고 이유를 기록한다. 동률은 종목 코드 오름차순이다.
- 순위일은 입력 캘린더의 주 첫/마지막 거래일이다. 캘린더는 계산 준비기간부터 실행 종료 후 다음 주까지 포함해야 하며, 실행 종료 이후 가격은 사용하지 않는다. 다음 거래일 시가 또는 당일 종가 체결은 실행 시 선택한다.
- 동일 비중은 계좌 평가액 / topN을 종목별 목표 금액으로 한다. 후보가 부족하면 남은 몫은 현금이다. 정수 주수 내림, 차입·공매도 없음. 매도 후 순위 순서로 현금 범위에서 매수한다. 추가 매수는 평균 매입단가를 갱신하며 일부 매도는 단가를 유지한다.
- 초기자금, 매수·매도 수수료율, 매도세율, 불리한 방향 슬리피지율은 실행 입력의 필수값이다. 실제 제도나 중개사 요율의 자동 기본값을 넣지 않는다. 거래량 규모·상하한가 체결 제약·배당·기업행위는 모델링하지 않는다.
- 고정 손절은 보유 평균 매입가격 대비 종가로 매일 판단한다. 손절과 리밸런싱 신호가 같은 날이면 해당 종목을 목표에서 제외한다. 손절한 당일 재매수하지 않으며 다음 주 순위에서 다시 선정될 수 있다.
- 보유 종목의 평가 가격이 없거나 필요한 주문 체결 봉 거래량이 0이면 부분 결과를 성공으로 저장하지 않고 실패 근거를 저장한다. 미보유 후보의 불충분 이력은 순위에서 제외한다. 종료일 보유분은 평가하며 강제 매도하지 않는다. 다음 체결일이 없으면 미체결 신호를 표시한다.
- 원본 종목 목록·캘린더·캔들·전략 버전·비용 설정·계좌 곡선·순위·거래를 별도 포트폴리오 실행 이력으로 저장한다. 이력 읽기는 다시 계산하지 않는다. 전략 삭제 시 연결 이력도 삭제한다.

## 구현 계획

**Goal:** 상대강도 전략을 해석·편집·저장한 뒤 국내주식 데이터로 주간 포트폴리오 백테스트하고 거래/순위/자산 이력을 조회한다.

**Architecture:** StrategyDefinition의 선택 portfolio 필드와 형식 3을 추가한다. portfolio 패키지의 실행 요청/순위·회계 엔진/저장 서비스/컨트롤러가 기존 CandleRepository와 SavedStrategyService를 재사용한다. 화면은 기존 초안·저장 흐름에 포트폴리오 편집과 실행 입력 및 결과 패널을 연결한다.

**Tech Stack:** Java 17, Spring Boot, Jackson, JUnit, H2, 기존 JavaScript/Playwright. 새 외부 의존성 없음.

**Spec:** 이 문서의 계약. 기존 변경 사항과 현재 작업 브랜치를 유지하며 자동 커밋하지 않는다.

- [x] 계약 테스트: 형식 3 상대강도 JSON 저장 가능, 필수값 누락/개별 조건 혼용/잘못된 위험 관리 거절, 단일 종목 실행 경로 차단.
- [x] 계산 테스트: 달력 6개월, SMA/순위 순서, 동률, 미래/미편입 종목 제외, 주간 휴일, 다음 시가, 비용·수량·현금, 일별 손절, 데이터 누락, 종료 미체결.
- [x] 포트폴리오 엔진과 실행 입력 검증 구현.
- [x] API 테스트: 저장 버전 실행·불변 이력·삭제, 입력 오류, 데이터 실패 저장. 별도 테이블로 개별 종목 이력 보존.
- [x] Codex 스키마·지시문, 초안 편집·실행·결과/이력 화면 연결. 원래 상대강도 문장은 필수 실행 정책을 보완 후 저장 가능.
- [x] 브라우저 통합과 실제 Codex 보완 전략 해석 확인.
- [x] 전체 Java/Node 검증, 직접 코드 검토, 사용법·데이터 예제·검증 기록 완성.

## 사용 방법

1. 서버를 재시작하고 브라우저를 강력 새로고침한다. 기존 DB에는 `portfolio_run` 이력 테이블이 추가된다. 이번 작업 중 실행 중인 사용자 서버나 파일 DB를 직접 변경하지 않았다.
2. 상대강도 전략을 다른 전략과 함께 입력하거나 아래의 명시적 예제를 입력한다.
3. 해당 초안을 선택하고 시장·순위/필터 순서·주간 계산일·비중 정책을 확인한다. 누락된 정책은 보완 입력으로 답해 다시 해석한다. 필수값을 자동으로 채우지 않는다.
4. 확인 후 저장하면 실행 화면에 초기자금·수수료·매도세·슬리피지와 시장 자료 입력란이 표시된다. 비용률은 퍼센트로 입력하며, 미적용을 선택하려면 `0`을 명시한다.
5. 실제 자료로 작성한 시장·거래일 JSON을 붙여 넣거나 파일로 선택한다. 종목별 일봉은 기존 CSV 적재 경로로 DB에 준비한다. 시작/종료일과 체결 방식을 선택해 실행한다.
6. 일별 계좌 평가액, 현금, 매매 수량·비용, 주간 선정·제외 근거와 미체결 목표를 확인한다. 저장 이력은 이후 캔들이 변경되어도 다시 계산하지 않는다.

명시적 예제(아래 정책은 이 예제의 선택이며 원래 입력의 기본값이 아니다):

> 국내 KOSPI와 KOSDAQ 통합 상대강도 전략. 종가가 200거래일 SMA 위인 종목을 먼저 걸러서 달력 6개월 종가 수익률 상위 10종목을 선정한다. 매주 마지막 거래일 종가로 순위를 계산하고 주간 순위에서 밀리면 매도한다. 각 종목은 계좌의 1/10 동일 비중으로 조정하며 10개보다 적으면 남은 몫은 현금이다. 평균 매입가격 대비 -8% 손절은 매일 종가로 판단한다. 다른 진입·청산 조건은 없다. 체결 방식, 초기자금, 비용, 시장 구성 자료는 실행 화면에서 지정한다.

## 시장 자료

`universe`는 다음 필드를 가진다.

- `source`: 자료 출처·구성 범위를 설명하는 문자열. 수집일과 시점별 이력인지 고정 목록인지도 기록한다.
- `tradingDates`: 실제 거래일의 날짜 문자열 배열(`YYYY-MM-DD`). 중복 없이 오름차순, 준비 이력부터 종료일 7일 이후까지 포함한다. 미래 날짜는 주간/휴일 경계만 결정하며 미래 가격은 읽지 않는다.
- `members`: `{symbol, market, from, to}` 배열. market은 KOSPI 또는 KOSDAQ이다. from/to는 해당 시장에 속한 유효 기간(양 끝 포함), to=null은 종료 미정이다. 동일 종목의 기간은 겹칠 수 없다. 시장 이동은 겹치지 않는 두 구간으로 입력한다.

전체 시장 전략을 검증하려면 해당 시점의 상장·폐지·시장 이동을 포함하는 종목 구성과 시세가 필요하다. 기존 `fetch_candles.py`는 현재 목록의 일부 종목을 수집하므로, 그것만으로 과거 전체 KOSPI/KOSDAQ를 재현하지 못한다. 이번 구현은 과거 전체 시장 자료의 자동 구매·수집을 추가하지 않는다.

캘린더는 자료에 없는 거래일을 임의로 만들어 채우지 않는다. 입력한 종목 중 시세·지표 이력이 부족한 종목은 선정 근거의 제외 목록에 표시된다. 이 상태에서 나온 결과는 입력 자료 범위의 결과이며 전체 시장 결과로 해석하면 안 된다.

CSV 자료를 JSON으로 옮기는 PowerShell 예: `members.csv` 헤더는 `symbol,market,from,to`, `calendar.csv` 헤더는 `date`이다. 파일에는 본인의 실제 자료를 넣는다. `source` 설명을 본인 자료에 맞게 지정한 뒤 실행한다.

```powershell
$portfolioMembers = @(Import-Csv ./members.csv | ForEach-Object {
    [ordered]@{ symbol=$_.symbol; market=$_.market; from=$_.from; to=if ($_.to) { $_.to } else { $null } }
})
$portfolioCalendar = @((Import-Csv ./calendar.csv).date)
$portfolioUniverse = [ordered]@{
    source='members.csv와 calendar.csv에 기록한 시장 구성 및 거래일 자료'
    tradingDates=$portfolioCalendar
    members=$portfolioMembers
}
$portfolioUniverse | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 ./portfolio-universe.json
```

요청 한도: 고유 종목 3000개, 편입 구간 10000개, 거래일 10000개, 실행 기간 10년, 로컬 캔들 100만개. 초과하면 기간·종목 범위를 줄여야 한다. 지표 준비 캔들도 100만개 한도에 포함된다. 3000종목의 200거래일 준비 이력만으로 60만개가 필요하므로 이를 수용하는 한도로 정했다. 최대 규모 자료의 실제 성능은 별도 확인이 필요하다.

## API와 저장 경계

- 전략의 `portfolio`는 `market`, `lookbackMonths`, `topN`, `smaPeriod`, `selectionOrder`, `rebalanceTiming`, `weighting`을 가지며 형식 3에서 필수다. 형식 1·2에는 이 필드가 없어도 된다.
- `POST /api/strategies/{id}/portfolio-backtests`: `{version,startDate,endDate,executionMode,initialCapital,commissionRate,taxRate,slippageRate,universe}`를 받는다. API 비용률은 소수 비율(0.1% = 0.001)이다. 비용값을 생략할 수 없다.
- `GET /api/strategies/{id}/portfolio-backtests?page=0&size=20`: 같은 전략의 포트폴리오 실행 요약 목록.
- `GET /api/portfolio/runs/{id}`: 전략 버전·실행 설정·시장 자료·캔들 SHA-256·결과/실패의 불변 스냅샷.
- `DELETE /api/portfolio/runs/{id}`: 해당 포트폴리오 실행 이력 삭제. 기존 전략 삭제는 연결된 포트폴리오 이력도 함께 삭제한다.
- 포트폴리오의 개별 종목 API 실행은 검증 오류로 막는다. 기존 `/api/backtest/runs/{id}`와 설명 API는 개별 종목 이력용으로 유지한다. 포트폴리오 결과는 전용 계좌 통계와 선정 근거를 표시한다.
- 실패 실행은 `status=FAILED`, `snapshot.result=null`, `snapshot.error`에 데이터 오류를 기록한다. 일부 거래만 계산된 결과를 성공으로 노출하지 않는다.

## 변경 파일

- 신규 `portfolio/PortfolioRequest.java`, `PortfolioEngine.java`, `PortfolioResult.java`: 실행 검증·순위·주간 조정·일별 계좌 계산.
- 신규 `portfolio/PortfolioRunEntity.java`, `PortfolioRunRepository.java`, `PortfolioService.java`, `PortfolioController.java`: 별도 불변 이력과 API.
- `strategy/StrategyDefinition.java`, `StrategyValidator.java`, `StrategyApiErrors.java`, `assistant/StrategyAssistantService.java`, `codex/strategy-output.schema.json`: 버전 3 계약과 해석.
- `backtest/CandleRepository.java`, `UserStrategyBacktestRequest.java`: 범위 제한 캔들 조회와 실행 경로 검증.
- `static/js/strategy-workbench.js`: 포트폴리오 초안 편집·시장 자료·비용 입력·계좌 및 순위 결과.
- 신규 `PortfolioEngineTests.java`, `PortfolioApiTests.java`, `StrategyPortfolioLiveTests.java`, `scripts/tests/strategy-portfolio.browser.cjs`; 기존 `StrategyBatchLiveTests.java`의 지원 범위 갱신.
- 이 문서와 README, 앞선 단계의 안내 문서.

## 검토 기록

분리 작업은 작업공간 크레딧 오류로 중단되어 주 작업에서 구현을 완료했다. 독립 리뷰가 완료되었다고 간주하지 않는다. 직접 검토한 범위는 버전 1·2 호환, 과거 입력만 사용하는 순위/시가 체결, 현금·수량·비용, 손절 우선, 불변 스냅샷과 삭제, 선택한 저장 버전별 화면/API 분리다.

직접 검토에서 손절 우선으로 제외된 종목의 주간 선정 표시가 실제 목표와 다른 문제를 발견했다. 실패 테스트를 추가하고 해당 날짜의 선정 상태를 해제하며 손절 제외 근거를 함께 저장하도록 수정했다.

## 최종 검증 — 2026-09-15

- 최종 코드에서 Java 17 `test bootJar --offline --console=plain` 성공. Java 총 144개 중 138개 통과, 실패·오류 0, 선택 실행 6개 제외. `RUN_WEB_SMOKE=true`로 기존 실제 HTTP 서버·H2·브라우저 흐름도 실행했다.
- 최종 전체 실행에서 제외한 실제 Codex 두 항목은 앞서 별도 활성화하여 모두 통과했다: 원래 여섯 전략 분리(`StrategyBatchLiveTests`)와 정책을 명시한 상대강도 전략(`StrategyPortfolioLiveTests`). 이후 변경은 테스트의 미지원 버전 예제(3→4)와 캔들 조회 한도이며 해석 계약은 같다.
- 원래 상대강도 문장은 `unsupported=[]`, `schemaVersion=3`으로 반환되고 미지정 주간 계산일·비중은 보완 질문과 필수값 오류로 남았다. 완성 예제는 `ready=true`였다.
- Node/Playwright 전체 16개 통과. 새 포트폴리오 화면, 기존 복수 초안, 개별 조건 편집, 저장/실행, 결과 차트와 설명 회귀를 포함한다. 모바일 가로 넘침 검사와 화면 확인을 수행했다.
- 포트폴리오 엔진 테스트 11개: 달력 개월 수익률, 동률, 순위/필터 순서, 휴일 주간 경계, 다음 시가의 미래 종가 배제, 비용·정수 주수·현금, 주간 순위 이탈, 손절 우선과 선정 근거, 데이터 누락과 종료 미체결.
- API 테스트 3개: 형식 3 저장/실행, 비용 필수 입력, 단일 엔진 차단, 혼용 조건 차단, 실패 스냅샷, 변경된 캔들에 영향받지 않는 조회, 전략 삭제 시 이력 삭제.
- 실제 시장 전체 데이터와 최대 100만 캔들 부하 검증은 수행하지 않았다. 포트폴리오 API는 메모리 H2와 합성 시세로 검증했고, 전용 브라우저 테스트는 API 응답을 대역으로 제공했다. 기존 실제 서버 브라우저 흐름은 개별 종목 경로다.
- 증거: `build/test-results/test/`, `build/strategy-batch-live.json`, `build/strategy-portfolio-live.json`, `build/strategy-web-smoke.log`, `build/strategy-portfolio-mobile.png`. 이후 테스트 실행 시 덮어쓸 수 있다.
- `git diff --check` 통과. 커밋·푸시·사용자 서버 재시작은 수행하지 않았다.
