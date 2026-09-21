package com.tossinvest.tossinvestbackend.composite;

import com.tossinvest.tossinvestbackend.portfolio.PortfolioResult;
import com.tossinvest.tossinvestbackend.strategy.StrategyEvaluator;
import java.time.LocalDate;
import java.util.List;

public record CompositeResult(PortfolioResult account, List<Evaluation> evaluations) {
    public record Evaluation(LocalDate date, String symbol, String phase, boolean ready, boolean matched,
                             String reason, List<NodeEvidence> nodes) { }
    public record NodeEvidence(String id, String sourceId, boolean ready, boolean matched,
                               List<StrategyEvaluator.Evidence> evidence) { }
}
