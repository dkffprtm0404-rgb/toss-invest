package com.tossinvest.tossinvestbackend.paper;

import com.tossinvest.tossinvestbackend.backtest.BacktestUniverse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 페이퍼 트레이딩 자동 스케줄러.
 * 매 거래일(월~금) 15:35에 신호를 평가하고 가상 포지션을 업데이트한다.
 * 공휴일은 별도 처리 없음 (API 호출이 실패해도 로그로만 기록하고 넘어감).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaperTradingScheduler {

    private final PaperTradingService paperTradingService;

    /** 매일 15:35 (KST) 실행. 주말이면 스킵. */
    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Seoul")
    public void runDaily() {
        LocalDate today = LocalDate.now();
        if (today.getDayOfWeek() == DayOfWeek.SATURDAY || today.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return;
        }
        log.info("[페이퍼 스케줄러] {} 일일 실행 시작", today);
        try {
            PaperTradingService.DailyRunResult result = paperTradingService.runDaily(BacktestUniverse.all());
            log.info("[페이퍼 스케줄러] 완료 - 매수: {}, 매도: {}, 보유유지: {}",
                    result.bought(), result.sold(), result.held().size());
        } catch (Exception e) {
            log.error("[페이퍼 스케줄러] 실행 중 에러: {}", e.getMessage(), e);
        }
    }
}
