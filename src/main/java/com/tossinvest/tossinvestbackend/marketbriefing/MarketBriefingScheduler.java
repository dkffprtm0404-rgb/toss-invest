package com.tossinvest.tossinvestbackend.marketbriefing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="market.briefing.scheduling-enabled",havingValue="true",matchIfMissing=true)
public class MarketBriefingScheduler {
    private final MarketBriefingService service;
    public MarketBriefingScheduler(MarketBriefingService service){this.service=service;}
    @Scheduled(cron="0 30 8 * * MON-FRI",zone="Asia/Seoul") public void at0830(){service.generateIfDue();}
    @Scheduled(initialDelayString="${market.briefing.initial-delay-ms:60000}",fixedDelayString="${market.briefing.retry-delay-ms:300000}")
    public void recover(){service.generateIfDue();}
}
