# MACD·볼린저밴드 전략 조건

2026-11-05 추가. 자연어 해석 → 편집·검증 → 저장 버전 → 단일 종목 백테스트·모의매매 및 형식 4 조합에 같은 조건을 사용한다. 새 조건은 `schemaVersion: 2`이며 형식 1에 조용히 추가하지 않는다. 형식 3의 상대강도 선정 계약은 유지한다.

## 자연어 입력 예

아래 숫자는 사용자가 선택해 명시하는 예시이며 자동 기본값이 아니다.

> 국내 개별 종목 완성 일봉 종가 기준. 단기 EMA 12봉·장기 EMA 26봉·시그널 EMA 9봉인 MACD선이 시그널선을 상향 교차하면 매수. MACD선이 시그널선을 하향 교차하면 매도. 별도 위험관리 없음.

> 국내 개별 종목 완성 일봉 종가 기준. 기간 20봉·모집단 표준편차 배수 2인 볼린저밴드 하단 이하 종가에서 매수. 종가가 같은 설정의 중단을 초과하면 매도. 별도 위험관리 없음.

> 일봉 종가 기준. MACD 단기 EMA 12봉·장기 EMA 26봉·시그널 EMA 9봉으로 계산한 히스토그램이 숫자 0을 상향 교차하면 매수. 별도 청산 및 위험관리 없음.

기간·배수·비교 대상이 빠지면 값을 `null`로 보존하고 기존 보완 질문과 서버 검증 오류를 표시한다. 'MACD 골든크로스'에 12/26/9, '볼린저 하단'에 20봉/2배를 자동으로 넣지 않는다. 보완 전 저장·실행 확인을 통과하지 못한다.

## MACD 계약

`type: MACD`의 필수 입력은 `fastPeriod`, `slowPeriod`, `signalPeriod`, `value`, `reference`, `comparison`이다. 기간은 정수 1~500봉이며 `fastPeriod < slowPeriod`다. 숫자 비교에도 세 기간을 명시한다.

- `value: LINE`: 단기 종가 EMA − 장기 종가 EMA.
- `value: SIGNAL`: MACD선의 `signalPeriod` EMA.
- `value: HISTOGRAM`: MACD선 − 시그널선.
- `reference: THRESHOLD`: 선택한 값과 숫자 `threshold`를 비교한다. 0과 음수도 유효하다.
- `reference: SIGNAL`: MACD선과 시그널선을 비교한다. 이 조합은 `value: LINE`, `threshold: null`이어야 한다.

각 종가 EMA의 초기값은 첫 해당 기간 종가의 SMA다. 시그널 EMA는 최초 유효 MACD선부터 계산하며 준비 중인 값을 0으로 채우지 않는다. 초기 평균과 EMA 갱신은 소수 12자리 `HALF_UP`이다. 기존 이동평균 조건의 계산 정밀도는 변경하지 않는다.

최초 판정에 필요한 봉 수는 MACD선 숫자 비교가 `slowPeriod`, 시그널·히스토그램·시그널 비교는 `slowPeriod + signalPeriod - 1`이다. 교차 비교는 이전 값이 필요하므로 한 봉 더 준비한다. 실제 수신 이력이 부족하면 진입하지 않는다.

```json
{
  "type": "MACD",
  "fastPeriod": 12,
  "slowPeriod": 26,
  "signalPeriod": 9,
  "value": "LINE",
  "reference": "SIGNAL",
  "threshold": null,
  "comparison": "CROSS_ABOVE"
}
```

## 볼린저밴드 계약

`type: BOLLINGER`의 필수 입력은 `period`, 양수 `multiplier`, `band`, `comparison`이다. 기간은 정수 1~500봉이다. 중단을 선택해도 명시한 배수를 보존하며 배수 누락을 임의로 채우지 않는다.

현재 완성 봉을 포함한 최근 `period`개 종가에서 SMA와 **모집단** 표준편차를 계산한다. 분산은 각 종가와 SMA 차이 제곱 합을 `period`로 나누며 표본 분산의 `period - 1`을 사용하지 않는다.

- `UPPER`: SMA + `multiplier` × 표준편차.
- `MIDDLE`: SMA.
- `LOWER`: SMA − `multiplier` × 표준편차.

현재 종가를 선택한 밴드와 비교한다. 중간 평균·제곱근은 `DECIMAL128`, 최종 밴드는 소수 12자리 `HALF_UP`이다. 최초 상태 비교는 `period`봉, 교차는 `period + 1`봉이 필요하다. 가격이 모두 같은 구간에서는 표준편차 0으로 세 밴드가 같아지며 등호를 포함하는 조건만 충족한다.

```json
{
  "type": "BOLLINGER",
  "period": 20,
  "multiplier": 2,
  "band": "LOWER",
  "comparison": "LTE"
}
```

## 비교·실행·검증

두 지표는 `GT`, `LT`, `GTE`, `LTE`, `CROSS_ABOVE`, `CROSS_BELOW`를 지원한다. 상향 교차는 이전 실제값 ≤ 이전 기준값이면서 현재 실제값 > 현재 기준값이다. 하향 교차는 그 반대다. 현재 기준값만 전일 값과 비교하지 않는다.

완성 일봉 종가에서 신호를 판단하고 사용자가 고른 `SAME_DAY_CLOSE` 또는 `NEXT_DAY_OPEN`으로 체결한다. 지표의 장중 터치·다이버전스·밴드폭·스퀴즈를 같은 조건으로 임의 대체하지 않는다. 기존 손절·비용·저장 버전·종료 시 미청산 처리 규칙은 그대로 적용한다.

`MacdBollingerStrategyTests`는 독립 수치 예, 준비기간, 교차·등호 경계, 미래 봉 불변성, JSON 보존, 두 체결 방식과 조합 말단을 검사한다. `MacdBollingerAssistantTests`와 `StrategyPaperEngineTests`는 보완 입력·모의매매 일치·체크포인트 재처리를 검사한다. `scripts/tests/strategy-indicators.browser.cjs`는 편집 입력·누락값·실행 조건 요약·거래 근거 표시와 모바일 폭을 검사한다.

실제 자연어 모델 검증은 `RUN_STRATEGY_INDICATORS_LIVE=true`와 `MacdBollingerLiveTests`로 별도 활성화한다. 명시 조건 해석과 기간 미지정 시 질문 반환을 검사하고 `build/strategy-indicators-live.json`, `build/strategy-indicators-missing-live.json`에 기록한다. 테스트 명세가 있다는 사실과 실행 통과는 구분한다.

계산식 참고: [Fidelity MACD](https://www.fidelity.com/learning-center/trading-investing/technical-analysis/technical-indicator-guide/macd), [Fidelity Bollinger Bands](https://www.fidelity.com/learning-center/trading-investing/technical-analysis/technical-indicator-guide/bollinger-bands). 초기값·반올림·모집단 분모와 준비기간은 위에 명시한 이 프로젝트의 실행 계약이다.
