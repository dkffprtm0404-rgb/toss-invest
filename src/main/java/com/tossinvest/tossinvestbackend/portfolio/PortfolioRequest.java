package com.tossinvest.tossinvestbackend.portfolio;

import com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestRequest.ExecutionMode;
import com.tossinvest.tossinvestbackend.strategy.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public record PortfolioRequest(Integer version, LocalDate startDate, LocalDate endDate, ExecutionMode executionMode,
        BigDecimal initialCapital, BigDecimal commissionRate, BigDecimal taxRate, BigDecimal slippageRate, Universe universe) {
    public record Universe(String source, List<LocalDate> tradingDates, List<Member> members) { }
    public record Member(String symbol, StrategyDefinition.Market market, LocalDate from, LocalDate to) { }
    public void requireValid(StrategyDefinition strategy) {
        var issues = new ArrayList<>(new StrategyValidator().validate(strategy));
        if (strategy == null || strategy.portfolio() == null) issue(issues, "strategy.portfolio", "포트폴리오 전략을 선택해 주세요.");
        if (version == null || version < 1) issue(issues, "version", "저장 버전을 선택해 주세요.");
        boolean dates = startDate != null && endDate != null && startDate.getYear() >= 1901 && endDate.getYear() <= 9997 && !startDate.isAfter(endDate) && !endDate.isAfter(startDate.plusYears(10));
        if (!dates) issue(issues, "dates", "시작일·종료일을 1901~9997년, 최대 10년 범위로 입력해 주세요.");
        if (executionMode == null) issue(issues, "executionMode", "체결 방식을 선택해 주세요.");
        if (initialCapital == null || initialCapital.signum() <= 0 || initialCapital.compareTo(new BigDecimal("1000000000000")) > 0) issue(issues,"initialCapital","초기자금은 0 초과, 1조 이하로 입력해 주세요.");
        rate(issues, commissionRate, "commissionRate"); rate(issues, taxRate, "taxRate"); rate(issues, slippageRate, "slippageRate");
        if (universe == null || universe.source() == null || universe.source().isBlank() || universe.source().length() > 1000) issue(issues,"universe.source","종목 목록과 캘린더의 출처·적용 범위를 입력해 주세요.");
        if (universe != null) {
            var calendar = universe.tradingDates();
            if (calendar == null || calendar.isEmpty() || calendar.size() > 10000) issue(issues,"universe.tradingDates","거래일을 1~10000개 입력해 주세요.");
            else {
                LocalDate prior = null;
                boolean ordered = true;
                for (var day : calendar) {
                    if (day == null || day.getYear() < 1900 || day.getYear() > 9998 || (prior != null && !day.isAfter(prior))) { ordered = false; break; }
                    prior = day;
                }
                if (!ordered) issue(issues,"universe.tradingDates","거래일은 중복 없이 날짜 오름차순이어야 합니다.");
                else if (dates) {
                    if (!calendar.get(0).isBefore(startDate) || !calendar.get(calendar.size()-1).isAfter(endDate.plusDays(7)))
                        issue(issues,"universe.tradingDates","준비 이력부터 종료일 7일 이후까지 거래 캘린더를 포함해 주세요.");
                }
            }
            var members = universe.members();
            if (members == null || members.isEmpty() || members.size() > 10000 || members.stream().filter(Objects::nonNull).map(Member::symbol).distinct().count() > 3000) issue(issues,"universe.members","최대 3000종목·10000개 편입 구간을 입력해 주세요.");
            else {
                Map<String,List<Member>> grouped = new HashMap<>();
                for (var m : members) {
                    if (m == null || m.symbol() == null || !m.symbol().matches("[A-Za-z0-9]{1,32}") || m.market() == null || m.market() == StrategyDefinition.Market.KOSPI_KOSDAQ || m.from() == null || (m.to() != null && m.to().isBefore(m.from()))) { issue(issues,"universe.members","종목 코드·개별 시장·유효 날짜를 확인해 주세요."); continue; }
                    grouped.computeIfAbsent(m.symbol(), k -> new ArrayList<>()).add(m);
                }
                for (var group : grouped.values()) {
                    group.sort(Comparator.comparing(Member::from));
                    for (int i=1;i<group.size();i++) if (group.get(i-1).to() == null || !group.get(i).from().isAfter(group.get(i-1).to())) issue(issues,"universe.members","동일 종목의 편입 기간이 겹칩니다.");
                }
            }
        }
        if (!issues.isEmpty()) throw new StrategyValidationException(issues);
    }
    private static void rate(List<StrategyValidator.Issue> issues, BigDecimal value, String path) {
        if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("0.25")) > 0) issue(issues,path,"비용률을 0~0.25로 명시해 주세요. 0은 미적용 선택입니다.");
    }
    private static void issue(List<StrategyValidator.Issue> issues, String path, String message) { issues.add(new StrategyValidator.Issue(path,"INVALID",message)); }
}
