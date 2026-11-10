package com.tossinvest.tossinvestbackend.backtest;

import com.tossinvest.tossinvestbackend.marketdata.CandleResponse;
import com.tossinvest.tossinvestbackend.marketdata.MarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 백테스트용 일봉 캔들을 토스증권 API에서 수집해 DB에 영구 저장한다.
 *
 * [페이지네이션 전략]
 * nextBefore 재사용 시 API가 typeMismatch 400을 반환하는 문제가 있어,
 * 응답의 실제 최저 날짜를 before로 전달한다. 중복·빈 페이지에서는 종료하며,
 * 공급자가 과거 페이지를 400으로 거부하면 성공한 최신 페이지는 보존한다.
 * 보관은 봉 개수가 아닌 한국 날짜 기준 최대 달력 3년이다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CandleCollectionService {

    private static final int PAGE_SIZE = 200;
    private static final int MAX_PAGES = 15;

    private final MarketDataService marketDataService;
    private final CandleRepository candleRepository;

    /** Also covers delisted, excluded and thin-history symbols outside the active universe. */
    @Transactional
    public long pruneExpiredCandles() {
        return candleRepository.deleteByTimestampLessThan(CandleDataPolicy.startOfDay(
                CandleDataPolicy.oldestDate(CandleDataPolicy.now())));
    }

    /**
     * targetDays는 수집 목표이며 기존 이력을 삭제하는 개수 제한이 아니다.
     * 최신 확정 일봉과 목표 수량 또는 3년 시작 경계까지의 이력이 있으면 재요청을 생략한다.
     * 주말만 계산하므로 공휴일·거래정지일의 데이터 제공 여부를 보장하지 않는다.
     * 반환값: 신규 저장된 캔들 개수.
     */
    @Transactional
    public int collectDailyCandles(String symbol, int targetDays) {
        if (targetDays < 1) throw new IllegalArgumentException("수집 목표는 1봉 이상이어야 합니다.");
        var now = CandleDataPolicy.now();
        LocalDate cutoff = CandleDataPolicy.oldestDate(now);
        LocalDate expectedLatest = CandleDataPolicy.latestCompletedWeekday(now);
        candleRepository.deleteBySymbolAndTimestampLessThan(symbol, CandleDataPolicy.startOfDay(cutoff));
        List<CandleEntity> existing = candleRepository.findBySymbolOrderByTimestampAsc(symbol);
        List<CandleEntity> completed = existing.stream()
                .filter(c -> !CandleDataPolicy.date(c.getTimestamp()).isAfter(expectedLatest)).toList();
        LocalDate oldestKnown = completed.isEmpty() ? null : CandleDataPolicy.date(completed.get(0).getTimestamp());
        boolean hasRangeBeginning = oldestKnown != null
                && !oldestKnown.isAfter(CandleDataPolicy.firstWeekdayOnOrAfter(cutoff));
        boolean isFresh = !completed.isEmpty()
                && !CandleDataPolicy.date(completed.get(completed.size() - 1).getTimestamp()).isBefore(expectedLatest);

        if (isFresh && (completed.size() >= targetDays || hasRangeBeginning)) {
            log.info("[캔들수집] {} 최신 확정 일봉({}) + {}개 보유 - 스킵", symbol, expectedLatest, completed.size());
            return 0;
        }

        Set<Long> existingTimestamps = new HashSet<>();
        existing.forEach(c -> existingTimestamps.add(c.getTimestamp()));

        List<CandleEntity> collected = new ArrayList<>();
        LocalDate previousOldest = null;
        String beforeDatetime = null;

        for (int page = 0; page < MAX_PAGES; page++) {
            CandleResponse response;
            try {
                response = page == 0 ? marketDataService.getCandles(symbol, "1d", PAGE_SIZE)
                        : marketDataService.getCandlesWithDateBefore(symbol, "1d", PAGE_SIZE, beforeDatetime);
            } catch (WebClientResponseException e) {
                if (page == 0 || e.getStatusCode().value() != 400) throw e;
                log.warn("[캔들수집] {} 과거 페이지 요청 거부(400): 수신한 {}개만 병합, 3년 전체 이력 제공은 미확인", symbol, collected.size());
                break;
            }

            List<CandleResponse.Candle> candles = response != null && response.getResult() != null
                    && response.getResult().getCandles() != null
                    ? response.getResult().getCandles()
                    : List.of();

            if (candles.isEmpty()) {
                log.info("[캔들수집] {} 페이지 {} - 더 이상 데이터 없음", symbol, page);
                break;
            }

            int addedThisPage = 0;
            LocalDate pageOldest = null;
            for (CandleResponse.Candle c : candles) {
                if (c == null) continue;
                Long ts = parseTimestamp(c.getTimestamp());
                if (ts == null) continue;
                LocalDate date = CandleDataPolicy.date(ts);
                if (pageOldest == null || date.isBefore(pageOldest)) pageOldest = date;
                if (date.isBefore(cutoff) || date.isAfter(expectedLatest) || existingTimestamps.contains(ts)) continue;
                CandleEntity entity;
                try {
                    entity = toEntity(symbol, c, ts);
                    if (!CandleDataPolicy.valid(entity)) continue;
                } catch (NumberFormatException e) {
                    log.warn("[캔들수집] {} {} 잘못된 OHLCV 제외", symbol, date);
                    continue;
                }
                existingTimestamps.add(ts);
                collected.add(entity);
                if (oldestKnown == null || date.isBefore(oldestKnown)) oldestKnown = date;
                addedThisPage++;
            }

            log.info("[캔들수집] {} 페이지 {}: {}개 반환, 신규 {}개, 누적 {}개",
                    symbol, page, candles.size(), addedThisPage, collected.size());

            if (pageOldest == null || previousOldest != null && !pageOldest.isBefore(previousOldest)) {
                log.warn("[캔들수집] {} 과거 방향으로 진행하지 않는 페이지, 종료", symbol);
                break;
            }
            if (!pageOldest.isAfter(cutoff) || completed.size() + collected.size() >= targetDays
                    || oldestKnown != null && !oldestKnown.isAfter(CandleDataPolicy.firstWeekdayOnOrAfter(cutoff))) break;
            previousOldest = pageOldest;
            beforeDatetime = pageOldest.format(DateTimeFormatter.ISO_LOCAL_DATE) + "T00:00:00+09:00";
        }

        if (!collected.isEmpty()) {
            candleRepository.saveAll(collected);
        }

        log.info("[캔들수집] {} 완료: 신규 {}개 저장, 총 보유 {}개",
                symbol, collected.size(), existing.size() + collected.size());
        return collected.size();
    }

    private CandleEntity toEntity(String symbol, CandleResponse.Candle c, Long ts) {
        return new CandleEntity(symbol, ts,
                decimal(c.getOpenPrice()), decimal(c.getHighPrice()),
                decimal(c.getLowPrice()), decimal(c.getClosePrice()), decimal(c.getVolume()));
    }

    private BigDecimal decimal(String raw) {
        return raw == null ? null : new BigDecimal(raw.trim());
    }

    /**
     * 토스 캔들 timestamp = ISO-8601 오프셋 문자열("2026-06-18T00:00:00.000+09:00")
     * epoch millis로 변환해 DB 정렬 키로 사용한다.
     */
    private Long parseTimestamp(String raw) {
        if (raw == null) return null;
        try {
            return OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .toInstant().toEpochMilli();
        } catch (Exception e) {
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException ex) {
                log.warn("[캔들수집] timestamp 파싱 실패: {}", raw);
                return null;
            }
        }
    }
}
