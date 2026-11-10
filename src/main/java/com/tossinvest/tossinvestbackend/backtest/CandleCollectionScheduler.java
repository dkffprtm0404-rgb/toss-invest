package com.tossinvest.tossinvestbackend.backtest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 백테스트용 캔들 데이터를 매일 자동 갱신하는 스케줄러.
 * 한국 날짜 기준 최대 달력 3년의 이력을 보관하며 최신 확정 일봉을 병합한다.
 * 공급자가 과거 페이지를 제공하지 않는 경우 실제 저장 범위는 더 짧을 수 있다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CandleCollectionScheduler {

    /** 거래일 수보다 큰 목표를 주어 3년 날짜 경계 또는 공급자의 마지막 페이지까지 수집한다. */
    private static final int HISTORY_TARGET_DAYS = CandleDataPolicy.RETENTION_YEARS * 366;

    private final CandleCollectionService collectionService;
    private final BacktestUniverse universe;

    /**
     * 정규장 + NXT 마감(20:00) 이후, KRX 정산 데이터 반영 시간을 감안해 21:00에 실행.
     * 평일에만 실행 (주말은 신규 거래일 없음).
     */
    @Scheduled(cron = "0 0 21 * * MON-FRI", zone = "Asia/Seoul")
    public void refreshDailyCandles() {
        collectionService.pruneExpiredCandles();
        List<String> symbols = universe.all();
        log.info("[캔들 자동갱신] 시작: 대상 {}종목, 최대 달력 {}년 보관", symbols.size(), CandleDataPolicy.RETENTION_YEARS);
        int totalNew = 0;
        for (String symbol : symbols) {
            try {
                totalNew += collectionService.collectDailyCandles(symbol, HISTORY_TARGET_DAYS);
            } catch (Exception e) {
                log.error("[캔들 자동갱신] {} 실패: {}", symbol, e.getMessage(), e);
            }
        }
        log.info("[캔들 자동갱신] 완료: 신규 캔들 {}개", totalNew);
    }

    /** 수동 트리거용 (컨트롤러 등에서 호출). 종목 하나 실패해도 나머지는 계속 진행된다. */
    public int runManual() {
        collectionService.pruneExpiredCandles();
        List<String> symbols = universe.all();
        int totalNew = 0;
        for (String symbol : symbols) {
            try {
                totalNew += collectionService.collectDailyCandles(symbol, HISTORY_TARGET_DAYS);
            } catch (Exception e) {
                log.error("[캔들 수동갱신] {} 실패: {}", symbol, e.getMessage(), e);
            }
        }
        return totalNew;
    }
}
