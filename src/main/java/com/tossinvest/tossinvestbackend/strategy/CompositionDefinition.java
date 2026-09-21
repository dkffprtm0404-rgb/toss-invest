package com.tossinvest.tossinvestbackend.strategy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static com.tossinvest.tossinvestbackend.strategy.StrategyDefinition.*;

/** Independent source snapshots and explicitly resolved common execution policies. */
public record CompositionDefinition(List<Source> sources, RuleNode entry, RuleNode exit,
        RelativeStrength selection, String selectionSourceId, Boolean rankExit, Allocation allocation,
        Boolean riskConfirmed, Boolean exitConfirmed) {
    public CompositionDefinition { sources = copy(sources); }
    public record Source(String id, String title, String prompt, StrategyDefinition definition,
                         List<String> questions, List<String> unsupported, Boolean included, String resolution) {
        public Source { questions = copy(questions); unsupported = copy(unsupported); }
    }
    public record RuleNode(String id, String sourceId, Condition condition, Operator operator, List<RuleNode> children) {
        public RuleNode { children = copy(children); }
    }
    public record Allocation(Integer maxPositions, Rebalance rebalance) { }
    public enum Rebalance { ENTRY_ONLY, WEEKLY_EQUAL }
    private static <T> List<T> copy(List<T> values) {
        return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
