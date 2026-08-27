package com.tossinvest.tossinvestbackend.paper;

import com.tossinvest.tossinvestbackend.backtest.BacktestUniverse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaperTradingScheduler {

    private final PaperTradingService paperTradingService;
    private final PaperRunLogRepository logRepo;
    private final BacktestUniverse universe;

    // 실행 실패 수정: 15:35엔 거래 자체가 불가능(정규장 종료~NXT 시작 사이 공백)하므로
    // 15:40(NXT 개시)으로 옮겨 신호 계산과 실제 체결 가능 시점을 일치시킨다.
    @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Seoul")
    public void runDaily() {
        log.info("[페이퍼 스케줄러] {} 15:40 자동 실행 시작", LocalDate.now());
        saveAndRun("SCHEDULER");
    }

    /**
     * 정규장 중 5분 간격 보유 포지션 모니터링 (09:00~15:20).
     * 손절/트레일링 스탑 조건 충족 시 즉시 가상 매도. 신호 없어도 로그를 남긴다(동작여부 확인용).
     */
    @Scheduled(cron = "0 0/5 9-15 * * MON-FRI", zone = "Asia/Seoul")
    public void monitorIntraday() {
        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(9, 0)) || now.isAfter(LocalTime.of(15, 20))) return;
        List<String> sold = paperTradingService.monitorOpenPositions();
        saveMonitorLog("INTRADAY_MONITOR", sold);
        if (!sold.isEmpty()) log.info("[페이퍼 정규장 모니터] 매도: {}", sold);
    }

    /**
     * NXT장 10분 간격 보유 포지션 모니터링 (15:40~20:00).
     * 거래 불가 구간(15:30~15:39) 제외.
     */
    @Scheduled(cron = "0 0/10 15-20 * * MON-FRI", zone = "Asia/Seoul")
    public void monitorNxt() {
        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(15, 40)) || now.isAfter(LocalTime.of(20, 0))) return;
        List<String> sold = paperTradingService.monitorOpenPositions();
        saveMonitorLog("NXT_MONITOR", sold);
        if (!sold.isEmpty()) log.info("[페이퍼 NXT 모니터] 매도: {}", sold);
    }

    private void saveMonitorLog(String trigger, List<String> sold) {
        logRepo.save(PaperRunLog.builder()
                .runDate(LocalDate.now())
                .runAt(LocalDateTime.now())
                .universeSizez(0) // 모니터링은 보유종목만 대상이라 전체 유니버스 크기는 의미 없음
                .boughtCount(0)
                .soldCount(sold.size())
                .heldCount(0)
                .boughtSymbols("")
                .soldSymbols(String.join(",", sold))
                .trigger(trigger)
                .build());
    }

    public PaperTradingService.DailyRunResult runManual() {
        return saveAndRun("MANUAL");
    }

    private PaperTradingService.DailyRunResult saveAndRun(String trigger) {
        try {
            PaperTradingService.DailyRunResult result = paperTradingService.runDaily(universe.all());
            logRepo.save(PaperRunLog.builder()
                    .runDate(LocalDate.now())
                    .runAt(LocalDateTime.now())
                    .universeSizez(result.universeSize())
                    .boughtCount(result.bought().size())
                    .soldCount(result.sold().size())
                    .heldCount(result.held().size())
                    .boughtSymbols(String.join(",", result.bought()))
                    .soldSymbols(String.join(",", result.sold()))
                    .trigger(trigger)
                    .build());
            log.info("[페이퍼 {}] 종목 {}개 평가 | 매수:{} 매도:{} 보유:{}",
                    trigger, result.universeSize(),
                    result.bought().size(), result.sold().size(), result.held().size());
            return result;
        } catch (Exception e) {
            log.error("[페이퍼 {}] 실행 실패: {}", trigger, e.getMessage(), e);
            throw e;
        }
    }
}