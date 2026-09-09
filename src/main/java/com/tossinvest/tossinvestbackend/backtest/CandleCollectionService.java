package com.tossinvest.tossinvestbackend.backtest;

import com.tossinvest.tossinvestbackend.marketdata.CandleResponse;
import com.tossinvest.tossinvestbackend.marketdata.MarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
 * 오늘 날짜에서 DAYS_PER_PAGE(300달력일)씩 거슬러 올라가는 날짜를
 * 직접 계산해 before에 "YYYY-MM-DD" 형식으로 전달한다.
 * 페이지 간 날짜 겹침으로 중복이 생길 수 있으나, timestamp 기반 중복 제거로 처리한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CandleCollectionService {

    private static final int PAGE_SIZE = 200;
    private static final int DAYS_PER_PAGE = 300; // 달력일 기준 (휴일/주말 포함해서 넉넉하게)
    private static final int MAX_PAGES = 15;       // 최대 15페이지 ≈ 12년치
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final MarketDataService marketDataService;
    private final CandleRepository candleRepository;

    /**
     * 종목의 일봉 캔들을 targetDays 거래일만큼 수집해 DB에 저장한다.
     * "이미 개수가 충분하면 스킵"이 아니라 "가장 최근 캔들이 오늘-1 기준 최근 영업일 것이면 스킵"으로 판단한다.
     * (개수 기준 스킵은 데이터가 특정 날짜에서 멈춰도 영원히 재수집이 안 되는 문제가 있었음)
     * 반환값: 신규 저장된 캔들 개수.
     */
    @Transactional
    public int collectDailyCandles(String symbol, int targetDays) {
        long existingCount = candleRepository.countBySymbol(symbol);
        LocalDate expectedLatest = expectedLatestTradingDate();
        List<CandleEntity> latest = candleRepository.findTop1BySymbolOrderByTimestampDesc(symbol);
        boolean isFresh = !latest.isEmpty() && !epochToKstDate(latest.get(0).getTimestamp()).isBefore(expectedLatest);

        if (isFresh && existingCount >= targetDays) {
            log.info("[캔들수집] {} 이미 최신({}) + {}개 보유 (목표 {}개) - 스킵",
                    symbol, epochToKstDate(latest.get(0).getTimestamp()), existingCount, targetDays);
            return 0;
        }

        Set<Long> existingTimestamps = new HashSet<>();
        candleRepository.findBySymbolOrderByTimestampAsc(symbol)
                .forEach(c -> existingTimestamps.add(c.getTimestamp()));

        List<CandleEntity> collected = new ArrayList<>();
        LocalDate pivotDate = LocalDate.now();

        for (int page = 0; page < MAX_PAGES; page++) {
            CandleResponse response;
            if (page == 0) {
                response = marketDataService.getCandles(symbol, "1d", PAGE_SIZE);
            } else {
                // "YYYY-MM-DDT09:00:00+09:00" 형식으로 생성 (한국 장 시작 시각 기준)
                String beforeDatetime = pivotDate.format(DateTimeFormatter.ISO_LOCAL_DATE) + "T09:00:00+09:00";
                log.debug("[캔들수집] {} 페이지 {}: before={}", symbol, page, beforeDatetime);
                response = marketDataService.getCandlesWithDateBefore(symbol, "1d", PAGE_SIZE, beforeDatetime);
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
            for (CandleResponse.Candle c : candles) {
                Long ts = parseTimestamp(c.getTimestamp());
                if (ts == null || existingTimestamps.contains(ts)) continue;
                existingTimestamps.add(ts);
                collected.add(toEntity(symbol, c, ts));
                addedThisPage++;
            }

            log.info("[캔들수집] {} 페이지 {}: {}개 반환, 신규 {}개, 누적 {}개",
                    symbol, page, candles.size(), addedThisPage, collected.size());

            if (existingTimestamps.size() >= targetDays) {
                log.info("[캔들수집] {} 목표 달성 ({}개), 종료", symbol, existingTimestamps.size());
                break;
            }

            pivotDate = pivotDate.minusDays(DAYS_PER_PAGE);
        }

        if (!collected.isEmpty()) {
            candleRepository.saveAll(collected);
        }

        trimToWindow(symbol, targetDays);

        log.info("[캔들수집] {} 완료: 신규 {}개 저장, 총 보유 {}개",
                symbol, collected.size(), existingCount + collected.size());
        return collected.size();
    }

    /** targetDays(영업일) 롤링 윈도우를 벗어난 과거 캔들을 정리해 항상 "최근 targetDays개"만 유지한다. */
    private void trimToWindow(String symbol, int targetDays) {
        List<CandleEntity> desc = candleRepository.findBySymbolOrderByTimestampDesc(symbol);
        if (desc.size() > targetDays) {
            List<CandleEntity> old = desc.subList(targetDays, desc.size());
            candleRepository.deleteAll(old);
            log.info("[캔들수집] {} 윈도우 초과분 {}개 정리 ({}개 → {}개)", symbol, old.size(), desc.size(), targetDays);
        }
    }

    /** epoch millis(토스 캔들 timestamp) → KST 기준 날짜 */
    private LocalDate epochToKstDate(Long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(KST).toLocalDate();
    }

    /**
     * "오늘-1"을 기준으로 가장 최근 영업일(주말 제외)을 계산한다.
     * 공휴일까지는 고려하지 않으므로 명절/임시공휴일 다음날엔 하루 정도 오차가 있을 수 있음.
     */
    private LocalDate expectedLatestTradingDate() {
        LocalDate d = LocalDate.now(KST).minusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    private CandleEntity toEntity(String symbol, CandleResponse.Candle c, Long ts) {
        return new CandleEntity(symbol, ts,
                safeDecimal(c.getOpenPrice()), safeDecimal(c.getHighPrice()),
                safeDecimal(c.getLowPrice()), safeDecimal(c.getClosePrice()),
                safeDecimal(c.getVolume()));
    }

    private BigDecimal safeDecimal(String raw) {
        return raw == null ? BigDecimal.ZERO : new BigDecimal(raw);
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
