# 여러 전략 입력 — 2단계

2026-09-15 사용자 계속 진행 승인. 1단계 확장 조건을 그대로 사용하고 여러 독립 전략의 초안을 개별 처리한다. 이후 추가한 포트폴리오 평가·리밸런싱은 [3단계 안내](strategy-portfolio.md)를 따른다. 아래 검증 수치는 2단계 완료 당시 기록이다.

## 설계와 계약

- 전체 원문(1~6000자)을 `/api/strategy-assistant/interpret-batch`로 보내 한 번의 Codex 호출에서 최대 10개 초안을 얻는다. 기존 단일 `/interpret` 계약은 유지한다.
- 모델은 공통 머리말 `sharedContext`와 순서대로 분리한 각 전략의 `sourceText`, 제목, 조건, 보완 질문, 미지원 사항을 반환한다. 원문을 정확히 인용한다. 서버는 원문 순서·공백 외 누락·변조를 검사한다.
- 각 초안의 원문은 공통 머리말과 해당 전략 원문으로 구성한다. 다른 전략 조건은 섞지 않는다. 구분할 전략이 없거나 10개를 넘는 경우 전체 보완 질문을 반환하며 임의로 버리지 않는다.
- 각 전략은 독립적으로 검증한다. 상대강도 미지원이나 손절률 누락은 그 초안만 막는다. 필수값을 임의로 채우지 않는다.
- 화면은 전체 입력란과 초안 선택 목록을 제공한다. 선택 초안에 원문·보완 입력·조건 편집·명시적 확인·저장을 연결한다. 초안을 전환해도 편집값과 질문을 유지하고 선택 시 확인 체크는 해제한다.
- 보완 재해석은 선택 전략만 `/interpret`로 보낸다. 저장·실행·이력은 기존 API를 사용하며 추가 모델 호출을 하지 않는다.
- 새 전체 해석이 실패하거나 전체 보완 질문만 반환되면 이전 초안 목록을 보존한다. 새 초안이 반환되면 새 목록으로 교체한다. 새 해석 전에 교체될 수 있음을 화면에 안내한다. 초안은 브라우저 메모리이며 새로고침 전에 개별 저장해야 한다.
- 저장 전략을 불러와도 초안 목록은 유지한다. 초안 선택 시 이전 저장 대상과 실행 이력을 분리해 다른 전략을 잘못 갱신하거나 실행하지 않게 한다.

## 구현 계획

- [x] 서버 테스트: 복수 전략에서 정상 초안과 누락/미지원 초안 분리, 정확한 원문 보존, 변조/누락/순서 오류 거절, 무전략 입력, 로컬 요청 제한.
- [x] `StrategyBatchService`, `CodexClient`, `StrategyAssistantController` 구현. 기존 전략 출력 스키마를 합성해 조건 정의 중복 방지.
- [x] `DraftCollection` 및 브라우저 테스트: 전환 시 편집/질문/보완 보존, 해당 초안만 재해석·저장, 저장 대상 격리, 전체 해석 실패 시 이전 목록 보존.
- [x] `strategy-workbench.js`에 전체 입력/선택 초안/개별 보완 흐름 연결.
- [x] 원래 6개 전략 입력으로 실제 Codex 분리·질문 배치 확인.
- [x] 전체 Java 테스트·bootJar·Node/브라우저 테스트·코드 리뷰·문서 갱신.

## 검증 예

`A: SMA 5/20 교차, -5% 손절`과 `B: SMA 50/200 교차, -8~10% 손절`을 함께 넣으면 A는 검증 가능하고 B만 정확한 손절률 질문이 있어야 한다. B의 보완을 -9%로 바꿔도 A의 원문/손절률/저장 버전은 변하지 않아야 한다.

## 사용 방법

1. 서버를 다시 시작하고 브라우저를 강력 새로고침한다. 기존 실행 서버는 이번 작업에서 재시작하지 않았다.
2. `전략 원문`에 여러 전략을 제목과 줄바꿈으로 구분해 붙여 넣고 `Codex로 조건 해석`을 누른다. 공통 시장 설명은 앞부분에 적는다.
3. 초안 선택 목록에서 검토할 전략을 선택한다. 선택 전략 원문·조건·질문이 함께 전환된다.
4. 보완이 필요하면 해당 초안의 보완 입력에 답하고 `선택 전략 다시 해석`을 누른다. 예를 들어 마지막 전략의 손절률을 `-9%`로 확정할 수 있다.
5. 조건을 수정했다면 검증하고, 확인 체크 후 개별 저장한다. 저장한 버전으로 종목을 지정해 기존 백테스트를 실행한다.

원문의 오타나 수치 충돌을 자동 확정하지 않는다. ATR 방식·추적 고점 기준·정확한 손절률 등의 보완 질문은 계속 필요하다. 여러 초안 생성은 여러 종목의 포트폴리오 실행이나 여러 전략의 일괄 실행을 의미하지 않는다.

## API

`POST /api/strategy-assistant/interpret-batch`는 기존 로컬·동일 출처 제한과 `X-Strategy-Local: 1` 헤더를 적용한다. 요청 예:

```json
{"prompt":"국내주식 기준.\n\nA: 종가 기준 SMA 5/20 상향 교차 매수, -5% 손절.\n\nB: 종가 기준 SMA 50/200 상향 교차 매수, -8~10% 손절."}
```

응답 `items`의 각 항목에는 `title`, 공통 문맥을 포함한 `prompt`, 기존 단일 응답과 같은 `draft`가 있다. `draft`는 `strategy`, `issues`, `questions`, `unsupported`, `ready`를 가진다. `ready`는 해당 전략의 검증 오류·질문·미지원 항목이 모두 없을 때만 참이다. 사용자의 저장 확인을 대체하지 않는다.

전체 입력을 분리할 수 없으면 `items: []`와 최상위 `questions`를 반환한다. 원문 누락·변조, 순서 오류, 질문도 초안도 없는 모델 응답은 `CODEX_INVALID_RESPONSE`로 거절한다. 공통 문맥과 각 전략 사이의 실제 원문 공백을 복사하여 6000자 한도를 인위적으로 늘리지 않는다.

## 검증 결과 — 2026-09-15

- Java 17에서 `test bootJar --offline --console=plain` 성공. 전체 129개 중 125개 통과, 오류·실패 0, 별도 선택 실행 항목 4개 제외.
- 이 실행에서 `RUN_STRATEGY_BATCH_LIVE=true`, `RUN_WEB_SMOKE=true`를 활성화했다. 실제 ChatGPT 로그인 Codex의 원래 여섯 전략 분리와 실제 HTTP 서버·메모리 H2의 해석 → 확인·저장 → 캔들 백테스트 → 이력 재조회가 통과했다. 실제 서버 흐름의 모델 응답은 테스트 대역이며, 실제 Codex 검증은 별도 테스트다.
- 실제 여섯 전략 결과: 52주 신고가와 SMA 장기 필터 초안은 준비 완료. 첫 전략은 0일/20일 충돌과 추적 고점 기준 질문, ATR 전략은 계산·진입·손절 방식 질문, 마지막 전략은 정확한 손절률 질문. 상대강도 전략에만 순위 선정·포트폴리오 미지원 안내가 남았다.
- Node/Playwright 15개 전부 통과. 수정한 초안 전환, 개별 재해석·저장, 저장 대상 격리, 해석 실패 시 목록 보존, 기존 결과 화면 회귀를 포함한다. 모바일 가로 넘침 검사와 화면 확인 완료.
- 이력 조회 실패 후 이전 편집기가 남는 문제와 6000자 입력에 줄바꿈을 강제로 더하던 문제를 실패 테스트로 재현 후 수정했다. 코드 리뷰에서 두 수정 재확인 완료.
- 생성 증거: `build/strategy-batch-live.json`, `build/strategy-web-smoke.log`, `build/strategy-batch-desktop.png`, `build/strategy-batch-mobile.png`, `build/test-results/test/`. 생성 결과는 다음 테스트에서 덮어쓸 수 있다.

Node 검증 명령:

```powershell
node --test --test-concurrency=1 scripts/tests/strategy-workbench.test.cjs scripts/tests/strategy-batch.browser.cjs scripts/tests/strategy-expansion.browser.cjs scripts/tests/strategy-workbench.browser.cjs scripts/tests/strategy-results.browser.cjs
```

## 변경 파일

- 서버: `strategy/assistant/StrategyBatchService.java` 추가, `StrategyAssistantService.java`, `CodexClient.java`, `StrategyAssistantController.java` 수정.
- 화면: `src/main/resources/static/js/strategy-workbench.js`.
- Java 테스트: `StrategyBatchApiTests.java`, `StrategyBatchLiveTests.java` 추가, `StrategyWebWorkflowTests.java` 수정.
- Node/브라우저 테스트: `scripts/tests/strategy-batch.browser.cjs` 추가, `strategy-workbench.test.cjs`, `strategy-workbench.browser.cjs`, `strategy-expansion.browser.cjs` 수정.
- 문서: 이 문서, `README.md`, `.docs/strategy-expansion.md`, `.docs/codex-strategy-web.md`.

상대강도 상위 N종목·시장 자료 범위의 자동 선정·주간 포트폴리오 리밸런싱은 이후 [3단계](strategy-portfolio.md)에 추가했다. 이 2단계에서는 기존 파일 DB나 실행 중인 서버를 변경하지 않았다.
