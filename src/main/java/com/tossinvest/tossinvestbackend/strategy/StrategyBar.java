package com.tossinvest.tossinvestbackend.strategy;

import java.math.BigDecimal;

/** A completed daily bar. Timestamp identifies the source bar, not an intraday fill time. */
public record StrategyBar(long timestamp, BigDecimal open, BigDecimal close, BigDecimal volume) { }
