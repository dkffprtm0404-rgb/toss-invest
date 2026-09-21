package com.tossinvest.tossinvestbackend.portfolio;

import com.tossinvest.tossinvestbackend.backtest.CandleEntity;
import java.math.*;
import java.time.LocalDate;
import java.util.*;
import static com.tossinvest.tossinvestbackend.portfolio.PortfolioResult.*;
import static com.tossinvest.tossinvestbackend.strategy.StrategyDefinition.*;

/** Historical weekly selection shared without changing v3 filtering, ties, or readiness. */
public final class PortfolioSelection {
    private static final MathContext MC=MathContext.DECIMAL128;
    private record Candidate(String symbol,BigDecimal momentum,BigDecimal close,BigDecimal sma) { }
    private PortfolioSelection() { }
    public static boolean scheduled(List<LocalDate> calendar,int index,RebalanceTiming timing) {
        LocalDate day=calendar.get(index);
        if(timing==RebalanceTiming.WEEK_START)return index>0&&!week(day).equals(week(calendar.get(index-1)));
        return index+1<calendar.size()&&!week(day).equals(week(calendar.get(index+1)));
    }
    private static LocalDate week(LocalDate day){return day.minusDays(day.getDayOfWeek().getValue()-1);}
    public static Selection rank(RelativeStrength rule,List<LocalDate> calendar,Map<String,List<PortfolioRequest.Member>> members,
                                 Map<String,Map<LocalDate,CandleEntity>> prices,int index) {
        LocalDate day=calendar.get(index),anchor=day.minusMonths(rule.lookbackMonths());
        int base=Collections.binarySearch(calendar,anchor);if(base<0)base=-base-2;
        var candidates=new ArrayList<Candidate>();var excluded=new ArrayList<Excluded>();
        for(String symbol: new TreeSet<>(members.keySet())) {
            boolean active=members.get(symbol).stream().anyMatch(m->!day.isBefore(m.from())&&(m.to()==null||!day.isAfter(m.to()))&&(rule.market()==Market.KOSPI_KOSDAQ||m.market()==rule.market()));
            if(!active){excluded.add(new Excluded(symbol,"대상 시장·편입 기간 밖"));continue;}
            var series=prices.getOrDefault(symbol,Map.of());var current=series.get(day);
            if(current==null||current.getVolume().signum()==0||base<0||index+1<rule.smaPeriod()||!series.containsKey(calendar.get(base))){excluded.add(new Excluded(symbol,"현재 봉·거래량 또는 수익률/SMA 준비 이력 부족"));continue;}
            BigDecimal sum=BigDecimal.ZERO;boolean ready=true;
            for(int j=index-rule.smaPeriod()+1;j<=index;j++){var c=series.get(calendar.get(j));if(c==null){ready=false;break;}sum=sum.add(c.getClosePrice(),MC);}
            if(!ready){excluded.add(new Excluded(symbol,"SMA 구간 거래일 캔들 누락"));continue;}
            BigDecimal sma=sum.divide(BigDecimal.valueOf(rule.smaPeriod()),MC);
            if(rule.selectionOrder()==SelectionOrder.FILTER_THEN_RANK&&current.getClosePrice().compareTo(sma)<=0){excluded.add(new Excluded(symbol,"종가가 SMA 위에 있지 않음"));continue;}
            candidates.add(new Candidate(symbol,current.getClosePrice().divide(series.get(calendar.get(base)).getClosePrice(),MC).subtract(BigDecimal.ONE),current.getClosePrice(),sma));
        }
        candidates.sort(Comparator.comparing(Candidate::momentum).reversed().thenComparing(Candidate::symbol));
        var ranked=new ArrayList<Ranked>();
        for(int i=0;i<candidates.size();i++){var c=candidates.get(i);ranked.add(new Ranked(c.symbol(),i+1,c.momentum(),c.close(),c.sma(),i<rule.topN()&&c.close().compareTo(c.sma())>0));}
        return new Selection(day,List.copyOf(ranked),List.copyOf(excluded));
    }
}
