# 국내주식 일봉 전략 확장 — 1단계

2026-09-14 사용자가 승인한 개별 종목 조건 확장. 기존 자연어 → 검증된 JSON → Java 평가 → 저장된 백테스트 경로를 확장한다. 2026-09-15 [2단계 복수 전략 분리](strategy-batch-input.md)와 [3단계 상대강도 포트폴리오](strategy-portfolio.md)를 추가했다.

2026-10-13에는 형식 2에 이동평균의 달력 개월 추세와 기간 고가·저가 대비 비율 조건을 추가했다. [6조건 AND 예제](#sma-정배열과-52주-가격-조건--2026-10-13)의 계산 기준을 사용자가 확인했다.

## 계약

- Java 17, 기존 Spring/Jackson/JUnit 사용. 외부 의존성 추가 없음.
- 기존 schemaVersion 1의 계산·저장 이력은 유지한다. 확장 조건은 schemaVersion 2로 명시한다.
- 완성 일봉의 종가로 신호와 리스크를 판단한다. 기존 SAME_DAY_CLOSE/NEXT_DAY_OPEN 체결 선택을 유지한다. 장중 주문·장중 손절 체결을 지원하는 것으로 표시하지 않는다.
- RANGE_BREAKOUT: 종가와 당일을 제외한 과거 구간의 HIGH/LOW를 비교. periodUnit은 BARS 또는 CALENDAR_WEEKS. 주 단위는 한국 날짜 기준 [판정일 - N주, 판정일)이며 구간 시작까지 확보된 이력이 있어야 한다. 52주를 임의로 252봉으로 바꾸지 않는다.
- MA_COMPARE: 동일 종류 SMA/EMA의 단기·장기 평균 상태 비교. PRICE_MA: 종가와 지정 SMA/EMA 비교. GT/LT/GTE/LTE와 명시적인 CROSS_ABOVE/CROSS_BELOW를 구분한다.
- ATR_BREAKOUT: 종가와 전일 종가 + multiplier × 전일 ATR(period)을 비교. ATR은 SIMPLE/WILDER를 명시한다. TR은 high-low, abs(high-previousClose), abs(low-previousClose)의 최댓값. 첫 TR은 이전 종가가 있는 두 번째 봉부터, 최초 ATR은 period개의 TR 평균이다.
- risk.trailingStop: 음수 rate와 peakBasis(CLOSE/HIGH) 필수. 진입 후 고점 × (1 + rate) 이하의 종가에서 청산 신호. 종가 진입 봉의 진입 전 고가는 포함하지 않는다. 다음 시가 진입 봉의 고가는 포함한다. 기존 단계식 trailing과 동시 지정 금지.
- risk.atrStop: method, period, multiplier 필수. 진입 직전 완성 봉 ATR을 진입 때 고정하여 entryPrice - multiplier × ATR 이하 종가에서 청산 신호. 준비되지 않으면 진입 금지. 고정 손절과 함께 지정하면 먼저 충족하는 조건으로 청산하며 같은 봉이면 고정 손절을 우선한다. ATR 손절 기준가가 0 이하이면 데이터/설정 오류로 실행을 거절한다.
- 리스크 우선순위: 고정 손절 → ATR 손절 → 익절 → 시간 청산 → 추적손절 → 지표 청산. 기존 단계식 트레일링의 시간 청산 억제만 유지한다.
- 숫자·기간·고점 기준·ATR 방식이 누락되면 해당 경로의 한국어 보완 안내를 표시한다. 자연어의 '또는'이 선택지인지 동시 조건인지 불명확하면 질문한다. 모호한 조건을 기본값으로 채우지 않는다.
- 고가·저가는 기존 저장 캔들의 값을 그대로 사용한다. 데이터 출처의 수정주가 일관성, 실제 거래일 누락, 상하한가 체결 가능성은 이 확장으로 보증하지 않는다.

## 구현 계획

**Goal:** 신고가/저가 이탈, 이동평균 위치, 비율 추적손절, ATR 진입·손절을 편집·검증·저장·백테스트할 수 있게 한다.

**Architecture:** StrategyDefinition/Validator에 버전 2 계약을 추가하고, StrategyBar에 저장된 OHLC를 전달한다. 별도 일봉 지표 계산 도우미를 StrategyEvaluator에서 사용한다. 기존 실행 엔진에 진입 시 ATR 고정과 고점 기준을 연결하며 화면·모델 스키마를 같은 계약으로 맞춘다.

**Spec:** 이 문서의 계약. 현재 사용자 작업 브랜치에서 수행하며 자동 커밋·서버 재시작은 하지 않는다.

### 1. 계약과 계산, 실행

- [x] `ExtendedStrategyTests`에 실제 JSON/캔들로 기대값을 검증하는 실패 테스트 추가: 당일 고가 제외, 52주 날짜 경계, SMA 위치, ATR 전일 값/갭, 진입 후 고점, 진입 ATR 고정, 준비기간, 버전 1 호환.
- [x] Java 17에서 `./gradlew.bat test --tests '*ExtendedStrategyTests' --offline --console=plain`로 실패 확인.
- [x] `StrategyDefinition`, `StrategyValidator`, `StrategyBar`, `StrategyEvaluator`, `StrategyIndicators`, `UserStrategyBacktestEngine` 구현.
- [x] 새 테스트와 기존 `Strategy*Tests`, `UserStrategyBacktest*Tests` 실행.

### 2. 자연어와 화면, 저장 이력

- [x] 모델 출력/검증/저장/실행/재조회 테스트와 화면 편집 회귀 테스트 추가.
- [x] `StrategyAssistantService`, `strategy-output.schema.json`, `strategy-workbench.js`, `strategy-results.js`를 확장 계약과 한국어 안내로 맞춤.
- [x] 새 입력에는 버전 2, 기존 저장 전략에 새 조건을 추가할 때만 버전 2로 전환. 모든 필수값은 빈칸 상태 유지.
- [x] 대표 전략 JSON 예제와 자연어 입력 예제를 문서에 추가.

### 3. 최종 검증

- [x] 전체 Java 테스트와 `bootJar`, Node 테스트, 브라우저 편집/저장/실행/재조회 확인.
- [x] 미래 봉을 추가해도 과거 신호·체결·근거가 바뀌지 않는지 검증.
- [x] `git diff --check`, 변경 범위 검토, 완료/미지원/재시작 필요 사항 기록.

## SMA 정배열과 52주 가격 조건 — 2026-10-13

다음 문장을 자연어 입력란에 붙여 넣으면 한 전략의 6개 AND 조건으로 해석한다. 이미 해석한 초안이라면 보완 입력 후 **다시 해석**해야 한다.

> 국내 주식 일봉 종가 기준. 다음 6개 조건을 모두 충족하면 매수(AND): 종가 > SMA50, SMA50 > SMA150, SMA150 > SMA200, 현재 SMA200 > 달력상 1개월 전 SMA200(해당 날짜가 휴장일이면 그 이전 최근 거래일), 종가 >= 당일 제외 달력상 직전 52주 최고 고가의 75%, 종가 >= 당일 제외 달력상 직전 52주 최저 저가의 130%. 한 달 동안 매일 상승을 요구하지 않는다. 별도 청산 및 위험관리 조건 없음.

기존 원문을 유지할 때의 보완 입력:

> 모두 충족하면 진입하는 AND 조건. SMA200 상승 기간은 달력상 1달: 현재 SMA200이 한 달 전보다 높다. 휴장일은 그 이전 최근 거래일. 일봉 종가 기준, 52주 고가·저가는 당일 제외. 별도 청산과 위험관리 조건 없음.

- `MA_TREND`: `averageType`(SMA/EMA), `period`(1~500봉), `lookbackMonths`(1~60개월), `direction`(UP/DOWN). UP은 현재 평균이 과거 평균보다 엄격히 큰 경우다. 동일 값은 상승이 아니다. DOWN은 엄격히 작은 경우다.
- 과거 기준일은 한국 날짜의 `minusMonths`다. 예: 2024-03-31의 한 달 전은 2024-02-29. 그 날짜가 휴장일이면 그 이전 최근 저장 거래일 값을 사용한다. 현재 평균과 과거 평균이 모두 준비되어야 한다. 한 달을 20거래일이나 30일로 바꾸지 않는다. 매일 연속 상승이나 회귀 기울기는 지원하지 않는다.
- `RANGE_RATIO`: `period`, `periodUnit`(BARS/CALENDAR_WEEKS), `priceField`(HIGH/LOW), 양수 `multiplier`, `comparison`. 기존 `RANGE_BREAKOUT`과 같은 당일 제외 구간의 극값에 배수를 곱한다. GT/LT/GTE/LTE/CROSS_ABOVE/CROSS_BELOW를 지원하며 교차에는 전일 기준값도 필요하다.
- 52주 고가의 25% 이내는 `HIGH × 0.75` 이상이다. 52주 저가보다 30% 이상 높다는 조건은 `LOW × 1.30` 이상이다. 각각 경계값을 포함한다. 별도의 최고가 상한은 추가하지 않는다.
- 52주 이력의 시작 경계는 포함하며 당일은 제외한다. 예: 2026-01-01 판정의 구간은 `[2025-01-02, 2026-01-01)`이다. 52주 시작일까지의 저장 이력이 없으면 해당 조건은 준비되지 않은 상태다.
- '상승 중'에 기간이 없으면 미지원 처리 대신 비교 개월 수를 질문한다. AND/OR 누락도 해당 연산자를 질문하며 해석 가능한 조건은 남긴다. 번호 목록은 한 전략의 필터로 처리하고, 보완 입력의 AND를 반영한다.
- 화면의 **이동평균 기간 추세**, **기간 고가·저가 대비 비율**에서 값을 편집한다. 비율 입력은 퍼센트이므로 고가 기준 75, 저가 기준 130을 넣는다. 빈칸은 자동으로 채우지 않는다.
- 백테스트와 저장 전략 모의매매가 같은 평가기를 사용한다. SMA 추세 근거에는 현재·과거 평균, 비율 근거에는 종가·극값에 배수를 곱한 가격을 저장한다. 저장된 전략 버전과 실행 스냅샷은 변경하지 않는다.

실행 가능한 전체 JSON: [`strategy-trend-template.json`](../src/test/resources/demo/strategy-trend-template.json). 청산·손절은 지정하지 않았으므로 자동으로 추가하지 않는다. 실행 종목·기간·체결 방식은 기존 실행 화면에서 지정한다.

변경 파일:

- 계산·검증: `StrategyDefinition.java`, `StrategyIndicators.java`, `StrategyEvaluator.java`, `StrategyValidator.java`.
- 해석·화면: `StrategyAssistantService.java`, `strategy-output.schema.json`, `strategy-workbench.js`.
- 검증: `TrendTemplateTests.java`, `StrategyAssistantTests.java`, `StrategyPersistenceApiTests.java`, `StrategyPaperEngineTests.java`, `StrategyExpansionLiveTests.java`, `strategy-workbench.test.cjs`, `strategy-expansion.browser.cjs`, 위 JSON 예제.
- 안내: 이 문서, `strategy-spec.md`, `README.md`.

새 JAR로 서버를 재시작하고 브라우저를 강력 새로고침한 뒤 이전 초안을 다시 해석한다. 새 입력 조건은 기존 형식 2 안에서 확장되므로 DB 구조 변경은 없다.

검증 결과 (2026-10-13):

- `scripts/verify-stage9.ps1 -Offline`: Java 224개 중 217개 통과, 선택 실행 7개 건너뜀, 실패·오류 0. `bootJar` 성공. Node/Edge 40개 통과. 고정 외부 응답과 테스트 DB를 사용하는 전체 검증 결과는 `build/stage9-verification.json`이다.
- 실제 ChatGPT 로그인 Codex: `StrategyExpansionLiveTests.clarifiedTrendTemplateProducesAllSixAndConditions` 통과. 원문+보완 입력은 6개 AND 조건·질문 0·미지원 0, 일괄 입력도 단일 전략으로 동일 조건을 반환했다. 상승 기간 누락은 개월 수 보완 질문을 반환하고 미지원으로 분류하지 않았다. 결과 파일은 `build/strategy-trend-live.json`, `build/strategy-trend-batch-live.json`, `build/strategy-trend-missing-live.json`이다. OpenAI API 키는 사용하지 않았다.
- 월말·윤년·휴장일 기준, 현재/과거 SMA 준비 여부, 52주 경계 포함과 당일 제외, 75%/130% 경계값, AND 불충족, 미래 봉 불변성, 양쪽 체결 방식, 저장 버전/실행 스냅샷 불변성과 모의매매 재처리의 중복 방지를 검사했다.
- 화면에서 기간·방향·비율 수정과 빈칸 유지, 저장 조건 요약의 한 달·75%·130% 표시, 모바일 폭과 키보드 이동을 확인했다. 캡처: `build/strategy-trend-desktop.png`, `build/strategy-ratio-desktop.png`, `build/strategy-trend-mobile.png`.
- 읽기 전용 코드 리뷰에서 수정이 필요한 결함 없음. 운영 서버 재시작·실제 시세 호출·운영 전략 저장은 수행하지 않았다.

## 기존 확장 조건 손계산 검증 예

- 이전 고가 10, 11, 12 / 현재 종가 15·고가 100: 직전 3봉 신고가 기준은 12이고 매수 신호 발생.
- 이전 종가 100, 현재 고가 114·저가 109: TR은 14로 갭을 포함한다.
- 진입가 100, 진입 후 고가 130, 추적손절 -20%: 기준 104. 종가 104에서 신호 발생.
- 진입 직전 ATR 4, 배수 1.5, 진입가 100: ATR 손절 기준 94를 고정. 이후 ATR이 커져도 기준을 낮추지 않는다.

ATR의 TR 정의와 Wilder 재귀 평균은 [Fidelity ATR 계산 안내](https://www.fidelity.com/learning-center/trading-investing/technical-analysis/technical-indicator-guide/atr)를 참고했다. 이 구현의 첫 TR, 초기 준비기간과 반올림은 위 계약을 따른다. ATR 중간 평균은 소수 12자리 HALF_UP이다.

## 화면에 넣어 테스트할 예제

아래 전략을 개별 또는 함께 입력할 수 있다. 함께 입력하면 2단계 화면에서 초안을 나누어 선택한다. 예제에 들어 있는 고점 기준·ATR 방식은 이 예제의 명시적 선택이며 다른 입력의 기본값이 아니다.

### 20일 고가 돌파

> KOSPI/KOSDAQ 개별 종목 일봉 전략. 당일 제외 직전 20거래일 최고 고가를 종가가 초과하면 매수한다. 진입가 대비 -5% 종가 손절, 진입 후 일중 최고가 대비 -20% 종가 추적손절. 그 외 조건 없음.

### 52주 신고가

> 국내 개별 종목 일봉 전략. 종가가 당일 제외 과거 달력상 52주 최고 고가를 초과하면 매수, 진입가 대비 -5% 종가 손절, 종가가 당일 제외 직전 10거래일 최저 저가 미만이면 매도. 그 외 조건 없음.

### SMA 교차와 장기 필터

> 국내 개별 종목 일봉. 종가 기준 SMA 5일선이 SMA 20일선을 상향 교차하고 동시에 SMA 20일선이 SMA 200일선보다 높을 때 매수. SMA 5일선이 SMA 20일선을 하향 교차하면 매도. 진입가 대비 -5% 종가 손절. 그 외 조건 없음.

### ATR 돌파와 두 손절 조건

> 국내 개별 종목 일봉. 종가가 전일 종가 + 0.5 × 전일 WILDER ATR(14)를 초과하면 매수. 진입 직전 완성 봉의 WILDER ATR(14)을 진입 때 고정해 진입가 - 1.5 × ATR 이하 종가에서 손절. -5% 고정 손절도 함께 사용해 둘 중 하나만 충족해도 종가 손절. 종가가 당일 제외 직전 10거래일 최저 저가 미만이면 매도. 다른 조건 없음.

50/200 SMA 교차는 기존 MA_CROSS로 지원한다. '-8~10%'는 손절률 하나를 지정해야 한다. 장중 체결, 상대강도 상위 종목 자동선정, 주간 포트폴리오 리밸런싱, 여러 전략 동시 분리는 1단계 범위에 포함되지 않는다.

## JSON 예제 — 20일 고가 돌파

```json
{
  "schemaVersion": 2,
  "name": "20일 고가 돌파",
  "originalPrompt": "20거래일 최고 고가 초과 종가 매수, -5% 손절, 진입 후 고가 대비 -20% 추적손절",
  "entry": {"operator": "AND", "conditions": [
    {"type": "RANGE_BREAKOUT", "period": 20, "periodUnit": "BARS", "priceField": "HIGH", "comparison": "GT"}
  ]},
  "exit": null,
  "risk": {
    "stopLoss": {"rate": -0.05},
    "trailingStop": {"rate": -0.20, "peakBasis": "HIGH"}
  }
}
```

기존 `/api/strategy-assistant/validate`, `/api/strategies`, `/api/backtest/run-strategy`와 저장 버전 실행 경로를 사용한다. 기간·종목·체결 방식을 명시해야 한다. 기존 형식 1은 그대로 조회/실행하며, 화면에서 새 조건/리스크를 추가하면 편집 초안만 형식 2로 전환한다. 저장된 이전 버전은 변경하지 않는다.

조건 지표와 ATR 손절 준비 기간은 진입에 필요한 만큼 확인한다. NEXT_DAY_OPEN은 신호일에 완성된 ATR을 다음 시가 진입에 사용하고, SAME_DAY_CLOSE는 진입일 이전 일봉 ATR을 사용한다. 52주 조건의 준비 여부는 고정 거래봉 수가 아닌 실제 날짜 범위로 판단한다. 중간 거래일 누락 여부를 거래소 달력으로 검증하지는 않는다.

## 변경 파일

Java 기본 경로는 `src/main/java/com/tossinvest/tossinvestbackend/`다.

- `strategy/StrategyDefinition.java`, `StrategyValidator.java`: 형식 2 계약, 기존 형식 호환, 필수값·정책 충돌 검증.
- `strategy/StrategyBar.java`, `StrategyIndicators.java`(신규), `StrategyEvaluator.java`: OHLC 전달, 기간 고가·저가/ATR/이동평균 비교, 리스크 판정과 근거.
- `backtest/UserStrategyBacktestEngine.java`, `BacktestAnalysis.java`: 진입 시점 ATR·고점 기준, 실행 가정과 청산 사유 표시.
- `strategy/assistant/StrategyAssistantService.java`, `src/main/resources/codex/strategy-output.schema.json`: 모델 지시·출력 계약.
- `src/main/resources/static/js/strategy-workbench.js`, `strategy-results.js`: 조건/위험관리 편집, 형식 전환, 당시 전략·청산 사유 표시.
- 테스트: `src/test/java/com/tossinvest/tossinvestbackend/backtest/ExtendedStrategyTests.java`(신규), `StrategyPersistenceApiTests.java`; `strategy/StrategyValidatorTests.java`; `strategy/assistant/StrategyAssistantTests.java`, `StrategyExpansionLiveTests.java`(신규).
- 브라우저/Node 테스트: `scripts/tests/strategy-workbench.test.cjs`, `strategy-expansion.browser.cjs`(신규).
- 문서: 이 문서(신규), `README.md`, `.docs/strategy-spec.md`, `.docs/user-strategy-backtest.md`, `.docs/codex-strategy-web.md`.

## 반영 방법

실행 중인 서버는 새 클래스·리소스를 읽도록 다시 시작하고 브라우저를 새로고침한다. JAR 실행이면 새 `build/libs/toss-invest-backend-0.0.1-SNAPSHOT.jar`를 사용한다. 이 작업은 사용자 서버 프로세스나 운영 DB를 직접 변경하지 않았다. 이전의 미지원 해석 초안은 새로 해석해야 한다.

## 최종 검증 — 2026-09-14

- Java 17: `./gradlew.bat test bootJar --offline --console=plain` 성공. 테스트 122개 발견, 117개 통과, 실패/오류 0, 선택 실행용 테스트 5개 건너뜀. 새 엔진 확장 테스트 15개 포함.
- 실제 Codex 연결은 `RUN_STRATEGY_EXPANSION_LIVE=true`로 별도 실행했다. `StrategyExpansionLiveTests` 1개 통과/건너뜀 0. 위 20일 신고가·52주 신고가·SMA 장기 필터·ATR 예제 4개 모두 ready=true, questions/unsupported 없음. 결과는 `build/strategy-expansion-live-0.json`부터 `-3.json`이다.
- Node/Edge 브라우저: `node --test --test-concurrency=1 scripts/tests/strategy-workbench.test.cjs scripts/tests/strategy-expansion.browser.cjs scripts/tests/strategy-workbench.browser.cjs scripts/tests/strategy-results.browser.cjs`에서 13개 통과. 새 편집 필드, 미입력 유지, 확인 후 저장, 기존 실행/이력, 차트·설명 동작 확인.
- 신규 브라우저 테스트의 초기 모바일 실패는 테스트 페이지에 실제 CSS를 로드하지 않은 원인을 수정했다. 병렬 실행에서 기존 150ms 설명 로딩 상태 테스트가 한 번 실패했고, 동일 테스트를 순차 실행해 통과했다. 애플리케이션 동작을 바꾸거나 테스트 시간 제한을 늘리지 않았다.
- 초기 새 계약 테스트 12개 실패 확인 후 구현했다. 리뷰에서 발견한 RSI 비교 방식의 스키마/화면 불일치를 별도 실패 테스트로 재현한 뒤 검증기에서 거절하도록 수정했다. 최종 읽기 전용 리뷰에서 추가 결함 지적 없음.
- 캔들/전략 수정 이후에도 이전 실행의 기준값과 OHLC 스냅샷이 불변인 것을 임시 H2 API 테스트로 확인했다. 운영 DB는 사용하지 않았다.
- 모바일 폼 넘침 검사 통과. `build/strategy-expansion-desktop.png`, `build/strategy-expansion-mobile.png` 생성 및 모바일 화면 확인. `git diff --check` 통과.
- 빌드 산출물: `build/libs/toss-invest-backend-0.0.1-SNAPSHOT.jar`. 커밋·푸시·운영 서버 재시작은 수행하지 않았다.
