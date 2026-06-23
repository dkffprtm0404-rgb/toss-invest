package com.tossinvest.tossinvestbackend.backtest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * trading-rules.md 8장에 명시된 "검증 전 추정값" 후보들로 그리드서치용 파라미터 조합을 생성한다.
 * 8장에서 명시적으로 비교 대상이라고 언급한 값들만 후보로 포함했다 (임의로 후보를 늘리지 않음):
 * - 거래대금: 20억/30억/50억 (8장)
 * - 갭상승: 7%/10%/15% (8장)
 * - 당일급등: 15%/20%/25% (8장)
 * - 점수 가중치: 현재값(2,1,3,2)과 피드백에서 제안된 대안(1,2,4,3) (7장 2.1 가중치 근거)
 * - 점수 임계값: 4점/5점 (2.1 병행기록)
 * - 손절: -2%/-3% (8장, ATR은 별도 미구현이라 그리드 제외)
 * - 시간청산 기준일: 5일/7일/10일 (8장)
 *
 * 전체 조합 수 = 3*3*3*2*2*2*3 = 648가지. 종목 10개 기준 6,480회 시뮬레이션이며,
 * 지표 계산이 아니라 조건 비교 연산이 대부분이라 종목당 1초 미만으로 충분히 처리 가능한 규모다.
 */
public final class BacktestParamGrid {

    private BacktestParamGrid() {}

    private static final List<BigDecimal> TRADING_VALUE_CANDIDATES = List.of(
            BigDecimal.valueOf(2_000_000_000L),
            BigDecimal.valueOf(3_000_000_000L), // 현재값
            BigDecimal.valueOf(5_000_000_000L)
    );

    private static final List<BigDecimal> GAP_UP_CANDIDATES = List.of(
            BigDecimal.valueOf(0.07),
            BigDecimal.valueOf(0.10), // 현재값
            BigDecimal.valueOf(0.15)
    );

    private static final List<BigDecimal> DAY_SURGE_CANDIDATES = List.of(
            BigDecimal.valueOf(0.15),
            BigDecimal.valueOf(0.20), // 현재값
            BigDecimal.valueOf(0.25)
    );

    /** [A, B, C, D] 가중치 조합. 현재값과 7장에서 언급된 대안 1개만 비교 (조합 폭증 방지) */
    private static final List<int[]> WEIGHT_CANDIDATES = List.of(
            new int[]{2, 1, 3, 2}, // 현재값 (v2.1)
            new int[]{1, 2, 4, 3}  // 2.1 가중치근거 문구에서 예시로 든 대안
    );

    private static final List<Integer> SCORE_THRESHOLD_CANDIDATES = List.of(4, 5);

    private static final List<BigDecimal> STOP_LOSS_CANDIDATES = List.of(
            BigDecimal.valueOf(-0.02), // 현재값
            BigDecimal.valueOf(-0.03)
    );

    private static final List<Integer> TIME_EXIT_DAYS_CANDIDATES = List.of(5, 7, 10);

    public static List<BacktestParams> generate() {
        List<BacktestParams> result = new ArrayList<>();
        for (BigDecimal tradingValue : TRADING_VALUE_CANDIDATES) {
            for (BigDecimal gapUp : GAP_UP_CANDIDATES) {
                for (BigDecimal daySurge : DAY_SURGE_CANDIDATES) {
                    for (int[] weights : WEIGHT_CANDIDATES) {
                        for (Integer scoreThreshold : SCORE_THRESHOLD_CANDIDATES) {
                            for (BigDecimal stopLoss : STOP_LOSS_CANDIDATES) {
                                for (Integer timeExitDays : TIME_EXIT_DAYS_CANDIDATES) {
                                    result.add(BacktestParams.builder()
                                            .minAvgTradingValueKrw(tradingValue)
                                            .gapUpInvalidateRate(gapUp)
                                            .daySurgeInvalidateRate(daySurge)
                                            .weightA(weights[0])
                                            .weightB(weights[1])
                                            .weightC(weights[2])
                                            .weightD(weights[3])
                                            .buyScoreThreshold(scoreThreshold)
                                            .stopLossRate(stopLoss)
                                            .timeExitHoldingDays(timeExitDays)
                                            .build());
                                }
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    /** v2.1 본문 기본값 그대로의 단일 파라미터 (베이스라인 비교용) */
    public static BacktestParams baseline() {
        return BacktestParams.builder().build();
    }

    /**
     * 그리드서치 결과로 도출된 최적 파라미터 조합.
     * 변경 근거: 샤프/손익비 기준 단변량 분석 + topBySharpe 1위 조합 교차 확인.
     * - 점수임계값 5→4: 샤프 +0.014, 손익비 +0.052 (명확한 개선)
     * - 청산기간 5→10일: 샤프 +0.009, 손익비 +0.034 (완만한 상승 종목 보호)
     * - 갭상승 필터 10→15%: 샤프 +0.003 (작지만 일관된 개선)
     * - 손절 -2% 유지: topBySharpe 1위 조합이 -2%를 선택, -3%는 거래수는 많지만 질이 낮음
     * - 가중치 2-1-3-2 유지: 대안(1-2-4-3) 샤프 -0.0496으로 명백히 나쁨
     */
    public static BacktestParams optimal() {
        return BacktestParams.builder()
                .buyScoreThreshold(4)
                .timeExitHoldingDays(10)
                .gapUpInvalidateRate(BigDecimal.valueOf(0.15))
                .build();
    }
}
