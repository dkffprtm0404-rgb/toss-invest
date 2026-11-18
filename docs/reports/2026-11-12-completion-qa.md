# 2026-11-12 데이터 준비 상태 및 후속 화면 개선 QA

> 문서 정리(2026-11-17): `build/2026-11-12-completion-qa.md`에서 이동했다. 아래 명령·검증 결과·상태와 이전 경로는 작성 당시 기록이다. 현재 문서 위치는 [전체 목차](../README.md)를 따른다.

- 담당: 품질 확인 담당 / QA / 토스
- 대상 프로젝트: `C:\Users\EM_NB171\Desktop\정성윤\TossInvest\toss-invest-backend`
- 연결 업무: `[업무 보드] 데이터 준비 상태와 후속 화면 개선 검증 마무리`
- 재사용 대상: 기존 `[대표 확인 필요·미실행] 데이터 준비 상태 통합 검증과 결과 화면 캡처` 카드
- 최종 판정: **실행 차단 / 검증 미완료**. 제품 실패나 회귀 테스트 통과로 판정하지 않는다.

## 결과

- 기존 카드를 대체하는 새 업무 카드는 만들지 않았다. 현재 도구에는 기존 카드 조회·수정 기능이 없어 카드 내용과 갱신 여부는 확인하지 못했다.
- `mcp__office__team` 조회에 성공했다. 화면 개발 담당은 `idle`로 표시되었으나, 완료 통지나 최신 소스 반영을 입증하지 않는다.
- 프로젝트 문서 및 지정 테스트 소스 읽기가 프로세스 생성 전 `Rejected("rejected by user")`로 차단되었다. 종료 코드나 테스트 실패 로그는 없다.
- Java 17, 기존 Gradle 캐시, 고정 외부응답, 격리 DB, 예약 비활성 전제를 검증하지 못했다. 집중 테스트 및 Stage9 실행은 시도하지 않았다.
- 제품 JS와 테스트 소스는 변경하지 않았다. 회귀 보완은 미완료다.
- 현재 작업 화면 조회는 `js execution timed out; kernel reset, rerun your request`로 종료되었다. 재시도하거나 과거 PNG로 대체하지 않았다.
- 모바일·키보드 확인 및 `report_image` 첨부는 미실행이다.

## 요구사항별 확인 범위

### 모의매매 A → 미지원 B → submit

- 재현 순서: 지원되는 저장 전략 A를 선택하고, 미지원 전략 B로 전환한 뒤 submit 경로를 호출한다.
- 기대 결과: A의 선택 및 실행 대상이 해제되고, B가 실행되지 않으며, submit으로 발생한 실행 요청은 0회다. A 선택 이전/도중의 조회 요청은 실행 요청 수와 구분해야 한다.
- 필요한 회귀: 동일 페이지에서 A → B 전환을 실제로 수행하고, 선택 상태와 요청 기록을 함께 검증한다. 단순한 버튼 disabled 확인만으로 완료 판정하지 않는다.
- 실제 결과: 최신 테스트·제품 소스를 읽지 못해 코드 보완과 실행 모두 미완료다. 요청 0회 또는 이전 선택 해제를 확인했다고 주장하지 않는다.

### 한국어 필드 안내

- 재현 순서: 화면 담당이 수정한 필드 안내가 나타나는 입력 및 미리보기 경로를 수행한다.
- 기대 결과: 최신 요구사항에서 정한 한국어 필드명과 안내가 사용자에게 표시된다. 실제 안내를 생성하는 경로와 연결해 검증해야 한다.
- 필요한 회귀: 최신 제품 문구 및 필드 매핑을 먼저 확인하고, 실제 반환·표시 결과를 비교한다. 확인되지 않은 문자열을 기대값으로 만들어 넣지 않는다.
- 실제 결과: 문서와 소스 읽기 차단으로 변경 필드와 정확한 기대 문자열을 확정하지 못했다. 문자열 불일치 후보를 확인된 결함으로 보고하지 않는다.

### portfolio 정상 미리보기

- 재현 순서: 최신 스키마에 맞는 portfolio가 포함된 전략을 미리보기로 전달한다.
- 기대 결과: 해당 portfolio의 실제 구성과 값이 미리보기에 반영된다. 이전 입력의 값으로 통과하지 않도록 구별되는 fixture를 사용해야 한다.
- 실제 결과: 소스·스키마·테스트 fixture를 확인하지 못해 미실행이다.

### portfolio 누락 미리보기

- 재현 순서: 정상 입력에서 portfolio 필드만 생략해 미리보기로 전달한다.
- 기대 결과: 최신 SPEC이 정의한 기본 처리 또는 필수 필드 안내를 정확히 비교해야 한다. 현재 문서를 읽지 못했으므로 portfolio가 필수인지 선택인지 단정하지 않는다.
- 필요한 회귀: 정상 사례와 같은 미리보기 경로를 사용하고, 누락 입력의 결과와 직전 결과 잔존 여부를 검증한다.
- 실제 결과: 누락 정책 미확정 및 미실행이다.

### portfolio 형식 오류 미리보기

- 재현 순서: 최신 스키마를 확인한 뒤 portfolio에 객체 대신 문자열 등 명백히 잘못된 타입을 전달한다.
- 기대 결과: 최신 요구사항에 맞는 한국어 안내가 표시되고, 잘못된 입력이 정상 미리보기로 오인되지 않는다. 표시와 내부 처리 결과를 함께 확인해야 한다.
- 실제 결과: 최신 스키마와 처리 경로를 확인하지 못해 미실행이다.

## 소유 파일 및 수정 현황

아래는 업무 지시에 기재된 대상이다. 승인 차단으로 실제 파일 존재·패키지 경로·이름을 확인하지 못했다.

- `scripts/tests/strategy-readiness.browser.cjs`: 읽기 차단 / 변경 없음 / 실행 없음
- `scripts/tests/strategy-workbench.test.cjs`: 읽기 차단 / 변경 없음 / 실행 없음
- `scripts/tests/strategy-workbench.browser.cjs`: 읽기 차단 / 변경 없음 / 실행 없음
- `scripts/tests/strategy-workbench.server.cjs`: 읽기 차단 / 변경 없음 / 실행 없음
- `scripts/tests/strategy-paper.browser.cjs`: 읽기 차단 / 변경 없음 / 실행 없음
- `backtest/DataReadinessTests.java`: 아래 추정 패키지 경로로 읽기를 요청했으나 프로세스 생성 전 차단 / 변경 없음 / 실행 없음
- `StrategyPersistenceApiTests.java`: 아래 추정 패키지 경로로 읽기를 요청했으나 프로세스 생성 전 차단 / 변경 없음 / 실행 없음

## 실제 도구 호출과 명령

아래 PowerShell 명령은 별도 표시가 없으면 프로젝트 폴더를 작업 디렉터리로 지정했다. 명령을 나눠 재실행하거나 다른 실행 도구로 우회하지 않았다.

### 승인 단계에서 차단된 읽기

1. 시작 안내 스킬 읽기. 작업 디렉터리 기본값 `C:\Users\EM_NB171`. 프로세스 생성 전 차단.

```powershell
Get-Content -LiteralPath 'C:\Users\EM_NB171\.codex\plugins\cache\claude-plugins-official\superpowers\7.0.0\skills\using-superpowers\SKILL.md'
```

2. 프로젝트 요구 문서·실행 설정·파일 목록 확인. 프로세스 생성 전 차단되어 어느 하위 명령도 실행되었다고 간주하지 않는다.

```powershell
Get-Content -LiteralPath '.docs\data-readiness-final-report.md'; Get-Content -LiteralPath 'build.gradle'; Get-Content -LiteralPath 'scripts\verify-stage9.ps1'; rg --files -g 'AGENTS.md' -g '*CONTEXT*' -g '*SPEC*' -g '*CHANGE*' -g '*readiness*' -g '*workbench*' -g '*paper*' -g '*DataReadinessTests.java' -g '*StrategyPersistenceApiTests.java' -g '*application*.yml' -g '*application*.yaml' -g '*application*.properties'
```

3. 검증 및 보고 스킬 읽기. 작업 디렉터리 기본값 `C:\Users\EM_NB171`. 프로세스 생성 전 차단.

```powershell
Get-Content -LiteralPath 'C:\Users\EM_NB171\.codex\plugins\cache\caveman\caveman\4.1.0\skills\verify-and-stop\SKILL.md'; Get-Content -LiteralPath 'C:\Users\EM_NB171\.codex\plugins\cache\claude-plugins-official\superpowers\7.0.0\skills\verification-before-completion\SKILL.md'; Get-Content -LiteralPath 'C:\Users\EM_NB171\.agents\skills\canvas\SKILL.md'
```

4. Node 테스트 읽기. 프로세스 생성 전 차단。

```powershell
Get-Content -LiteralPath 'scripts\tests\strategy-readiness.browser.cjs','scripts\tests\strategy-workbench.test.cjs','scripts\tests\strategy-workbench.browser.cjs','scripts\tests\strategy-workbench.server.cjs','scripts\tests\strategy-paper.browser.cjs'
```

5. Java 테스트 읽기. 패키지 경로は未確認ではなく、아래 경로 자체가 미확인 상태이며 프로세스 생성 전 차단되었다.

```powershell
Get-Content -LiteralPath 'src\test\java\com\tossinvest\tossinvestbackend\backtest\DataReadinessTests.java','src\test\java\com\tossinvest\tossinvestbackend\strategy\StrategyPersistenceApiTests.java'
```

6. 컴퓨터 사용 스킬 읽기. 작업 디렉터리 기본값 `C:\Users\EM_NB171`. 프로세스 생성 전 차단.

```powershell
Get-Content -LiteralPath 'C:\Users\EM_NB171\.codex\plugins\cache\openai-bundled\computer-use\26.1106.51428\skills\computer-use\SKILL.md'
```

7. 과거 QA 요약 읽기. 작업 디렉터리 기본값 `C:\Users\EM_NB171`. 프로세스 생성 전 차단. 이 파일은 증거로 사용하지 않았다.

```powershell
Get-Content -LiteralPath 'C:\Users\EM_NB171\.codex\memories\rollout_summaries\2026-11-10T05-14-40-SgdJ-toss_invest_static_qa_stage9_evidence_gap_audit.md'
```

8. 출력 보고서 존재 확인. 프로세스 생성 전 차단. 기존 파일 존재 여부를 확인하지 못했다.

```powershell
if (Test-Path -LiteralPath 'build\2026-11-12-completion-qa.md') { Get-Content -LiteralPath 'build\2026-11-12-completion-qa.md' } else { Write-Output 'QA_REPORT_DOES_NOT_EXIST' }
```

### 실행된 읽기 및 UI 조회

- 메모리 레지스트리 검색만 종료 코드 0으로 실행되었다. 출력 일부가 잘렸으며, 현재 프로젝트·테스트의 검증 근거로 사용하지 않았다. 과거 스크린샷과 과거 Stage9 결과를 현재 증거로 바꾸지 않는 원칙만 참고했다.

```powershell
rg -n --context 3 'readiness|Stage.?9|stage9|marketbriefing|TossInvest|StrategyPersistence' 'C:\Users\EM_NB171\.codex\memories\MEMORY.md'
```

- `mcp__office__team({})`: 성공. 팀원 상태만 확인했다.
- `await cua.getState();`: 시간 초과. 앱·탭 목록이나 화면이 반환되지 않았다.
- CUA 종료 요약: `작업 화면 확인이 시간 초과됨`. 같은 UI 조회를 재시도하지 않았다.

## 대표 확인 필요

아래는 **실행하지 않은 명령 후보**다. 최신 파일·진입점·실행 설정을 먼저 읽어야 하므로 곧바로 안전하게 실행할 수 있다고 보증하지 않는다. 이미 차단된 읽기를 재시도하지 않았다.

1. Java 집중 검증: Java 17의 실제 설치 경로와 기존 캐시, 고정 외부응답, 격리 DB, 예약 비활성 설정을 모두 확인한 뒤에만 수행할 후보.

```powershell
.\gradlew.bat test --offline --no-daemon --console=plain --rerun-tasks --tests '*DataReadinessTests' --tests '*StrategyPersistenceApiTests'
```

2. Node 집중 검증: 실제 파일명과 `build.gradle`의 기존 테스트 진입점을 먼저 확인해야 한다. `strategy-workbench.server.cjs`가 테스트인지 보조 서버인지도 미확인이다. 아래 명령은 독립 실행 가능한 테스트 파일이라는 점이 확인되어야 사용할 수 있다.

```powershell
node --test scripts/tests/strategy-readiness.browser.cjs scripts/tests/strategy-workbench.test.cjs scripts/tests/strategy-workbench.browser.cjs scripts/tests/strategy-paper.browser.cjs
```

3. Stage9: 집중 검증 이후 전체 검증이 추가로 필요한 경우에만 검토한다. 스크립트 내용을 읽지 못했으며, 이번 턴에는 실행 조건이 충족되지 않았다.

```powershell
.\scripts\verify-stage9.ps1 -Offline
```

- 서버 시작, 실제 외부 API 호출, POST, 삭제, 배포, push, 외부 전송은 실행하지 않았다. 재현을 위해 필요한 안전한 시작 명령을 확정할 소스가 없어 임의 명령을 만들지 않았다.
- 추가 승인을 요청하거나 대표의 응답을 기다리지 않았다. 도구가 차단한 읽기·실행의 재시도에는 새로운 실행 가능 상태가 필요하다.

## 가정

- 프로젝트 경로는 이번 인수인계에 명시된 경로를 사용했다. 도구로 실제 저장소 상태를 재확인하지 못했다.
- 화면 개발 담당의 `idle` 상태는 완료 통지로 취급하지 않았다.
- 11/10 보관 Yahoo 응답, 이전 테스트 결과 및 과거 PNG는 이번 회귀의 현재 증거로 사용하지 않았다.
- Java 테스트의 실제 패키지 경로와 Node 파일 이름은 존재가 확인되지 않았으므로 추정 경로를 실제 파일명으로 확정하지 않았다.
- 제품 JS 수정 금지, 승인 차단 명령의 중복 재시도 및 다른 실행 도구 우회 금지를 유지했다.

## 변경 및 검증 한계

- 변경 대상: 이 보고서 한 파일. 제품 및 테스트 코드 변경 없음.
- 실제 통과한 제품 검증: 없음. Java·Node·Stage9 테스트의 실행 건수는 0건이다.
- 실제 테스트 실패: 확인되지 않음. 테스트를 실행하지 못했으므로 실패 0건을 성공 의미로 해석하지 않는다.
- 미완료: 최신 문서·소스 확인, 화면 담당 완료 확인, 의미 있는 회귀 코드 보완, 집중 검증, 현재 모바일·키보드 확인, 신규 화면 캡처, 기존 업무 카드 갱신 확인.
- 본 보고서는 수행 불가 경계를 기록한 결과이며, 데이터 준비 상태 통합 검증 완료 또는 화면 개선 검증 완료 증거가 아니다.
