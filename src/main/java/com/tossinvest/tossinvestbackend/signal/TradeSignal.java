package com.tossinvest.tossinvestbackend.signal;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 단일 종목에 대한 매매 신호 판단 결과.
 * .docs/trading-rules.md (v2.0) 의 점수제 규칙을 기반으로 계산된다.
 */
@Getter
@Builder
public class TradeSignal {

    private String symbol;
    private SignalType signalType;
    private BigDecimal currentPrice;
    private BigDecimal ema5;
    private BigDecimal ema20;
    private BigDecimal rsi7;
    private BigDecimal recentHigh5;
    private BigDecimal recentLow5;
    private Boolean volumeSurge;
    private Integer score;
    private Integer scoreThreshold;
    private BigDecimal trailingStopPrice;
    private List<String> matchedConditions;
    private List<String> excludedReasons;
    private String summary;
}
