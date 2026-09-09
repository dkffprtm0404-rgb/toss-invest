package com.tossinvest.tossinvestbackend.signal;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TechnicalIndicatorCalculatorTests {

    @Test
    void emaStartsWithSmaAndThenUsesExponentialWeighting() {
        var ema = TechnicalIndicatorCalculator.emaSeries(prices("10", "20", "30", "40"), 3);

        assertThat(ema.subList(0, 2)).containsOnlyNulls();
        assertThat(ema.get(2)).isEqualByComparingTo("20");
        assertThat(ema.get(3)).isEqualByComparingTo("30");
    }

    @Test
    void insufficientRsiDataReturnsNull() {
        assertThat(TechnicalIndicatorCalculator.rsi(prices("10", "11", "12"), 3)).isNull();
    }

    @Test
    void rsiUsesOnlyRecentWindowRatherThanWilderSmoothing() {
        var full = prices("10", "20", "10", "11", "12", "11");
        var recent = prices("10", "11", "12", "11");

        assertThat(TechnicalIndicatorCalculator.rsi(full, 3)).isEqualByComparingTo("66.67");
        assertThat(TechnicalIndicatorCalculator.rsi(full, 3))
                .isEqualByComparingTo(TechnicalIndicatorCalculator.rsi(recent, 3));
    }

    @Test
    void flatPricesCurrentlyReturnRsiOneHundred() {
        assertThat(TechnicalIndicatorCalculator.rsi(prices("10", "10", "10", "10"), 3))
                .isEqualByComparingTo("100");
    }

    private List<BigDecimal> prices(String... values) {
        return Stream.of(values).map(BigDecimal::new).toList();
    }
}
