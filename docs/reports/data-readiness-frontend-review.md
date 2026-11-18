# 실행 전 데이터 준비 안내 — 화면 구현 인계

> 문서 정리(2026-11-17): `build/data-readiness-frontend-review.md`에서 이동했다. 아래 명령·검증 결과·상태와 이전 경로는 작성 당시 기록이다. 현재 문서 위치는 [전체 목차](../README.md)를 따른다.

- 작성: 화면 개발 담당 / 2026-11-11
- 프로젝트: `C:\Users\EM_NB171\Desktop\정성윤\TossInvest\toss-invest-backend`
- 브랜치: `feat/data-readiness-20261111`
- 상태: 승인된 화면 구현과 회귀 테스트 작성 완료. 구문 검사·테스트·실브라우저 동작은 미검증.

## 적용한 계획과 구현

1. 확정된 서버 DTO를 기준으로 상태 전이 단위 테스트와 화면 회귀 테스트를 먼저 작성했다. 실행 금지 지시 때문에 실패→통과 실행은 하지 않았다.
2. 기존 `strategy-workbench.js`의 `api`, `node`, `button`, `pairs`, 입력/선택 흐름과 기존 CSS를 재사용했다.
3. 단일 종목 저장 전략 형식 1·2에만 GET `/api/strategies/{id}/data-readiness`를 호출한다. version, symbol, startDate, endDate, executionMode를 기존 실행 입력에서 전달한다.
4. 요청 기간, 전체/요청 구간 보유 기간과 봉 수, 시작 전/필요 준비 봉 수, 최초 진입 판단 가능일과 판단 가능 봉 수를 표시한다.
5. READY/PARTIAL/NO_DATA/INSUFFICIENT_DATA/INVALID_DATA와 조회 실패를 구별한다. INVALID_DATA의 null 계산값은 `계산 불가`, 달력 기준의 필요 준비 봉 수는 고정 수량으로 표시할 수 없다고 안내한다.
6. READY 또는 PARTIAL 응답의 현황·주의사항 확인란을 체크한 경우에만 단일 종목 실행을 허용한다. 실행 핸들러에서도 동일한 조건을 확인한다.
7. 종목·시작일·종료일·체결 방식·저장 전략/버전 변경 시 결과와 확인을 무효화한다. 입력 서명과 요청 순번을 함께 검사하여 늦은 성공·실패와 A→B→A 경합을 차단한다.
8. 기간 수정/종목 변경은 해당 입력으로 초점을 이동한다. README의 수동 데이터 준비 절차는 펼침 안내에 표시하며 자동 수집·CSV 가져오기·실행 저장·Codex 호출을 추가하지 않는다.
9. 형식 3·4에는 별도 준비 확인 필요 범위를 안내하고 사전 조회를 호출하지 않는다. 기존 포트폴리오·조합 실행 동작은 유지한다.
10. 새 loadReadiness 오류 처리만 한국어 복구 안내로 통일했다. 연결 오류·영문 서버 검증 오류·잘못된 응답 원문은 새 상태 패널에 노출하지 않으며, 기존 전역 api 오류 체계는 변경하지 않았다.

공통 안내: 저장 데이터 기준 진입 판단 가능 여부이며 거래일 누락·매도 조건·실제 체결·수익을 보장하지 않는다. 요청 날짜와 보유 범위의 차이를 휴장/누락으로 단정하지 않는다.

## 이 담당이 수정한 파일

- `src/main/resources/static/js/strategy-workbench.js`
- `src/main/resources/static/css/strategy-workbench.css`
- `scripts/tests/strategy-workbench.test.cjs`
- `scripts/tests/strategy-workbench.browser.cjs`
- `scripts/tests/strategy-workbench.server.cjs`
- `scripts/tests/strategy-readiness.browser.cjs` (신규)
- `build/data-readiness-frontend-review.md` (이 인계 파일)

Java, README, .docs는 이 담당이 수정하지 않았다. 해당 파일들의 동시 변경은 서버 담당 작업이다. 기존 미추적 .docs 문서 3개도 보존했다. build 산출물은 Git에서 제외될 수 있으므로 이 보고서는 로컬 인계 경로로 확인한다.

## 작성한 회귀 사례 — 실행하지 않음

- 단위 3개: READY/PARTIAL 확인 후 실행 허용과 나머지 상태 차단, 입력별 권한과 A→B→A/역순 성공·실패 무효화, 형식 1·2 적용 범위.
- 새 브라우저 8개: 형식 1·2 GET 입력/현황/확인, 부분·OR·달력 안내, 없음/부족/오류 및 null 표시, 입력·버전·대상 변경, 역순 성공/실패, 재조회 실패·잘못된 응답·키보드 복구·360px 너비, 형식 3·4 미호출, 1899년 등 영문 서버 검증 오류의 한국어 복구 안내와 실행 차단.
- 기존 브라우저/격리 서버의 저장→실행 사례에는 준비 조회와 명시적 현황 확인 단계를 추가했다.

## 실제 수행한 정적 확인

다음 명령은 최종 수정 후 종료 코드 0, 출력 없음이었다.

```powershell
git --no-optional-locks diff --check -- src/main/resources/static/js/strategy-workbench.js src/main/resources/static/css/strategy-workbench.css scripts/tests/strategy-workbench.test.cjs scripts/tests/strategy-workbench.browser.cjs scripts/tests/strategy-workbench.server.cjs
```

다음 검색은 종료 코드 1, 일치 항목 없음이었다. 구문 검사나 동작 검증의 대체 증거는 아니다.

```powershell
rg -n '[ \t]+$|^(<<<<<<<|=======|>>>>>>>)' scripts/tests/strategy-readiness.browser.cjs
rg -n '[\p{Hiragana}\p{Katakana}]' src/main/resources/static/js/strategy-workbench.js src/main/resources/static/css/strategy-workbench.css scripts/tests/strategy-readiness.browser.cjs
```

신규 파일의 `git diff --no-index --check -- NUL scripts/tests/strategy-readiness.browser.cjs`는 종료 코드 1과 LF→CRLF 경고를 반환했다. 이를 성공 판정으로 사용하지 않았다. 추가로 diff 및 `DataReadiness.java`의 필드·nullable 계약을 소스에서 대조했다.

## 차단된 정적 구문 검사

다음 3개 명령은 프로세스 생성 전에 `Rejected(rejected by user)`로 거절됐다. 자동 승인 검토의 상세 사유는 제공되지 않았다. 허용되지 않은 다른 실행 경로로 대체하지 않았다.

```powershell
node --check src/main/resources/static/js/strategy-workbench.js
node --check scripts/tests/strategy-readiness.browser.cjs
node --check scripts/tests/strategy-workbench.test.cjs
```

따라서 최종 JavaScript 파싱·회귀 통과·브라우저 동작을 확인했다고 주장하지 않는다. 이 거절 이후 한국어 catch 보완과 회귀 테스트를 추가했고 최종 diff/문구 검사를 다시 수행했다.

## 캡처 가능 여부

현재 구현의 런타임 캡처 없음. 서버와 브라우저 테스트 스크립트 실행 금지가 유지되어 새 화면을 실행·렌더링하지 않았다. 과거 build PNG나 합성 값을 현재 실행 화면으로 보고하지 않았다. 실제 화면, 모바일 표시, 키보드 동작, API 결합은 후속 실행 검증 대상이다.

## 가정

- '현황을 확인한 뒤 실행'은 READY/PARTIAL 결과의 주의사항 확인란으로 명시했다.
- PARTIAL은 현황 확인 후 실행 가능하며, 저장 봉의 범위·진입 판단 상태에 대한 제한을 함께 표시한다.
- 형식 3·4는 범위 안내만 추가하고 기존 실행을 별도 차단하지 않는다.
- 수집 안내의 주소는 기존 README의 기본 로컬 서버 `localhost:8090`을 따른다. 새 수집 기능이나 연결 설정은 추가하지 않았다.

## 대표 확인 필요 — 현재 미실행

실행 제한이 해제된 후 담당자가 검토할 명령이다. 이 문서 작성 중 실행하지 않았다.

```powershell
node --check scripts/tests/strategy-workbench.browser.cjs
node --check scripts/tests/strategy-workbench.server.cjs
node --test --test-concurrency=1 scripts/tests/strategy-workbench.test.cjs scripts/tests/strategy-workbench.browser.cjs scripts/tests/strategy-readiness.browser.cjs scripts/tests/strategy-composition.browser.cjs scripts/tests/strategy-portfolio.browser.cjs
.\scripts\verify-stage9.ps1 -Offline
```

위에서 거절된 node --check 3개도 최종본으로 재검증이 필요하다. `*.server.cjs`는 임의로 직접 실행하지 않고 기존 Java 격리 서버 검증 경로를 사용한다. 서버 기동·실제 수집·외부 연동·삭제·배포·push는 이번 화면 작업에서 계획하거나 실행하지 않았다. 별도 외부 전송 명령도 없다.
