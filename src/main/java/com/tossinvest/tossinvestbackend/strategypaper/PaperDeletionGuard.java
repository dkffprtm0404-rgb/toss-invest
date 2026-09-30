package com.tossinvest.tossinvestbackend.strategypaper;
import com.tossinvest.tossinvestbackend.strategy.StrategyDeletionGuard;
import org.springframework.stereotype.Component;

@Component
public class PaperDeletionGuard implements StrategyDeletionGuard {
    private final PaperRunRepository runs;
    public PaperDeletionGuard(PaperRunRepository runs){this.runs=runs;}
    public void beforeDelete(long id){if(runs.existsByStrategyIdAndStatusNot(id,"STOPPED")) throw new PaperConflict("실행 중이거나 청산 관리 중인 모의매매가 있습니다. 종료 후 전략을 삭제해 주세요.");}
}
