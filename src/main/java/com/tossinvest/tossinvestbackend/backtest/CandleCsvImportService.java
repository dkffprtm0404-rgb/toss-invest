package com.tossinvest.tossinvestbackend.backtest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.FileReader;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * scripts/fetch_candles.py가 생성한 CSV를 읽어 backtest_candle 테이블에 적재한다.
 * 토스 API의 before 파라미터 문제 우회용: pykrx(KRX 공식 데이터)로 2~3년치 수집.
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

        // 기존 timestamp 캐시로 중복 방지
        Set<String> existing = new HashSet<>();
        candleRepository.findAll().forEach(c ->
                existing.add(c.getSymbol() + ":" + c.getTimestamp()));

        List<CandleEntity> toSave = new ArrayList<>();
        int skipped = 0;
        int total = 0;

        try (BufferedReader br = new BufferedReader(new FileReader(path.toFile()))) {
            String line = br.readLine(); // 헤더 스킵
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                total++;

                String[] cols = line.split(",");
                if (cols.length < 7) { skipped++; continue; }

                String symbol    = cols[0].trim();
                Long   timestamp = Long.parseLong(cols[1].trim());
                BigDecimal open  = new BigDecimal(cols[2].trim());
                BigDecimal high  = new BigDecimal(cols[3].trim());
                BigDecimal low   = new BigDecimal(cols[4].trim());
                BigDecimal close = new BigDecimal(cols[5].trim());
                BigDecimal vol   = new BigDecimal(cols[6].trim());

                String key = symbol + ":" + timestamp;
                if (existing.contains(key)) { skipped++; continue; }

                existing.add(key);
                toSave.add(new CandleEntity(symbol, timestamp, open, high, low, close, vol));
            }
        } catch (Exception e) {
            throw new RuntimeException("CSV 읽기 실패: " + e.getMessage(), e);
        }

        if (!toSave.isEmpty()) {
            candleRepository.saveAll(toSave);
        }

        log.info("[CSV적재] 완료: 전체 {}행, 신규 {}개 저장, {}개 중복스킵", total, toSave.size(), skipped);
        return new ImportResult(total, toSave.size(), skipped);
    }

    public record ImportResult(int total, int saved, int skipped) {}
}
