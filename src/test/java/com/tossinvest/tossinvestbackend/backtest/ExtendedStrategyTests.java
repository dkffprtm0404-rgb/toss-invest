package com.tossinvest.tossinvestbackend.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.strategy.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestRequest.ExecutionMode.*;
import static org.assertj.core.api.Assertions.*;

class ExtendedStrategyTests {
    private final StrategyJson json = new StrategyJson(new ObjectMapper());
    private final UserStrategyBacktestEngine engine = new UserStrategyBacktestEngine(new StrategyEvaluator());
    private static final String VOLUME = "{\"type\":\"VOLUME\",\"period\":1,\"multiplier\":1,\"comparison\":\"GTE\"}";
    private static final String RANGE = "{\"type\":\"RANGE_BREAKOUT\",\"period\":3,\"periodUnit\":\"BARS\",\"priceField\":\"HIGH\",\"comparison\":\"GT\"}";

    @Test void rangeUsesPreviousHighsExcludesCurrentBarAndExitsBelowPreviousLows() throws Exception {
        var candles = candles(10, 11, 12, 15, 9);
        candles.get(3).setHighPrice(bd(100));
        var result = run(RANGE, RANGE.replace("3", "2").replace("HIGH", "LOW").replace("GT", "LT"), null, candles, SAME_DAY_CLOSE);
        assertThat(result.trades()).hasSize(1);
        var trade = result.trades().get(0);
        assertThat(trade.entry().price()).isEqualByComparingTo("15");
        assertThat(trade.entry().evidence().get(0).referenceValue()).isEqualByComparingTo("12");
        assertThat(trade.exit().price()).isEqualByComparingTo("9");
        assertThat(trade.exit().evidence().get(0).referenceValue()).isEqualByComparingTo("12");
    }

    @Test void strictBreakoutDoesNotBuyEqualHighOrShortHistory() throws Exception {
        assertThat(run(RANGE, null, null, candles(10, 11, 12), SAME_DAY_CLOSE).status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(run(RANGE, null, null, candles(10, 11, 12, 12), SAME_DAY_CLOSE).status()).isEqualTo("NO_TRADES");
    }

    @Test void calendar52WeeksUsesKoreanDateWindowNot252Rows() throws Exception {
        var candles = candles(1000, 20, 25);
        candles.get(0).setTimestamp(time(LocalDate.of(2024, 1, 1)));
        candles.get(1).setTimestamp(time(LocalDate.of(2024, 1, 4)));
        candles.get(2).setTimestamp(time(LocalDate.of(2025, 1, 1)));
        var result = run(RANGE.replace("3", "52").replace("BARS", "CALENDAR_WEEKS"), null, null, candles, SAME_DAY_CLOSE);
        assertThat(result.openPosition().entry().price()).isEqualByComparingTo("25");
        assertThat(result.openPosition().entry().evidence().get(0).referenceValue()).isEqualByComparingTo("20");
        assertThat(run(RANGE.replace("3", "52").replace("BARS", "CALENDAR_WEEKS"), null, null,
                candles.subList(1, 3), SAME_DAY_CLOSE).status()).isEqualTo("INSUFFICIENT_DATA");
    }

    @Test void movingAverageStateAndPriceFilterNeedNoFreshCross() throws Exception {
        String ma = "{\"type\":\"MA_COMPARE\",\"averageType\":\"SMA\",\"shortPeriod\":2,\"longPeriod\":3,\"comparison\":\"GT\"}";
        String price = "{\"type\":\"PRICE_MA\",\"averageType\":\"SMA\",\"period\":3,\"comparison\":\"GT\"}";
        var result = run(ma + "," + price, null, null, candles(10, 11, 12), SAME_DAY_CLOSE);
        assertThat(result.openPosition().entry().price()).isEqualByComparingTo("12");
        assertThat(result.openPosition().entry().evidence()).hasSize(2);
        assertThat(result.openPosition().entry().evidence().get(0).actualValue()).isEqualByComparingTo("11.5");
        assertThat(result.openPosition().entry().evidence().get(0).referenceValue()).isEqualByComparingTo("11");
    }

    @Test void atrBreakoutUsesPreviousAtrIncludingGapsAndExcludesCurrentRange() throws Exception {
        var candles = candles(100, 110, 112, 117);
        candles.get(1).setHighPrice(bd(114)); candles.get(1).setLowPrice(bd(109)); // TR 14
        candles.get(2).setHighPrice(bd(113)); candles.get(2).setLowPrice(bd(109)); // TR 4, ATR 9
        candles.get(3).setHighPrice(bd(999)); candles.get(3).setLowPrice(bd(1));
        String entry = "{\"type\":\"ATR_BREAKOUT\",\"method\":\"SIMPLE\",\"period\":2,\"multiplier\":0.5,\"comparison\":\"GT\"}";
        var result = run(entry, null, null, candles, SAME_DAY_CLOSE);
        assertThat(result.openPosition().entry().price()).isEqualByComparingTo("117");
        assertThat(result.openPosition().entry().evidence().get(0).referenceValue()).isEqualByComparingTo("116.5");
    }

    @Test void wilderAtrSmoothsAfterInitialArithmeticSeed() throws Exception {
        var candles = candles(100, 110, 112, 115, 121);
        candles.get(1).setHighPrice(bd(114)); candles.get(1).setLowPrice(bd(109)); // TR 14
        candles.get(2).setHighPrice(bd(113)); candles.get(2).setLowPrice(bd(109)); // TR 4 -> seed 9
        candles.get(3).setHighPrice(bd(118)); candles.get(3).setLowPrice(bd(110)); // TR 8 -> Wilder 8.5
        String entry = "{\"type\":\"ATR_BREAKOUT\",\"method\":\"WILDER\",\"period\":2,\"multiplier\":0.5,\"comparison\":\"GT\"}";
        var result = run(entry, null, null, candles, SAME_DAY_CLOSE);
        assertThat(result.openPosition().entry().price()).isEqualByComparingTo("121");
        assertThat(result.openPosition().entry().evidence().get(0).referenceValue()).isEqualByComparingTo("119.25");
    }

    @Test void percentTrailingUsesSelectedHighAndIncludesEquality() throws Exception {
        var candles = candles(100, 100, 120, 104);
        candles.get(2).setHighPrice(bd(130));
        String risk = "{\"trailingStop\":{\"rate\":-0.20,\"peakBasis\":\"HIGH\"}}";
        var result = run(VOLUME, null, risk, candles, SAME_DAY_CLOSE);
        assertThat(result.trades()).hasSize(1);
        assertThat(result.trades().get(0).exit().reason()).isEqualTo("TRAILING_STOP");
        assertThat(result.trades().get(0).exit().evidence().get(0).referenceValue()).isEqualByComparingTo("104");
        assertThat(run(VOLUME, null, risk.replace("HIGH", "CLOSE"), candles, SAME_DAY_CLOSE).trades()).isEmpty();
    }

    @Test void closeEntryExcludesEntryBarsEarlierHighButOpenEntryIncludesItsHigh() throws Exception {
        var candles = candles(100, 100, 100);
        candles.get(1).setHighPrice(bd(200));
        String risk = "{\"trailingStop\":{\"rate\":-0.20,\"peakBasis\":\"HIGH\"}}";
        assertThat(run(VOLUME, null, risk, candles, SAME_DAY_CLOSE).trades()).isEmpty();
        candles.get(2).setHighPrice(bd(200));
        var next = run(VOLUME, null, risk, candles, NEXT_DAY_OPEN);
        assertThat(next.pendingOrder().action()).isEqualTo("SELL");
        assertThat(next.pendingOrder().evidence().get(0).referenceValue()).isEqualByComparingTo("160");
    }

    @Test void atrStopWaitsForHistoryAndFreezesAtrBeforeEntry() throws Exception {
        var candles = candles(100, 100, 100, 100, 94);
        for (int i = 1; i < 3; i++) { candles.get(i).setHighPrice(bd(102)); candles.get(i).setLowPrice(bd(98)); }
        candles.get(3).setHighPrice(bd(150)); candles.get(3).setLowPrice(bd(50));
        String risk = "{\"atrStop\":{\"method\":\"SIMPLE\",\"period\":2,\"multiplier\":1.5}}";
        var result = run(VOLUME, null, risk, candles, SAME_DAY_CLOSE);
        assertThat(result.trades()).hasSize(1);
        assertThat(result.trades().get(0).entry().executionBarTimestamp()).isEqualTo(candles.get(3).getTimestamp());
        assertThat(result.trades().get(0).exit().reason()).isEqualTo("ATR_STOP_LOSS");
        assertThat(result.trades().get(0).exit().evidence().get(0).referenceValue()).isEqualByComparingTo("94");
        assertThat(run(VOLUME, null, risk, candles.subList(0, 3), SAME_DAY_CLOSE).status()).isEqualTo("INSUFFICIENT_DATA");
    }

    @Test void fixedStopWinsWhenBothStopsTriggerAndFutureBarsDoNotRewriteTrade() throws Exception {
        var candles = candles(100, 100, 100, 100, 90);
        for (int i = 1; i < 3; i++) { candles.get(i).setHighPrice(bd(102)); candles.get(i).setLowPrice(bd(98)); }
        String risk = "{\"stopLoss\":{\"rate\":-0.05},\"atrStop\":{\"method\":\"SIMPLE\",\"period\":2,\"multiplier\":1.5}}";
        var result = run(VOLUME, null, risk, candles, SAME_DAY_CLOSE);
        assertThat(result.trades().get(0).exit().reason()).isEqualTo("STOP_LOSS");
        var extended = new ArrayList<>(candles);
        extended.add(new CandleEntity("005930", candles.get(4).getTimestamp() + 86400000, bd(999), bd(999), bd(999), bd(999), bd(100)));
        assertThat(run(VOLUME, null, risk, extended, SAME_DAY_CLOSE).trades().get(0)).isEqualTo(result.trades().get(0));
    }

    @Test void missingFieldsAndConflictingTrailingPoliciesAreRejectedWithoutDefaults() throws Exception {
        var strategy = definition(RANGE.replace("\"HIGH\"", "null"), null,
                "{\"trailing\":\"LEGACY_STEP_3_PERCENT\",\"trailingStop\":{\"rate\":-0.2},\"atrStop\":{\"period\":14}}");
        assertThat(new StrategyValidator().validate(strategy)).extracting(StrategyValidator.Issue::path)
                .contains("entry.conditions[0].priceField", "risk.trailingStop.peakBasis", "risk.trailingStop", "risk.atrStop.method", "risk.atrStop.multiplier");
    }

    @Test void versionOneCannotSilentlyUseNewConditionsButStillAcceptsOldDefinitions() throws Exception {
        var v2 = definition(RANGE, null, null);
        var v1 = new StrategyDefinition(1, v2.name(), null, v2.entry(), null, null);
        assertThat(new StrategyValidator().validate(v1)).extracting(StrategyValidator.Issue::path).contains("schemaVersion");
        var old = definition(VOLUME, null, "{\"trailing\":\"LEGACY_STEP_3_PERCENT\"}");
        assertThat(new StrategyValidator().validate(new StrategyDefinition(1, old.name(), null, old.entry(), null, old.risk()))).isEmpty();
    }

    @Test void nextOpenCanSignalWhenAtrIsReadyAndUsesSignalDaysAtrAtActualEntry() throws Exception {
        var candles = candles(100, 100, 100, 94, 90);
        candles.get(1).setHighPrice(bd(102)); candles.get(1).setLowPrice(bd(98));
        candles.get(2).setHighPrice(bd(102)); candles.get(2).setLowPrice(bd(98));
        candles.get(3).setOpenPrice(bd(100)); candles.get(3).setHighPrice(bd(200));
        String risk = "{\"atrStop\":{\"method\":\"SIMPLE\",\"period\":2,\"multiplier\":1.5}}";
        var result = run(VOLUME, null, risk, candles, NEXT_DAY_OPEN);
        var trade = result.trades().get(0);
        assertThat(trade.entry().signalBarTimestamp()).isEqualTo(candles.get(2).getTimestamp());
        assertThat(trade.entry().executionBarTimestamp()).isEqualTo(candles.get(3).getTimestamp());
        assertThat(trade.exit().price()).isEqualByComparingTo("90");
        assertThat(trade.exit().evidence().get(0).referenceValue()).isEqualByComparingTo("94");
    }

    @Test void impossibleAtrStopCannotOpenACompletelyUnprotectedPosition() throws Exception {
        var candles = candles(100, 100, 100, 100);
        candles.get(1).setHighPrice(bd(200)); candles.get(1).setLowPrice(bd(1));
        candles.get(2).setHighPrice(bd(200)); candles.get(2).setLowPrice(bd(1));
        assertThatThrownBy(() -> run(VOLUME, null, "{\"atrStop\":{\"method\":\"WILDER\",\"period\":2,\"multiplier\":1.5}}", candles, SAME_DAY_CLOSE))
                .isInstanceOf(UserStrategyBacktestEngine.DataException.class).hasMessageContaining("0 이하");
    }

    @Test void highBreakoutCrossWaitsForPreviousReferenceAndDoesNotTreatStateAsFreshCross() throws Exception {
        var candles = candles(10, 11, 12, 11, 14);
        String cross = RANGE.replace("\"GT\"", "\"CROSS_ABOVE\"");
        var result = run(cross, null, null, candles, SAME_DAY_CLOSE);
        assertThat(result.openPosition().entry().executionBarTimestamp()).isEqualTo(candles.get(4).getTimestamp());
        var evidence = result.openPosition().entry().evidence().get(0);
        assertThat(evidence.previousActualValue()).isEqualByComparingTo("11");
        assertThat(evidence.previousReferenceValue()).isEqualByComparingTo("12");
        assertThat(evidence.referenceValue()).isEqualByComparingTo("12");
    }

    private StrategyDefinition definition(String entry, String exit, String risk) throws Exception {
        return json.read("{\"schemaVersion\":2,\"name\":\"국내주식 조건 테스트\",\"entry\":{\"operator\":\"AND\",\"conditions\":[" + entry
                + "]},\"exit\":" + (exit == null ? "null" : "{\"conditions\":[" + exit + "]}") + ",\"risk\":" + risk + "}", StrategyDefinition.class);
    }

    private UserStrategyBacktestResult run(String entry, String exit, String risk, List<CandleEntity> candles,
                                           UserStrategyBacktestRequest.ExecutionMode mode) throws Exception {
        return engine.run(new UserStrategyBacktestRequest(definition(entry, exit, risk), "005930", LocalDate.of(2024, 1, 1), LocalDate.of(2026, 1, 1), mode), candles);
    }

    private List<CandleEntity> candles(int... closes) {
        List<CandleEntity> result = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) result.add(new CandleEntity("005930", time(LocalDate.of(2025, 1, 1).plusDays(i)),
                bd(closes[i]), bd(closes[i]), bd(closes[i]), bd(closes[i]), bd(100)));
        return result;
    }
    private static long time(LocalDate date) { return date.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli(); }
    private static BigDecimal bd(int value) { return BigDecimal.valueOf(value); }
}
