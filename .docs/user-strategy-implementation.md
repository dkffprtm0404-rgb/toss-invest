# 사용자 전략 백테스트 구현 계획

작성일: 2026-09-09. 사용자 승인: 3단계 설계·정책 승인 및 세부 구현 위임.

**목표:** 검증한 사용자 전략과 종목·기간·체결 방식을 전달해 실행하고, 거래 근거와 미체결 상태를 조회한다.

**기준:** [기획서](project-plan.md), [전략 명세](strategy-spec.md), [기존 검증](engine-validation.md).

**구조:** `strategy`에 불변 명세, 검증, DB와 독립적인 일봉 조건 판정을 둔다. `backtest`에 사용자 전략 실행기와 API를 추가한다. 기존 엔진·그리드서치의 계약은 유지한다. Java 17 / 기존 Spring Boot·Jackson·JUnit을 사용하며 의존성을 추가하지 않는다.

## 승인된 실행 정책

- 단일 종목 일봉, 한 번에 하나의 매수 포지션. 수량·자금·비용 모델은 도입하지 않는다.
- 조건 그룹은 한 단계 AND/OR. 단일 조건은 결합 연산자 생략 가능.
- 필수값은 자동 보충하지 않는다. 청산 정책은 선택 사항이다.
- 신호는 완성된 종가 기준. SAME_DAY_CLOSE / NEXT_DAY_OPEN 모두 매수·매도·리스크 청산에 적용한다.
- 같은 종가에서 청산 후 재진입하지 않는다. 시가 청산 후 그날 종가 신호는 다음 시가 주문으로 허용한다.
- 보유기간은 진입 봉을 0으로 한 일봉 차이. 존재하지 않는 휴장일은 세지 않는다.
- 마지막 보유분은 미실현 수익률로 반환한다. 다음 시가가 없는 주문은 미체결로 반환한다.
- 유효하지 않은 가격·중복 일봉은 오류로 반환하며 임의 보정하지 않는다.
- 기존 거래 단위 수익률 합·낙폭·샤프를 명확히 표시한다. 미실현 손익은 완료 거래 지표에 넣지 않는다.
- RSI는 기존 단순 평균 방식(SIMPLE)만 제공하며 평탄 구간 RSI 100 동작을 명시한다.
- 기간은 1~500봉, 그룹은 1~20조건. 실행 구간 이전 데이터는 지표 준비에만 사용한다.

## 구현 순서와 검증

- [x] 전략 모델·검증: `strategy/StrategyDefinition.java`, `StrategyValidator.java`, `StrategyValidationException.java`. 누락 목록, 범위, 비선택 청산, 조건 조합 테스트와 구현 완료.
- [x] 공통 판정: `StrategyBar.java`, `StrategyEvaluator.java`. SMA/EMA 교차, SIMPLE RSI 비교·돌파, 직전 평균 거래량, 리스크 우선순위(손절→고정 익절→시간→트레일링→지표) 검증 완료.
- [x] 실행: `backtest/UserStrategyBacktest{Request,Result,Engine}.java`. 종가/다음 시가 시나리오, 재진입, 기간 준비, 미체결, 미실현, 데이터 오류, 미래 데이터 추가 시 과거 거래 불변 검증 완료.
- [x] API: `UserStrategyBacktestController.java`, `UserStrategyBacktestService.java`와 저장 캔들 조회. 실제 JSON 및 임시 H2로 실행·오류 응답·기간 경계 검증 완료.
- [x] 문서·최종 검증: 요청 JSON 예제·계산 기준 작성, 전체 테스트·bootJar 실행, 기존 200종목 CSV 결과 비교, 코드 리뷰 지적 수정 및 회귀 검증 완료.

각 구현 단위는 기대 동작 테스트 → 실패 확인 → 구현 → 통과 확인 순서로 진행한다. 작업 중인 기존 문서·테스트는 보존하며 커밋·푸시는 별도 요청 없이 수행하지 않는다.

검증 명령(PowerShell):

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
.\gradlew.bat test bootJar --offline --console=plain --rerun-tasks
```

## 최종 검증 결과

2026-09-09 위 명령으로 전체 작업을 다시 실행했다. **63개 테스트 통과, 실패·오류·건너뜀 0개**, 실행 JAR 빌드 성공. 기존 테스트 22개와 신규 테스트 41개다.

- 신규 검증: 전략 검증 5개, 공통 판정 9개, 사용자 백테스트 엔진 16개, 실제 JSON·임시 H2 API 11개.
- 기존 CSV: 200종목, 128,177봉, 기존 베이스라인 거래 2,953건.
- 변경 전후 `baseline.json`의 SHA-256 모두 `5733c2d1c99874c494ca651694fcbe35e2630dadf1f3c953d06fc6ba8641cb36`으로 동일.
- 입력 CSV SHA-256: `6bd7ec5675d6183d77c25bafcad7c1fbe3f51f49628e320a027f909d0b40ea57`.
- 코드 리뷰에서 확인한 OR/청산 준비 기간에 의한 진입 차단과 반올림에 의한 리스크 경계 오류를 실패 테스트로 재현한 후 수정했다. API의 소수 정밀도 손실·문자열 강제 변환·중복 키도 회귀 테스트로 확인 후 수정했다.
- `git diff --check`에서 공백 오류 없음. Gradle 테스트 중 JVM 클래스 공유 경고가 출력되지만 실패는 없었다.
- 보고서: `build/reports/tests/test/index.html`, 기준 비교: `build/reports/engine-validation/baseline-before-user-strategy.json` 및 `baseline.json`. 빌드 산출물: `build/libs/toss-invest-backend-0.0.1-SNAPSHOT.jar`.

실제 운영 DB·외부 시세 API·모의매매 스케줄러를 호출하지 않았다. API 통합 검증은 테스트 전용 메모리 H2에서 수행했다. 비용·자금·저장·LLM·웹·전략별 페이퍼 실행은 후속 범위다.

## 이번 작업의 파일 목록

Java 경로 기준: `src/main/java/com/tossinvest/tossinvestbackend/`.

- 추가: `strategy/StrategyDefinition.java`, `StrategyValidator.java`, `StrategyValidationException.java`, `StrategyBar.java`, `StrategyEvaluator.java`.
- 추가: `backtest/UserStrategyBacktestRequest.java`, `UserStrategyBacktestResult.java`, `UserStrategyBacktestEngine.java`, `UserStrategyBacktestService.java`, `UserStrategyBacktestController.java`.
- 수정: `backtest/CandleRepository.java`의 종료 시각 이전 캔들 조회 메서드.
- 테스트 추가: `src/test/java/com/tossinvest/tossinvestbackend/strategy/StrategyValidatorTests.java`, `StrategyEvaluatorTests.java`, `backtest/UserStrategyBacktestEngineTests.java`, `UserStrategyBacktestApiTests.java`.
- 문서 수정: `.docs/strategy-spec.md`, `.docs/project-plan.md`.
- 문서·예제 추가: `.docs/user-strategy-implementation.md`, `.docs/user-strategy-backtest.md`, `.docs/examples/golden-cross-backtest.json`.

작업 시작 전부터 존재하던 `TossInvestBackendApplicationTests` 수정, 기존 엔진·지표 테스트와 `.docs/engine-validation.md`는 이번 작업에서 수정하지 않았다. 커밋·푸시는 수행하지 않았다.
