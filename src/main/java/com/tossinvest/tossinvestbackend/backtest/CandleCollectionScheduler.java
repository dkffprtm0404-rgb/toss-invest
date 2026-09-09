package com.tossinvest.tossinvestbackend.backtest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 백테스트용 캔들 데이터를 매일 자동 갱신하는 스케줄러.
 * 항상 "오늘-1 기준 최근 ROLLING_WINDOW_DAYS 영업일" 데이터를 유지한다.
 * (CandleCollectionService의 개수 기준 스킵 문제로 특정 날짜에서 멈추던 것을 방지)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CandleCollectionScheduler {

    /** 롤링 윈도우 크기 (영업일 기준). 그리드서치/백테스트 최소 요구치(MIN_CANDLES=100)보다 넉넉하게 유지. */
    private static final int ROLLING_WINDOW_DAYS = 200;

    private final CandleCollectionService collectionService;
    private final BacktestUniverse universe;

    /**
     * 정규장 + NXT 마감(20:00) 이후, KRX 정산 데이터 반영 시간을 감안해 21:00에 실행.
     * 평일에만 실행 (주말은 신규 거래일 없음).
     */
    @Scheduled(cron = "0 0 21 * * MON-FRI", zone = "Asia/Seoul")
    public void refreshDailyCandles() {
        List<String> symbols = universe.all();
        log.info("[캔들 자동갱신] 시작: 대상 {}종목, 윈도우 {}영업일", symbols.size(), ROLLING_WINDOW_DAYS);
        int totalNew = 0;
        for (String symbol : symbols) {
            try {
                totalNew += collectionService.collectDailyCandles(symbol, ROLLING_WINDOW_DAYS);
            } catch (Exception e) {
                log.error("[캔들 자동갱신] {} 실패: {}", symbol, e.getMessage(), e);
            }
        }
        log.info("[캔들 자동갱신] 완료: 신규 캔들 {}개", totalNew);
    }

    /** 수동 트리거용 (컨트롤러 등에서 호출). 종목 하나 실패해도 나머지는 계속 진행된다. */
    public int runManual() {
        List<String> symbols = universe.all();
        int totalNew = 0;
        for (String symbol : symbols) {
            try {
                totalNew += collectionService.collectDailyCandles(symbol, ROLLING_WINDOW_DAYS);
            } catch (Exception e) {
                log.error("[캔들 수동갱신] {} 실패: {}", symbol, e.getMessage(), e);
            }
        }
        return totalNew;
    }
}
