package com.tossinvest.tossinvestbackend.backtest;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * trading-rules.md(v2.1) 8장에 "검증 전 추정값"으로 명시된 파라미터들을 그리드서치 가능한 형태로 표현한다.
 * 각 필드의 기본값은 v2.1 본문 값과 동일하다. 그리드서치 시 이 값들을 조합해 BacktestParamGrid가 생성한다.
 */
@Getter
@Builder
public class BacktestParams {

    /** 1.4 거래대금 필터 임계값 (KRW). v2.1 기본값: 30억 */
    @Builder.Default
    private BigDecimal minAvgTradingValueKrw = BigDecimal.valueOf(3_000_000_000L);

    /** 1.5 갭상승 필터 임계값. v2.1 기본값: 0.10 (10%) */
    @Builder.Default
    private BigDecimal gapUpInvalidateRate = BigDecimal.valueOf(0.10);

    /** 1.9 당일급등 필터 임계값. v2.1 기본값: 0.20 (20%) */
    @Builder.Default
    private BigDecimal daySurgeInvalidateRate = BigDecimal.valueOf(0.20);

    /** 2.1 점수 가중치: A(골든크로스). v2.1 기본값: 2 */
    @Builder.Default
    private int weightA = 2;

    /** 2.1 점수 가중치: B(RSI 과매도 반등). v2.1 기본값: 1 */
    @Builder.Default
    private int weightB = 1;

    /** 2.1 점수 가중치: C(거래량 동반 돌파). v2.1 기본값: 3 */
    @Builder.Default
    private int weightC = 3;

    /** 2.1 점수 가중치: D(변동성 돌파). v2.1 기본값: 2 */
    @Builder.Default
    private int weightD = 2;

    /** 2.1 매수 점수 임계값. v2.1 기본값: 5 */
    @Builder.Default
    private int buyScoreThreshold = 5;

    /** 3.1 손절 임계값 (음수). v2.1 기본값: -0.02 (-2%) */
    @Builder.Default
    private BigDecimal stopLossRate = BigDecimal.valueOf(-0.02);

    /** 3.4 시간 기반 강제매도 기준일수. v2.1 기본값: 5 */
    @Builder.Default
    private int timeExitHoldingDays = 5;

    /** 3.4 트레일링 시작 임계값 완화 기준일수 (이 일수 초과 시 트레일링 시작 임계값을 낮춤). v2.1 기본값: 3 */
    @Builder.Default
    private int trailingRelaxAfterDays = 3;

    /** 3.4 기본 트레일링 시작 임계값. v2.1 기본값: 0.03 (3%) */
    @Builder.Default
    private BigDecimal trailingStartThreshold = BigDecimal.valueOf(0.03);

    /** 3.4 완화된 트레일링 시작 임계값 (trailingRelaxAfterDays 초과 시 적용). v2.1 기본값: 0.015 (1.5%) */
    @Builder.Default
    private BigDecimal trailingRelaxedThreshold = BigDecimal.valueOf(0.015);

    /** 사람이 읽을 파라미터 조합 라벨 (리포트용) */
    public String label() {
        return String.format(
                "거래대금%.0f억_갭상승%.0f%%_급등%.0f%%_가중치%d-%d-%d-%d_임계%d_손절%.1f%%_청산%d일_트레일%.1f%%",
                minAvgTradingValueKrw.divide(BigDecimal.valueOf(100_000_000L)).doubleValue(),
                gapUpInvalidateRate.doubleValue() * 100,
                daySurgeInvalidateRate.doubleValue() * 100,
                weightA, weightB, weightC, weightD,
                buyScoreThreshold,
                stopLossRate.doubleValue() * 100,
                timeExitHoldingDays,
                trailingStartThreshold.doubleValue() * 100
        );
    }
}
