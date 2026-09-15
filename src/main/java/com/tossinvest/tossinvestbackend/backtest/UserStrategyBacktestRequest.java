package com.tossinvest.tossinvestbackend.backtest;

import com.tossinvest.tossinvestbackend.strategy.StrategyDefinition;
import com.tossinvest.tossinvestbackend.strategy.StrategyValidationException;
import com.tossinvest.tossinvestbackend.strategy.StrategyValidator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public record UserStrategyBacktestRequest(StrategyDefinition strategy, String symbol, LocalDate startDate,
                                         LocalDate endDate, ExecutionMode executionMode) {
    public enum ExecutionMode { SAME_DAY_CLOSE, NEXT_DAY_OPEN }

    public void requireValid() {
        List<StrategyValidator.Issue> issues = new ArrayList<>(new StrategyValidator().validate(strategy));
        if (strategy != null && strategy.portfolio() != null) issues.add(issue("strategy.portfolio", "INVALID", "포트폴리오 실행 화면/API를 사용해 주세요."));
        if (symbol == null || symbol.isBlank()) issues.add(issue("symbol", "REQUIRED", "Supply a symbol."));
        else if (!symbol.matches("[A-Za-z0-9]{1,32}")) issues.add(issue("symbol", "INVALID", "Use 1 to 32 alphanumeric characters."));
        date(startDate, "startDate", issues);
        date(endDate, "endDate", issues);
        if (startDate != null && endDate != null && startDate.isAfter(endDate))
            issues.add(issue("endDate", "INVALID", "endDate must be on or after startDate."));
        if (executionMode == null) issues.add(issue("executionMode", "REQUIRED", "Select SAME_DAY_CLOSE or NEXT_DAY_OPEN."));
        if (!issues.isEmpty()) throw new StrategyValidationException(issues);
    }

    private static void date(LocalDate date, String path, List<StrategyValidator.Issue> issues) {
        if (date == null) issues.add(issue(path, "REQUIRED", "Supply " + path + "."));
        else if (date.getYear() < 1900 || date.getYear() > 9998)
            issues.add(issue(path, "INVALID", "Use a date between years 1900 and 9998."));
    }

    private static StrategyValidator.Issue issue(String path, String code, String message) {
        return new StrategyValidator.Issue(path, code, message);
    }
}
