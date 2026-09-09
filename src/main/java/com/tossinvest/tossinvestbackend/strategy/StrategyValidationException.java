package com.tossinvest.tossinvestbackend.strategy;

import java.util.List;

public class StrategyValidationException extends IllegalArgumentException {
    private final List<StrategyValidator.Issue> issues;

    public StrategyValidationException(List<StrategyValidator.Issue> issues) {
        super("Strategy or execution settings require correction.");
        this.issues = List.copyOf(issues);
    }

    public List<StrategyValidator.Issue> getIssues() { return issues; }
}
