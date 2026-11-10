package com.tossinvest.tossinvestbackend.backtest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * scripts/fetch_candles.py가 생성한 CSV를 읽어 backtest_candle 테이블에 적재한다.
 * 토스 API의 과거 페이지 제한을 우회해 FinanceDataReader로 수집한 이력을 병합한다.
 * API 수집과 동일하게 한국 날짜 기준 최대 달력 3년의 유효한 확정 일봉만 보관한다.
 * CSV 형식: symbol,timestamp,open_price,high_price,low_price,close_price,volume
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CandleCsvImportService {

    private final CandleRepository candleRepository;

    @Transactional
    public ImportResult importFromCsv(String csvPath) {
        Path path = Paths.get(csvPath);
        if (!path.toFile().exists()) {
            throw new IllegalArgumentException("CSV 파일을 찾을 수 없음: " + csvPath);
        }

        var now = CandleDataPolicy.now();
        var cutoff = CandleDataPolicy.oldestDate(now);
        var latestCompleted = CandleDataPolicy.latestCompletedWeekday(now);
        long cutoffTimestamp = CandleDataPolicy.startOfDay(cutoff);
        // 기존 timestamp 캐시로 중복 방지. 만료된 데이터는 아래에서 같은 트랜잭션으로 정리한다.
        Set<String> existing = new HashSet<>();
        candleRepository.findAll().stream().filter(c -> c.getTimestamp() >= cutoffTimestamp).forEach(c ->
                existing.add(c.getSymbol() + ":" + c.getTimestamp()));

        List<CandleEntity> toSave = new ArrayList<>();
        int duplicates = 0;
        int invalid = 0;
        int outsideRange = 0;
        int total = 0;

        try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line = br.readLine();
            if (line == null || !line.replace("\uFEFF", "").trim()
                    .equals("symbol,timestamp,open_price,high_price,low_price,close_price,volume")) {
                throw new IllegalArgumentException("CSV 헤더가 symbol,timestamp,open_price,high_price,low_price,close_price,volume 형식이어야 합니다.");
            }
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                total++;

                String[] cols = line.split(",", -1);
                if (cols.length != 7) { invalid++; continue; }
                try {
                    String symbol = cols[0].trim();
                    long timestamp = Long.parseLong(cols[1].trim());
                    var date = CandleDataPolicy.date(timestamp);
                    if (date.isBefore(cutoff) || date.isAfter(latestCompleted)) { outsideRange++; continue; }
                    var candle = new CandleEntity(symbol, timestamp,
                            new BigDecimal(cols[2].trim()), new BigDecimal(cols[3].trim()),
                            new BigDecimal(cols[4].trim()), new BigDecimal(cols[5].trim()), new BigDecimal(cols[6].trim()));
                    if (!CandleDataPolicy.valid(candle)) { invalid++; continue; }
                    String key = symbol + ":" + timestamp;
                    if (!existing.add(key)) { duplicates++; continue; }
                    toSave.add(candle);
                } catch (NumberFormatException | DateTimeException e) {
                    invalid++;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("CSV 읽기 실패: " + e.getMessage(), e);
        }

        long expired = candleRepository.deleteByTimestampLessThan(cutoffTimestamp);
        if (!toSave.isEmpty()) {
            candleRepository.saveAll(toSave);
        }

        int skipped = duplicates + invalid + outsideRange;
        log.info("[CSV적재] 완료: 전체 {}행, 신규 {}개 저장, 제외 {}개(중복 {}, 잘못된 OHLCV/형식 {}, 보관기간 밖/미확정 {}), 기존 만료 {}개 정리",
                total, toSave.size(), skipped, duplicates, invalid, outsideRange, expired);
        return new ImportResult(total, toSave.size(), skipped);
    }

    public record ImportResult(int total, int saved, int skipped) {}
}
