package com.tossinvest.tossinvestbackend.paper;

import com.tossinvest.tossinvestbackend.backtest.BacktestUniverse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaperTradingScheduler {

    private final PaperTradingService paperTradingService;
    private final PaperRunLogRepository logRepo;
    private final BacktestUniverse universe;

    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Seoul")
    public void runDaily() {
        log.info("[페이퍼 스케줄러] {} 15:35 자동 실행 시작", LocalDate.now());
        saveAndRun("SCHEDULER");
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