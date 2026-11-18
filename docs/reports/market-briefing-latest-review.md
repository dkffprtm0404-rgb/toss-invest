# 장전 브리핑 최신 변경 최종 정적 검토

> 문서 정리(2026-11-17): `build/market-briefing-latest-review.md`에서 이동했다. 아래 명령·검증 결과·상태와 이전 경로는 작성 당시 기록이다. 현재 문서 위치는 [전체 목차](../README.md)를 따른다.

- 검토일: 2026-11-11
- 작성자: 코드 검토 담당
- 프로젝트: `C:\Users\EM_NB171\Desktop\정성윤\TossInvest\toss-invest-backend`
- 기준: HEAD `033f3c3f829059d8b9c171416fc7eb6811fdb918` 대비 미커밋 변경. 마지막 인계의 한국어 비교일 표기와 서비스 회귀 테스트까지 포함한다.
- 범위: 기존 P2 수정과 직접 관련된 파서·모델 지침·숫자 검증·회귀 테스트·화면 소비 경로. 기존 복구 변경의 diff와 `.docs/market-briefing.md`도 대조했다.
- 방법: 소스와 테스트 단언의 정적 대조. 이번 검토에서 수정한 파일은 이 보고서뿐이다.

## 최종 판정

**기존 P2는 네이버 수집 경로에서 정적으로 해소됐다.** 실제 비교 기준일을 한국어로 표시해 중간 봉 누락을 전거래일 변동으로 잘못 명명하지 않는다. 마지막 소규모 보완과 직접 영향 범위에서 추가 P0·P1·P2 결함은 확인하지 못했다.

이 판정은 코드 경로와 단언의 대조 결과다. 컴파일·테스트 통과, 실제 공급원 수집, 모델의 지침 준수, 운영 반영을 확인한 판정은 아니다.

## 기존 P2 해소 근거

### 실제 비교일을 출처 제목에 명시

- 위치: `src/main/java/com/tossinvest/tossinvestbackend/marketbriefing/MarketBriefingSources.java:73–81`.
- `lowerEntry(last.getKey())`로 선택한 실제 이전 유효 자료의 날짜를 `DateTimeFormatter.ofPattern("uuuu년 M월 d일")`로 표기한다. 날짜를 추정하거나 직전 거래일이라고 치환하지 않는다. 지수와 네이버 환율은 이 제목 생성 경로를 공유한다.
- 기존 지적의 `11/06=100, 11/09=null, 11/10=110`에서 계산은 `110 / 100 - 1 = 0.1`이며 제목은 `코스피 종가 110, 2026년 11월 6일 자료 대비 10%`가 된다. 9일 행 자체가 없어도 동일한 이전 유효 날짜를 선택한다. 최신 관측일은 계속 10일이다.
- `MarketBriefingSourcesTests.java:106–119`의 `aMissingIntermediateSessionNamesTheActualComparisonDate`는 두 입력을 모두 순회하며 10일 관측 시각, `0.1`, `2026년 11월 6일 자료 대비` 포함 및 `전거래일 대비` 부재를 단언한다. 이전 보고서의 해당 테스트 빈틈은 해소됐다. 실행 결과는 확인하지 않았다.

### 한국어 날짜와 기존 숫자 검증의 호환성

- `MarketBriefingService.java:169–179`의 `NUMBER` 패턴과 숫자 집합 검증은 변경되지 않았다. ISO `2026-11-03`은 `2026`, `-11`, `-03`으로 분리될 수 있지만, 새 제목의 `2026년 11월 3일`은 양수 `2026`, `11`, `3`으로 읽힌다.
- 새 서비스 회귀 `realProviderComparisonDateCanBeSummarizedInKorean`은 실제 네이버 파서로 `11/03=100, 11/04=110` 출처를 만든 뒤 수집·AI만 모의 처리한다(`MarketBriefingServiceTests.java:213–222`). AI 응답의 `코스피 종가는 110이며 11월 3일 자료 대비 10%입니다.`가 `READY`로 저장되는지 단언한다.
- 정적 대조상 출처 제목의 숫자 집합에는 `2026, 11, 3, 110, 10`이 있고 요약은 그 부분집합을 사용한다. 기존 검증 규칙을 넓히지 않고 한국어 월·일 요약을 허용하는 경로다. 이 테스트도 실행하지 않았다.
- `MarketBriefingService.java:25`는 비교 기준일을 함께 쓰고 임의로 전일·전거래일 대비로 바꾸지 않도록 지시한다. `CodexClient.java:49–51`에서 해당 지침이 실제 요약 세션에 전달되는 참조를 확인했다.

## 주변 영향 확인

- **계산·시각·응답 형식:** 제목 보완은 기존 가격·변동률 계산, 당일·미래 자료 배제, `observedAt`, 공급원 URL, `Source` 필드 구성을 바꾸지 않는다. 네이버의 지수 15:30 KST와 환율 23:59:59 KST 완료 경계는 유지된다(`MarketBriefingSources.java:55–82`).
- **FX 라우팅:** `collect()`는 `USDKRW`를 네이버 환율 URL과 환율 파서 분기로 보낸다(`MarketBriefingSources.java:27–35`). `MarketBriefingSourcesTests.java:90–104`는 모의 HTTP 응답을 실제 `collect()`에 연결해 환율 ID·값·관측 시각을 단언한다. 직접 파서 테스트만 있던 검증 범위를 보완한다.
- **기준일 비교·저장본 보전:** 기존 Yahoo FX 종료 시각의 하루 보정은 `quoteDate()`에서 수행되며 출처 제목을 파싱하지 않는다(`MarketBriefingService.java:77–103`). 오늘 재시도와 교체 직전 재검사·원본 이력 추가 경로도 제목 형식에 의존하지 않는다(`107–137`). 이번 제목 변경으로 기존 저장본을 소급 수정하거나 재생성을 강제하지 않는다.
- **화면 소비:** `market-briefing.js:103–112`는 가격·변동률 필드를 표시하고, `139–143`은 출처 제목을 그대로 링크 텍스트로 사용한다. 이 경로에는 ISO 비교일 문자열을 해석하는 처리가 없다. 화면 코드는 수정하지 않았으며 화면 실행·캡처도 수행하지 않았다.
- **문서:** `.docs/market-briefing.md:8,41–43`의 한국어 비교일·추가 회귀·실행 미검증 설명은 최종 소스와 일치한다. 문서에 기록된 담당자의 공개 응답 확인을 이번 검토의 수집 성공 근거로 사용하지 않았다.

## 테스트 단언과 남은 빈틈

수집 신규 **6건**, 서비스 신규 **8건**, 합계 **14건**을 최종 diff의 선언·입력·단언과 대조했다. 기존 서비스 테스트의 이름 변경은 신규 건수에 포함하지 않았다.

- 수집 6건: 국내 지수 완료 봉 선택, 네이버 환율 파싱, 유효 값 부족 예외, 실제 `collect()`의 FX 라우팅, 중간 날짜 null·행 누락 시 비교일 명시, 코스피 Yahoo fallback.
- 서비스 8건: 오늘 시세 개선·원본 보관, 예약 복구, AI 예외 시 보전, 개선 없음·자동 3회 제한, 기존 환율 누락 시 보전, 오래된 환율 경고, legacy Yahoo FX 가격 기준일, 실제 파서 출처의 한국어 날짜 요약.

다음은 별도 확정 결함이나 이번 수정의 실패로 단정하지 않는 검증 한계다.

1. 모델의 비교일 누락·의미 왜곡을 서버가 별도로 거부하는 검증은 없다. 기존 검증은 출처 ID·숫자 집합을 대조하며, 새 지침과 모의 AI 응답 테스트가 실제 모델의 준수를 증명하지는 않는다.
2. 게시된 저장본의 기준일 후퇴, 수집 전체 실패, AI 응답 검증 실패를 각각 넣은 직접 보전 회귀는 여전히 없다. 기존 보전 회귀는 AI 예외·개선 없음·기존 환율 누락을 다룬다.
3. 두 번째 교체의 이력 누적 순서, 별도 트랜잭션 간 낙관락 충돌, 저장 실패 시 본문·이력 동시 보전은 직접 검증하지 않았다. `@DataJpaTest` 단일 트랜잭션을 동시성 검증으로 취급하지 않는다.
4. FX의 네이버 파싱 실패→Yahoo fallback 및 양쪽 공급원 실패의 직접 회귀는 없다. 변경하지 않은 Yahoo `parseQuote()`의 `전거래일 대비` 제목까지 실제 비교일 표기로 바뀐 것은 아니다(`MarketBriefingSources.java:84–103`). 이번 P2 해소 판정은 지적 대상이었던 네이버 파서에 한정한다.

## 수행한 검증

- HEAD·Git 상태와 최종 서버 3개·테스트 2개·문서 1개 diff를 확인했다. 파서·서비스·테스트 소스와 `CodexClient` 지침 참조, 화면의 출처·수치 소비 경로를 정적으로 대조했다.
- `git diff --check 033f3c3 -- .docs/market-briefing.md src/main/java/com/tossinvest/tossinvestbackend/marketbriefing src/test/java/com/tossinvest/tossinvestbackend/marketbriefing` 종료 코드 **0**. LF→CRLF 안내만 있었고 공백 오류는 없었다. 컴파일·테스트 통과를 뜻하지 않는다.
- 사용자 제한에 따라 소스 수정·스크립트 실행·컴파일·JUnit·브라우저 검사·서버 실행·DB 조회/변경·실제 수집·AI 호출을 수행하지 않았다. 기존 테스트 로그도 이번 변경의 통과 증거로 사용하지 않았다.

## 가정

- 검토 기준은 마지막 인계 후 로컬에 존재하는 미커밋 변경이다. 실행 환경이나 배포 상태는 추정하지 않는다.
- 등락률은 같은 공급원 내부의 직전 유효 자료와 비교할 수 있으며 실제 비교일을 표시한다. 반드시 직전 거래일 값이 있어야만 수치를 제공하는 정책으로 바꾸지는 않는다.
- 기존 게시 상태 `READY`·`PARTIAL` 보전, 직전 KRX 거래일 목표, 처음부터 누락된 시세의 보충 제외, 네이버 환율 완료 경계에 대한 기존 범위는 유지한다.
- 이번 작업은 최종 정적 판정과 보고서 갱신이며 구현·DB 반영·배포는 포함하지 않는다.

## 대표 확인 필요

다음 명령은 **실행하지 않았다**. 실행 제한 해제 후 Java 17·테스트 DB 격리·의존성 캐시를 확인한 뒤 수행할 검증 후보로 남긴다.

```powershell
$env:MARKET_BRIEFING_SCHEDULING_ENABLED='false'
$env:RUN_MARKET_BRIEFING_LIVE='false'
.\gradlew.bat compileJava compileTestJava --offline --console=plain
.\gradlew.bat test --tests 'com.tossinvest.tossinvestbackend.marketbriefing.MarketBriefingSourcesTests' --tests 'com.tossinvest.tossinvestbackend.marketbriefing.MarketBriefingServiceTests' --offline --console=plain
```

- 기존 DB의 `snapshot_history_json` 반영, 복수 이력·동시 갱신·저장 실패 원자성은 격리 DB 사본에서 별도 검증이 필요하다. 이번 검토에서는 DB에 접근하거나 DDL을 작성·실행하지 않았다.
- 실제 공급원 응답·모델 요약·실서버 반영·오늘 재생성은 미검증이다. 수집·AI·저장을 일으키는 POST나 외부 조회를 수행하지 않았다.
- 삭제·배포·push·외부 전송은 이번 검토에 필요하지 않아 실행 명령을 계획하지 않았다. 요청된 AI Office 보고서 첨부만 수행한다.

## 갱신 파일

- `build/market-briefing-latest-review.md` — 본 보고서 1개. 기존 소스·테스트·프로젝트 문서는 수정하지 않았다.
