package com.tossinvest.tossinvestbackend.backtest;

import java.math.BigDecimal;

/**
 * 백테스트 시뮬레이션 중 발생한 단일 매수→매도 거래 1건.
 * entryIndex/exitIndex는 캔들 배열 내 인덱스(거래일 단위).
 */
public class BacktestTrade {
    public final int entryIndex;
    public final int exitIndex;
    public final BigDecimal entryPrice;
    public final BigDecimal exitPrice;
    public final BigDecimal returnRate;
    public final int holdingDays;
    public final String exitReason; // STOP_LOSS / TRAILING_STOP / TIME_EXIT / DEAD_CROSS / END_OF_DATA

    public BacktestTrade(int entryIndex, int exitIndex, BigDecimal entryPrice, BigDecimal exitPrice,
                          BigDecimal returnRate, int holdingDays, String exitReason) {
        this.entryIndex = entryIndex;
        this.exitIndex = exitIndex;
        this.entryPrice = entryPrice;
        this.exitPrice = exitPrice;
        this.returnRate = returnRate;
        this.holdingDays = holdingDays;
        this.exitReason = exitReason;
    }
}
