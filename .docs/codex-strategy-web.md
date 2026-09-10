# Codex 전략 입력과 웹 연결 (5단계)

## 승인된 설계

2026-09-10 사용자가 개인 PC에서 ChatGPT 구독 로그인으로 Codex를 연결하고 API 과금을 사용하지 않는 방식을 승인했다. 기존 전략 표현·검증·버전 저장·백테스트 API와 정적 대시보드를 재사용한다.

- Java 17 / Spring Boot / Jackson / H2를 유지한다. 별도 Node 서비스나 프론트엔드 프레임워크를 추가하지 않는다.
- Java가 설치된 `codex app-server`를 stdio JSON-RPC로 호출한다. 매 요청에서 `account/read`로 ChatGPT 인증을 확인하고 API 키·다른 제공업체·미로그인을 거절한다. 일반 OpenAI API 호출 및 과금 경로로의 자동 전환은 구현하지 않는다.
- 모델은 `gpt-5.6-terra`를 사용하고 설정으로 바꿀 수 있다. Codex 구독 한도는 적용된다. 추가 크레딧 구매나 자동 결제는 수행하지 않는다.
- 개인 PC용 기능이다. Codex 경로는 서버 설정에서만 지정하고 HTTP 요청으로 명령·모델·경로를 받지 않는다. 새 연동 API는 loopback 및 동일 출처 요청으로 제한한다.
- 전략 변환에는 도구 실행이 필요 없다. 별도 작업 디렉터리, 읽기 전용 권한, 도구 비활성화, 임시 세션과 구조화 응답을 사용한다. 인증 파일을 직접 파싱·전달하거나 API 응답에 노출하지 않는다.
- 자연어 원문과 추가 보완 입력을 받아 지원하는 MA_CROSS / RSI / VOLUME 및 AND / OR, 손절·익절·시간 청산·명시적인 기존 트레일링 정책만 표현한다. 필수값 누락은 null과 보완 질문으로 남기며 임의의 수치나 점수제 필터를 추가하지 않는다.
- 서버가 LLM 출력의 JSON 타입·필드·수치와 전략 명세를 재검증한다. 사용자가 조건을 확인하고 보완한 뒤 저장한다. 변환 요청은 저장·백테스트를 실행하지 않는다.
- 저장은 기존 전략 버전 API를 사용한다. `originalPrompt`에는 원문을 그대로 두고 보완 입력이 있으면 `\n\n[보완 입력]\n` 뒤에 원문 그대로 덧붙여 재현 근거를 남긴다.
- 화면은 자연어 입력 → 보완·조건 확인 → 명시적 확인과 저장 → 종목·기간·체결 방식 선택 → 결과/이력 조회를 제공한다. 결과는 거래 단위 지표이며 자금·비용 미적용임을 표시한다.
- 고급 차트·AI 성과 설명·전략 비교·모의매매 연결은 후속 단계다.

## 구현 계획

이 계획은 위 승인 설계를 구현한다. `test-driven-development`로 실패를 확인한 후 구현하고, `subagent-driven-development`로 독립적인 화면 작업과 최종 리뷰를 수행한다. 현재 체크아웃에 이전 단계의 미커밋 변경이 있으므로 그대로 보존하며 자동 커밋은 하지 않는다.

- [x] 서버 계약: `POST /api/strategy-assistant/interpret`에 `{prompt, clarifications}`를 받고 `{strategy, issues, questions, unsupported, ready}`를 반환한다. `GET /api/strategy-assistant/status`는 `{ready, code, message, model}`만 반환한다. `POST /api/strategy-assistant/validate`는 전략 JSON 자체를 받고 같은 검증 결과 구조를 반환한다. POST에는 `X-Strategy-Local: 1`이 필수다.
- [x] `strategy/assistant/`에 Codex 프로세스·프로토콜, 구조화 출력 스키마/프롬프트, 전략 해석 서비스, 컨트롤러와 로컬 요청 제한을 구현한다. `CODEX_NOT_AVAILABLE`, `CODEX_LOGIN_REQUIRED`, `CODEX_API_KEY_NOT_ALLOWED`, `CODEX_TIMEOUT`, `CODEX_BUSY`, `CODEX_FAILED`, `CODEX_INVALID_RESPONSE`를 정해진 한국어 메시지로 반환한다. 공급자 원문 오류나 인증 값은 반환하지 않는다.
- [x] 실제 프로토콜을 흉내 내는 테스트 프로세스로 ChatGPT 성공, API 키 차단, 실패·시간 초과·잘못된 결과를 검증한다. 메모리 H2 API 테스트로 해석 → 검증 → 저장 → 실행 → 재조회 및 누락값·미지원 조건 차단을 검증한다.
- [x] `static/index.html`, `static/js/strategy-workbench.js`, `static/css/strategy-workbench.css`에 기존 대시보드와 연결된 작업 화면을 구현한다. 기존 계좌/모의매매 화면은 유지한다. 모델 출력은 textContent로 표시한다. 변경된 초안의 확인 상태를 해제하고 요청 중 중복 버튼을 막는다.
- [x] 테스트·bootJar, 실제 Codex 구독 호출, 브라우저 핵심 흐름과 빈 결과/오류 상태를 확인한다. `README.md`와 이 문서에 실행 방법·검증 결과·제약을 기록한다.

## 실행과 설정

1. 서버와 같은 OS 사용자로 `codex login`을 실행하고 ChatGPT 계정으로 로그인한다. `codex login status`에서 `Logged in using ChatGPT`를 확인한다. API 키 인증은 사용하지 않는다.
2. 저장소 루트에서 Java 17로 `./gradlew.bat bootRun --args='--server.address=127.0.0.1'`을 실행한다.
3. [대시보드](http://localhost:8090/) 상단에서 자연어 전략을 입력한다. 보완 질문이 있으면 보완 입력란에 답하고 다시 해석한다.
4. 조건을 수정했다면 `입력 조건 검증`을 누른다. 원문·조건을 확인한 뒤 체크하고 새 전략 또는 선택 전략의 새 버전으로 저장한다.
5. 종목·시작일·종료일·체결 방식을 선택하여 저장된 버전으로 실행한다. 편집 중인 미저장 초안은 실행하지 않는다. 결과와 이전 버전의 실행 이력을 조회할 수 있다.

설정은 환경변수로 주입한다. 설정 파일이나 HTTP 요청에 API 키를 추가할 필요가 없다.

- `STRATEGY_CODEX_EXECUTABLE`: 기본 `codex`. IDE/서버가 PATH에서 찾지 못하면 설치된 실행 파일의 절대 경로를 지정한다. Windows 확인 명령: `(Get-Command codex).Source`.
- `STRATEGY_CODEX_MODEL`: 기본 `gpt-5.6-terra`. 구독에서 이용 가능한 모델만 지정한다. 모델 사용 불가 시 자동 대체하지 않는다.
- `STRATEGY_CODEX_TIMEOUT_MILLIS`: 기본 `120000`, 적용 범위 1000~300000ms. 상태 확인은 20초 제한이다.
- `SERVER_ADDRESS=127.0.0.1`: 개인 PC용 서버 바인딩을 권장한다. 연동 API 자체도 원격 주소·Host·Origin·Sec-Fetch-Site를 검사하고 POST에는 `X-Strategy-Local: 1`을 요구한다. 프록시 전달 헤더를 신뢰하지 않는다.

원문·보완 입력은 각각 6000자까지다. 저장한 원문을 다시 선택하면 결합한 보완 입력을 두 입력란으로 복원한다. 외부에서 저장한 더 긴 원문 등 안전하게 분리할 수 없는 값은 보존하고 재해석 전에 정리하도록 안내한다. 값 누락이나 미지원 조건은 확인 체크를 차단한다. 종목 가격·비용·자금 등의 실행 정책에 기본값을 추가하지 않는다.

이 기능은 로컬 캔들 캐시를 사용한다. 데이터가 없으면 `NO_DATA`, 지표 준비 데이터가 부족하면 `INSUFFICIENT_DATA`, 청산 거래가 없으면 `NO_TRADES`를 표시한다. 기존 토스 계좌·시세 화면의 연결 상태와 Codex 상태는 별개다. 기존 스케줄러의 실행 정책은 변경하지 않았다.

## 오류와 한도

- `CODEX_LOGIN_REQUIRED`: 같은 사용자로 ChatGPT 로그인 필요.
- `CODEX_API_KEY_NOT_ALLOWED`: API 키/다른 제공업체 인증 또는 사용자 정의 OpenAI 공급자 설정을 거절했다. 구독 전용 경로를 보장하기 위해 일반 API로 전환하지 않는다.
- `CODEX_LIMIT_REACHED` (429): Codex가 `usageLimitExceeded`를 반환했다. 한도 복구 후 재시도하며 크레딧 구매·사용량 초기화·API 전환은 하지 않는다.
- `CODEX_BUSY` (429): 한 번에 하나의 해석 또는 연결 확인만 수행한다.
- `CODEX_TIMEOUT` (504): 제한 시간 안에 완료하지 못했다. 소유한 프로세스를 종료하고 재시도 가능 상태로 복구한다.
- `CODEX_FAILED` / `CODEX_INVALID_RESPONSE` (502): 공급자 실패·프로토콜 오류·지원하지 않는 도구 요청·잘못된 모델 응답. 공급자 오류 원문이나 로컬 인증 정보는 화면에 노출하지 않는다.
- `CODEX_NOT_AVAILABLE` (503): Codex 실행 경로 또는 설치 확인 필요.
- `LOCAL_ONLY` (403): 로컬 동일 출처 조건을 만족하지 못했다.

해석 중 실패해도 기존 전략과 실행 결과는 유지된다. 자동 재시도는 하지 않으며, 변환 자체는 DB에 전략을 저장하거나 엔진을 실행하지 않는다. LLM이 표현을 정확히 해석했는지는 사용자가 최종 확인해야 한다.

## 검증 실행 방법

일반 Java 테스트는 LLM을 호출하지 않는다. 실제 Codex 호출과 브라우저 통합 검증은 환경변수를 명시할 때만 실행한다. `CodexLiveTests`는 구독 로그인으로 명시적인 골든크로스와 누락값 질문을 검사한다. `StrategyWebWorkflowTests`는 테스트 H2·합성 캔들·로컬 HTTP 서버를 사용하며, `RUN_CODEX_LIVE=true`이면 실제 Codex를 브라우저 흐름에 연결한다. 운영 DB와 기존 스케줄러는 이 테스트에 사용하지 않는다.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
.\gradlew.bat test bootJar --offline --console=plain

# 선택: 설치된 Node + Playwright + Edge가 필요하다.
# NODE_PATH는 playwright가 설치된 node_modules 경로를 지정한다.
$env:RUN_CODEX_LIVE = 'true'
$env:RUN_WEB_SMOKE = 'true'
.\gradlew.bat test bootJar --rerun-tasks --offline --console=plain

# HTTP 대체 응답을 사용하는 독립 화면 테스트 (Codex 호출 없음)
node --test scripts/tests/strategy-workbench.test.cjs scripts/tests/strategy-workbench.browser.cjs

# 선택 검증을 마친 후, 같은 터미널의 다음 테스트에서 실제 호출을 방지한다.
$env:RUN_CODEX_LIVE = 'false'
$env:RUN_WEB_SMOKE = 'false'
```

실제 확인한 Codex 버전은 `0.153.4`다. App Server의 실험적 필드(`environments`, `selectedCapabilityRoots`)를 사용하므로 다른 버전에서는 프로토콜 호환성을 확인해야 한다. 일반 API 키로 대체하는 호환성 처리는 없다.

## 검증 결과 (2026-09-10)

- Java 테스트 **91개 통과**, 실패·오류·건너뜀 0개. 실제 Codex 호출을 사용하는 테스트 2개와 실제 브라우저 통합 테스트 1개를 포함한다. `bootJar` 성공.
- Node/Edge 화면 테스트 **7개 통과**. 수치 변환, 누락값, 확인 상태 무효화, 요청 중 편집 잠금, 미지원 조건 보완, 저장·실행 요청과 이력 재조회, 긴 원문 복원과 모바일 긴 문구 줄바꿈을 검증했다.
- 실제 구독 호출에서 SMA 5/20 골든크로스와 손절 -0.05 변환을 확인했다. 추가 진입 필터·청산 조건이 없었다. 종류·기간이 생략된 골든크로스 입력은 보완 질문을 반환하고 실행 가능 상태가 되지 않았다.
- 실제 웹 화면 → Codex App Server → 서버 검증 → H2 전략 저장 → 합성 일봉으로 백테스트 → 거래·결과 조회 → 페이지 새로고침 → 당시 실행 이력 조회를 검증했다. 한 개 이상의 청산 거래가 발생했고 조회는 재실행을 만들지 않았다. 실제 금융시장 성과를 검증한 것은 아니다.
- 읽기 전용 코드 리뷰에서 발견한 긴 보완 입력 재조회 문제를 수정했고 재검토에서 해소를 확인했다. 프로토콜 완료 알림이 RPC 응답보다 먼저 오는 경우도 회귀 테스트로 보호한다.
- 산출물: `build/codex-live-draft.json`, `build/strategy-web-smoke.log`, `build/strategy-workbench-desktop.png`, `build/strategy-workbench-mobile.png`, `build/reports/tests/test/index.html` (모두 로컬 build 산출물).

**완료 범위:** 승인된 개인 PC용 5단계. 공개 배포·사용자별 인증, 고급 결과 차트·AI 성과 설명, 전략 비교, 전략별 모의매매는 이 단계의 완료 범위에 포함하지 않는다. 기존 운영 DB를 변경한 시연이 아니라 테스트 전용 DB로 검증했다. 구독 한도와 네트워크 상태에 따라 실제 Codex 호출은 실패할 수 있으며, 저장된 수치 결과 조회에는 Codex 호출이 필요 없다.

## 변경 파일

- `src/main/java/com/tossinvest/tossinvestbackend/strategy/assistant/`: 프로세스 실행, RPC 세션, ChatGPT 인증 검사, 해석·검증 API, 로컬 접근 제한과 오류 계약.
- `src/main/resources/codex/strategy-output.schema.json`: 모델 응답 JSON Schema.
- `src/main/resources/static/index.html`, `static/js/strategy-workbench.js`, `static/css/strategy-workbench.css`: 대시보드 작업 화면.
- `src/test/java/com/tossinvest/tossinvestbackend/strategy/assistant/`: 서비스·프로토콜·API 테스트 및 선택 실행 실연동/웹 통합 테스트.
- `scripts/tests/strategy-workbench*.cjs`: Node 단위·브라우저 테스트.
- `README.md`, `.docs/codex-strategy-web.md`: 실행·설정·검증 안내.

## 참고

- [Codex 인증](https://learn.chatgpt.com/docs/auth)
- [Codex App Server](https://learn.chatgpt.com/docs/app-server)
- [전략 저장 계약](strategy-persistence.md)
- [전략 실행 계약](user-strategy-backtest.md)
