# 저장 전략 비교 — 7단계 A 구현 계획

**Goal:** 같은 유형의 저장 전략 2~6개를 공통 조건·동일 입력 데이터로 실행하고 당시 조건과 성과를 저장·재조회한다.

**Architecture:** comparison 패키지가 저장 버전을 고정하고 요청을 모두 검증한 뒤 캔들을 한 번 읽는다. 기존 단일 종목·포트폴리오·조합 엔진을 재사용한다. 공통 입력과 전략별 원본 결과를 비교 전용 불변 JSON 스냅샷으로 저장한다. 정적 비교 화면은 기존 전략 목록에서 선택한 버전을 받아 비교 실행·이력을 제공한다.

**Tech Stack:** Java 17, Spring Boot/JPA/H2, 기존 JavaScript/CSS, JUnit/Playwright. 새 의존성 없음.

**Spec:** 사용자가 2026-09-21 승인한 A안과 `.docs/project-plan.md` FR-05. 현재 체크아웃에서 진행하며 운영 서버·DB 변경, 커밋·배포는 하지 않는다.

## 승인 범위와 정책

- SINGLE, PORTFOLIO, COMPOSITE 중 같은 유형끼리 비교한다. 동일 전략의 중복 선택은 거절하고 각 전략의 버전을 명시한다.
- 단일 종목은 거래 수익률 합계·거래 낙폭(%p)·비연율화 거래 샤프·승률·청산 거래 수를 표시한다. 자금·비용은 NOT_MODELED다.
- 계좌 유형은 계좌 수익률·일별 최대 낙폭·최종 평가액·매수/매도 체결 건수를 표시한다. 계산하지 않는 승률·샤프를 만들지 않는다.
- 공통 종목/시장 자료·기간·체결 방식·초기자금·비용을 한 번 입력한다. 계좌 유형 비용 0도 명시적으로 입력한다. 조합 전략에서 선정 기능 유무가 다른 경우 공통 종목을 요구한다.
- 한 번 읽은 캔들 목록과 SHA-256을 공유한다. 조회 시 전략이나 시세를 다시 읽어 계산하지 않는다.
- 결과별 데이터 부족·거래 없음·실패 상태와 실제 적용 기간을 표시한다. 순위를 자동으로 매기지 않는다.
- 비교는 독립 이력으로 저장한다. 참여 전략 삭제 시 그 전략이 포함된 비교 이력 전체도 삭제한다. 비교 단독 삭제는 전략과 캔들을 보존한다.
- 2~6개 및 입력 캔들 최대 100만 개로 동기 실행을 제한한다. POST 성공은 이력 생성이며 모든 전략의 성공을 뜻하지 않는다.

## API 계약

`POST /api/comparisons` 요청:
```json
{"strategies":[{"id":1,"version":1},{"id":2,"version":1}],"symbol":"005930","startDate":"2025-01-01","endDate":"2025-12-31","executionMode":"NEXT_DAY_OPEN","initialCapital":null,"commissionRate":null,"taxRate":null,"slippageRate":null,"universe":null}
```

- `GET /api/comparisons?page=0&size=20`: `[{id,createdAt,type,strategyNames}]`.
- `GET /api/comparisons/{id}` 및 POST: `{id,createdAt,snapshot}`.
- `DELETE /api/comparisons/{id}`: 204.
- snapshot: `{schemaVersion,type,execution,data,entries}`. execution은 위 공통 요청, data는 `{source,sha256,candles}`.
- entries: `[{strategy:{id,version,strategy},engineVersion,status,error,single,account,composite,metrics,curve}]`.
- single은 기존 UserStrategyBacktestResult, account는 PortfolioResult, composite는 CompositeResult 또는 null.
- metrics: `{returnRate,maxDrawdown,tradeCount,winRate,sharpe,finalEquity}`. 미지원/실패 값 null. 단일 낙폭은 기존 음수 %p, 계좌 낙폭은 기존 양수 비율.
- curve: `[{date,value}]`, 날짜 YYYY-MM-DD, 단일은 청산 거래 누적 합계, 계좌는 초기자금 대비 평가액 변화율. 실패/데이터 부족은 빈 배열.
- 유형 혼합·중복·잘못된 입력은 기존 400 오류 계약, 없는 버전은 404. 입력 검증 실패는 이력을 만들지 않는다.

## 작업 및 검증

- [x] 백엔드 API/H2 테스트를 먼저 작성하여 미구현 경로 실패 확인. 같은 캔들·다른 조건의 다른 성과, 버전 고정, 유형 혼합·비용 누락 거절, 실패와 빈 결과, 삭제 경계 확인.
- [x] ComparisonService/Controller/Entity/Repository 구현. SavedStrategyService 삭제 연동, StrategyApiErrors 범위 확장.
- [x] 독립 `strategy-comparison.js`와 CSS 구현. 기존 전략 선택에서 버전 추가, 제거, 공통 폼, 성과·조건·곡선·원본 근거 및 이력 조회. index.html 연결.
- [x] 브라우저 테스트 먼저 실패 확인 후 구현·통과. 혼합 차단, 명시적 비용, 오류·빈 결과, 이력, HTML 안전 표시, 모바일 폭 확인.
- [x] 통합·전체 회귀 테스트 및 bootJar, diff 확인. 테스트 H2만 사용하며 실제 AI는 호출하지 않는다.
- [x] 사용 방법, 실제 검증 결과와 A안의 미지원 범위를 README와 기획서에 기록.

## 진행 기록

- 승인: A안을 먼저 구현한다. 추가 승인 없이 이 계약에 따라 진행한다.
- Ruling: 기존 작업 디렉터리의 codex 브랜치에서 구현한다. 사용자가 보고 있는 체크아웃에 결과를 남기며 불필요한 브랜치/작업 디렉터리 이동을 하지 않는다.

## 사용 방법

1. 서버를 새 코드로 재시작하고 브라우저를 강력 새로고침한다. 기존 파일 H2에는 `strategy_comparison`, `strategy_comparison_member` 테이블이 추가된다. 이번 작업에서 운영 서버를 재시작하지 않았다.
2. 기존 저장 전략 목록에서 전략과 버전을 선택하고 **선택 버전을 비교에 추가**를 누른다. 같은 유형의 서로 다른 전략을 2~6개 추가한다. 특정 전략의 버전을 바꾸려면 기존 선택을 제거한 뒤 다시 추가한다.
3. **저장 전략 비교**에서 공통 기간·체결 방식을 지정한다. 단일 종목은 종목 코드가 필수다. 포트폴리오는 시장 자료를 사용한다. 선정 기능 없는 조합이 포함되면 공통 종목 코드도 입력한다.
4. 계좌 유형은 초기자금(0 초과 1조 원 이하), 수수료·매도세·슬리피지(각 0~25%)를 모두 지정한다. 비용이 없으면 0을 명시한다. 시장 자료는 기존 포트폴리오 실행과 같은 출처·거래일·편입 이력 JSON이다.
5. **비교 실행 및 저장**에서 성과 곡선·전략별 지표·실제 기간·당시 조건을 확인한다. 미청산 포지션, 미체결 주문과 실패도 표시한다. 원본 판정·거래와 공통 입력 캔들은 펼쳐서 조회한다.
6. 새로고침 후 **저장된 비교 이력**의 보기 버튼으로 당시 스냅샷을 조회한다. 비교 삭제는 전략을 보존하지만, 참여 전략 삭제는 해당 전략이 포함된 비교 전체도 삭제한다.

## 계산 범위와 오류 구분

- 단일 종목은 거래 수익률 단순 합계·거래 낙폭(%p)·비연율화 거래 샤프다. 미청산 포지션은 청산 지표에서 제외된다. 초기자금·수수료·세금·슬리피지는 미반영이며 API에 값을 보내면 거절한다.
- 계좌 유형은 입력 자금·비용·정수 주수를 반영한 일별 평가액 기준 수익률과 낙폭이다. 체결 건수는 매수·매도 각각을 포함하며 왕복 거래 수와 다르다. 승률·샤프는 계산하지 않는다.
- 요청 구간의 대상 가격이 없으면 `NO_DATA`, 모든 진입 판단이 준비되지 않았거나 모든 선정 후보가 준비 이력/캔들 누락으로 제외되면 `INSUFFICIENT_DATA`로 비교 상태를 표시하고 지표·곡선을 제공하지 않는다. 실행했다면 원본 엔진 결과도 보존한다.
- 일부 후보의 데이터 부족·필터 탈락은 원본 선정 근거에 남는다. 입력 자료의 완전성은 보증하지 않으며 현재 목록을 과거 전체 시장으로 간주하지 않는다. 전략별 준비 기간·후보 차이와 실제 적용 기간을 확인한다.
- 비교는 개별 실행 이력 테이블에 행을 추가하지 않으며 비교 전용 스냅샷에 원본 결과·근거를 보존한다.
- 공유 비교 행을 ID 순서로 잠가 참여 전략의 동시 삭제 충돌을 방지한다. 단독 비교 삭제도 같은 행 잠금을 사용한다.
- 유형 간 비교, 단일 종목의 계좌 모델 확장, 자동 순위·추천, AI 비교 설명, 전략별 페이퍼 트레이딩은 이번 A안에 포함하지 않는다.

## 검증 실행

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
$env:NODE_PATH = 'C:/Users/EM_NB171/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules'
$env:RUN_WEB_SMOKE = 'true'
$env:RUN_CODEX_LIVE = 'false'
$env:RUN_EXPLANATION_LIVE = 'false'
$env:RUN_STRATEGY_BATCH_LIVE = 'false'
$env:RUN_STRATEGY_EXPANSION_LIVE = 'false'
$env:RUN_STRATEGY_PORTFOLIO_LIVE = 'false'
.\gradlew.bat --gradle-user-home "$env:USERPROFILE/.gradle" test bootJar --offline --console=plain
node --test --test-concurrency=1 scripts/tests/strategy-workbench.test.cjs scripts/tests/strategy-workbench.browser.cjs scripts/tests/strategy-results.browser.cjs scripts/tests/strategy-portfolio.browser.cjs scripts/tests/strategy-expansion.browser.cjs scripts/tests/strategy-composition.test.cjs scripts/tests/strategy-composition.browser.cjs scripts/tests/strategy-batch.browser.cjs scripts/tests/strategy-comparison.browser.cjs
```

테스트는 메모리/임시 파일 H2와 합성 캔들을 사용한다. 새 비교 웹 통합 테스트는 실제 HTTP·Java 엔진·H2·Edge를 연결하며 모델 호출을 하지 않는지도 검사한다.

## 변경 파일

- `src/main/java/com/tossinvest/tossinvestbackend/comparison/Comparison{Service,Controller,Entity,Repository}.java`: 검증, 공통 데이터 실행, 불변 저장과 조회·삭제.
- `strategy/SavedStrategyService.java`, `strategy/StrategyApiErrors.java`: 삭제 수명주기와 오류 계약 연결.
- `src/main/resources/static/js/strategy-comparison.js`, `css/strategy-comparison.css`, `js/strategy-workbench.js`, `index.html`: 비교 화면과 선택 버전 연결.
- `src/test/java/com/tossinvest/tossinvestbackend/comparison/Comparison{Api,Persistence,WebWorkflow}Tests.java`: API/DB/재시작/동시 삭제/실제 웹 흐름.
- 기존 `StrategyPersistenceRestartTests.java`, `BacktestExplanationPersistenceTests.java`: 새 비교 엔티티·저장소를 테스트 컨텍스트에 포함.
- `scripts/tests/strategy-comparison.browser.cjs`, `strategy-comparison.server.cjs`: 브라우저 상호작용과 실제 서버 비교.
- `README.md`, `.docs/project-plan.md`, 이 문서: 사용법·검증 기록.
## 검증 결과 (2026-09-22)

- 전체 Java 테스트 180개: 174개 통과, 실패·오류 0개, 명시적 실모델 호출 테스트 6개 제외. 실제 HTTP·브라우저 통합 테스트를 포함했다.
- 전체 Node/Edge 회귀 테스트 29개 통과, 실패 0개. 새 비교 브라우저 3개는 과거 버전 선택·유형 혼합·중복 차단, 실패 지표 표시, 저장/삭제, 비용 입력, 유형 전환, HTML 안전 표시와 모바일 폭을 검증했다.
- 비교 전용 Java 테스트 11개: API 8개, 파일 H2 재시작·동시 삭제 2개, 실제 웹 통합 1개. 손절 5%와 20%를 같은 가격으로 실행해 수익률 합계 -6%/0%, 청산 거래 1건/0건을 확인했다.
- 전략 수정·원본 캔들 삭제 후에도 스냅샷·해시·조건·결과가 같았고, 파일 DB 종료 후 스키마 검증 모드로 재개해 동일 이력을 확인했다.
- 빈 시장 데이터의 가짜 0% 성과, 조합 진입 준비 부족, 이스케이프 문자가 많은 긴 전략 이름 저장, 공유 비교의 동시 삭제 충돌을 재현하고 수정했다. 동시 삭제는 4회 반복 시나리오를 포함한다.
- `bootJar` 성공, `git diff --check` 통과. 기존 코드와 화면 회귀 테스트도 통과했다.
- 실제 서버 비교 로그: `build/strategy-comparison-web.log`. 화면: `build/strategy-comparison-server-desktop.png`, `build/strategy-comparison-server-mobile.png`.
- 운영 서버 재시작·운영 DB 변경·실제 AI 호출·커밋·배포는 하지 않았다. 적용 시 서버 재시작과 브라우저 강력 새로고침이 필요하다.

**완료 범위:** 승인된 7단계 A안의 같은 유형 전략 비교, 공통 데이터 실행, 당시 조건·결과 저장과 웹 재조회. 유형 간 비교·단일 종목 계좌 모델과 8단계 페이퍼 트레이딩은 후속 범위다.