# 3년 일봉 보관·전략 보완·장전 브리핑 구현 계획

> **For agentic workers:** 기존 작업 공간에서 담당 파일을 나누어 구현한다. 테스트를 먼저 작성하고 실패·통과를 확인하며 마지막에 전체 회귀와 독립 검토를 수행한다.

**Goal:** 최대 달력 3년의 일봉 이력을 유지하고, MACD·볼린저밴드 조건과 확인된 오류 수정, 한국시간 08:30 시장 브리핑을 제공한다.

**Architecture:** 기존 Java 전략 검증·판정·저장 경로를 확장한다. 브리핑은 공개 자료 수집 → 출처가 있는 입력 스냅샷 → Codex 요약 → 날짜별 H2 저장 → 대시보드 조회로 구성한다.

**Tech Stack:** 기존 Java 17 / Spring Boot / H2 / Jackson / Vanilla JS / JUnit / Node·Playwright. 새 제품 의존성은 추가하지 않는다.

**Spec:** 사용자 요청과 2026-11-05 감사에서 확정된 아래 계약. 생성 시각은 사용자가 **매 거래일 오전 8:30 한국시간**으로 확인했다.

## 범위와 결정

- 일봉은 한국 날짜의 오늘에서 달력상 3년 전까지 보관한다. 200봉 초과 삭제를 제거하고 CSV·API 수집 모두 같은 정책을 적용한다. 저장 가능 기간과 공급자가 실제 제공한 이력은 구분한다.
- 기존 CSV로 복원 가능한 자료를 검토하되 잘못된 OHLC를 정상 가격으로 만들지 않는다. 운영 데이터 변경은 별도 백업과 검증 후 적용한다.
- MACD는 EMA 단기−장기, 그 값의 EMA 시그널, 히스토그램을 지원한다. 기간·비교 대상·숫자/시그널 비교를 명시하며 누락된 값은 보완 질문으로 처리한다.
- 볼린저밴드는 종가 SMA와 같은 기간 모집단 표준편차로 상·중·하단을 계산한다. 종가와 선택 밴드의 대소·상향/하향 교차를 지원한다. 기간·표준편차 배수·밴드를 명시한다.
- 기존 형식 1~4·저장 버전을 보존하며 새 조건은 형식 2 및 형식 4 조합에 연결한다. 기존 단일 종목 모의매매도 공통 판정기를 사용한다.
- 확정 오류: 조합 전략의 빈 실행 기간을 NO_DATA로 반환, 기존 점수제 모의매매의 보유 기간을 실제 수신 일봉 날짜로 계산, 장 마감 후 최신 일봉 수집을 전날 데이터 때문에 생략하는 오류 수정.
- 브리핑은 국내장 준비용으로 전일 국내 지수·밤사이 미국 지수·원달러·최근 경제 뉴스의 수집시각/관측시각/링크를 보존한다. 수집 실패나 오래된 자료는 그대로 표시하고 숫자를 추측하지 않는다.
- 브리핑은 읽기 전용 조회와 동일 날짜 생성의 멱등성을 제공한다. 정상 생성본은 실패로 덮어쓰지 않는다. 앱 실행 중 08:30 예약 실행, 재시작 시 누락 보충·제한된 재시도, 휴장일 판단과 자료 부족 안내를 제공한다.
- 요약의 각 항목은 실제 입력 자료 ID를 참조하도록 서버 검증한다. 외부 기사 전문 저장·투자 주문·알림 발송은 포함하지 않는다.
- 새 워크트리·자동 커밋·푸시·원격 배포 없이 현재 사용자가 지정한 작업 공간에서 작업한다. 기존 실행 스냅샷을 수정하지 않는다.

## Review Focus

1. 윤년/3년 경계, 일부 페이지만 성공한 수집, 기존 200봉에 긴 이력을 합치는 경우.
2. 신규 지표 준비기간·교차 경계·미래 봉 추가 후 과거 결과 보존·스키마 호환.
3. 주말·휴장·거래정지일이 보유 기간으로 잘못 계산되는 경우.
4. 모든 캔들이 준비기간에만 있거나 다른 종목 자료뿐인 조합 실행.
5. RSS 외부 엔티티·잘못된 링크·없는 근거 ID·중복 생성·Codex/자료 수집 실패·오래된 브리핑.

## 작업과 검증

- [x] 1. 데이터 보관·수집: CandleCollectionService/Scheduler/Repository, CandleCsvImportService, fetch_candles.py 및 해당 회귀 테스트. 3년 전 경계·긴 이력 보존·마감 후 갱신·중복 페이지 종료를 검증한다.
- [x] 2. 전략 지표: StrategyDefinition/Validator/Indicators/Evaluator, assistant 지시/JSON 스키마, workbench/results와 테스트. 독립 계산값·준비기간·편집/저장/백테스트 연결을 검증한다.
- [x] 3. 오류 수정: CompositeEngine, PaperTradingService와 테스트. 빈 자료/준비 봉만 있는 실행, 주말을 가로지르는 보유 기간을 먼저 재현한다.
- [x] 4. 브리핑: marketbriefing 패키지, CodexClient의 별도 요약 호출/스키마, 대시보드 UI와 테스트. 고정 HTTP 자료와 실제 DB로 수집·근거 검증·저장·재시도·재조회를 확인한다.
- [x] 5. 전체 통합: verify-stage9.ps1, 새 브리핑/지표 브라우저 검증, bootJar, git diff --check, 독립 코드 검토. 외부 호출은 통합 테스트 결과와 따로 기록한다.
- [x] 6. 데이터 적용: 기존 DB 백업, 유효한 CSV 이력 복원과 날짜별 건수 확인, 실제 앱 반영/브리핑 생성 확인. 적용할 수 없는 항목은 이유와 정확한 명령을 기록한다.

## 진행 기록

- 시작 시점: 추적 파일 변경 없음. 감사 회귀 Java 220/227(실모델 7 제외), Node/브라우저 42/42, bootJar 통과. 실제 자연어 해석 1회도 성공.

### 2026-11-10 재개·완료

- 한도 중단 후 잔여 실패를 확인하고 재검증했다. Java 275개 중 266 통과·외부 호출 9 제외, Node/브라우저 53/53 통과, bootJar·diff 검사 통과.
- 첫 전체 실행의 브라우저 1실패는 요청 이벤트와 테스트의 기록 시점 경쟁이었다. 실제 route 진입을 기다리도록 수정 후 전체 Node를 다시 실행했다. Java 소스는 해당 재실행 사이 변경하지 않았다.
- 실제 앱의 자연어 MACD·볼린저 해석은 ready=true이며, 오늘 시장 요약은 READY(5개 항목·17개 출처)로 저장·재조회됐다.
- 공급자 API 자동 수집으로 긴 이력이 이미 복원되어 오래된 CSV는 운영 DB에 병합하지 않았다. 삼성전자·SK하이닉스는 2023-11-10~2026-11-09의 728봉이다.
- DB 백업 확인 후 새 전체 갱신 경로를 실행했다. 신규 수신 0봉, 보관 만료 행 0건 확인. 기존 실행 스냅샷은 보존했다.
- 요약의 월명 번역·종가 관측시각, 제외 종목 전역 만료 정리, 자정/예약 이후 화면 갱신도 회귀 검증했다.
- 상세 검증: [완료 결과](../build/completion-verification.json), [시장 요약 사용법](market-briefing.md), [지표 사용법](strategy-indicators.md).

### 변경 파일

- `.docs/2026-11-05-data-indicators-briefing-plan.md`
- `.docs/market-briefing.md`
- `.docs/strategy-indicators.md`
- `.docs/strategy-spec.md`
- `scripts/fetch_candles.py`
- `scripts/tests/market-briefing.browser.cjs`
- `scripts/tests/strategy-indicators.browser.cjs`
- `scripts/tests/strategy-workbench.test.cjs`
- `scripts/verify-stage9.ps1`
- `src/main/java/com/tossinvest/tossinvestbackend/backtest/CandleCollectionScheduler.java`
- `src/main/java/com/tossinvest/tossinvestbackend/backtest/CandleCollectionService.java`
- `src/main/java/com/tossinvest/tossinvestbackend/backtest/CandleCsvImportService.java`
- `src/main/java/com/tossinvest/tossinvestbackend/backtest/CandleDataPolicy.java`
- `src/main/java/com/tossinvest/tossinvestbackend/backtest/CandleRepository.java`
- `src/main/java/com/tossinvest/tossinvestbackend/composite/CompositeEngine.java`
- `src/main/java/com/tossinvest/tossinvestbackend/marketbriefing/MarketBriefing.java`
- `src/main/java/com/tossinvest/tossinvestbackend/marketbriefing/MarketBriefingController.java`
- `src/main/java/com/tossinvest/tossinvestbackend/marketbriefing/MarketBriefingEntity.java`
- `src/main/java/com/tossinvest/tossinvestbackend/marketbriefing/MarketBriefingRepository.java`
- `src/main/java/com/tossinvest/tossinvestbackend/marketbriefing/MarketBriefingScheduler.java`
- `src/main/java/com/tossinvest/tossinvestbackend/marketbriefing/MarketBriefingService.java`
- `src/main/java/com/tossinvest/tossinvestbackend/marketbriefing/MarketBriefingSources.java`
- `src/main/java/com/tossinvest/tossinvestbackend/marketbriefing/MarketTradingCalendar.java`
- `src/main/java/com/tossinvest/tossinvestbackend/marketbriefing/PublicMarketHttp.java`
- `src/main/java/com/tossinvest/tossinvestbackend/paper/PaperTradingService.java`
- `src/main/java/com/tossinvest/tossinvestbackend/signal/TradeSignalService.java`
- `src/main/java/com/tossinvest/tossinvestbackend/strategy/assistant/CodexClient.java`
- `src/main/java/com/tossinvest/tossinvestbackend/strategy/assistant/StrategyAssistantService.java`
- `src/main/java/com/tossinvest/tossinvestbackend/strategy/StrategyDefinition.java`
- `src/main/java/com/tossinvest/tossinvestbackend/strategy/StrategyEvaluator.java`
- `src/main/java/com/tossinvest/tossinvestbackend/strategy/StrategyIndicators.java`
- `src/main/java/com/tossinvest/tossinvestbackend/strategy/StrategyValidator.java`
- `src/main/resources/codex/market-briefing.schema.json`
- `src/main/resources/codex/strategy-output.schema.json`
- `src/main/resources/marketbriefing/krx-holidays-2026.json`
- `src/main/resources/static/css/market-briefing.css`
- `src/main/resources/static/index.html`
- `src/main/resources/static/js/market-briefing.js`
- `src/main/resources/static/js/strategy-results.js`
- `src/main/resources/static/js/strategy-workbench.js`
- `src/test/java/com/tossinvest/tossinvestbackend/backtest/CandleRetentionTests.java`
- `src/test/java/com/tossinvest/tossinvestbackend/composite/CompositeEngineTests.java`
- `src/test/java/com/tossinvest/tossinvestbackend/marketbriefing/MarketBriefingLiveTests.java`
- `src/test/java/com/tossinvest/tossinvestbackend/marketbriefing/MarketBriefingServiceTests.java`
- `src/test/java/com/tossinvest/tossinvestbackend/marketbriefing/MarketBriefingSourcesTests.java`
- `src/test/java/com/tossinvest/tossinvestbackend/paper/PaperTradingServiceTests.java`
- `src/test/java/com/tossinvest/tossinvestbackend/strategy/assistant/MacdBollingerAssistantTests.java`
- `src/test/java/com/tossinvest/tossinvestbackend/strategy/assistant/MacdBollingerLiveTests.java`
- `src/test/java/com/tossinvest/tossinvestbackend/strategy/MacdBollingerStrategyTests.java`
- `src/test/java/com/tossinvest/tossinvestbackend/strategypaper/StrategyPaperEngineTests.java`
