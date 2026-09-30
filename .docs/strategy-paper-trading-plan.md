# 8단계 A 구현 계획

**Goal:** 단일 종목 저장 전략을 독립 가상 계좌로 실행·중지·조회한다.
**Architecture:** `strategypaper` 패키지에서 순수 일봉 처리, 영속 실행, 시세 수집, API를 분리한다. 기존 `StrategyEvaluator`와 `PortfolioCalculations`를 재사용한다. 정적 화면에서 저장 버전을 전달한다.
**Tech Stack:** Java 17, Spring Boot/JPA/H2, JavaScript, JUnit, Playwright. 새 의존성 없음.
**Spec:** [승인된 설계](strategy-paper-trading-design.md). 2026-09-28 사용자 진행 승인. 중지는 신규 매수 취소·차단 후 기존 포지션 청산 관리로 확정.

## 공통 제약

- 형식 1·2, 국내 주식, 완결된 전일 이전 일봉, 실행당 종목 하나.
- 비용·자금·배분 비율·체결 방식을 명시적으로 입력한다.
- 기존 기본 전략, 백테스트, 비교의 수치·실행 규칙을 변경하지 않는다.
- 실제 주문·AI 호출·운영 DB 조작·서버 재시작·배포는 하지 않는다.
- 현재 체크아웃에서 직접 구현한다. 사용자 변경을 덮어쓰지 않는다.

## 작업

- [x] 1. `StrategyPaperApiTests`에서 신규 경로 미구현 실패를 확인하고 구현했다. `StrategyPaperEngineTests`로 두 체결 모드·비용·중지·위험 정책의 기존 백테스트 일치를 검증했다.
- [x] 2. `PaperDefinition`, `PaperState`, `StrategyPaperEngine`으로 입력·보존 일봉·대기 주문·포지션·거래·일별 평가를 구현했다.
- [x] 3. `StrategyPaperPersistenceTests`로 같은 봉 동시 처리, 수정 후 버전 고정, 삭제 후 조회, 파일 H2 재시작과 실패 시 원자적 롤백을 검증했다.
- [x] 4. 실행 엔티티·저장소와 `StrategyPaperService`를 구현했다. 실행 잠금과 스냅샷 저장을 원자적으로 처리하며 종료 이력을 독립 보존한다.
- [x] 5. `PaperMarketDataTests`로 당일 봉 제외·통화·마지막 처리일 연결 검사를 검증했다. 가격 수정·거래량 0은 엔진 테스트로 확인했다. 실제 제공업체의 페이지 응답 검증은 별도 미검증 항목이다.
- [x] 6. API와 기존 저장 전략 화면을 연결했다. 시작·동일 요청 재시도·중복 실행·삭제 보호·중지·삭제 후 조회를 API 테스트로 검증했다.
- [x] 7. 브라우저에서 저장 버전 전달·명시 입력·시작 실패 후 같은 요청 식별자 재시도·HTML 텍스트 처리·중지·새로고침 후 재조회를 검증했다.
- [x] 8. 전체 Java·프론트 회귀 검증을 수행하고 실패한 삭제 문구를 수정한 뒤 관련 3개 화면 테스트를 재검증했다.

## 검토 중점

- 중지·새 봉 처리 경쟁 시 중지 후 매수가 생기지 않아야 한다.
- API 재시도가 같은 계좌를 반환하되 다른 설정을 같은 키로 보내면 거절해야 한다.
- 다음 시가 매수 수량은 해당 일봉 종가에 영향을 받지 않아야 한다.
- 공용 캔들 정리·전략 삭제 후에도 종료 이력의 숫자와 설명이 유지되어야 한다.
- 데이터 실패·수정·불충분·현금 부족은 거래 없음과 구분되어야 한다.

## 실행 기록

### 검증 결과

- 변경 전 전체 Java: 180개 중 171 통과, 9 건너뜀, 실패·오류 0.
- 2026-09-28 모의매매 전용 Java 및 실제 HTTP 브라우저 검증: 11 통과, 실패·건너뜀 0. 새 기능의 엔진·API·영속성·시세 어댑터·웹 통합을 포함한다.
- 2026-09-29 전체 Java: 191개 중 **181 통과, 10 건너뜀**, 실패·오류 0. opt-in 웹·외부 연동 테스트는 기본 실행에서 건너뛰며 신규 실제 HTTP 검증은 위 별도 실행으로 확인했다.
- 프론트 전체 31개 실행에서 30 통과, 기존 삭제 확인 문구 검사 1개 실패. 원인은 기존 ‘실행 이력’ 표현이 새 안내에서 빠진 것이며, ‘백테스트 실행 이력’으로 명확하게 수정했다. 수정 후 `strategy-workbench.browser.cjs`와 `strategy-paper.browser.cjs`를 함께 재실행하여 **관련 3개 모두 통과**했다. 나머지 통과한 기능은 변경하지 않았다.
- 별도 읽기 전용 코드 리뷰에서 같은 금액의 소수 자릿수 차이로 시작 요청 재시도가 충돌하는 문제를 발견했다. API 테스트 실패를 확인한 뒤 `BigDecimal.compareTo`로 수정했고 회귀 테스트가 통과했다.
- 실제 HTTP·H2·브라우저 검증은 시세/AI 제공업체만 대체한다. 실제 토스 인증·운영 데이터 수신 및 장기간 예약 실행은 검증하지 않았다. 운영 서버는 재시작하거나 배포하지 않았다.

### 실행 명령

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
.\gradlew.bat test --offline --console=plain

$env:NODE_PATH = 'C:/Users/EM_NB171/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules'
$env:RUN_WEB_SMOKE = 'true'
.\gradlew.bat test --offline --console=plain --tests '*strategypaper*'
node --test scripts/tests/strategy-paper.browser.cjs
```

### 2026-09-29 실환경 일봉 수신 오류 수정

- 재현: 최신 200봉 조회는 정상, 다음 페이지의 `before=2025-12-03T00:00:00+09:00` 요청은 제공업체 HTTP 400 `before/typeMismatch`.
- 원인: `MarketDataService`의 `+` → `%2B` 수동 치환 결과가 WebClient 문자열 URI 처리에서 다시 `%252B`로 인코딩됐다. `TossApiClient`는 HTTP 상태를 일반 예외로 바꾸고, 모의매매 서비스는 이를 모두 연결 실패로 표시했다.
- 수정: 날짜를 URI 템플릿 값으로 전달해 한 번만 인코딩한다. HTTP 예외의 상태를 유지하고 요청 오류·인증·호출 제한·연결/서버·응답 처리 오류를 구분한다. 상세 안내에 최근 상태의 원인과 조치를 표시하고 원문 응답·인증 정보는 노출하지 않는다. 준비 데이터 정상 수신 시 이전 실패 상태도 해제한다.
- 회귀 테스트: 수정 전 인코딩·HTTP 상태 보존·오류 분류 3개 실패를 확인하고 수정 후 통과했다. 프론트의 원인 표시·정상 복구 검사도 실패 확인 후 수정했다.
- 전체 Java `test bootJar --offline --console=plain`: **194개 중 184 통과, 10 건너뜀, 실패·오류 0**, 빌드 성공. `strategy-paper.browser.cjs`: **2개 모두 통과**.
- 실환경: 같은 다음 페이지 요청에서 200봉 수신. 개발 서버 자동 반영 후 기존 실행 #2가 2985봉을 저장했고 마지막 처리일은 2026-09-28. 브라우저의 수동 갱신 후에도 저장 개수·현금을 유지하며 `WAITING_DATA`를 표시했다. 실행 #2는 계속 실행 중이며 실패 로그는 보존했다.
- 변경 파일: `marketdata/MarketDataService.java`, `client/TossApiClient.java`, `strategypaper/StrategyPaperService.java`, `strategypaper/StrategyPaperEngine.java`, `static/js/strategy-paper.js`, `MarketDataServiceTests.java`, `StrategyPaperPersistenceTests.java`, `scripts/tests/strategy-paper.browser.cjs`, 이 문서와 사용 안내.
- 증거: `build/paper-candle-red.log`, `build/paper-candle-green.log`, `build/paper-candle-regression.log`, `build/paper-candle-ui-red.log`, `build/paper-candle-ui-green.log`.

### 구현 중 확정한 사항

- 별도 일봉·거래 테이블 대신 실행별 JSON 체크포인트를 사용한다. DB 실행 행 잠금과 마지막 처리 위치, 날짜 중복·수정 검사를 함께 적용해 잔고·입력·이력을 원자적으로 저장한다. 초기 단일 종목 범위에서 테이블·부분 저장 경로를 줄였으며 입력 원문과 SHA-256을 보존한다.
- 시작일은 서버 생성일 다음 한국 날짜다. 과거 이력을 소급 매매하지 않고 준비 데이터로만 사용한다.
- 시세 어댑터는 공용 캔들 캐시 정리 서비스를 호출하지 않고 기존 `MarketDataService`로 실행 전용 데이터를 수집한다.
- 국내 주식 확인은 코드 형식뿐 아니라 제공업체의 시장·통화·주식 분류를 확인한다. 알려지지 않은 분류는 실행을 거절한다.
- UI 요청 식별자는 일반 HTTP 환경에서도 가능한 `crypto.getRandomValues`로 만든다. 실패 후 같은 입력을 재시도하면 같은 식별자를 유지한다.
- 사용 안내·API·변경 파일 목록은 [전략별 모의매매 안내](strategy-paper-trading.md)를 따른다.
