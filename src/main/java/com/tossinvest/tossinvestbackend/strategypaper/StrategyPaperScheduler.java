package com.tossinvest.tossinvestbackend.strategypaper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(name="strategy.paper.scheduling-enabled",havingValue="true",matchIfMissing=true)
public class StrategyPaperScheduler {
    private static final Logger log=LoggerFactory.getLogger(StrategyPaperScheduler.class);
    private final PaperRunRepository runs;private final StrategyPaperService service;
    public StrategyPaperScheduler(PaperRunRepository runs,StrategyPaperService service){this.runs=runs;this.service=service;}
    @Scheduled(initialDelayString="${strategy.paper.initial-delay-ms:60000}",fixedDelayString="${strategy.paper.delay-ms:1800000}")
    public void refresh(){for(long id:runs.activeIds()){try{service.refresh(id);}catch(Exception ex){log.warn("사용자 전략 모의매매 #{} 갱신 실패 ({})",id,ex.getClass().getSimpleName());}}}
}
