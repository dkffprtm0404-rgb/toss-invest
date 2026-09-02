package com.tossinvest.tossinvestbackend.scalping;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 1분봉 단타 자동 폴링 스케줄러.
 * 장중(09:01 ~ 15:40) 매 1분마다 실행한다.
 * 장 외 시간 체크는 ScalpingService.tick() 내부에서 수행한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScalpingScheduler {

    private final ScalpingService scalpingService;

    /** 매 1분 0초 실행 (KST) */
    @Scheduled(cron = "0 * * * * MON-FRI", zone = "Asia/Seoul")
    public void tick() {
        try {
            ScalpingService.ScalpingResult result = scalpingService.tick();
            if (!result.bought().isEmpty() || !result.sold().isEmpty()) {
                log.info("[스캘핑 스케줄러] 매수:{} 매도:{}", result.bought(), result.sold());
            }
        } catch (Exception e) {
            log.error("[스캘핑 스케줄러] 에러: {}", e.getMessage());
        }
    }
}
