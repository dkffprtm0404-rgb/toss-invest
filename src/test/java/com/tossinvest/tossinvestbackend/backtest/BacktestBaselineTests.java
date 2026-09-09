package com.tossinvest.tossinvestbackend.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BacktestBaselineTests {

    @Test
    void checkedInCandlesProduceRepeatableBaselineResults() throws Exception {
        Path csv = Path.of("scripts/candles.csv");
        byte[] contents = Files.readAllBytes(csv);
        Map<String, List<CandleEntity>> bySymbol = new LinkedHashMap<>();
        List<String> rows = new String(contents, StandardCharsets.UTF_8).lines().skip(1).toList();
        int nonPositivePrices = 0;
        int inconsistentPrices = 0;
        int zeroVolume = 0;
        int negativeVolume = 0;
        for (String row : rows) {
            if (row.isBlank()) continue;
            String[] values = row.split(",");
            assertThat(values).hasSize(7);
            CandleEntity candle = new CandleEntity(values[0], Long.parseLong(values[1]),
                    new BigDecimal(values[2]), new BigDecimal(values[3]), new BigDecimal(values[4]),
                    new BigDecimal(values[5]), new BigDecimal(values[6]));
            bySymbol.computeIfAbsent(candle.getSymbol(), key -> new ArrayList<>()).add(candle);
            if (candle.getOpenPrice().signum() <= 0 || candle.getHighPrice().signum() <= 0
                    || candle.getLowPrice().signum() <= 0 || candle.getClosePrice().signum() <= 0) {
                nonPositivePrices++;
            }
            if (candle.getHighPrice().compareTo(candle.getOpenPrice().max(candle.getClosePrice())) < 0
                    || candle.getLowPrice().compareTo(candle.getOpenPrice().min(candle.getClosePrice())) > 0) {
                inconsistentPrices++;
            }
            if (candle.getVolume().signum() == 0) zeroVolume++;
            if (candle.getVolume().signum() < 0) negativeVolume++;
        }

        BacktestEngine engine = new BacktestEngine();
        BacktestParams params = BacktestParams.builder().build();
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> summaries = new ArrayList<>();
        int totalTrades = 0;
        int sameBarReentries = 0;
        int duplicateTimestamps = 0;
        int outOfOrderTimestamps = 0;
        for (var entry : bySymbol.entrySet()) {
            List<CandleEntity> candles = entry.getValue();
            long distinct = candles.stream().map(CandleEntity::getTimestamp).distinct().count();
            duplicateTimestamps += candles.size() - (int) distinct;
            for (int i = 1; i < candles.size(); i++) {
                if (candles.get(i).getTimestamp() < candles.get(i - 1).getTimestamp()) outOfOrderTimestamps++;
            }
            BacktestResult first = engine.run(entry.getKey(), candles, params);
            BacktestResult second = engine.run(entry.getKey(), candles, params);
            assertThat(mapper.writeValueAsString(second)).isEqualTo(mapper.writeValueAsString(first));
            totalTrades += first.getTotalTrades();
            List<BacktestTrade> trades = first.getTrades();
            for (int i = 1; i < trades.size(); i++) {
                if (trades.get(i).entryIndex == trades.get(i - 1).exitIndex) sameBarReentries++;
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("symbol", entry.getKey());
            summary.put("candles", candles.size());
            summary.put("firstTimestamp", candles.get(0).getTimestamp());
            summary.put("lastTimestamp", candles.get(candles.size() - 1).getTimestamp());
            summary.put("result", first);
            summaries.add(summary);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("csvSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents)));
        report.put("symbols", bySymbol.size());
        report.put("candles", bySymbol.values().stream().mapToInt(List::size).sum());
        report.put("nonPositivePriceRows", nonPositivePrices);
        report.put("inconsistentOhlcRows", inconsistentPrices);
        report.put("zeroVolumeRows", zeroVolume);
        report.put("negativeVolumeRows", negativeVolume);
        report.put("duplicateTimestamps", duplicateTimestamps);
        report.put("outOfOrderTimestamps", outOfOrderTimestamps);
        report.put("totalTrades", totalTrades);
        report.put("sameBarReentries", sameBarReentries);
        report.put("results", summaries);
        Path output = Path.of("build/reports/engine-validation/baseline.json");
        Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);

        assertThat(bySymbol).isNotEmpty();
        assertThat(totalTrades).isPositive();
        assertThat(duplicateTimestamps).isZero();
        assertThat(outOfOrderTimestamps).isZero();
    }
}
