# 사용자 전략 백테스트 API

3단계 구현 안내. 기준: [기획서](project-plan.md), [전략 명세](strategy-spec.md).

`POST /api/backtest/run-strategy`는 사용자 전략과 실행 설정을 받아 **DB에 저장된 일봉**으로 실행한다. 외부 시세 수집이나 주문을 호출하지 않는다. 기존 `/run`, `/run-baseline`, `/run-optimal`, `/compare`는 기존 점수제 방식으로 유지된다.

4단계에서 추가한 전략 버전 저장·실행 이력 조회는 [저장 API 안내](strategy-persistence.md)를 따른다. 이 문서의 직접 실행 API는 이력을 저장하지 않는다.

## 실행 예제

[골든크로스·5% 손절 요청 JSON](examples/golden-cross-backtest.json)의 이동평균 종류·기간·종목·기간·체결 방식은 예제를 위한 명시적 선택값이다. 자연어에서 생략한 값의 자동 기본값이 아니다.

서버를 실행하고 필요한 시세가 DB에 적재된 상태에서 프로젝트 루트 PowerShell에서 실행한다. 데이터가 없다면 기존 CSV 적재 기능을 먼저 사용한다.

```powershell
$strategyJson = Get-Content -Raw -Encoding UTF8 .docs/examples/golden-cross-backtest.json
$strategyResult = Invoke-RestMethod -Method Post `
  -Uri http://localhost:8080/api/backtest/run-strategy `
  -ContentType 'application/json; charset=utf-8' `
  -Body ([System.Text.Encoding]::UTF8.GetBytes($strategyJson))
$strategyResult | ConvertTo-Json -Depth 30
```

명령의 서버 포트는 실행 환경에 맞춘다. 제공된 종목·기간에 데이터와 신호가 있어야 거래가 발생하며, 예제가 특정 성과를 보장하지 않는다.

## 요청 계약

- `strategy`: `schemaVersion: 1`, `entry` 필수. `name`, `originalPrompt`는 선택 메타데이터다.
- `symbol`, `startDate`, `endDate`, `executionMode`: 모두 필수다. 날짜는 `YYYY-MM-DD`, 시작·종료일 포함, 한국 시간 기준이다. 시작일은 종료일 이하여야 한다. 날짜 연도 범위는 1900~9998이다.
- `executionMode`: `SAME_DAY_CLOSE` 또는 `NEXT_DAY_OPEN`.
- `entry`, 선택한 `exit`: `conditions` 배열 1~20개. 여러 조건이면 `operator`에 `AND` 또는 `OR` 필수. 중첩 그룹은 지원하지 않는다.
- 지표 기간은 정수 1~500봉이다. 이동평균 단기 기간은 장기 기간보다 작아야 한다.
- 선택하지 않은 `exit`와 `risk`는 생략 또는 `null`로 표현한다. 선택한 손절 객체 `{}`는 수치 누락 오류다. `risk: {}`는 선택한 청산 정책이 없는 전략이다.
- 알려지지 않은 필드·조건 종류·enum, 소수 지표 기간, 숫자로 쓴 enum, 숫자 대신 쓴 문자열, 빈 enum 문자열, 중복 JSON 키와 뒤에 붙은 추가 JSON은 거절한다. `stopLos` 같은 오타를 무시해 손절 없는 전략으로 실행하지 않는다. 비율은 중간 실수 변환 없이 `BigDecimal`로 읽어 입력 정밀도를 보존한다.

### 지원 조건

이동평균 교차:

```json
{"type":"MA_CROSS","averageType":"EMA","shortPeriod":5,"longPeriod":20,"direction":"UP"}
```

`averageType`은 `SMA`/`EMA`, `direction`은 `UP`/`DOWN`. UP은 직전 단기선 ≤ 장기선이고 현재 단기선 > 장기선인 사건이다. DOWN은 반대다. 단순히 단기선이 위에 있는 모든 봉에서 매수하지 않는다. EMA는 읽은 과거 데이터의 최초 SMA로 초기화한다.

RSI:

```json
{"type":"RSI","method":"SIMPLE","period":7,"threshold":30,"comparison":"CROSS_ABOVE"}
```

`method`는 기존 계산기의 최근 구간 단순 평균 방식인 `SIMPLE`만 지원한다. Wilder 방식은 지원하지 않는다. 기준값은 0~100. `comparison`은 `GTE`(이상), `LTE`(이하), `CROSS_ABOVE`(직전 ≤, 현재 >), `CROSS_BELOW`(직전 ≥, 현재 <). 기존 계산기와 동일하게 평탄 구간 RSI는 100이다.

거래량:

```json
{"type":"VOLUME","period":5,"multiplier":1.5,"comparison":"GTE"}
```

현재 거래량을 **현재 봉을 제외한 직전 N봉 평균 × 양수 배수**와 비교한다. `GTE`/`LTE`만 지원한다. 직전 평균이 0이면 배수 조건을 판정할 수 없어 해당 조건은 준비되지 않은 상태다.

예를 들어 OR로 결합하려면 다음 형식을 사용한다.

```json
{
  "operator":"OR",
  "conditions":[
    {"type":"RSI","method":"SIMPLE","period":7,"threshold":30,"comparison":"LTE"},
    {"type":"VOLUME","period":5,"multiplier":1.5,"comparison":"GTE"}
  ]
}
```

### 선택 청산 정책

```json
{
  "stopLoss":{"rate":-0.05},
  "takeProfit":{"rate":0.10},
  "timeExit":{"days":5},
  "trailing":"LEGACY_STEP_3_PERCENT"
}
```

위 객체는 `strategy.risk`에 넣는다. 각각 독립 선택이며 예시 전체를 강제로 적용하지 않는다.

- 손절률은 -1 초과·0 미만의 소수 비율. 익절률은 양수 소수 비율이다.
- `timeExit.days`: 0~10000 정수. 매수 봉을 0으로 한 보유 봉 수가 기준을 **초과**하고 트레일링 미활성일 때 청산한다. 5이면 여섯 번째 후속 봉에서 판단한다.
- 트레일링: 시작 3%, 보유 3봉 초과 후 시작 1.5%, 상승 간격 3%p. 시작에서 보호선 0%, 다음 단계에서 3%다. 전체 보유기간의 최고 종가를 현재 시작 기준에 비교하고, 현재 가격이 보호선보다 **낮을 때** 청산한다.
- 리스크 판단은 반올림 전 가격과 `매수가 × (1 + 기준률)`을 비교한다. 반환 수익률의 반올림이 주문 판단을 바꾸지 않는다.
- 동시에 발생하면 손절 → 고정 익절 → 시간 청산 → 트레일링 → 지표 `exit` 순서에서 첫 사유를 사용한다. 선택하지 않은 정책은 건너뛴다.

## 실행과 데이터 정책

- 종목별 하나의 매수 포지션만 유지한다. 공매도·추가 매수·수량·자금 배분 모델은 없다.
- 완성된 종가로 판단한다. 장중 고가·저가만으로 손절·익절하지 않는다.
- 종가 방식은 그 종가로 체결하는 시뮬레이션 가정이다. 다음 시가 방식은 이전 종가에서 확정한 주문을 다음 저장 일봉의 시가에 처리한다. 시가 체결 전에 그날 종가를 신호 판단에 사용하지 않는다.
- 종가 청산 후 같은 종가에 재진입하지 않는다. 시가 청산 후 그날 종가의 새 진입 신호는 다음 시가 주문으로 허용한다. 시가 매수 후 그날 종가의 청산 신호도 다음 시가 주문으로 허용한다.
- 시작일 이전 저장 데이터는 지표 계산에만 사용한다. 최초 준비 구간에서 나온 과거 신호·주문을 시작일로 넘기지 않는다. 종료일 뒤 봉은 조회·판정·체결에 사용하지 않는다.
- 진입 AND는 모든 조건, OR는 하나 이상의 조건이 준비되면 판정 가능하다. 준비되지 않은 OR 분기는 참으로 보지 않는다. 긴 지표 청산 조건이 짧은 매수 조건을 막지 않는다.
- 종료 시 보유분은 마지막 종가 평가손익으로 표시한다. 다음 시가가 없는 주문은 `NO_NEXT_BAR`로 반환한다. 가짜 청산 거래를 만들지 않는다.
- 일수는 저장 일봉 개수 차이다. 누락된 휴장·거래정지·데이터 공백을 달력으로 추정하지 않는다. 날짜 공백이 있으면 '다음 시가'는 다음 저장 일봉을 의미한다.
- 사용한 과거 구간을 포함하여 OHLC는 양수, 고가·저가는 시가·종가와 일관적이어야 하고, 거래량은 음수일 수 없다. 같은 한국 날짜에 두 봉이 있거나 날짜 역전·종목 불일치가 있으면 오류다. 데이터를 자동 삭제·보정하지 않는다.
- 거래량 0인 봉은 지표·보유일수에 포함하되, 그 봉에서 체결해야 한다면 `INVALID_DATA`로 거절한다. 직전 평균 0의 거래량 배수는 판정 불가다.
- 응답의 신호 시각은 한국 시간 15:30, 체결 시각은 종가 방식 15:30 또는 시가 방식 09:00로 가정한 epoch milliseconds다. 실제 거래소 특별 개장·폐장 시각은 지원하지 않는다. 원본 봉의 타임스탬프도 별도로 보존한다.

## 응답 해석

- `execution`: 입력 전략과 종목·기간·체결 방식의 스냅샷. 이 직접 실행 경로는 DB 이력을 저장하지 않는다.
- `status`: `NO_DATA`(요청 구간 일봉 없음), `INSUFFICIENT_DATA`(구간 내 진입 조건 준비 불가), `NO_TRADES`(판정 가능하지만 주문·포지션·완료 거래 없음), `COMPLETED`.
- `candleCount`: 요청 구간 봉 수. `warmupBars`: 시작일 이전 읽은 봉 수. `requiredWarmupBars`: 진입 조건 준비에 필요한 최소 이전 봉 수(AND 최대, OR 최소). 실제 준비 여부는 지표 유효값도 검사한다.
- `actualStartDate`, `actualEndDate`: 요청 구간에서 실제 사용한 첫·마지막 데이터 날짜.
- `trades`: 청산 완료 거래. `entry`/`exit`에 `signalTimestamp`, `executionTimestamp`, 원본 `signalBarTimestamp`/`executionBarTimestamp`, `price`, `reason`, `evidence`가 있다.
- `evidence`: 조건 경로와 종류, 실제값·비교 기준값, 직전값, 조건별 참/거짓을 반환한다. AND·OR 그룹에서는 불충족 분기도 포함한다. 이동평균·거래량은 가격·거래량, RSI는 지표값, 리스크 가격 청산은 가격, 시간 청산은 봉 수를 비교한다. 트레일링은 보호선과 활성 기준의 가격 근거를 함께 반환한다.
- `openPosition`: 진입 체결, 마지막 평가 시각·가격, 보유 봉 수, `unrealizedReturnRate`. 없으면 `null`.
- `pendingOrder`: `BUY`/`SELL`, 신호 시각·원본 봉·사유·근거와 `NO_NEXT_BAR`. 없으면 `null`.
- `assumptions`: 비용·자금·체결·지표 계산 가정을 설명하는 코드 목록.

### 성과 계산

모든 비율은 소수 단위다. `0.05`는 5%. **청산 완료 거래만** 통계에 포함한다.

- 거래 수익률: `(청산가 - 진입가) / 진입가`, 소수 6자리 반올림.
- `closedTrades`: 완료 거래 수.
- `winRate`: 양수 수익 거래 수 / 완료 거래 수.
- `averageTradeReturnRate`: 거래 수익률 평균.
- `sumTradeReturnRate`: 거래 수익률의 단순 합. 계좌 복리 수익률이 아니다.
- `tradeReturnMaxDrawdown`: 거래별 수익률 누적 합의 최고점 대비 최대 하락분(음수). 일별 계좌 평가자산 낙폭이 아니다.
- `tradeSharpeRatio`: 기존 계산과 동일한 거래 평균 / 모집단 표준편차. 무위험수익률 0, 연율화 없음. 평균은 6자리, 최종 샤프는 4자리 반올림. 표준편차 0이면 0.
- `averageHoldingBars`: 완료 거래 보유 봉 수의 평균.

완료 거래가 없으면 지표는 0이며 `openPosition`과 `pendingOrder`를 함께 확인해야 한다. 수수료·세금·슬리피지·자금·수량은 계산하지 않는다. 기존 Profit Factor의 임의 상한값은 새 응답에 노출하지 않는다.

## 오류

- HTTP 400 `VALIDATION_ERROR`: 누락·범위 오류를 `issues`의 `path`, `code`, `message`로 함께 반환한다. 필요한 값은 사용자에게 보완받는다.
- HTTP 400 `INVALID_REQUEST`: 잘못된 JSON·미지원 필드·조건·enum 등. JSON 역직렬화가 중단된 경우 최초 오류 위치를 반환한다.
- HTTP 422 `INVALID_DATA`: 시세 오류 또는 체결 불가. `timestamp`와 `message`로 위치·사유를 확인한다.

## 범위와 검증

`StrategyEvaluator.prepare(strategy, bars)`의 `entry(index)`와 `exit(index, PositionContext)`는 DB·스케줄러·주문 없이 조건을 판단한다. 모의매매 어댑터는 같은 완료 일봉·포지션의 진입가/보유 봉 수/최고 종가를 전달해 재사용할 수 있다. 실제 전략별 페이퍼 실행·저장·LLM·웹 화면 연결은 후속 단계다.

검증 코드: `StrategyValidatorTests`, `StrategyEvaluatorTests`, `UserStrategyBacktestEngineTests`, `UserStrategyBacktestApiTests`. API 테스트는 임시 H2와 실제 JSON·조회·엔진을 사용하고, 기존 스케줄러는 테스트 대역으로 교체한다.

2026-09-09 최종 실행에서 신규 41개를 포함한 전체 63개 테스트와 `bootJar`가 통과했다. 기존 200종목 결과 JSON은 변경 전후 동일했다. 상세 근거·변경 파일은 [구현 및 검증 기록](user-strategy-implementation.md)에 있다.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
.\gradlew.bat test bootJar --offline --console=plain --rerun-tasks
```

로컬 의존성 캐시가 없는 환경에서는 의존성을 먼저 내려받아야 한다. 실데이터 이상과 외부 시스템 상태는 이 테스트만으로 검증되지 않는다.
