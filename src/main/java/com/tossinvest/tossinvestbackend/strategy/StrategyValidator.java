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
        if (Integer.valueOf(4).equals(strategy.schemaVersion())) return new CompositionValidator().validate(strategy);
        if (strategy.composition() != null) invalid("composition", "통합 조건은 전략 형식 버전 4가 필요합니다.", issues);
        if (!required(strategy.schemaVersion(), "schemaVersion", issues) && strategy.schemaVersion() != 1 && strategy.schemaVersion() != 2 && strategy.schemaVersion() != 3)
            invalid("schemaVersion", "전략 형식 버전은 1, 2, 3 또는 4이어야 합니다.", issues);
        if (Integer.valueOf(1).equals(strategy.schemaVersion()) && usesExtensions(strategy))
            invalid("schemaVersion", "확장 조건과 위험 관리는 전략 형식 버전 2가 필요합니다.", issues);
        if (strategy.portfolio() == null) {
            if (Integer.valueOf(3).equals(strategy.schemaVersion())) required(null, "portfolio", issues);
            group(strategy.entry(), "entry", issues);
        } else {
            if (!Integer.valueOf(3).equals(strategy.schemaVersion())) invalid("schemaVersion", "포트폴리오는 버전 3이 필요합니다.", issues);
            var p = strategy.portfolio();
            required(p.market(), "portfolio.market", issues);
            required(p.selectionOrder(), "portfolio.selectionOrder", issues);
            required(p.rebalanceTiming(), "portfolio.rebalanceTiming", issues);
            required(p.weighting(), "portfolio.weighting", issues);
            bounded(p.lookbackMonths(), 1, 60, "portfolio.lookbackMonths", issues);
            bounded(p.topN(), 1, 100, "portfolio.topN", issues);
            period(p.smaPeriod(), "portfolio.smaPeriod", issues);
            if (strategy.entry() != null || strategy.exit() != null) invalid("portfolio", "포트폴리오와 개별 종목 진입·청산 그룹은 혼용할 수 없습니다.", issues);
            var r = strategy.risk();
            if (r != null && (r.takeProfit() != null || r.timeExit() != null || r.trailing() != null || r.trailingStop() != null || r.atrStop() != null))
                invalid("risk", "상대강도 포트폴리오는 고정 손절만 지원합니다.", issues);
        }
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
            if (risk.trailingStop() != null) {
                lossRate(risk.trailingStop().rate(), "risk.trailingStop.rate", issues);
                required(risk.trailingStop().peakBasis(), "risk.trailingStop.peakBasis", issues);
                if (risk.trailing() != null)
                    invalid("risk.trailingStop", "기존 단계식 트레일링과 비율 추적손절 중 하나만 선택해 주세요.", issues);
            }
            if (risk.atrStop() != null) {
                required(risk.atrStop().method(), "risk.atrStop.method", issues);
                period(risk.atrStop().period(), "risk.atrStop.period", issues);
                positive(risk.atrStop().multiplier(), "risk.atrStop.multiplier", issues);
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
                if (rsi.comparison() == Comparison.GT || rsi.comparison() == Comparison.LT)
                    invalid(p + ".comparison", "RSI는 이상·이하·상향 교차·하향 교차만 지원합니다.", issues);
            } else if (condition instanceof Volume volume) {
                period(volume.period(), p + ".period", issues);
                positive(volume.multiplier(), p + ".multiplier", issues);
                if (!required(volume.comparison(), p + ".comparison", issues)
                        && volume.comparison() != Comparison.GTE && volume.comparison() != Comparison.LTE)
                    invalid(p + ".comparison", "Volume supports GTE or LTE.", issues);
            } else if (condition instanceof RangeBreakout range) {
                period(range.period(), p + ".period", issues);
                required(range.periodUnit(), p + ".periodUnit", issues);
                required(range.priceField(), p + ".priceField", issues);
                required(range.comparison(), p + ".comparison", issues);
            } else if (condition instanceof MovingAverageCompare ma) {
                required(ma.averageType(), p + ".averageType", issues);
                period(ma.shortPeriod(), p + ".shortPeriod", issues);
                period(ma.longPeriod(), p + ".longPeriod", issues);
                required(ma.comparison(), p + ".comparison", issues);
                if (ma.shortPeriod() != null && ma.longPeriod() != null && ma.shortPeriod() >= ma.longPeriod())
                    invalid(p + ".longPeriod", "장기 기간은 단기 기간보다 커야 합니다.", issues);
            } else if (condition instanceof PriceMovingAverage ma) {
                required(ma.averageType(), p + ".averageType", issues);
                period(ma.period(), p + ".period", issues);
                required(ma.comparison(), p + ".comparison", issues);
            } else if (condition instanceof AtrBreakout atr) {
                required(atr.method(), p + ".method", issues);
                period(atr.period(), p + ".period", issues);
                positive(atr.multiplier(), p + ".multiplier", issues);
                required(atr.comparison(), p + ".comparison", issues);
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
        issues.add(new Issue(path, "REQUIRED", path.equals("strategy") ? "구조화할 전략을 입력해 주세요." : "필수 항목을 입력해 주세요: " + path));
        return true;
    }

    private void invalid(String path, String message, List<Issue> issues) {
        issues.add(new Issue(path, "INVALID", message));
    }

    private void bounded(Integer value, int min, int max, String path, List<Issue> issues) {
        if (!required(value, path, issues) && (value < min || value > max)) invalid(path, min + "~" + max + " 범위로 입력해 주세요.", issues);
    }

    private void lossRate(BigDecimal rate, String path, List<Issue> issues) {
        if (!required(rate, path, issues) && (rate.signum() >= 0 || rate.compareTo(BigDecimal.ONE.negate()) <= 0))
            invalid(path, "손절률은 -1 초과, 0 미만이어야 합니다. 예: -20%는 -0.20", issues);
    }

    private boolean usesExtensions(StrategyDefinition strategy) {
        Risk risk = strategy.risk();
        if (risk != null && (risk.trailingStop() != null || risk.atrStop() != null)) return true;
        for (ConditionGroup group : new ConditionGroup[]{strategy.entry(), strategy.exit()}) {
            if (group == null || group.conditions() == null) continue;
            for (Condition c : group.conditions()) {
                if (c instanceof RangeBreakout || c instanceof MovingAverageCompare || c instanceof PriceMovingAverage || c instanceof AtrBreakout)
                    return true;
                if (c instanceof Rsi rsi && (rsi.comparison() == Comparison.GT || rsi.comparison() == Comparison.LT)) return true;
            }
        }
        return false;
    }
}
