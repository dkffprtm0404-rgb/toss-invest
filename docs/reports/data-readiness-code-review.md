# 데이터 준비 상태 코드 검토

> 문서 정리(2026-11-17): `build/data-readiness-code-review.md`에서 이동했다. 아래 명령·검증 결과·상태와 이전 경로는 작성 당시 기록이다. 현재 문서 위치는 [전체 목차](../README.md)를 따른다.

- 작성자: 코드 검토 담당
- 기준일: 2026-11-11
- 브랜치: `feat/data-readiness-20261111`
- 기준 HEAD: `ac53bf84bf599075b974e9b73ded6445075f3b70`. 검토에는 미커밋 변경과 신규 파일을 포함한다.
- 상태: **인수인계 후 현재 변경분 재검토 완료. 남은 중대 결함 미발견. 컴파일·테스트·캡처 미검증.**
- 범위: 서버·화면·관련 테스트의 로컬 diff와 신규 파일. 프로젝트 소스 수정 없음.

## 최종 판정

현재 서버·화면 diff와 신규 구현·테스트를 다시 읽었으며, 남아 있는 실제 중대 결함은 확인하지 못했다. Bugbot 역할의 독립 검토 1회도 같은 결과였다. 기존 P2 지적은 아래와 같이 소스에서 수정 반영을 재확인했다. 컴파일·자동 테스트·실제 화면 동작의 성공 여부는 미확인이다. 실행 검증을 완료한 것으로 판정하지 않는다.

## 인수인계 후 재검토 기준

- 업무 보드 지시와 `.docs/data-readiness.md`를 현재 계약으로 사용했다. 이전 코드 검토·QA·화면 인계 보고서는 조사 맥락으로만 사용하고 현재 구현과 대조했다.
- `git branch --show-current`, `git rev-parse HEAD`에서 위 브랜치와 HEAD를 재확인했다. 검토 범위는 이 HEAD 대비 작업 트리의 기능 변경과 미추적 DTO·테스트·기능 문서다.
- `NO_DATA`는 요청 구간 봉 0개, `INSUFFICIENT_DATA`는 봉은 있으나 판단 가능 봉 0개, `PARTIAL`은 일부 판단 가능 또는 요청 날짜의 저장 범위 이탈로 구분한다. 날짜 공백을 휴장·누락으로 추정하지 않는다.
- 검토 담당과 독립 검토자는 제품 코드·테스트를 수정하지 않았고 프로젝트 스크립트·서버·컴파일러·테스트·외부 연동을 실행하지 않았다.

## 수정된 지적

### 기존 P2 — 전략 상세 GET 픽스처 누락: 수정 반영 확인, 재실행 미확인

- 최종 파일·라인: `scripts/tests/strategy-readiness.browser.cjs:37-38`.
- 기존 이유: 목록에서 전략을 선택하면 `src/main/resources/static/js/strategy-workbench.js:683-685`의 상세 GET을 거친다. 픽스처의 `/api/strategies/7`·`/api/strategies/8` 응답 누락으로 404가 반환되어 공통 준비 단계에서 전략을 선택하지 못하는 경로였다.
- 확인한 수정: `/api/strategies/7`은 `saved`, `/api/strategies/8`은 `second`를 반환한다. 두 경로 모두 마지막 404 분기보다 앞에 있으며 실제 선택 경로와 일치한다.
- 수정자: 서버 개발 담당. 검토 담당은 소스·테스트를 수정하지 않았다.
- 한계: 브라우저 테스트를 재실행하지 않았으므로 통과를 확인한 것은 아니다.

## 추가 테스트 2개 확인

- `DataReadinessTests.java:140-156`: ATR 기간 2에서 `SAME_DAY_CLOSE`의 필요 준비 봉 수 3과 `INSUFFICIENT_DATA`, `NEXT_DAY_OPEN`의 필요 준비 봉 수 2와 `READY`를 구분한다. 요청 종료일 뒤 봉이 있는 입력에서도 실제 실행에는 사용하지 않아 `NO_NEXT_BAR`가 남는 단언을 포함한다. 기존 `requiredWarmupBars(boolean)`·`entryReady(int, boolean)`·`PendingOrder.status()` 선언과 대조했다.
- `DataReadinessTests.java:159-168`: 같은 UTC 날짜의 `14:30Z`와 `15:00Z`를 사용해 KST 1월 1일·2일을 나누고, 요청 구간 1봉과 준비 이력 1봉을 구분한다. 기존 `Asia/Seoul` 변환과 기대값이 일치한다.
- 최종 `DataReadinessTests`는 테스트 선언 14개다. 이는 소스 확인 결과이며 실행된 테스트 개수가 아니다.

## 남은 테스트 빈틈 — 확인된 구현 결함과 구분

- **달력 조건의 API 판정 경계:** `DataReadinessTests.java:87-98`은 52주 `RANGE_RATIO` 이력 부족만 검사한다. `73-85`의 OR/AND는 거래봉 조건끼리의 조합이다. 달력 조건의 첫 준비 완료일 전후, `MA_TREND`의 월 경계·이전 최근 거래일, 준비되지 않은 달력 조건과 짧은 준비 완료 조건을 OR로 결합했을 때의 `status/firstReadyDate/readyCandleCount`를 새 readiness Java 테스트에서 직접 단언하지 않는다.
- `strategy-readiness.browser.cjs:79-91`은 달력·OR 응답을 고정 값으로 제공하므로 화면 표시 검증이며 서버 계산 검증은 아니다. 기존 `StrategyEvaluator`와 `StrategyIndicators`를 그대로 재사용하는 것을 확인해 이를 실제 판정 오류로 분류하지 않았다.
- **부작용 검증의 한계:** 신규 API 테스트는 이력 미생성과 캔들 건수 유지를 단언한다. 기존 캔들 행 내용·저장 스냅샷의 동일성과 외부 호출 0회를 실행 중 계측하는 단언은 아니다. 이번 외부 호출 부재 판단은 아래 소스 호출 경로 검토에 근거한다.

## 확인한 범위 — 정적 검토

- `DataReadiness.java:9-16`, `UserStrategyBacktestEngine.java:33-77`: 전체 보유 범위·요청 구간·준비 이력을 구분한다. 종료일 뒤 OHLC는 기존 `validatedBars`(`164-184`)에서 제외되며 `entryReady`로 준비 상태를 센다. `ENTRY_READINESS_ONLY`를 포함한다.
- 진입의 달력 개월·주 조건은 `calendarBasedWarmup=true`, `requiredWarmupBars=null`로 표시한다. `INVALID_DATA`의 계산 불가 수치는 `null`이다.
- `SavedBacktestService.java:18,56-64`의 readiness는 클래스의 읽기 전용 트랜잭션을 적용받고 저장 버전·캔들 조회와 엔진 계산만 호출한다. `SavedStrategyService.java:81-85,103-105`도 저장 버전 조회와 JSON 복원이다. 이 조회 경로에 저장·수집·외부 연동·명시적 잠금 호출이 없다.
- `SavedStrategyController`의 신규 GET은 기존 실행 입력 검증을 사용하며 정상 응답에 `Cache-Control: no-store`를 붙인다.
- `StrategyEvaluator.java:61-78,175-197,272-275`의 OR/AND·지표 유효값·ATR 체결 방식별 준비 기준을 재사용한다. 달력 주·월은 `StrategyIndicators.java:25-60`의 KST 기준 과거 구간을 따른다. Java 선언·타입·호출 관계를 대조했으며 컴파일은 실행하지 않았다.
- 화면은 선택 전략·버전·종목·기간·체결 방식을 조회 키로 사용하고 입력 변경과 재조회 때 이전 확인을 무효화한다. 순서 토큰으로 늦은 응답을 배제한다. 미확인·오류·데이터 없음·부족·잘못된 데이터에서는 단일 종목 실행을 차단한다.
- `READY`·`PARTIAL`은 현황 확인란을 체크한 뒤 실행할 수 있다. 잘못된 응답과 조회 오류는 한국어 복구 상태로 처리한다. 포트폴리오·조합은 별도 준비 확인 범위를 안내한다.
- `strategy-workbench.js:139-160,772-859`에서 순번·입력 키·선택 전략/버전을 함께 검사한다. `174-178,801-835`의 새 결과 출력은 `textContent`와 기존 `pairs`를 사용하며 응답을 HTML로 삽입하지 않는다. 수치 타입·범위, 날짜 문자열 형식, 상태 코드 등 응답 검증을 통과하지 못하면 조회 실패로 처리한다.
- `SavedBacktestService.java:107-110`의 저장 실행 결과 조회는 기존 JSON 스냅샷을 사용한다. 화면 이력 조회도 저장 실행 API를 읽으며 readiness로 결과를 덮어쓰지 않는다. 데이터 준비 안내는 명령을 텍스트로 보여 주며 자동 수집·가져오기를 호출하지 않는다.
- 이번 인수인계 후에는 서버·화면·관련 테스트의 현재 diff와 신규 파일을 다시 검토했다. Git 제외 경로의 이전 보고서만으로 현재 구현을 판정하지 않았다.

검토 대상은 Java 구현·테스트 6개, JavaScript·CSS 2개, Node/브라우저 테스트 4개, README·`.docs/data-readiness.md` 2개다. 기존 인계 문서 3개는 기능 변경 범위에 포함하지 않았다.

## 수행한 검증과 미검증

- 최종 `git diff --check`: 종료 코드 0. 출력은 README의 LF→CRLF 경고뿐이었다. 이 검사는 미추적 파일을 포함하지 않는다.
- 신규 DTO·Java 테스트·브라우저 테스트·기능 문서에 `rg -n '[ \t]+$|^(<<<<<<<|=======|>>>>>>>)' <대상 파일>` 실행: 종료 코드 1, 일치 항목 없음. 후행 공백·충돌 표식 검색이며 컴파일·파싱 검사가 아니다.
- 신규 API 테스트는 저장 버전 고정, 이력 미생성, 캔들 건수 유지, 잘못된 버전·역전 기간·빈 캐시를 확인하도록 작성돼 있다. 캔들 건수 단언만으로 기존 행 내용의 불변성과 외부 호출 부재가 계측되는 것은 아니다.
- 선언·기대값을 재확인한 신규 검사는 Java 엔진 14개, API 2개, 화면 상태 단위 3개, 브라우저 8개다. 기존 브라우저·격리 서버 실행 시나리오에도 조회·확인 단계를 추가했다. 모두 작성 수이며 통과 수가 아니다.
- `scripts/verify-stage9.ps1:98-103`의 파일명 검색에 신규 `strategy-readiness.browser.cjs`도 포함되는 것을 소스로 확인했다. 스크립트는 실행하지 않았다.
- 컴파일·JavaScript 파싱·JUnit·Node·브라우저·서버·실제 외부 연동은 이번 검토에서 실행하지 않았다. 이전 `javac`·`node --check` 차단 인계도 성공 근거로 보지 않았다.
- 현재 변경 화면을 렌더링하거나 캡처하지 않았다. 모바일 너비·키보드·실제 API 결합·최종 테스트 통과는 미검증이다.
- 이번 턴의 `Get-Content` 기반 스킬·소스 읽기는 자동 승인 검토에서 `Rejected("rejected by user")`로 거절됐다. 상세 사유는 없었다. 허용된 `rg` 읽기로 필요한 문서를 확인했으며, 거절된 컴파일·테스트 실행을 다른 방법으로 우회하지 않았다.

## 가정

- 이번 읽기 전용 제한에서 사용자가 지정한 `build/data-readiness-code-review.md` 보고서 작성만 허용된 파일 변경으로 해석했다.
- 준비 완료는 기존 `entryReady` 판정의 의미이며 매도 조건 전체·체결 가능성·실제 거래일 완전성을 보장하지 않는다.
- 범위는 저장된 단일 종목 전략 형식 1·2다. 포트폴리오·조합의 사전 점검은 후속 범위다.
- 이번 업무는 구현이 끝난 현재 변경의 재검토로 해석했다. 제품 수정·실행 승인 요청·추가 구현 업무 배정은 하지 않는다. 기존 미추적 `.docs` 문서 3개는 보존한다.

## 대표 확인 필요

다음은 실행하지 않은 검증 명령이며 격리된 테스트 설정·고정 외부 응답을 사용해야 한다.

```powershell
node --check src/main/resources/static/js/strategy-workbench.js
node --check scripts/tests/strategy-readiness.browser.cjs
node --check scripts/tests/strategy-workbench.test.cjs
node --check scripts/tests/strategy-workbench.browser.cjs
node --check scripts/tests/strategy-workbench.server.cjs
node --test --test-concurrency=1 scripts/tests/strategy-workbench.test.cjs scripts/tests/strategy-readiness.browser.cjs scripts/tests/strategy-workbench.browser.cjs
.\scripts\verify-stage9.ps1 -Offline
```

`*.server.cjs`는 기존 Java 격리 서버 검증 경로에서 실행해야 한다. Java 17·기존 의존성 캐시·Node/Playwright/Edge와 테스트 DB 격리를 확인한 뒤 수행할 명령이다. Java 검사 2종은 마지막 통합 명령에 포함되며 개별 Gradle 실행에도 그 스크립트의 격리 환경을 먼저 재현해야 한다. 이번 검토에서 실행하거나 자동 실행을 예약하지 않았다. 현재 변경을 제공하는 격리 환경에서 화면 1~2장과 모바일·키보드 동작을 확인하는 작업도 미완료다.

삭제·배포·push·실제 외부 전송·수집은 이번 검토에 필요한 명령이 없어 실행하지 않았다.

## 변경 파일

- 검토 담당이 작성한 파일: `build/data-readiness-code-review.md`만 해당한다.
- 다른 담당자의 서버·화면·테스트·문서 변경은 검토 대상이며 검토 담당이 수정하지 않았다.
