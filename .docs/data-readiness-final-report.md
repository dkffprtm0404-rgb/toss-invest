# 단일 종목 백테스트 데이터 준비 상태 — 최종 취합 보고

- 보고자: 서버 개발 담당 / 토스 팀장
- 기준일: 2026-11-11
- 프로젝트: `C:\Users\EM_NB171\Desktop\정성윤\TossInvest\toss-invest-backend`
- 브랜치: `feat/data-readiness-20261111`
- 기준 HEAD: `ac53bf84bf599075b974e9b73ded6445075f3b70`. 미커밋 변경·신규 파일을 함께 확인했다.
- 판정: **하위 업무의 소스 구현·회귀 작성 결과를 취합했다. 컴파일·자동 테스트·실제 화면 검증과 캡처는 미완료이므로 기능 전체의 검증 완료 판정은 보류한다.**

## 확인한 구현

1. 저장된 단일 종목 전략 형식 1·2에 `GET /api/strategies/{id}/data-readiness`를 연결했다. 저장 버전·종목·기간·체결 방식을 명시하고 정상 응답에 `Cache-Control: no-store`를 설정한다.
2. 전체 저장 기간·봉 수, 요청 구간의 기간·봉 수, 시작 전 이력과 진입 판단 준비를 구분한다. 기존 `validatedBars`, `entryReady(index, nextOpen)` 판정을 공유하며 종료일 뒤의 봉은 지표 준비에 사용하지 않는다.
3. `READY`, `PARTIAL`, `NO_DATA`, `INSUFFICIENT_DATA`, `INVALID_DATA`와 HTTP 조회 실패를 구분한다. 계산 불가 값은 `null`, 달력 기준의 고정 필요 봉 수도 `null`로 유지한다.
4. 화면에서는 `READY/PARTIAL`의 현황·주의사항 확인란을 체크해야 실행할 수 있다. 종목·기간·체결 방식·저장 전략/버전 변경과 재조회는 이전 확인을 해제한다. 요청 순번과 입력 키로 늦은 성공·실패 응답을 무시한다.
5. 조회 실패를 한국어 복구 안내로 표시하고 기간·종목 입력으로 이동하거나 수동 데이터 준비 방법을 볼 수 있다. 새 조회·복구 동선에 자동 수집·CSV 가져오기·Codex 호출은 없다.
6. `SavedBacktestService`의 읽기 전용 조회는 저장 버전과 캔들 조회 및 준비 판정만 수행한다. 기존 이력 조회는 저장된 실행 스냅샷을 계속 사용한다. 이는 소스 확인이며 런타임 외부 호출 0회나 운영 DB 불변을 계측한 결과는 아니다.

계약·상태 정의와 복구 절차는 [데이터 준비 상태 명세](data-readiness.md)를 따른다. 형식 3·4의 사전 점검은 이번 적용 범위 밖이다.

## 하위 업무 취합과 추가 보완

- 화면 담당의 구현·회귀 작성 보고와 현재 JavaScript/CSS 및 테스트 변경을 대조했다.
- QA-01과 코드 검토의 전략 상세 GET 응답 누락은 `scripts/tests/strategy-readiness.browser.cjs:37-38`의 `/api/strategies/7`, `/api/strategies/8` 응답으로 보완돼 있다. 브라우저 재실행 성공은 확인하지 않았다.
- 코드 검토에서 남긴 달력 조건 회귀 공백은 품질 확인 담당에게 실제로 위임하고 아래 3개 검사를 추가했다. 서버 담당도 저장된 검사와 인접 판정기·타입·테스트 자료를 정적으로 대조했다. 제품 소스 추가 변경은 없다.

추가 검사:

1. `DataReadinessTests.calendarWeekRangeRatioBecomesReadyOnTheFirstFullCalendarBoundary`: 최초 이력일이 2025-01-01일 때 52주 경계 직전인 12-30은 부족, 12-31은 준비, 경계를 포함한 구간은 일부 준비로 단언한다. 범위의 과거 경계 포함·당일 고가 제외도 독립적인 수치로 확인하도록 작성했다.
2. `DataReadinessTests.monthEndTrendReadinessUsesTheLastReadyAverageAtTheCalendarCutoff`: 2024년 윤일의 SMA2와 3월 말 한 달 전 기준을 사용한다. 3-28은 부족, 최초 준비일 3-29, 3-31의 기준값 15·현재값 55를 단언한다.
3. `StrategyPersistenceApiTests.dataReadinessAllowsShortVolumeOrBeforeCalendarWarmupWithoutSideEffects`: 같은 저장 전략의 달력 조건만 있는 버전은 부족, 짧은 VOLUME OR를 추가한 버전은 `READY`로 응답하도록 작성했다. `calendarBasedWarmup=true`, `requiredWarmupBars=null`, 최초 준비일·봉 수·no-store와 조회 전후 캔들 수/내용·전략/버전·실행 이력을 비교한다.

달력 주 기준은 `StrategyIndicators.range`의 `minusWeeks`와 과거 봉만 포함하는 범위, 월 기준은 `monthsAgo`의 `minusMonths`와 기준일 이하의 마지막 준비 평균, OR는 `StrategyEvaluator.Evaluation.entryReady`의 `anyMatch`를 근거로 기대값을 대조했다. 이 검사는 합성 일봉을 사용하며 실제 거래일 달력이나 거래 성과 검증이 아니다.

## 작성된 검사와 실제 검증

- Java 준비 상태 검사: **16개**.
- 이번 기능의 API 검사: **3개**.
- 화면 상태 단위 검사 추가: **3개**.
- 신규 준비 상태 브라우저 검사: **8개**.
- 기존 저장→실행 브라우저/Java 격리 서버 시나리오에 준비 상태 확인 단계를 반영했다.

위 숫자는 모두 작성된 검사 선언 수다. 초기 하위 보고의 Java 14개·API 2개에서 이번에 2개·1개를 더했다. 통과 수를 의미하지 않는다.

실제로 수행한 확인:

- 현재 소스·diff·관련 명세·하위 업무 보고서를 읽고 요구사항과 대조했다.
- `git diff --check` 종료 코드 0. README의 LF→CRLF 예고 경고만 있었다. 이 명령은 미추적 파일을 포함하지 않는다.
- 신규 DTO·Java 검사·브라우저 검사·명세·이 보고서를 `rg`로 따로 검색해 후행 공백·병합 충돌 표식의 일치 항목이 없음을 확인했다(종료 코드 1, 출력 없음). 구문 검사나 컴파일 성공을 의미하지 않는다.
- `scripts/verify-stage9.ps1:99-103`은 `*.test.cjs`·`*.browser.cjs`를 자동 선택하므로 새 브라우저 검사도 실행 대상이다. `*.server.cjs`는 Java 소유 격리 fixture에서 호출하는 기존 흐름을 사용한다. 이번 취합에서는 실행하지 않았다.

미완료 확인:

- Java 컴파일과 JUnit, JavaScript 구문 검사 및 Node/브라우저 회귀.
- 실제 HTTP 직렬화·화면/API 결합, 모바일 너비와 키보드 동작.
- 현재 구현의 실제 결과 화면 캡처 **0장**. 서버와 브라우저 스크립트를 기동하지 않았고 과거 PNG를 현재 증거로 재사용하지 않았다.

독립 코드 검토에서 실제 중대 결함을 확인하지 못했다는 보고는 정적 검토 범위의 결론이다. 새 회귀를 포함한 최종 변경의 실행 성공은 아직 알 수 없다.

## 변경 파일

이번 취합에서 보완한 파일은 다음 4개다.

- `src/test/java/com/tossinvest/tossinvestbackend/backtest/DataReadinessTests.java`: 달력 경계 2개 추가.
- `src/test/java/com/tossinvest/tossinvestbackend/backtest/StrategyPersistenceApiTests.java`: 달력·짧은 OR의 API 검사 1개 추가.
- `.docs/data-readiness.md`: 최신 검사 수와 취합 결과 연결.
- `.docs/data-readiness-final-report.md`: 이 보고서 신규 작성.

앞선 하위 업무를 포함한 기능 변경 범위:

- 서버 4개: `src/main/java/com/tossinvest/tossinvestbackend/` 아래 `backtest/DataReadiness.java`(신규), `backtest/UserStrategyBacktestEngine.java`, `backtest/SavedBacktestService.java`, `strategy/SavedStrategyController.java`.
- 화면 2개: `src/main/resources/static/js/strategy-workbench.js`, `src/main/resources/static/css/strategy-workbench.css`.
- Java 검사 2개: 위의 `DataReadinessTests.java`(신규), `StrategyPersistenceApiTests.java`.
- 화면 검사 4개: `scripts/tests/strategy-workbench.test.cjs`, `strategy-workbench.browser.cjs`, `strategy-workbench.server.cjs`, `strategy-readiness.browser.cjs`(신규). 모두 `scripts/tests/` 안이다.
- 문서: `README.md`, `.docs/data-readiness.md`(신규), 이 최종 보고서(신규).

기존 미추적 기획·조사 문서 3개는 기능 변경 범위에 포함하지 않았고 수정하지 않았다. 캔들·운영 DB·저장 전략·스냅샷을 변경하는 명령은 수행하지 않았다.

하위 보고 원문은 Git 제외 경로인 `build/data-readiness-frontend-review.md`, `build/data-readiness-qa-review.md`, `build/data-readiness-code-review.md`다. 이들은 각각의 확인 시점·범위를 기록하며, 추가 회귀의 최신 수치는 이 문서와 명세를 따른다. 업무 완료 이벤트는 대표가 전달한 인수인계를 근거로 삼았으며 전체 업무 보드의 상태를 직접 조회한 것으로 보고하지 않는다.

## 가정

- 첫 적용은 저장된 단일 종목 전략 형식 1·2다. `PARTIAL`은 제한 사항과 현황을 명시적으로 확인한 뒤 실행할 수 있다.
- 준비 상태는 조회 시점의 진입 판단 준비 정보다. 매도 준비·거래일 완전성·실제 체결·거래 발생·수익을 보장하지 않는다. 조회 뒤 캐시는 달라질 수 있고 최종 실행 근거는 실행 스냅샷이다.
- 이번 지시는 로컬 소스 보완과 결과 취합을 허용하지만 기존 실행 금지는 해제하지 않은 것으로 적용했다.

## 대표 확인 필요 — 전부 미실행

품질 확인 담당에게 **`[대표 확인 필요·미실행] 데이터 준비 상태 통합 검증과 결과 화면 캡처`** 카드를 실제로 등록·배정했다. 실행 제한 해제 전에는 실행하거나 검증 완료로 처리하지 않도록 명시했다.

대표 확인 후 프로젝트 루트에서 검토할 명령:

```powershell
node --check src/main/resources/static/js/strategy-workbench.js
node --check scripts/tests/strategy-readiness.browser.cjs
node --check scripts/tests/strategy-workbench.test.cjs
node --check scripts/tests/strategy-workbench.browser.cjs
node --check scripts/tests/strategy-workbench.server.cjs
.\scripts\verify-stage9.ps1 -Offline
```

Java 17·기존 Gradle 의존성 캐시·Node/Playwright/Edge와 고정 외부 응답·테스트 DB 격리를 먼저 확인해야 한다. 전체 검증에는 Java 테스트·`bootJar`·Node/브라우저·Java 격리 서버 시나리오가 포함된다. `*.server.cjs`는 독립 실행하지 않는다. 실제 실행 화면 1~2장만 `report_image`로 보고하는 항목도 위 카드에 포함했다.

이전 `javac`와 `node --check`는 자동 승인 검토에서 `Rejected(rejected by user)`로 거부됐다는 인계를 확인했다. 이번 일부 PowerShell 파일 읽기도 같은 문구로 거부됐으며 상세 사유는 제공되지 않았다. 허용된 읽기 전용 `rg`·Git 확인은 수행했으나 거부된 컴파일·구문 검사나 프로젝트 스크립트는 재시도하지 않았다.

삭제·배포·push·실제 외부 전송·실제 수집은 이번 범위에서 필요한 후속 작업이 없어 실행 명령을 제안하지 않았다.
