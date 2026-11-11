# 토스 프로젝트 자료 조사와 스탠바이

기준일은 2026-11-10이며 작성자는 토스 팀 자료 조사 담당이다. Toss Invest의 현재 문서, 핵심 소스, 저장된 검증 기록을 확인했다. 다음 지시에서 전략·데이터·브리핑 관련 자료와 근거 파일을 바로 찾을 수 있도록 준비했다. 현재 서버 가동과 테스트 재실행은 확인 범위에 포함하지 않는다.

## 가정

- 토스 방의 프로젝트는 `C:\Users\EM_NB171\Desktop\정성윤\TossInvest\toss-invest-backend`로 해석했다. 해당 위치의 README와 Git 이력에서 Toss Invest 프로젝트임을 확인했다.
- 스탠바이는 현황 파악과 인수인계 자료 준비로 해석했다. 기능 구현이나 실행 환경 변경은 범위에 넣지 않았다.

## 현재 기준

- 조사 시작 당시 브랜치는 `master`, HEAD는 `ac53bf8`이었다. `git status --short --branch`에 변경 파일은 없었다. `origin/master` 표시는 로컬 참조 기준이며 원격 조회는 수행하지 않았다.
- 최신 커밋은 2026-11-10의 3년 일봉·AI 시황·신규 지표 반영이다. 변경 범위는 50개 파일이다. 직전 커밋은 2026-10-29의 오류 수정과 테스트·빌드 기록이다.
- [README](../README.md)는 2026-10-20 기준이므로 최신 기능은 [11월 구현 기록](2026-11-05-data-indicators-briefing-plan.md), [시장 요약](market-briefing.md), [지표 명세](strategy-indicators.md)를 함께 읽는다. [전체 기획서](project-plan.md)의 초기 조사 장은 당시 기록이며 뒤쪽 진행 기록과 구분한다.

## 서비스 흐름과 주요 파일

Toss Invest는 자연어 투자 조건을 구조화된 전략으로 변환하고, 검증·저장·백테스트·결과 비교·전략별 모의매매로 연결하는 국내 주식 분석 프로젝트다. 자연어 해석과 설명은 Codex가 담당하고 실제 조건 판정과 체결 계산은 Java가 담당한다.

- 웹 화면은 [index.html](../src/main/resources/static/index.html), [전략 작성](../src/main/resources/static/js/strategy-workbench.js), [결과 화면](../src/main/resources/static/js/strategy-results.js), [시장 요약 화면](../src/main/resources/static/js/market-briefing.js)에서 시작한다. 정적 HTML·CSS·Vanilla JavaScript를 Spring Boot가 제공한다.
- 전략 계약은 [StrategyDefinition](../src/main/java/com/tossinvest/tossinvestbackend/strategy/StrategyDefinition.java), 검증은 [StrategyValidator](../src/main/java/com/tossinvest/tossinvestbackend/strategy/StrategyValidator.java), 조건 판정은 [StrategyEvaluator](../src/main/java/com/tossinvest/tossinvestbackend/strategy/StrategyEvaluator.java), 계산은 [StrategyIndicators](../src/main/java/com/tossinvest/tossinvestbackend/strategy/StrategyIndicators.java)를 먼저 확인한다.
- 자연어 연결은 `strategy/assistant`, 단일 종목 실행은 `backtest`, 포트폴리오는 `portfolio`, 조합 전략은 `composite`, 비교는 `comparison` 패키지에 있다. 전체 흐름은 [전략 명세](strategy-spec.md), [버전과 실행 저장](strategy-persistence.md), [결과 설명](backtest-results-explanation.md)을 따른다.
- 저장 전략의 모의매매는 `strategypaper`다. 기존 점수제 `paper`와 1분봉 `scalping`은 별도 실행 경로다. [전략별 모의매매 명세](strategy-paper-trading.md)와 [기존 매매 규칙](trading-rules.md)을 구분해 읽는다.
- 시장 요약은 `marketbriefing` 패키지의 [Controller](../src/main/java/com/tossinvest/tossinvestbackend/marketbriefing/MarketBriefingController.java), [Service](../src/main/java/com/tossinvest/tossinvestbackend/marketbriefing/MarketBriefingService.java), [Scheduler](../src/main/java/com/tossinvest/tossinvestbackend/marketbriefing/MarketBriefingScheduler.java)로 연결된다.

설정상 Java 17, Spring Boot 3.5.15, Spring Data JPA, H2, Gradle Wrapper 8.14.5를 사용한다. 기본 포트는 8090이고 H2 경로는 `./data/tossinvest`, 스키마 설정은 `ddl-auto: update`다. 이는 [빌드 설정](../build.gradle), [Wrapper 설정](../gradle/wrapper/gradle-wrapper.properties), [기본 앱 설정](../src/main/resources/application.yaml)에서 확인한 값이며 현재 프로세스의 적용값은 미확인이다.

## 최신 기능에서 확인한 계약

1. 공용 일봉은 한국 날짜 기준 달력 3년을 보관한다. [CandleDataPolicy](../src/main/java/com/tossinvest/tossinvestbackend/backtest/CandleDataPolicy.java)의 `RETENTION_YEARS = 3`과 OHLCV 검증, 관련 수집·가져오기 경로가 있다. 실제 확보 기간은 공급자 응답과 종목 이력에 따라 다르다. 저장된 실행 스냅샷은 별도 보존한다.
2. `MACD`와 `BOLLINGER`가 전략 JSON 타입과 공통 검증·판정 경로에 추가돼 있다. MACD 기간·비교 대상, 볼린저 기간·배수·밴드를 명시해야 한다. 누락값에 12/26/9 또는 20/2를 임의로 채우지 않는다. 신규 조건은 형식 2와 형식 4 조합에 연결한다. 계산·준비기간·교차 계약은 [지표 명세](strategy-indicators.md)를 따른다.
3. 장전 시장 요약은 거래일 08:30 KST에 생성하도록 구성돼 있다. 확정 일봉과 기사 제목의 원본 시각·출처를 보존하고 날짜별 H2 결과를 조회한다. `READY`, `PARTIAL`, `NO_DATA`, `FAILED`를 구분한다. 예약 복구와 제한된 재시도 경로가 있으며 구동·인증·공급자 상태에 따라 실제 생성 여부가 달라진다.
4. 최신 구현 기록에는 조합 전략의 빈 기간 `NO_DATA`, 점수제 모의매매의 실제 일봉 기준 보유 기간, 장 마감 후 일봉 수집 누락 수정이 포함돼 있다. 각 기능의 회귀 테스트 파일도 최신 커밋에 포함돼 있다.

근거는 [최신 구현 기록](2026-11-05-data-indicators-briefing-plan.md), [시장 요약 계약](market-briefing.md), [전략 지표 계약](strategy-indicators.md), 위 핵심 소스와 `git show --stat HEAD`다. 현재 구현 존재와 실제 운영 성공은 구분한다.

## 다음 업무에서 유지할 기준

- 전략·버전·실행 당시 데이터와 결과 스냅샷을 보존한다. 과거 이력 분석을 현재 전략이나 캔들로 다시 계산하지 않는다.
- [CodexClient](../src/main/java/com/tossinvest/tossinvestbackend/strategy/assistant/CodexClient.java)는 ChatGPT 인증 타입을 검사하고 API 키 인증을 거절한다. 자연어 해석·설명·브리핑 생성은 모델 호출 경로이며 저장·검증·계산·기존 이력 조회와 구분한다.
- 형식 1·2는 단일 종목, 형식 3은 포트폴리오, 형식 4는 조합 전략이다. 전략별 모의매매 지원은 단일 종목 형식 1·2다.
- 단일 종목 백테스트의 거래 수익률 합계와 포트폴리오·조합의 자금·비용 적용 성과를 같은 계좌 수익률로 표현하지 않는다. [README의 계산 기준](../README.md)과 [전략 비교 명세](strategy-comparison.md)를 따른다.
- 확정봉 대기 `WAITING_DATA`와 공급자 제한 `MARKET_RATE_LIMITED`를 구분한다. `refresh`, 데이터 수집, 브리핑 생성은 외부 호출이나 저장을 수행하므로 단순 상태 확인과 구분한다.
- `GET`이라는 이유만으로 부작용 없는 상태 확인이라고 판단하지 않는다. [HealthCheckController](../src/main/java/com/tossinvest/tossinvestbackend/controller/HealthCheckController.java)의 토큰·환율 점검은 OAuth나 외부 API를 호출한다.

## 검증 기록과 현재 한계

이번 조사에서는 Git 상태·이력, 문서, 핵심 소스, 아래 기존 결과 파일과 로그를 읽었다. 테스트·빌드·수집·모델 호출은 새로 실행하지 않았다.

- [최초 통합 결과](../build/stage9-verification.json)는 `2026-11-10T01:16:02Z` 기준 `FAILED`다. Java 275개 중 266 통과·9 제외, 실패·오류 0이며 Node 실행 오류가 기록돼 있다. [최초 Node 로그](../build/stage9-node.log)는 53개 중 52 통과·1 실패다.
- [후속 완료 결과](../build/completion-verification.json)는 `2026-11-10T01:36:04Z` 기준 `PASSED`다. Java는 266 통과·9 제외, Node는 53개 모두 통과이고 bootJar·diff 검사 통과를 기록한다. [최종 Node 로그](../build/completion-node-final.log)의 53 통과·실패 0과 [Java 로그](../build/stage9-java.log)의 `BUILD SUCCESSFUL`을 대조했다.
- [최신 구현 기록](2026-11-05-data-indicators-briefing-plan.md)은 최초 브라우저 실패를 테스트 기록 시점 경쟁으로 설명하고, 대기 조건 수정 후 Node 전체를 재실행했다고 기록한다. 최초 Stage 9 결과 파일은 실패 상태로 남아 있다. 이후 테스트 결과를 인용할 때 두 기록을 함께 설명한다.
- 실제 브리핑 `READY`, 5개 항목·17개 출처와 표본 728봉 등의 값은 완료 결과 파일에 저장된 당시 확인 내용이다. 이번 조사에서 현재 DB나 외부 공급자로 재확인한 수치는 아니다.
- 로컬 정적 루트의 HTTP 응답 확인은 실행 승인 단계에서 `rejected by user`로 거부돼 실행되지 않았다. 현재 서버 가동, 표시 화면, 현재 Codex 로그인·외부 연결은 미확인이다.
- [SecurityConfig](../src/main/java/com/tossinvest/tossinvestbackend/config/SecurityConfig.java)에는 전역 `permitAll`, CSRF 비활성화가 있고 기본 설정에서 H2 콘솔을 활성화한다. 원격 공개 전 인증·접근 제어 검토가 필요하다. 현재 네트워크 노출 여부는 확인하지 않았다. 로컬 비밀 설정과 토큰 값은 보고서에 포함하지 않았다.

## 바로 이어서 확인할 자료

1. 기능 범위와 최신 상태는 README, 11월 구현 기록, 시장 요약·지표 명세 순서로 읽는다.
2. 전략 변경 지시는 `StrategyDefinition → StrategyValidator → StrategyIndicators/StrategyEvaluator → assistant 스키마 → workbench/results → 관련 테스트` 순서로 연결 지점을 찾는다.
3. 데이터 이상은 `CandleDataPolicy`, 수집·CSV 가져오기·Repository와 `CandleRetentionTests`를 확인한다. 이전 실행 스냅샷과 공용 캔들은 구분한다.
4. 브리핑 이상은 저장 날짜·자료 기준 시각·상태·출처를 먼저 구분하고 `MarketBriefingService`, `MarketBriefingSources`, `MarketTradingCalendar` 및 해당 테스트를 확인한다. 생성·재시도는 별도 실행 판단 대상이다.
5. 재현 검증의 사전 조건과 실행 격리는 [통합 검증 안내](integration-demo.md)를 따른다. 테스트 수는 과거 숫자로 고정하지 않고 실행 결과 파일로 판정한다.

## 대표 확인 필요

아래 명령은 모두 미실행이다. 작업 디렉터리는 위 프로젝트 루트다.

- 테스트·빌드·브라우저 검증 재실행은 스크립트 실행 제한 때문에 보류했다. Java 17·Node·Playwright·Edge 준비 후 실행할 문서상 명령: `.\scripts\verify-stage9.ps1`.
- 로컬 정적 응답 확인은 승인 거부로 보류했다. 거부된 정확한 명령: `curl.exe --silent --show-error --max-time 5 --noproxy 127.0.0.1 --head --output NUL --write-out 'HTTP %{http_code}\n' http://127.0.0.1:8090/`. 구체적인 거부 사유는 제공되지 않았다.
- 삭제·배포·push·외부 전송·운영 데이터 변경은 이번 스탠바이 범위에 필요한 작업이 없어 실행 명령을 만들지 않았다.

## 변경 파일

자료 조사 담당이 추가한 파일은 `.docs/research-standby-2026-11-10.md` 한 개다. 애플리케이션 소스·설정·DB는 변경하지 않았다. 화면이나 페이지를 만들거나 수정하지 않아 결과 화면 캡처는 생성하지 않았다.
