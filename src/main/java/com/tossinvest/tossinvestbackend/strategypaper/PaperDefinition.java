package com.tossinvest.tossinvestbackend.strategypaper;

import com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestRequest.ExecutionMode;
import com.tossinvest.tossinvestbackend.strategy.*;
import java.math.BigDecimal;
import java.util.*;

public record PaperDefinition(Long strategyId, Integer version, String symbol, ExecutionMode executionMode,
        BigDecimal initialCapital, BigDecimal allocationRate, BigDecimal commissionRate,
        BigDecimal taxRate, BigDecimal slippageRate, String requestId) {
    public void validate() {
        var issues=new ArrayList<StrategyValidator.Issue>();
        if(strategyId==null || strategyId<1) issue(issues,"strategyId","저장 전략을 선택해 주세요.");
        if(version==null || version<1) issue(issues,"version","저장 버전을 선택해 주세요.");
        if(symbol==null || !symbol.matches("[0-9]{6}")) issue(issues,"symbol","국내 종목 코드 6자리를 입력해 주세요.");
        if(executionMode==null) issue(issues,"executionMode","체결 방식을 선택해 주세요.");
        number(issues,"initialCapital",initialCapital,new BigDecimal("1000000000000"),false);
        number(issues,"allocationRate",allocationRate,BigDecimal.ONE,false);
        number(issues,"commissionRate",commissionRate,new BigDecimal("0.25"),true);
        number(issues,"taxRate",taxRate,new BigDecimal("0.25"),true);
        number(issues,"slippageRate",slippageRate,new BigDecimal("0.25"),true);
        if(requestId==null || !requestId.matches("[A-Za-z0-9-]{8,80}")) issue(issues,"requestId","시작 요청 식별자가 필요합니다.");
        if(!issues.isEmpty()) throw new StrategyValidationException(issues);
    }
    public void validateStrategy(StrategyDefinition strategy) {
        new StrategyValidator().requireValid(strategy);
        if(strategy.portfolio()!=null || strategy.composition()!=null || strategy.schemaVersion()>2)
            throw new StrategyValidationException(List.of(new StrategyValidator.Issue("strategyId","UNSUPPORTED","단일 종목 전략(형식 1·2)만 지원합니다.")));
    }
    private static void number(List<StrategyValidator.Issue> issues,String path,BigDecimal value,BigDecimal max,boolean zero) {
        if(value==null || value.signum()<(zero?0:1) || value.compareTo(max)>0 || value.scale()>10)
            issue(issues,path,"값을 명시해 주세요. 허용 범위: "+(zero?"0 이상":"0 초과")+", "+max+" 이하, 소수 10자리 이하.");
    }
    private static void issue(List<StrategyValidator.Issue> issues,String path,String message) {issues.add(new StrategyValidator.Issue(path,"INVALID",message));}
}
