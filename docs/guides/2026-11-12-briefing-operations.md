# 장전 브리핑 운영 확인 준비서

> 문서 정리(2026-11-17): `build/2026-11-12-briefing-operations.md`에서 이동했다. 아래 명령·검증 결과·상태와 이전 경로는 작성 당시 기록이다. 현재 문서 위치는 [전체 목차](../README.md)를 따른다.

- 작성: 2026-11-12 / 서버 개발 담당(리드 직접 수행)
- 상태: 현재 소스 기반 준비서. 실제 공급원·모델·서버·DB·복구는 확인하지 않았다.
- 기준: MarketBriefingSources/Service/Entity/TradingCalendar, 기존 복구 계획과 최신 사용 문서.

## 현재 계약과 회귀

- 코스피/코스닥은 네이버 국내 지수 일봉, 원달러는 네이버 날짜별 환율을 우선 사용하고 실패 시 기존 Yahoo 일봉으로 대체한다. 같은 공급원의 두 유효 값을 비교한다.
- Yahoo FX의 stored observedAt은 가격 기준일의 다음 날 봉 종료 시각일 수 있어 기존 보정으로 하루를 빼서 최신성을 비교한다. 저장 원본의 시각/가격을 고치지 않는다.
- 네이버 FX의 23:59:59 KST는 날짜 자료 완료 경계이며 실제 은행 고시·체결 시각이 아니다.
- 오늘 stale READY/PARTIAL만 복구한다. 기존 국내 시세가 빠지거나 후퇴하면 유지하고, 저장 당시 미국 지수·뉴스를 보존해 요약을 재검증한다. 처음부터 누락된 시세의 보충과 과거 날짜 재작성은 범위 밖이다.
- 날짜당 자동 3회 제한, 수동 오늘 재시도와 기존 공개 HTTP 계약을 유지한다.
- GET은 시세 재수집·AI 재생성·저장 교체를 하지 않는다. 다만 표시용 직전 거래일 판정은 달력 캐시가 없으면 KRX 공개 휴장일 조회를 할 수 있다. 완전한 무네트워크 조회라고 보고하지 않는다.
- 11-11 생성/11-10 시세는 기존 고정 회귀의 예시다. 현재 코드의 실제 복구 대상은 KST 오늘이며 과거 11-11 저장본을 오늘로 바꾸지 않는다. 실제 기대 직전 거래일은 실행 환경의 달력으로 확인해야 한다.

서비스 신규 9건은 비대상 근거, 두 번의 이력, 수집/날짜 후퇴, AI 검증, AI 대기 중 저장본 변경, 직렬화/flush/낙관락을 다룬다. 수집 신규 2건은 환율 fallback과 양쪽 실패를 다룬다. 소스 존재·단언만 확인했으며 실행은 차단됐다. DB 트랜잭션·동시성/보존을 런타임에서 입증한 결과는 없다.

## 가정

- 실제 datasource는 profile/환경 변수에 따라 달라질 수 있다. 기본 설정의 data/tossinvest나 포트 8090을 현재 운영 사실로 간주하지 않는다.
- 파일 H2의 일관된 복사는 정상 종료 확인 뒤 수행한다. 정지 방법·실제 파일·백업 위치가 미확인이라 프로세스 종료/파일 복사를 실행하지 않는다.
- 원본 JSON과 이력은 삭제/정리하지 않는다. 스키마 확인과 복구 검증은 먼저 DB 사본에서 한다.

## 대표 확인 필요 — 아래 명령은 미실행

### 1. 환경·백업 준비

설정의 실제 DB 연결·프로필·서버 주소를 확인한다. 기본 경로의 후보/기본 포트 현황을 읽는 명령일 뿐 실제 연결의 증거와 구분한다.

```powershell
Get-ChildItem -LiteralPath '.\data' -Filter '*.mv.db' | Select-Object Name,Length,LastWriteTime
Get-NetTCPConnection -LocalPort 8090 -State Listen
```

정상 종료를 확인하고 `$briefingDbFile`, `$briefingBackupFile`을 검증한 실제 절대 경로로 지정한 뒤 검토할 복사/해시 명령:

```powershell
Copy-Item -LiteralPath $briefingDbFile -Destination $briefingBackupFile -ErrorAction Stop
Get-FileHash -LiteralPath $briefingDbFile -Algorithm SHA256
Get-FileHash -LiteralPath $briefingBackupFile -Algorithm SHA256
```

열린 H2 파일을 복사해 정상 백업으로 보고하지 않는다. 복원/정지/배포 명령은 실제 운영 방식이 미확인이라 확정하지 않았다.

### 2. DB 사본과 스키마

사본 연결에서 기존 테이블·컬럼을 먼저 확인한다.

```sql
SELECT COLUMN_NAME, DATA_TYPE
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_NAME = 'MARKET_BRIEFING'
  AND COLUMN_NAME IN ('SNAPSHOT_JSON', 'SNAPSHOT_HISTORY_JSON', 'VERSION');
```

기존 이력 컬럼 부재를 실제로 확인하고 사본에서 검증한 경우의 기존 변경 DDL 후보:

```sql
ALTER TABLE market_briefing ADD COLUMN snapshot_history_json CLOB;
```

컬럼이 이미 있으면 실행하지 않는다. VERSION 매핑·기존 행·본문/이력 동시 보존과 낙관락은 사본에서 별도 확인한다. 이번 11-12 서비스 보완의 새 컬럼/의존성은 없다. 사본 검증 전 실운영 스키마 반영은 하지 않았다.

### 3. 격리 회귀·빌드

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
$env:MARKET_BRIEFING_SCHEDULING_ENABLED='false'
$env:STRATEGY_PAPER_SCHEDULING_ENABLED='false'
$env:RUN_MARKET_BRIEFING_LIVE='false'
.\gradlew.bat test --tests 'com.tossinvest.tossinvestbackend.marketbriefing.*' --offline --console=plain
.\scripts\verify-stage9.ps1 -Offline -JavaHome 'C:/Program Files/Java/jdk-17' -GradleUserHome 'C:/Users/EM_NB171/.gradle'
```

집중 Gradle 실행과 Node 구문 검사는 자동 승인 검토에서 거절됐다. 같은 실행을 다른 수단으로 우회하지 않았다. 빌드/회귀 성공은 미확인이다.

### 4. 서버 반영·오늘 복구

백업/사본/검증 후 실제 운영 방식에 따라 새 빌드를 반영해야 한다. 개발 기동 후보는 다음과 같으며 기본 프로필의 실제 DB와 예약 작업 영향을 먼저 확인한다.

```powershell
.\gradlew.bat bootRun --offline --console=plain
```

`$briefingBaseUri`를 확인된 실제 로컬 서버 주소로 지정한 뒤의 조회/재시도 후보:

```powershell
Invoke-RestMethod -Method Get -Uri "$briefingBaseUri/api/market-briefings/latest"
Invoke-RestMethod -Method Post -Uri "$briefingBaseUri/api/market-briefings/today" -Headers @{ 'Origin'=$briefingBaseUri; 'X-Strategy-Local'='1' }
```

POST는 실제 시세 수집·AI 호출·DB 교체가 발생할 수 있다. 저장 날짜, 각 시세의 실제 기준일/출처/숫자, 남은 경고와 원본 JSON 이력을 확인한다. 실패/개선 없음/날짜 후퇴·동시 갱신 때 기존 게시본이 유지되는지도 별도로 검증해야 한다.

삭제·push·추가 외부 전송은 이 복구에 필요한 명령이 없어 만들지 않았다. 운영 성공·실제 DB 보존·오늘 저장본 개선을 확인했다고 보고하지 않는다.
