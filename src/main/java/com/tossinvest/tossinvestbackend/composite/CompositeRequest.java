package com.tossinvest.tossinvestbackend.composite;

import com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestRequest.ExecutionMode;
import com.tossinvest.tossinvestbackend.portfolio.PortfolioRequest;
import com.tossinvest.tossinvestbackend.strategy.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public record CompositeRequest(Integer version, LocalDate startDate, LocalDate endDate, ExecutionMode executionMode,
        BigDecimal initialCapital, BigDecimal commissionRate, BigDecimal taxRate, BigDecimal slippageRate,
        String symbol, PortfolioRequest.Universe universe) {
    public void requireValid(StrategyDefinition strategy) {
        var issues=new ArrayList<>(new StrategyValidator().validate(strategy));
        var composition=strategy==null?null:strategy.composition();
        if(composition==null) issue(issues,"strategy.composition","통합 전략을 선택해 주세요.");
        if(version==null||version<1) issue(issues,"version","저장 버전을 선택해 주세요.");
        if(startDate==null||endDate==null||startDate.getYear()<1901||endDate.getYear()>9997||startDate.isAfter(endDate)||endDate.isAfter(startDate.plusYears(10))) issue(issues,"dates","시작일·종료일을 1901~9997년, 최대 10년 범위로 입력해 주세요.");
        if(executionMode==null) issue(issues,"executionMode","체결 방식을 선택해 주세요.");
        if(initialCapital==null||initialCapital.signum()<=0||initialCapital.compareTo(new BigDecimal("1000000000000"))>0) issue(issues,"initialCapital","초기자금은 0 초과, 1조 이하입니다.");
        rate(issues,commissionRate,"commissionRate");rate(issues,taxRate,"taxRate");rate(issues,slippageRate,"slippageRate");
        if(symbol!=null&&!symbol.matches("[A-Za-z0-9]{1,32}")) issue(issues,"symbol","종목 코드를 확인해 주세요.");
        if(composition!=null&&composition.selection()==null&&(symbol==null||symbol.isBlank())) issue(issues,"symbol","종목 선정 없는 조합은 실행 종목이 필요합니다.");
        if(composition!=null&&composition.selection()!=null) {
            // The portfolio request is the single contract for historical membership and exchange calendars.
            try {
                new PortfolioRequest(version,startDate,endDate,executionMode,initialCapital,commissionRate,taxRate,slippageRate,universe)
                        .requireValid(new StrategyDefinition(3,"selection",null,null,null,null,composition.selection()));
            } catch(StrategyValidationException ex) { issues.addAll(ex.getIssues()); }
            if(symbol!=null&&universe!=null&&universe.members()!=null&&universe.members().stream().filter(Objects::nonNull).noneMatch(m->symbol.equals(m.symbol()))) issue(issues,"symbol","지정 종목을 비교 유니버스 편입 이력에 포함해 주세요.");
        }
        if(!issues.isEmpty()) throw new StrategyValidationException(issues);
    }
    private static void rate(List<StrategyValidator.Issue> issues,BigDecimal rate,String path){if(rate==null||rate.signum()<0||rate.compareTo(new BigDecimal("0.25"))>0)issue(issues,path,"비용률은 0~0.25로 명시해 주세요.");}
    private static void issue(List<StrategyValidator.Issue> issues,String path,String message){issues.add(new StrategyValidator.Issue(path,"INVALID",message));}
}
