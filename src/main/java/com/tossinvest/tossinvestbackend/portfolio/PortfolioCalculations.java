package com.tossinvest.tossinvestbackend.portfolio;

import com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestEngine;
import java.math.*;

/** Shared integer sizing and execution costs for the v3 and v4 account engines. */
public final class PortfolioCalculations {
    private PortfolioCalculations() { }
    public static BigDecimal executionPrice(BigDecimal price,BigDecimal slippage,boolean buy) {
        return price.multiply(buy?BigDecimal.ONE.add(slippage):BigDecimal.ONE.subtract(slippage),MathContext.DECIMAL128);
    }
    public static BigDecimal buyUnit(BigDecimal price,BigDecimal slippage,BigDecimal commission) {
        return executionPrice(price,slippage,true).multiply(BigDecimal.ONE.add(commission),MathContext.DECIMAL128);
    }
    public static long shares(BigDecimal budget,BigDecimal price) {
        try{return budget.divide(price,0,RoundingMode.DOWN).longValueExact();}
        catch(ArithmeticException e){throw new UserStrategyBacktestEngine.DataException(null,"계산 수량이 허용 범위를 초과합니다.");}
    }
}
