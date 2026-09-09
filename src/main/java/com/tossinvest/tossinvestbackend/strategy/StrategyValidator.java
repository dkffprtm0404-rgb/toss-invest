package com.tossinvest.tossinvestbackend.strategy;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.tossinvest.tossinvestbackend.strategy.StrategyDefinition.*;

@Component
public class StrategyValidator {
    public record Issue(String path, String code, String message) { }

    public List<Issue> validate(StrategyDefinition strategy) {
        List<Issue> issues = new ArrayList<>();
        if (required(strategy, "strategy", issues)) return List.copyOf(issues);
        if (!required(strategy.schemaVersion(), "schemaVersion", issues) && strategy.schemaVersion() != 1)
            invalid("schemaVersion", "Only schemaVersion 1 is supported.", issues);
        group(strategy.entry(), "entry", issues);
        if (strategy.exit() != null) group(strategy.exit(), "exit", issues);
        Risk risk = strategy.risk();
        if (risk != null) {
            if (risk.stopLoss() != null) {
                BigDecimal rate = risk.stopLoss().rate();
                if (!required(rate, "risk.stopLoss.rate", issues)
                        && (rate.signum() >= 0 || rate.compareTo(BigDecimal.ONE.negate()) <= 0))
                    invalid("risk.stopLoss.rate", "Use a decimal rate strictly between -1 and 0 (5% loss = -0.05).", issues);
            }
            if (risk.takeProfit() != null) positive(risk.takeProfit().rate(), "risk.takeProfit.rate", issues);
            if (risk.timeExit() != null) {
                Integer days = risk.timeExit().days();
                if (!required(days, "risk.timeExit.days", issues) && (days < 0 || days > 10000))
                    invalid("risk.timeExit.days", "Use 0 to 10000 bars; exit triggers only after this count is exceeded.", issues);
            }
        }
        return List.copyOf(issues);
    }

    public void requireValid(StrategyDefinition strategy) {
        List<Issue> issues = validate(strategy);
        if (!issues.isEmpty()) throw new StrategyValidationException(issues);
    }

    private void group(ConditionGroup group, String path, List<Issue> issues) {
        if (required(group, path, issues)) return;
        if (required(group.conditions(), path + ".conditions", issues)) return;
        if (group.conditions().isEmpty() || group.conditions().size() > 20) {
            invalid(path + ".conditions", "Supply 1 to 20 conditions.", issues);
            return;
        }
        if (group.conditions().size() > 1) required(group.operator(), path + ".operator", issues);
        for (int i = 0; i < group.conditions().size(); i++) {
            Condition condition = group.conditions().get(i);
            String p = path + ".conditions[" + i + "]";
            if (required(condition, p, issues)) continue;
            if (condition instanceof MovingAverageCross ma) {
                required(ma.averageType(), p + ".averageType", issues);
                period(ma.shortPeriod(), p + ".shortPeriod", issues);
                period(ma.longPeriod(), p + ".longPeriod", issues);
                required(ma.direction(), p + ".direction", issues);
                if (ma.shortPeriod() != null && ma.longPeriod() != null && ma.shortPeriod() >= ma.longPeriod())
                    invalid(p + ".longPeriod", "longPeriod must be greater than shortPeriod.", issues);
            } else if (condition instanceof Rsi rsi) {
                required(rsi.method(), p + ".method", issues);
                period(rsi.period(), p + ".period", issues);
                if (!required(rsi.threshold(), p + ".threshold", issues)
                        && (rsi.threshold().signum() < 0 || rsi.threshold().compareTo(BigDecimal.valueOf(100)) > 0))
                    invalid(p + ".threshold", "RSI threshold must be between 0 and 100.", issues);
                required(rsi.comparison(), p + ".comparison", issues);
            } else if (condition instanceof Volume volume) {
                period(volume.period(), p + ".period", issues);
                positive(volume.multiplier(), p + ".multiplier", issues);
                if (!required(volume.comparison(), p + ".comparison", issues)
                        && volume.comparison() != Comparison.GTE && volume.comparison() != Comparison.LTE)
                    invalid(p + ".comparison", "Volume supports GTE or LTE.", issues);
            }
        }
    }

    private void period(Integer value, String path, List<Issue> issues) {
        if (!required(value, path, issues) && (value < 1 || value > 500))
            invalid(path, "Period must be between 1 and 500 bars.", issues);
    }

    private void positive(BigDecimal value, String path, List<Issue> issues) {
        if (!required(value, path, issues) && value.signum() <= 0)
            invalid(path, "Supply a positive decimal value.", issues);
    }

    private boolean required(Object value, String path, List<Issue> issues) {
        if (value != null) return false;
        issues.add(new Issue(path, "REQUIRED", "Supply " + path + "."));
        return true;
    }

    private void invalid(String path, String message, List<Issue> issues) {
        issues.add(new Issue(path, "INVALID", message));
    }
}
