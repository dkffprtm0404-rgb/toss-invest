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

## ⚠️ 실행 전 필수 설정 (API 키)

이 저장소/압축파일에는 **보안을 위해 실제 API 키를 포함하지 않았습니다.**
실행하려면 아래 위치에 본인의 키를 직접 채워 넣어야 합니다.

### 1. `src/main/resources/application.yml` (또는 `application-secret.yml`)

아래와 같이 **플레이스홀더(`YOUR_API_KEY_HERE` 등)로 표시된 항목**을 본인의 실제 값으로 교체하세요:

```yaml
# TODO: 아래 항목에 실제 값을 입력하세요
your-broker:
  api-key: YOUR_API_KEY_HERE
  api-secret: YOUR_API_SECRET_HERE
  account-no: YOUR_ACCOUNT_NUMBER_HERE
```

> ※ 실제 프로퍼티 이름(`your-broker.api-key` 등)은 예시입니다.
> 본인의 `application.yml`을 열어 **API 키/시크릿/계좌번호 등 민감한 값이 하드코딩된 모든 위치**를 찾아 위와 같이 플레이스홀더로 바꿔주세요.
> (검색 팁: 파일 내에서 `key`, `secret`, `token`, `password` 등의 키워드로 검색하면 빠르게 찾을 수 있습니다.)

### 2. 환경변수 사용을 권장

가능하면 `application.yml`에는 아래처럼 환경변수 참조만 남기고, 실제 값은 로컬 환경변수나 `.env` 파일로 분리하는 것을 권장합니다:

```yaml
your-broker:
  api-key: ${BROKER_API_KEY}
  api-secret: ${BROKER_API_SECRET}
```

## 실행 방법

1. 위 API 키 설정 완료
2. `./gradlew bootRun` (또는 IDE에서 `Application` 클래스 실행)
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
