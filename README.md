# toss-invest-backend

Spring Boot 기반 자동매매 백엔드 프로젝트입니다.
백테스트 엔진, 페이퍼 트레이딩 시스템, 1분봉 스캘핑 시뮬레이션을 포함합니다.

## 기술 스택

- Java 17
- Spring Boot
- H2 Database
- FinanceDataReader (KOSPI/KOSDAQ 시세 데이터 수집)

## 프로젝트 구조

```
src/main/java/.../
├── backtest/   # 백테스트 엔진 (그리드 서치, 지표 계산 등)
├── paper/      # 페이퍼 트레이딩 (스케줄러, 배치 처리)
```

## 실제 토스 계좌·시세 연결 설정

실제 계좌·시세 기능은 `toss.oauth.client-id`와 `toss.oauth.client-secret`을 사용합니다. 다음 내용을 Git에서 제외된 `src/main/resources/application-local.yaml`에 두고 환경변수 `TOSS_OAUTH_CLIENT_ID`, `TOSS_OAUTH_CLIENT_SECRET`에 본인 값을 설정할 수 있습니다.

```yaml
toss:
  oauth:
    client-id: ${TOSS_OAUTH_CLIENT_ID}
    client-secret: ${TOSS_OAUTH_CLIENT_SECRET}
```

Codex의 자연어 해석·AI 설명은 별도의 ChatGPT 로그인 연결을 사용합니다. 아래 **9단계 자동 통합 검증**은 테스트용 설정과 고정 응답을 사용하므로 토스 인증 정보와 Codex 로그인 없이 실행할 수 있습니다.

## 실행 방법

1. Java 17 및 위 로컬 앱 설정 준비(자동 통합 검증만 실행할 때는 아래 9단계 명령 사용)
2. `./gradlew bootRun` (Windows PowerShell: `.\gradlew.bat bootRun`, 또는 IDE에서 `Application` 클래스 실행)
3. 서버는 기본적으로 `8090` 포트에서 실행됩니다.

## 참고사항

- H2 파일 DB(`./data/tossinvest`)를 사용하며, 같은 DB 경로로 재시작하면 데이터가 유지됩니다.
- 페이퍼 트레이딩은 매일 15:40 KST에 스케줄러가 자동 실행됩니다.

## 사용자 전략 저장과 실행 이력

전략 이름·원문·조건을 버전별로 저장하고, 저장한 버전으로 백테스트를 실행할 수 있습니다.
결과에는 실행 당시 전략·캔들·체결 및 비용 적용 상태·거래 근거를 보존합니다.
API 계약, PowerShell 예제와 재시작 검증은 [전략과 실행 결과 저장 안내](.docs/strategy-persistence.md)를 참고하세요.

## 자연어 전략 화면 · Codex 구독 연동

대시보드 상단에서 자연어 입력 → 조건 확인·보완 → 저장 → 백테스트 → 실행 이력 조회를 사용할 수 있습니다.
로컬 `codex app-server`와 ChatGPT 구독 로그인을 사용합니다. **OpenAI API 키를 입력하지 않으며 API 과금으로 자동 전환하지 않습니다.** 구독의 Codex 사용 한도는 적용됩니다.

```powershell
# 같은 Windows 사용자로 Codex에 ChatGPT 로그인
codex login
codex login status

# 저장소 루트에서 실행. 기본 포트 8090, 기존 파일 H2 사용.
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
.\gradlew.bat bootRun --args='--server.address=127.0.0.1'
```

[로컬 대시보드](http://localhost:8090/)에서 시작합니다. 첫 예시:

> 종가 기준 SMA 5일선이 SMA 20일선을 상향 돌파하면 매수하고, 진입가 대비 5% 손실이면 손절한다. 다른 진입 필터나 청산 조건은 없다.

이동평균 종류·기간처럼 필요한 값을 생략하면 보완 질문이 표시됩니다. 보완 입력 후 다시 해석하고 조건을 확인해야 저장할 수 있습니다. 종목·기간·체결 방식은 실행 화면에서 직접 선택합니다. 로컬 캔들 데이터가 없으면 `데이터 없음` 결과가 저장됩니다.

모델 기본값은 `gpt-5.6-terra`이며 `STRATEGY_CODEX_MODEL` 환경변수로 변경할 수 있습니다. Codex를 찾지 못하면 `STRATEGY_CODEX_EXECUTABLE`에 설치된 실행 파일의 절대 경로를 지정하고 서버를 다시 시작합니다. API 키 인증은 차단되므로 `codex login`에서 ChatGPT 로그인을 사용하세요.

이 화면은 본인 PC용입니다. 새 연동 API는 로컬·동일 출처만 허용합니다. 기존 계좌·시세 화면의 토스 API 설정은 별개이며, 전략 백테스트는 저장된 일봉 데이터를 사용합니다. 자세한 계약과 검증 방법은 [Codex 웹 연동 안내](.docs/codex-strategy-web.md)를 참고하세요.

## 백테스트 결과 차트와 AI 설명

국내주식 일봉 전략 형식 2에서는 기간 고가·저가 돌파, 52주 신고가, 이동평균 위치 비교, 비율 추적손절, ATR 진입·손절을 지원합니다. [확장 조건과 바로 입력할 예제](.docs/strategy-expansion.md)를 참고하세요. 모든 신호와 손절은 종가 확인 기준입니다. 이전에 저장한 전략은 기존 방식으로 실행됩니다.

여러 전략을 함께 입력하면 최대 10개 초안으로 나누어 각각 보완·편집·저장할 수 있습니다. 한 전략의 미지원 조건이나 보완 질문이 다른 전략의 저장을 막지 않습니다. [여러 전략 입력과 사용 방법](.docs/strategy-batch-input.md)을 참고하세요.

형식 3은 KOSPI/KOSDAQ 상대강도 순위·SMA 필터·주간 동일 비중 리밸런싱·일별 고정 손절을 지원합니다. 초기자금과 비용, 과거 시장 구성·거래일 자료를 명시해 포트폴리오 백테스트를 실행하고 계좌 평가액·거래·종목 선정 근거를 저장합니다. [포트폴리오 입력과 데이터 준비](.docs/strategy-portfolio.md)를 참고하세요. 과거 전체 시장 자료의 자동 수집은 포함하지 않습니다.

실행 결과 또는 저장된 실행 이력을 열면 종가·매수/매도 체결점, 거래 수익률 누적 합계와 낙폭 차트, 청산월·청산 사유별 집계를 확인할 수 있습니다. 거래 번호를 누르면 당시 진입·청산 조건의 실제 값과 기준 값을 확인합니다.

`AI 설명 생성`을 누르면 기존 Codex 연결로 중요한 실행 근거를 선택하고, 서버가 수치와 거래 번호를 검증한 설명을 저장합니다. 새로고침이나 서버 재시작 후에도 같은 실행의 설명을 조회하며 재조회에는 모델 호출이 없습니다. AI가 실패해도 지표·차트·거래 기록은 유지되고 설명만 다시 요청할 수 있습니다.

성과는 **청산 거래 수익률의 단순 합산, 비용·자금 미반영, 샤프비율 비연율화** 기준입니다. 낙폭은 이 누적 합계의 고점 대비 차이(%p)이며 일별 계좌 낙폭이 아닙니다. API·예제·검증 방법은 [결과 차트와 AI 설명 안내](.docs/backtest-results-explanation.md)를 참고하세요. Java 코드와 새 H2 테이블을 반영하려면 서버를 다시 빌드·시작하고 화면을 새로고침하세요.

## 저장 전략 비교 (7단계 A)

저장 전략에서 버전을 선택하고 **선택 버전을 비교에 추가**를 누르면 같은 유형의 전략 2~6개를 공통 조건·동일 일봉 데이터로 비교할 수 있습니다. 조건·수익률·낙폭·거래 횟수·성과 곡선과 원본 근거를 표시하고 비교 이력을 저장합니다. 비교 실행은 AI를 호출하지 않습니다.

단일 종목 비교는 자금·비용 미반영 거래 수익률 합계이며, 포트폴리오·조합 비교는 입력한 자금·비용을 반영한 계좌 지표입니다. 다른 유형끼리의 비교는 지원하지 않습니다. 적용하려면 서버 재시작과 브라우저 강력 새로고침이 필요합니다. 자세한 사용법·API·검증은 [저장 전략 비교 안내](.docs/strategy-comparison.md)를 참고하세요.

## 저장 전략 모의매매 (8단계 A)

저장한 단일 종목 전략의 버전을 선택하고 **이 버전으로 모의매매**를 누르면 전략별 가상 계좌를 시작할 수 있습니다. 국내 주식 종목·체결 방식·초기자금·매수 비중·비용을 명시하며, 실행 당시 전략 버전과 계좌·거래 이력을 보존합니다.

생성 다음 한국 날짜부터의 확정 일봉을 사용하므로 시작 직후에는 데이터 대기가 정상입니다. **신규 매수 중지** 이후 보유분은 원래 청산 조건으로 관리합니다. 사용법, 중복 실행 제한과 시세 오류 처리는 [전략별 모의매매 안내](.docs/strategy-paper-trading.md)를 참고하세요.

## 통합 검증과 발표·시연 (9단계)

[통합 검증·시연 안내](.docs/integration-demo.md)에 정상 흐름, 잘못된 입력·데이터 부족·거래 없음·AI 실패·재시작·중복 실행의 확인 기준과 발표 순서를 정리했습니다. 고정 [전략 A](src/test/resources/demo/strategy-a.json)·[전략 B](src/test/resources/demo/strategy-b.json)와 [합성 일봉 CSV](src/test/resources/demo/candles.csv)를 같은 저장 전략의 백테스트 → 비교 → 모의매매 연결 테스트에서 사용합니다.

같은 안내의 **발표 질문** 절에는 실제 저장 필드, 현재 앱에서 읽어 확인한 전략 예시, **AI 해석 → 서버 검증 → 사용자 검토 → 버전 저장 → Java 백테스트 → 당시 결과 조회** 흐름과 발표용 답변을 추가했습니다.

Java 17, Node.js, Playwright, Microsoft Edge가 준비된 환경에서 저장소 루트의 PowerShell로 실행합니다.

```powershell
.\scripts\verify-stage9.ps1 -Offline

# 설치 위치가 다르면 지정합니다. Gradle 캐시가 없으면 -Offline을 생략합니다.
# .\scripts\verify-stage9.ps1 -JavaHome 'C:/Program Files/Java/jdk-17' -NodeModules 'C:/tools/node_modules'
```

스크립트는 전체 Java 테스트·JAR 빌드, 실제 HTTP·H2·브라우저 흐름, Node 화면 회귀를 재실행합니다. AI·시세는 고정 테스트 응답을 사용하고 운영 DB와 분리된 테스트 DB를 사용합니다. 실제 Codex 호출은 끄며 기존 환경변수는 종료 시 복원합니다. 브라우저는 화면 없이 실행한 뒤 자동 종료합니다.

결과는 `build/stage9-verification.json`, `build/stage9-java.log`, `build/stage9-node.log`, `build/reports/tests/test/index.html`과 화면 캡처로 남습니다. 실제 Codex·토스 인증과 장기간 예약 실행은 이 재현 검증에 포함되지 않습니다. 발표용 예상 수치와 검증한 범위는 [상세 안내](.docs/integration-demo.md)를 확인하세요.
