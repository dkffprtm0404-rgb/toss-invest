# 토스 마무리 취합 보고

- 작성일: 2026-11-12
- 담당: 서버 개발 담당 / 토스 팀장
- 프로젝트: `C:/Users/EM_NB171/Desktop/정성윤/TossInvest/toss-invest-backend`
- 상태: **취합 중. 아래 구현 기록을 전체 검증 완료로 보지 않는다. 자동 승인된 하위 업무의 QA·문서·독립 검토 결과를 기다리고 있다.**

## 현재 확보한 결과

### 서버 직접 보완

1. 오늘 국내 시세 복구는 기존 저장본의 국내 지수·환율만 교체하고 저장 당시 미국 지수·뉴스·근거 ID와 순서를 유지한다. 새 수집에서 기존 국내 시세가 빠지거나 더 오래되면 교체하지 않는다.
2. 수집·AI 대기 중 다른 요청이 공개 본문을 변경했으면 최초 JSON과 쓰기 직전 본문의 일치를 확인해 새 저장본을 이전 근거로 덮어쓰지 않는다.
3. 최종 저장만 기존 Spring TransactionTemplate/JPA 쓰기 트랜잭션으로 묶었다. 본문·이력을 모두 직렬화한 뒤 관리 엔티티에 반영하고 기존 @Version을 유지한다. 저장 오류를 AI 실패 처리로 재저장하지 않는다.
4. 서비스 회귀 9건·공급자 회귀 2건을 추가 작성했다. 현재 선언 수는 서비스 28개, 공급자 12개다. 모두 **소스에서 센 선언 수**이며 통과 수가 아니다.
5. 내부 생성자에 추가된 PlatformTransactionManager에 맞춰 수동 생성 호출과 opt-in 실연동 테스트의 저장소 double을 갱신했다. 실연동 검사의 double은 DB 검증 근거가 아니다.

### 화면 담당 보고 수신분

- 미지원 모의매매 선택 시 내부 대상·표시·요청 키를 함께 해제하고 시작 버튼/submit을 차단한다. 선택 전환 뒤 늦은 시작 응답·오류가 안내를 덮어쓰지 않게 했다.
- 한국어 필드명·JSON 경로·실제 범위 기반 수정 안내, 포트폴리오 정책/시장자료 예시·오류 위치·입력 미리보기를 기존 화면에 연결했다.
- 초기자금·비용·정책을 임의 자동 입력하지 않는다. 합성 템플릿을 실제 시장 구성·거래일·일봉 준비 현황으로 표시하지 않는다.
- 화면 구현·정적 대조 보고만 확보했다. 구문 검사·자동 회귀·실제 화면 검증·캡처는 미완료다.

## 검증 근거와 제한

- 대상 소스·인접 호출·기존 명세·화면 담당 보고를 읽었다. 원본 근거 보존, JSON 비교, 최종 쓰기, 생성자 호출을 정적으로 대조했다.
- 리드 변경 파일 6개의 텍스트 검사에서 후행 공백·충돌 표식 일치가 없었다. 컴파일·파싱·JUnit·브라우저 통과를 뜻하지 않는다.
- Java 17 실행 파일, Gradle 캐시 폴더, 기존 Playwright 폴더의 **존재만** 확인했다. 실행 버전·캐시 완전성·브라우저 실행 성공은 확인하지 않았다.
- 기존 stage9/completion 결과 파일은 2026-11-10 산출물이다. 이번 변경의 검증 근거로 사용하지 않는다.
- 현재 결과 화면 캡처는 0장이다. 화면 담당은 브라우저 접근 호스트 불가를 보고했고 과거 PNG를 현재 결과로 재사용하지 않았다.
- Git 상태 조회와 집중 Gradle 실행은 `Rejected("rejected by user")`로 차단됐다. 상세 사유는 제공되지 않았다. 같은 실행을 다른 경로로 우회하지 않았다.
- 추가 직접 검토 위임은 사무실 도구의 도움 요청 사슬 내 재위임·카드 생성 제한으로 거절됐다. 추가 위임은 성립하지 않았으며 자동 승인된 기존 업무를 유지한다.

## 확인된 변경 파일

서버:

- `src/main/java/com/tossinvest/tossinvestbackend/marketbriefing/MarketBriefingService.java`
- `src/test/java/com/tossinvest/tossinvestbackend/marketbriefing/MarketBriefingServiceTests.java`
- `src/test/java/com/tossinvest/tossinvestbackend/marketbriefing/MarketBriefingSourcesTests.java`
- `src/test/java/com/tossinvest/tossinvestbackend/marketbriefing/MarketBriefingLiveTests.java`

화면:

- `src/main/resources/static/js/strategy-paper.js`
- `src/main/resources/static/js/strategy-workbench.js`
- `src/main/resources/static/css/strategy-workbench.css`
- `src/main/resources/static/index.html` — 캐시 버전 `20261112-followup`

리드 문서:

- `.docs/market-briefing.md`
- `.docs/2026-11-12-completion-plan.md`
- `.docs/2026-11-12-completion-report.md` — 본 보고서

QA·기획·자료 조사·독립 검토의 변경/산출물은 수신 후 실제 파일을 확인해 추가한다.

## 가정

- 대표의 계속 진행 지시에 따라 기존 프로젝트에서 되돌릴 수 있는 소스·문서 보완과 허용되는 격리 검증을 진행한다. 도구의 명시적 실행 거절은 해제된 것으로 간주하지 않는다.
- 작업 중인 공유 체크아웃과 기존 미커밋 변경을 보존한다. 브랜치 전환·worktree 이동·커밋·push를 하지 않는다.
- 데이터 준비 사전 조회는 기존 단일 종목 형식 1·2 범위를 유지한다. 포트폴리오 미리보기는 입력 도움이며 실제 준비 상태나 수익을 보장하지 않는다.
- 실제 운영 DB·H2 파일·스키마·오늘 저장본은 조회/수정하지 않았다. 기존 복구의 운영 성공은 소스 구현과 구분한다.
- 새 제품 의존성·공개 HTTP API·매매 계산 정책을 도입하지 않는다.

## 대표 확인 필요

다음은 실행 승인/환경 확인 뒤 검토할 **미실행 명령**이다. 현재 실행 성공을 의미하지 않는다.

### 격리 검증

프로젝트 루트에서:

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
$env:MARKET_BRIEFING_SCHEDULING_ENABLED='false'
$env:STRATEGY_PAPER_SCHEDULING_ENABLED='false'
$env:RUN_MARKET_BRIEFING_LIVE='false'
.\gradlew.bat test --tests 'com.tossinvest.tossinvestbackend.marketbriefing.*' --offline --console=plain
node --check src/main/resources/static/js/strategy-paper.js
node --check src/main/resources/static/js/strategy-workbench.js
.\scripts\verify-stage9.ps1 -Offline -JavaHome 'C:/Program Files/Java/jdk-17' -GradleUserHome 'C:/Users/EM_NB171/.gradle'
```

기존 스크립트는 `stage9-test` 프로필·고정 외부 응답·격리 DB·자동 실행 비활성 설정을 사용한다. 실제 실행 전 현재 테스트와 환경에 이 격리가 유지되는지 재확인해야 한다. 실제 화면의 데스크톱/모바일·키보드 동작과 캡처 1~2장도 이 단계에 포함한다.

### 운영 DB·실제 오늘 저장본 복구

실제 DB 연결 경로·백업/복원·서버 정지/기동 방법과 로컬 주소가 미확인이다. 검증되지 않은 DB 파일 복사·프로세스 종료·배포 명령은 만들지 않는다. DB 사본에서 기존 이력 컬럼/버전과 본문·이력 동시 보존을 확인한 뒤 운영 적용 여부를 결정해야 한다.

`$briefingBaseUri`를 확인된 실제 로컬 서버 주소로 지정한 뒤 실행할 후보:

```powershell
Invoke-RestMethod -Method Get -Uri "$briefingBaseUri/api/market-briefings/latest"
Invoke-RestMethod -Method Post -Uri "$briefingBaseUri/api/market-briefings/today" -Headers @{ 'Origin'=$briefingBaseUri; 'X-Strategy-Local'='1' }
```

POST는 실제 공개 자료 수집·AI 호출·DB 저장을 동반한다. 이번에 실행하지 않았다. 과거 저장본은 복구 대상으로 재작성하지 않는다.

삭제·배포·push·외부 쓰기/전송은 이번 로컬 마무리에 필요한 작업이 없어 실행 명령을 만들지 않았다. 자동 승인된 사무실 내부 계획·보고 등록은 수행했다.
