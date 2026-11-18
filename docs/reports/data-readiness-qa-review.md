# 데이터 준비 상태 QA 정적 검토

> 문서 정리(2026-11-17): `build/data-readiness-qa-review.md`에서 이동했다. 아래 명령·검증 결과·상태와 이전 경로는 작성 당시 기록이다. 현재 문서 위치는 [전체 목차](../README.md)를 따른다.

- 작성자: 품질 확인 담당 / 토스 QA
- 최초 확인 시점: 2026-11-11 09:41 KST. 동시 작업 중 저장된 파일을 대상으로 했다.
- 최종 보완: 2026-11-11 서버 담당의 QA-01 수정 공유를 반영했다. 지정된 브라우저 검사·Java 검사 최종 파일과 이 보고서만 다시 읽었다.
- 프로젝트: `C:\Users\EM_NB171\Desktop\정성윤\TossInvest\toss-invest-backend`
- 실제 브랜치: `feat/data-readiness-20261111`
- 기준 HEAD: `ac53bf84bf599075b974e9b73ded6445075f3b70`. 검토 대상에는 커밋되지 않은 변경과 신규 파일도 포함한다.
- 판정: 최종 보고 보완 완료. **QA-01은 소스 보완·실행 미검증**이다. Java 검사 14개·신규 브라우저 검사 8개의 작성 현황을 확인했으며, 컴파일·파싱·자동 테스트·실제 화면 검증은 완료되지 않았다.
- QA가 변경한 파일: 이 보고서 1개. 소스·테스트·계약 문서는 수정하지 않았다.

## 발견 사항과 최종 조치

### QA-01 / 최초 P1 / 소스 보완·실행 미검증

- 문제: 신규 브라우저 검사에서 전략 상세 응답이 빠져 공통 준비 단계가 404로 끝나는 경로를 발견했다.
- 수정 근거: `scripts/tests/strategy-readiness.browser.cjs:37-38`. 최초 호출 흐름 근거는 `src/main/resources/static/js/strategy-workbench.js:683-684`다.
- 발견 당시 재현 순서:
  1. 신규 브라우저 검사의 `withWorkbench()` 공통 fixture로 화면을 구성한다.
  2. helper가 전략 #7 선택 버튼을 누른다.
  3. 실제 화면 코드는 `GET /api/strategies/7`로 상세 정보를 요청한다.
  4. 당시 fixture에는 목록·버전·이력 경로만 있고 `/api/strategies/7` 상세 분기가 없어 마지막 404 분기로 갔다.
- 기대 결과: 저장 전략 객체를 반환하여 `selected`를 설정하고 준비 상태 확인을 진행한다. 전략 #8 변경 검사에도 `/api/strategies/8` 상세 응답이 필요하다.
- 발견 당시 실제 결과: 소스 추적에서 404 → `api()` 예외 → `operation()` 오류 안내 경로를 확인했다. 실제 브라우저 실행으로 재현한 결과는 아니었다.
- 최종 실제 결과: 서버 담당이 추가한 `/api/strategies/7 -> saved`, `/api/strategies/8 -> second` 두 상세 응답을 파일에서 확인했다. 해당 경로가 마지막 404 분기 전에 처리되므로, 지적한 응답 누락은 소스상 보완됐다.
- 남은 확인: 공통 helper를 사용하는 브라우저 8개를 실행하지 않았다. 전략 선택 성공과 이후 시나리오의 동작·통과는 미검증이다. QA는 검사 소스를 수정하지 않았다.

### 추가된 Java 경계 검사 — 작성 확인, 실행 미검증

- `DataReadinessTests.java:140`의 `atrReadinessUsesTheExecutionModeWithoutPromisingANextOpenFill`을 확인했다.
  - 재현 순서: 같은 일봉·ATR 조건에 SAME_DAY_CLOSE와 NEXT_DAY_OPEN을 적용하고 요청 종료일 안의 다음 체결 봉을 없앤다.
  - 기대 결과: 체결 방식에 따라 ATR 준비 경계를 구분하며, 진입 판단 준비와 다음 봉 체결 가능성을 혼동하지 않는다.
  - 실제 결과: 검사 소스에 SAME_DAY_CLOSE의 INSUFFICIENT_DATA·필요 준비 3봉, NEXT_DAY_OPEN의 READY·필요 준비 2봉·판단 가능 1봉 및 실행 결과의 `NO_NEXT_BAR` assertion이 있다. 이 코드를 실행한 것은 아니다.
- `DataReadinessTests.java:159`의 `midnightBoundaryUsesKoreanDatesInsteadOfUtcDates`를 확인했다.
  - 재현 순서: UTC `2025-01-01T14:30:00Z`와 `2025-01-01T15:00:00Z` 자료를 사용한다.
  - 기대 결과: KST의 1월 1일 23:30과 1월 2일 00:00을 구분해 요청 구간·준비 이력을 계산한다.
  - 실제 결과: 검사 소스에 READY, 전체 시작일 1월 1일·요청 구간 시작일 1월 2일, 요청 구간 1봉·준비 1봉 assertion이 있다. 실행 결과는 미확인이다.
- `@Test` 선언 14개와 신규 브라우저 `test()` 선언 8개를 파일 읽기로 확인했다. 이 숫자는 작성된 검사 수이며 성공 수가 아니다.

## API·화면 계약 검토 결과

- `SavedStrategyController.java:66-74`: GET 경로에 저장 버전·종목·시작일·종료일·체결 방식이 필수이며 `Cache-Control: no-store`를 지정한다.
- `SavedBacktestService.java:18,56-64`: 읽기 전용 트랜잭션에서 지정 저장 버전을 읽고, 기존 단일 종목 입력 검증 후 해당 종목의 저장 캔들을 조회한다.
- `DataReadiness.java:9-16`: null을 포함해 직렬화하도록 선언한 응답 record와 변경 불가 경고 목록을 확인했다. 실제 HTTP 직렬화는 실행하지 않았다.
- 전체 저장 현황: `totalCandleCount`, `availableStartDate`, `availableEndDate`.
- 요청 구간 현황: `candleCount`, `actualStartDate`, `actualEndDate`.
- 준비 현황: `warmupBars`, `requiredWarmupBars`, `calendarBasedWarmup`, `firstReadyDate`, `readyCandleCount`.
- 상태·입력·오류: `status`, `symbol`, `startDate`, `endDate`, `invalidDataDate`, `warnings`.
- `UserStrategyBacktestEngine.java:32-76,164-184`: 기존 일봉 검증과 `entryReady`를 재사용한다. 종료일 이후의 봉은 지표 준비에서 제외하며, 달력 기준 조건에서는 고정 필요 봉 수를 null로 둔다.
- `strategy-workbench.js:135-160,775-860`: 요청 식별값·순번·응답 필드 형식을 검사한다. 입력·선택 변경과 재조회 시작 시 확인 상태를 지우고, 현재 결과의 READY/PARTIAL 및 사용자 확인이 있어야 화면 실행을 허용한다.
- 조회 실패 안내가 한국어 공통 복구 문구로 변경된 최종 저장본을 재확인했다. 이전에 읽은 화면 diff와 달라진 부분도 재검토했다.
- 과거 실행 조회는 `SavedBacktestService.get()`에서 저장 snapshot을 읽는다. 신규 readiness를 과거 결과 조회에 연결한 변경은 확인되지 않았다.

## 시나리오별 확인

아래의 실제 결과는 모두 소스와 검사 코드의 정적 확인이다. 자동 테스트 통과나 실제 화면 동작을 뜻하지 않는다.

### 1. 충분한 자료와 정확한 요청

- 재현 순서: 저장된 단일 전략 형식 1·2를 선택하고 종목·기간·체결 방식을 입력한 뒤 조회한다.
- 기대 결과: 전체 보유 기간과 요청 구간·준비 봉 수를 구분하고, 현황 확인 전에는 실행을 막는다.
- 실제 결과: 서버 필드 분리와 화면 표시·확인 체크박스·실행 조건이 작성돼 있다. 최종 파일의 `DataReadinessTests:20`, 신규 브라우저 검사 `:64`가 대응한다. QA-01의 상세 응답은 보완됐지만 검사 실행은 하지 않았다.

### 2. 요청 범위 부족·준비 이력 부족·날짜 입력

- 재현 순서: 요청 기간을 보유 범위 밖으로 늘리거나 지표 기간을 늘리고, 시작일·종료일·체결 방식을 변경한다. 시작일이 종료일 뒤인 입력과 1900년 미만 입력도 구분한다.
- 기대 결과: 자료가 일부 준비된 경우와 전혀 준비되지 않은 경우를 구분하고 입력 변경 후 다시 확인한다. 잘못된 요청은 데이터 없음으로 표시하지 않는다.
- 실제 결과: 봉이 없으면 NO_DATA, 판단 가능한 봉이 없으면 INSUFFICIENT_DATA, 일부 준비 또는 보유 범위 이탈이면 PARTIAL로 판정하는 분기를 확인했다. 날짜 역전은 화면 조회 조건에서 막고 서버도 검증한다. 서버는 1900~9998년을 검사한다. 1899년 응답을 한국어 복구 상태로 처리하는 브라우저 검사 `:184`도 추가됐으나 미실행이다.

### 3. 자료 없음·자료 오류와 null

- 재현 순서: 빈 종목 자료, 요청 기간 외 자료만 있는 경우, 잘못된 OHLC 자료를 각각 제공한다.
- 기대 결과: NO_DATA·INSUFFICIENT_DATA·INVALID_DATA를 구분하고 계산 불가를 0봉으로 바꾸지 않는다.
- 실제 결과: 요청 구간·준비 수치가 null인 INVALID_DATA는 화면에서 `계산 불가`로 표시한다. 데이터 없음의 날짜는 `없음`으로 표시한다. DTO의 null 선언과 최종 브라우저 검사 `:93-109`의 개별 필드 확인을 검토했다. 거래 결과의 NO_TRADES와는 별도의 사전 조회다.

### 4. 일자 공백·달력 조건·OR/AND

- 재현 순서: 중간 날짜가 빠진 봉, 52주 달력 조건, OR의 한 갈래만 준비된 조건과 AND 조건을 사용한다.
- 기대 결과: 달력 조건을 고정 부족 봉 수로 환산하지 않고, READY를 거래일 완전성이나 매도·체결·수익 보장으로 설명하지 않는다.
- 실제 결과: 달력 조건에서는 `requiredWarmupBars=null`이며 해당 설명을 표시한다. OR/AND는 기존 판정기를 따른다. 거래일 달력 미확인 경고와 화면 주의사항이 있다. 날짜 공백 자료도 READY일 수 있도록 작성된 검사 `DataReadinessTests:129`는 명시된 범위와 일치한다.

### 5. 상태 조회 실패·잘못된 응답

- 재현 순서: 정상 확인 뒤 재조회에 HTTP 503, 필드 누락, 다른 종목 또는 알 수 없는 상태를 반환한다.
- 기대 결과: 이전 성공·사용자 확인을 지우고 조회 실패 상태와 재시도 안내를 표시하며 실행을 막는다.
- 실제 결과: 재조회 시작과 거부 처리 모두 확인 상태를 지운다. 응답 형식 및 입력 대응 검사, 한국어 복구 안내가 있다. 신규 브라우저 검사 `:153-173`은 이 경로를 작성했지만 미실행이다.

### 6. 입력·대상 변경 후 역순 응답

- 재현 순서: A 조회를 지연시키고 종목·기간·버전·전략 ID를 변경해 B를 조회한 뒤 A의 성공·오류를 늦게 도착시킨다. A→B→A도 확인한다.
- 기대 결과: 현재 입력과 현재 요청 순번에 해당하는 응답만 반영한다.
- 실제 결과: 식별값에 전략 ID·버전·형식·종목·시작일·종료일·체결 방식을 포함한다. 입력 변경의 무효화와 순번 검사가 성공·실패 양쪽에 있다. 종목 변경 역순 브라우저 검사 `:130`, A→B→A 상태 단위 검사, 전략 #8 전환 검사가 작성돼 있다. QA-01 상세 응답은 소스 보완됐으며 실제 경합 동작은 실행 미검증이다.

### 7. 수집·저장·외부 호출 부재

- 재현 순서: 화면 진입·부족 상태 조회·입력 변경·재조회·복구 도움말 열기를 수행한다.
- 기대 결과: 사전 조회에서 수집·CSV 가져오기·새 실행 저장·외부 시세·Codex 호출이 발생하지 않는다.
- 실제 결과: 신규 화면 경로는 로컬 GET이며 도움말의 수집 명령은 표시 문자열이다. 서비스는 저장 버전 조회 → 종목 캔들 조회 → 순수 판정 경로이고 공급자 호출·쓰기 메서드가 없다. API 검사는 이력 미생성과 캔들 개수 불변을, 브라우저 fixture는 GET 이외 요청 및 collect/import-csv/refresh-daily/interpret 부재를 검사하도록 작성돼 있다. **실제 호출 0회·DB 불변을 계측한 것은 아니다.**

### 8. 단일 전략 범위

- 재현 순서: 형식 1·2와 포트폴리오·조합 형식 3·4를 각각 선택한다.
- 기대 결과: 형식 1·2 단일 종목만 사전 조회하며 다른 유형을 준비 완료로 표시하지 않는다.
- 실제 결과: 화면의 형식·portfolio·composition 검사와 기존 서버 단일 종목 검증을 확인했다. 형식 3·4는 별도 준비 범위를 안내하고 조회 버튼을 숨기는 검사 `:176`이 있다. 실행 확인은 하지 않았다.

## 남은 검증 문제와 한계

1. QA-01 fixture 응답 누락은 소스 보완을 확인했다. 신규 브라우저 8개의 실행·통과 확인은 남아 있다.
2. 연결 중단(`route.abort`)·비정상 JSON 본문·날짜 역전의 브라우저 전용 검사는 신규 파일에서 확인하지 못했다. 503과 유효 JSON의 필드 오류 검사는 존재한다.
3. `DataReadinessTests`는 14개다. 이전에 부족했던 체결 방식별 ATR 준비 경계·다음 봉 미체결 구분 및 UTC/KST 자정 경계의 검사 소스가 추가됐다. 컴파일·실행·통과는 미검증이다.
4. API의 캔들 개수 불변만으로 기존 값 불변이나 공급자 무호출까지 증명하지는 않는다. 공급자 호출 검증·전체 내용 불변·모의매매 계좌 미생성은 추가 검증 대상이다.
5. 브라우저의 금지 요청 정규식에는 `/debug-candles`가 빠져 있다. 현재 신규 구현에서 그 호출을 발견한 것은 아니며, 전용 부재 검사의 공백이다.
6. 응답 검사는 타입·음수·입력 대응·날짜 문자열 형식을 검사한다. 모든 상태별 수치 관계와 실제 달력 날짜 유효성을 증명하는 검사는 아니다.
7. Stage 9 스크립트는 `*.test.cjs`와 `*.browser.cjs`를 찾아 실행하므로 신규 browser 파일도 수집 대상이다. 스크립트를 실행해 확인한 결과는 아니다.
8. 거래소 달력에 따른 일자 공백 해석은 계약상 제외다. 봉 수를 근거로 누락 거래일이 없다고 판정하지 않는다.

## 수행한 주요 명령과 결과

모든 프로젝트 명령의 작업 경로는 위 프로젝트 루트다. Git·rg는 조회·정적 검사이며 프로젝트 스크립트를 실행하지 않았다. 아래 Git 실행 결과는 QA-01 소스 보완 전의 기록이다. 최종 보완 단계에서는 지정된 두 검사 파일과 보고서 읽기만 수행했고 Git·파싱 검사도 재실행하지 않았다.

- `git status --short --branch`: 종료 코드 0. 지정 브랜치와 진행 중인 변경·신규 파일을 확인했다.
- `git rev-parse HEAD`: 종료 코드 0. 위 기준 SHA를 확인했다.
- `git diff --stat`, Java·화면·CSS·기존 검사 파일 대상 `git diff -- <경로>`: 종료 코드 0. 변경 범위를 읽었다.
- `rg -n '.' .docs/data-readiness.md .docs/2026-11-11-follow-up-cards-b5ea6f8d.md`: 종료 코드 0. 계약·범위·미실행 제한을 확인했다.
- `rg -n '.' src/main/java/com/tossinvest/tossinvestbackend/backtest/DataReadiness.java src/test/java/com/tossinvest/tossinvestbackend/backtest/DataReadinessTests.java`: 종료 코드 0.
- `rg -n '.' scripts/tests/strategy-readiness.browser.cjs src/test/java/com/tossinvest/tossinvestbackend/backtest/DataReadinessTests.java`: 최종 보완 시 종료 코드 0. 출력이 길어 수정 구간과 검사 선언을 같은 파일 내에서 다시 좁혀 읽었다. 최종 브라우저 파일은 196행·검사 8개, Java 검사 파일은 187행·검사 14개다.
- `rg`로 기존 요청 검증·일봉 검증·판정기·저장 버전·저장 이력 조회·통합 검증 파일의 관련 분기를 읽었다.
- `git diff --check`: 앞선 검토에서 종료 코드 0을 확인했다. 출력은 README의 LF→CRLF 경고뿐이었다. 이 명령은 미추적 신규 파일을 포함하지 않으며 QA-01 보완 후 두 미추적 검사 파일의 검증 근거로 사용하지 않는다.

최종 보완에서 수행한 확인 명령은 다음과 같다. 모두 파일 읽기이며 종료 코드 0이었다.

```powershell
rg -n '.' scripts/tests/strategy-readiness.browser.cjs src/test/java/com/tossinvest/tossinvestbackend/backtest/DataReadinessTests.java
rg --no-filename -n -A 60 'atrReadinessUsesTheExecutionMode' src/test/java/com/tossinvest/tossinvestbackend/backtest/DataReadinessTests.java
rg --no-filename -n -A 12 -B 2 "url.pathname === '/api/strategies'" scripts/tests/strategy-readiness.browser.cjs
rg -n '@Test|^test\(' scripts/tests/strategy-readiness.browser.cjs src/test/java/com/tossinvest/tossinvestbackend/backtest/DataReadinessTests.java
rg -n '.' build/data-readiness-qa-review.md
```

이전 검토에서 미추적 파일은 다음을 별도로 수행했다. QA-01 보완 후 재실행하지 않았다.

```powershell
git diff --no-index --check -- NUL src/main/java/com/tossinvest/tossinvestbackend/backtest/DataReadiness.java
git diff --no-index --check -- NUL src/test/java/com/tossinvest/tossinvestbackend/backtest/DataReadinessTests.java
git diff --no-index --check -- NUL scripts/tests/strategy-readiness.browser.cjs
git diff --no-index --check -- NUL .docs/data-readiness.md
```

4건 모두 종료 코드 1이며 출력은 각 파일의 LF→CRLF 경고뿐이었다. 공백 오류 진단은 없었지만, 종료 코드 0인 일반 `git diff --check` 결과와 구분해 기록한다.

다음 이전 시도는 자동 승인 검토에서 `Rejected("rejected by user")`로 차단됐다. 실행 결과나 성공으로 계산하지 않았으며 상세 사유는 제공되지 않았다. 최종 보완에서는 재시도하지 않았다.

```powershell
node --check src/main/resources/static/js/strategy-workbench.js
Get-NetTCPConnection -State Listen -LocalPort 8090 -ErrorAction SilentlyContinue | Select-Object LocalAddress,LocalPort,OwningProcess
```

- `git hash-object --`로 계약·대상 소스·검사 파일 13개의 내용 식별값을 얻는 시도도 거부됐다. 전체 파일의 동일 시점 해시는 확보하지 못했다.
- `using-superpowers`, `verify-and-stop` 스킬 안내 파일 읽기와 상위 AGENTS 파일 존재 조회도 거부됐다. 읽지 못한 스킬 내용은 적용했다고 주장하지 않는다. 제공된 AGENTS 지시와 이번 업무 범위를 따랐다.
- 최초 보고서 작성 전 존재 조회는 파일 없음으로 종료됐다. 최초 작성 뒤에는 이 보고서만 갱신했다.

## 캡처

- 새 캡처 0장. 실행 중인 환경이 현재 Java/API·화면 변경을 제공하는지 확인하지 못했다.
- 로컬 8090 리스너 조회가 거부됐고, 새 서버·브라우저 테스트·테스트용 화면은 기동하지 않았다.
- 기존 `build/strategy-workbench-desktop.png`, `build/strategy-workbench-mobile.png`를 현재 결과로 재사용하지 않았다. `report_image`에 올릴 현재 결과 화면이 없다.
- 최종 보완 지시에서도 캡처 미수행을 유지했다. 추가 런타임 조회·브라우저 조작은 수행하지 않았다.

## 가정

- 이번 업무는 저장된 구현에 대한 소스 무수정 QA 정적 검토와 보고서 작성이다.
- 최종 보완 범위는 서버 담당이 지정한 두 검사 파일과 보고서 갱신이다. 제품 코드의 추가 변경이 없다는 내용은 서버 담당의 공유 사항이며, 이 단계에서는 제품 파일을 다시 조사하지 않았다.
- `.docs/data-readiness.md`를 이번 기능의 계약으로 사용했다. 검사 코드는 명세·의도 근거이며 통과 증거가 아니다.
- 다른 담당자의 동시 저장이 있었으므로 위 확인 시점 이후의 변경은 추가 검토가 필요하다. 확정 릴리스나 배포 상태를 가정하지 않는다.
- Java 17·Node·Playwright·전용 Gradle 캐시가 현재 실행 가능한 상태라고 가정하지 않았다. 이번 턴에서 컴파일러·테스트·서버는 실행하지 않았다.

## 대표 확인 필요

QA-01은 소스 보완됐으며 아래는 실행 제한이 해제된 뒤 담당자가 검토할 명령이다. **모두 미실행**이며 자동 실행 예약이나 승인 요청을 만들지 않았다.

```powershell
# 이전 파싱 시도는 도구 거부. 최종 보완에서는 재시도하지 않았고 다른 cjs 파싱도 수행하지 않음.
node --check src/main/resources/static/js/strategy-workbench.js
node --check scripts/tests/strategy-readiness.browser.cjs
node --check scripts/tests/strategy-workbench.test.cjs
node --check scripts/tests/strategy-workbench.browser.cjs
node --check scripts/tests/strategy-workbench.server.cjs

# 프로젝트 스크립트·자동 테스트: 금지 유지
node --test --test-concurrency=1 scripts/tests/strategy-workbench.test.cjs scripts/tests/strategy-readiness.browser.cjs scripts/tests/strategy-workbench.browser.cjs
.\gradlew.bat --gradle-user-home "$env:USERPROFILE/.gradle-qa-readiness" test --offline --console=plain --tests '*DataReadinessTests' --tests '*StrategyPersistenceApiTests'
.\scripts\verify-stage9.ps1 -Offline -JavaHome 'C:/Program Files/Java/jdk-17' -GradleUserHome "$env:USERPROFILE/.gradle-qa-readiness"
```

Java 17, Playwright/Edge 및 Node 모듈 경로, 필요한 의존성 캐시와 테스트 DB 격리의 준비 여부를 먼저 확인해야 한다. `--offline` 전용 캐시는 제안 경로이며 준비된 것으로 확인하지 않았다. 컴파일·자동 테스트 성공은 주장하지 않는다.

삭제·배포·push·실제 외부 전송·수집은 이번 QA에 필요하지 않아 수행하거나 실행 명령을 제안하지 않았다. 보고서를 AI Office 결과물로 등록한 뒤 대기한다.
