# 장전 AI 시장 요약

한국시간 매 거래일 **08:30**에 공개 시세·최근 경제 뉴스로 국내 정규장 준비용 요약을 생성한다. 대시보드 상단에서 저장 날짜, 자료 기준 시각, 지수·환율, 요약과 기사 출처를 확인한다.

- 앱과 Codex ChatGPT 구독 로그인이 실행 가능한 상태여야 한다. API 키로 자동 전환하지 않는다.
- 한국거래소 공개 휴장일 목록으로 주말·휴장일을 제외한다. 조회 장애 시 2026년 공식 목록을 보조 자료로 사용하며, 확인할 수 없는 연도는 생성하지 않는다.
- 코스피·코스닥은 네이버 금융의 국내 지수 일봉, 원/달러는 네이버 금융의 한국 날짜별 환율을 우선 사용한다. 이 공급원 조회·파싱 실패 시 Yahoo Finance 일봉을 사용하고 대체 출처를 경고한다. S&P 500·나스닥은 기존 Yahoo Finance 확정 일봉을 사용한다. 연합뉴스·CNBC는 기준 시각 이전 최근 36시간 기사 제목만 수집하며 기사 전문은 저장하지 않는다.
- 지수의 관측 시각은 완료 판단에 사용한 정규장 마감 시각이다. 네이버 환율의 원본은 날짜만 제공하므로 `observedAt`의 한국시간 23:59:59는 해당 날짜 자료의 완료 판정 경계이며 실제 고시·체결 시각을 뜻하지 않는다. 네이버 일별 환율과 Yahoo 글로벌 FX 종가는 서로 다른 가격 계열이다. 등락률은 선택한 공급원 내부의 직전 유효 일별 값으로 계산하며 공급원 간 가격을 섞지 않는다. 네이버 자료의 제목에는 `2026년 11월 9일 자료 대비`처럼 실제 비교 기준일을 명시한다. 중간 봉이 누락된 여러 거래일 변동을 하루 변동이라고 부르지 않는다. 비교일을 한국어 날짜로 표기해 기존 숫자 검증에서 ISO 날짜의 하이픈이 음수 부호로 해석되는 문제를 피한다.
- 브리핑 날짜는 생성 대상일이고, **시세 기준**·**기사 작성**은 자료의 날짜다. 국내 지수·환율에 직전 KRX 거래일 자료가 없으면 `PARTIAL`과 실제 기준일·누락 거래일을 표시한다. 이전 Yahoo 환율 스냅샷의 일봉 종료 시각은 가격 기준일보다 하루 뒤이므로 최신성 판정에서 하루를 빼서 비교한다. 원본 스냅샷의 시각을 직접 고치지는 않는다.
- 모델이 인용한 출처 ID와 숫자를 서버에서 대조한다. 영문 월명은 한국어 숫자 월로 번역할 수 있다. 이 검증은 자유 서술 전체의 사실성을 보증하지 않으므로 원문 링크도 제공한다.
- 정상 결과는 날짜별로 저장하고 같은 날짜의 재요청에는 재사용한다. 단, **오늘 저장본의 국내 지수·환율 기준일이 오래됐을 때** 생성 요청과 예약 복구가 재수집한다. 기존 모든 시세를 유지하고 국내 시세 기준일이 개선됐을 때만 AI를 다시 호출한다. 요약 검증을 통과한 경우에만 교체하고, 교체 전 원본 JSON은 같은 행의 `snapshot_history_json`에 누적 보관한다. 수집·AI 실패, 개선 없음, 기존 시세 누락·퇴행 시에는 기존 저장본을 유지한다. 과거 날짜 저장본은 재생성하지 않는다.
- `PARTIAL`은 일부 수집 실패·오래된 시세, `NO_DATA`는 자료 없음, `FAILED`는 모델 호출·응답 검증 실패다. 실패 시에도 수집 근거는 남긴다.
- 재시작 시 08:30 이후 오늘 누락분을 보충하며 자동 재시도는 날짜당 3회다. 화면의 **오늘 브리핑 다시 시도**는 별도 수동 재시도다.
- 화면이 표시 중이면 분당 저장본을 다시 읽고 화면 복귀 시 갱신한다. 이 조회로 AI를 다시 호출하지 않는다.

## API

- `GET /api/market-briefings/latest`: 가장 최근 저장본. 없으면 404.
- `GET /api/market-briefings`: 최근 20일 이력.
- `GET /api/market-briefings/YYYY-MM-DD`: 지정일 저장본.
- `POST /api/market-briefings/today`: 오늘 생성·재시도. 로컬 동일 출처와 `X-Strategy-Local: 1` 헤더가 필요하다. 08:30 전에는 409다.

예약 실행만 끄려면 `MARKET_BRIEFING_SCHEDULING_ENABLED=false`로 실행한다. 테스트 스크립트는 이를 자동으로 설정해 실제 모델과 시세를 호출하지 않는다.

## 2026-11-11 직전 거래일 누락 수정

11월 11일 장전 브리핑의 국내 지수·환율 목표 기준일은 직전 KRX 거래일인 11월 10일이다. 당일 11일 지수봉과 환율 행은 08:30 기준 시각 이후 자료이므로 제외한다. 예를 들어 `[9일 100, 10일 110, 11일 999]`라면 10일 110과 같은 공급원의 9일 100으로 등락률을 계산한다.

기존 Yahoo 단독 경로는 최신 거래일의 종가가 `null`이면 이전 유효 봉으로 후퇴했다. 환율은 일봉 시작에 24시간을 더한 종료 시각을 사용해 한국 08:30에 전날 일봉이 아직 미완료로 취급되는 경우도 있었다. 이미 `READY`·`PARTIAL`로 저장되면 재시도와 예약 복구가 수집을 건너뛰어 오래된 값이 유지됐다.

확인 근거: 기존 `build/briefing-date-source-KS11.json`에는 11월 9일 일봉의 값이 `null`로 저장되어 있다. 이번 공개 조회에서는 [네이버 코스피 차트](https://api.stock.naver.com/chart/domestic/index/KOSPI?periodType=dayCandle)의 11월 10일 행과 [네이버 일별 환율](https://api.stock.naver.com/marketindex/exchange/FX_USDKRW/prices?page=1&pageSize=10)의 11월 10일 행이 확인됐다. 이는 공급원 공개 응답 확인이며 현재 애플리케이션 수집 성공의 증거가 아니다. 코스닥 차트의 동일 공급원 실응답과 애플리케이션 서버의 오늘 응답은 미확인이다.

### 가정

- 국내 지수·환율의 장전 목표 기준일은 직전 KRX 거래일로 통일한다. 미국 지수는 기존 거래소 시간대를 유지한다.
- 네이버 일별 환율을 우선 자료로 사용하며 글로벌 FX 종가와 동일한 가격이라고 간주하지 않는다.
- 현재 재시도 대상은 저장본에 존재하면서 오래된 국내 시세다. 처음부터 누락된 시세만 있는 저장본의 보충은 이번 변경 범위에 포함하지 않는다.

### 검증 현황

- 시세 수집 6건과 서비스 복구·보존 8건의 회귀 테스트를 추가했다. 테스트는 작성 후 소스로 대조했으며 실행하지 않았다. 중간 날짜의 값이 null이거나 행 자체가 없는 경우에도 실제 비교 기준일이 표기되는 사례와 실제 파서의 출처를 한국어 날짜로 요약해 수치 검증을 통과시키는 사례를 포함한다.
- 변경 diff·참조 관계·날짜 경계·원본 JSON 보존 경로를 정적으로 확인했다. `git diff --check`는 종료 코드 0이며 LF→CRLF 안내만 출력됐다. 컴파일 검증은 아니다.
- 사용자 지시의 스크립트 실행·배포 제한 때문에 컴파일·JUnit·브라우저·DB 스키마 반영을 수행하지 않았다. 이전 실행 로그와 이번 소스 검토를 테스트 통과 근거로 사용하지 않는다.

변경 파일은 서버의 `MarketBriefingSources.java`, `MarketBriefingService.java`, `MarketBriefingEntity.java`, 회귀 테스트의 `MarketBriefingSourcesTests.java`, `MarketBriefingServiceTests.java`, 이 문서까지 6개다. 서버·테스트는 기존 `marketbriefing` 패키지 안의 파일만 변경했다. 독립 정적 검토와 수정 후 재검토는 [검토 보고서](../reports/market-briefing-latest-review.md)에 별도로 기록한다.

### 대표 확인 필요

다음 명령은 실행하지 않았다. Java 17과 기존 의존성 캐시, 테스트 DB 격리를 먼저 확인한다.

```powershell
$env:MARKET_BRIEFING_SCHEDULING_ENABLED='false'
$env:RUN_MARKET_BRIEFING_LIVE='false'
.\gradlew.bat test --tests 'com.tossinvest.tossinvestbackend.marketbriefing.*' --offline --console=plain
.\gradlew.bat test bootJar --offline --console=plain
curl.exe --silent --show-error --max-time 10 http://localhost:8090/api/market-briefings/latest
curl.exe --silent --show-error --max-time 15 'https://api.stock.naver.com/chart/domestic/index/KOSDAQ?periodType=dayCandle'
curl.exe --silent --show-error --max-time 180 --request POST --header 'X-Strategy-Local: 1' http://localhost:8090/api/market-briefings/today
```

`snapshot_history_json`은 신규 nullable LOB 컬럼이다. DB 사본에서 컬럼 추가·스냅샷 보존을 검증한 뒤 적용해야 한다. H2에서 기존 컬럼이 없음을 확인한 경우의 수동 추가 명령은 다음과 같다. 기본 설정의 `ddl-auto: update`가 시작할 때 이를 추가할 수 있지만 이번 턴에는 서버를 시작하지 않았다.

```sql
ALTER TABLE market_briefing ADD COLUMN snapshot_history_json CLOB;
```

운영 DB의 ALTER·삭제·실서버 재시작·배포·push·오늘 재생성 POST는 수행하지 않았다. 설정 파일의 기본 포트는 `8090`이지만 실행 중 서버의 실제 주소·포트·기동 상태는 미확인이다. 승인 후 실제 주소를 확인하고 최신 빌드를 반영한 뒤 오늘 생성 버튼이나 위 POST로 재시도한다. POST는 공개 자료 수집·AI 호출·저장을 동반한다. 삭제·push는 이번 수정에 필요하지 않아 실행 명령을 만들지 않았다.

이번 `curl.exe`의 로컬 서버 GET과 Yahoo 공개 시세 조회는 자동 승인 검토에서 `Rejected("rejected by user")`로 거절됐고 상세 사유는 제공되지 않았다. `Get-Content` 기반 스킬 읽기도 같은 결과였으며 허용된 `rg` 읽기로 필요한 지침을 확인했다. 거절된 서버 접근을 다른 실행 수단으로 우회하지 않았으며 컴파일·테스트도 실행하지 않았다. 허용된 웹 읽기 도구로 확인한 공개 네이버 응답과 로컬 서버 검증을 구분한다.

## 일봉 3년 보관

백테스트 공용 캔들은 한국 날짜 기준 `오늘.minusYears(3)`부터의 자료를 보관한다. API 수집과 CSV 가져오기는 같은 날짜·OHLCV 검증을 적용한다. 예약·수동 전체 갱신 시 제외 종목을 포함한 만료 행을 정리한다. 수집 목표 봉 수가 작아도 보관 중인 장기 이력을 봉 수로 잘라내지 않는다.

이는 저장 가능한 최대 기간이며 신규 상장·거래정지·공급자의 과거 조회 제한에 따라 실제 자료가 더 짧을 수 있다. 준비 이력 부족은 전략 결과에서 구분한다. 과거 백테스트에 보존한 실행 스냅샷은 변경하지 않는다.

신규 지표의 자연어 예와 계산 기준은 [MACD·볼린저밴드](strategy-indicators.md)를 따른다.

## 2026-11-12 마무리 보완 — 근거 보존과 저장 원자성

- 오늘 국내 시세 복구는 저장본에 있던 국내 지수·환율만 새 수집 값으로 교체한다. 저장 당시 미국 지수·뉴스와 근거 ID·순서는 유지하며 새 수집의 다른 기사로 덮어쓰지 않는다. 기존 국내 시세가 새 응답에서 빠지거나 날짜가 후퇴하면 교체하지 않는다. 처음부터 누락된 시세를 새로 보충하는 기능은 추가하지 않았다.
- 유지한 비대상 근거의 기존 경고를 보존하고 대상 국내 시세의 이전 경고는 새 수집 경고와 기준일 검사로 갱신한다. 새 수집의 오류 경고도 남기므로 일부 수집이 실패한 복구는 PARTIAL로 표시될 수 있다.
- 수집·모델 호출과 별도로 최종 저장만 기존 Spring TransactionTemplate/JPA 트랜잭션에서 수행한다. 쓰기 직전에 처음 읽은 JSON 원문과 현재 공개 저장본의 일치·상태·시세 개선을 다시 확인하고, 본문과 이력을 모두 직렬화한 뒤 관리 엔티티에 반영한다. 직렬화/저장/낙관적 잠금 오류는 AI 실패 결과로 재저장하지 않고 쓰기 실패로 전파한다. 기존 @Version과 스키마를 재사용하며 이번 보완의 추가 컬럼은 없다.
- 이전 JSON 문자열은 공백을 포함해 그대로 이력에 보존한다. 비교일 계산은 DB 쓰기 트랜잭션 전에 하고, 표시용 경고도 저장 처리 뒤 계산한다. 이전 스냅샷의 날짜·가격·본문을 소급 수정하지 않는다.
- 서비스 회귀 9건과 공급자 회귀 2건을 추가 작성했다. 비대상 근거 보존/비대상 공급원 장애, 두 번째 이력 누적·GET 무변경, 수집 실패·날짜 후퇴, AI 숫자 검증 실패, AI 응답 대기 중 저장본 변경, 직렬화 실패, flush 뒤 실패 롤백, 별도 트랜잭션의 낙관락 충돌, FX 대체·양쪽 공급원 실패를 다룬다. 저장 실패·충돌 검사는 DataJpaTest의 기본 외부 트랜잭션을 NOT_SUPPORTED로 해제하고 합성 fixture를 독립 쓰기 단위로 저장하도록 작성했다. 이 설명은 테스트 소스 구성에 대한 정적 확인이며 실제 롤백·동시성 실행의 성공 증거가 아니다.
- 테스트 소스 작성 뒤 `./gradlew.bat test --tests com.tossinvest.tossinvestbackend.marketbriefing.MarketBriefingServiceTests --offline --console=plain` 실행을 요청했으나 `Rejected("rejected by user")`로 거절됐다. 상세 사유는 제공되지 않았다. 따라서 RED/GREEN, Java 컴파일, JUnit 통과, 실제 DB 보존·화면·외부 수집·운영 복구는 이번에도 미검증이다. 거절된 실행을 다른 실행 경로로 우회하지 않았다.
- 변경 파일: MarketBriefingService.java, MarketBriefingServiceTests.java, MarketBriefingSourcesTests.java, 내부 생성자 변경에 맞춘 opt-in MarketBriefingLiveTests.java, 이 사용 문서. 새 제품 의존성·공개 HTTP API·실제 운영 DB 변경은 없다. 실연동 검사의 저장소·트랜잭션은 double이므로 DB 검증에 사용하지 않는다.

후속 집중 명령(미실행):

```powershell
$env:MARKET_BRIEFING_SCHEDULING_ENABLED='false'
$env:RUN_MARKET_BRIEFING_LIVE='false'
.\gradlew.bat test --tests 'com.tossinvest.tossinvestbackend.marketbriefing.*' --offline --console=plain
```

나머지 격리 통합 검증·DB 사본 확인·실서버 반영·오늘 복구 순서와 차단 내역은 [마무리 계획](../plans/2026-11-12-completion-plan.md)과 이후 최종 취합 보고를 따른다.
