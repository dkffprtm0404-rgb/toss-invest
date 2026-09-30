package com.tossinvest.tossinvestbackend.strategypaper;

import com.tossinvest.tossinvestbackend.strategy.*;
import com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestEngine;
import com.tossinvest.tossinvestbackend.portfolio.PortfolioCalculations;
import org.springframework.stereotype.Component;
import java.math.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import static com.tossinvest.tossinvestbackend.strategypaper.PaperState.*;
import static com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestRequest.ExecutionMode.NEXT_DAY_OPEN;

@Component
public class StrategyPaperEngine {
    private static final MathContext MC=MathContext.DECIMAL128;
    private final StrategyEvaluator evaluator;
    public StrategyPaperEngine(StrategyEvaluator evaluator) {this.evaluator=evaluator;}

    public void process(StrategyDefinition strategy,PaperDefinition request,PaperState s,List<StrategyBar> incoming,Instant now) {
        if("STOPPED".equals(s.status)) return;
        LocalDate today=now.atZone(UserStrategyBacktestEngine.MARKET_ZONE).toLocalDate();
        var received=new TreeMap<LocalDate,StrategyBar>();
        for(var bar:incoming) {
            LocalDate day=date(bar);
            if(!day.isBefore(today)) continue;
            valid(bar);
            if(received.putIfAbsent(day,bar)!=null) throw data("동일 날짜의 일봉이 중복되었습니다.");
        }
        var known=new TreeMap<LocalDate,StrategyBar>();s.bars.forEach(b->known.put(date(b),b));
        for(var entry:received.entrySet()) {
            var old=known.get(entry.getKey());
            if(old!=null && !same(old,entry.getValue())) throw data("이미 사용한 일봉이 변경되었습니다. 수정주가·분할·데이터 출처를 확인해 주세요.");
            if(old==null && !known.isEmpty() && entry.getKey().isBefore(known.lastKey())) throw data("처리한 이력 사이에 새로운 과거 봉이 추가되어 이력이 변경되었습니다.");
        }
        if(!known.isEmpty() && !received.isEmpty() && received.lastKey().isAfter(known.lastKey()) && !received.containsKey(known.lastKey()))
            throw data("마지막 처리 일봉과 새 데이터의 연결을 확인할 수 없습니다.");
        int oldSize=s.bars.size();
        received.forEach((day,bar)->{if(!known.containsKey(day)) s.bars.add(bar);});
        if(s.bars.size()>20000) throw data("실행 보존 한도 20000봉을 초과했습니다.");
        s.dataSha256=dataHash(s.bars);
        s.lastObservedAt=now;
        if(oldSize==s.bars.size()) {s.dataStatus="WAITING_DATA";s.log(now,s.dataStatus,"새 확정 일봉을 기다립니다.",s.lastProcessedDate);return;}
        // A successful warm-up-only refresh must clear the previous fetch failure too.
        s.dataStatus="WAITING_DATA";
        var evaluation=evaluator.prepare(strategy,s.bars);
        boolean nextOpen=request.executionMode()==NEXT_DAY_OPEN;
        for(int i=oldSize;i<s.bars.size();i++) {
            var bar=s.bars.get(i);LocalDate day=date(bar);
            if(day.isBefore(s.firstTradingDate)) {s.lastProcessedDate=day;continue;}
            s.dataStatus="READY";
            if(s.pending!=null) {
                var order=s.pending;s.pending=null;
                if(!"BUY".equals(order.side()) || "RUNNING".equals(s.status)) fill(s,request,evaluation,i,bar.open(),order,now);
            }
            if(s.position!=null) {
                var p=s.position;boolean high=strategy.risk()!=null && strategy.risk().trailingStop()!=null && strategy.risk().trailingStop().peakBasis()==StrategyDefinition.PeakBasis.HIGH;
                s.position=new Position(p.entryIndex(),p.quantity(),p.entryPrice(),p.costBasis(),p.peakPrice().max(high?bar.high():bar.close()));
                var decision=evaluation.exit(i,new StrategyEvaluator.PositionContext(p.entryPrice(),i-p.entryIndex(),s.position.peakPrice()));
                if(decision.matched()) order(s,request,evaluation,i,"SELL",decision,now);
            } else if("RUNNING".equals(s.status)) {
                if(!evaluation.entryReady(i,nextOpen)) {s.dataStatus="INSUFFICIENT_DATA";s.log(now,s.dataStatus,"전략 지표 준비에 필요한 데이터가 부족합니다.",day);}
                else {var decision=evaluation.entry(i,nextOpen);if(decision.matched()) order(s,request,evaluation,i,"BUY",decision,now);}
            }
            mark(s,request,bar);
            s.lastProcessedDate=day;
            if("STOPPING".equals(s.status) && s.position==null && s.pending==null) {s.status="STOPPED";s.log(now,"STOPPED","보유분 청산을 완료했습니다.",day);break;}
        }
        if("WAITING_DATA".equals(s.dataStatus)) s.log(now,s.dataStatus,"지표 준비 일봉을 받았습니다. 첫 판단 가능일 이후 확정 일봉을 기다립니다.",s.lastProcessedDate);
    }
    private void order(PaperState s,PaperDefinition r,StrategyEvaluator.Evaluation evaluation,int index,String side,StrategyEvaluator.Decision decision,Instant now) {
        var pending=new Pending(side,date(s.bars.get(index)),decision.reason(),decision.evidence());
        if(r.executionMode()==NEXT_DAY_OPEN) s.pending=pending;
        else fill(s,r,evaluation,index,s.bars.get(index).close(),pending,now);
    }
    private void fill(PaperState s,PaperDefinition r,StrategyEvaluator.Evaluation evaluation,int index,BigDecimal reference,Pending order,Instant now) {
        var bar=s.bars.get(index);
        if(bar.volume().signum()==0) throw data("거래량 0인 일봉에서는 체결할 수 없습니다.");
        boolean buy="BUY".equals(order.side());
        BigDecimal price=PortfolioCalculations.executionPrice(reference,r.slippageRate(),buy);
        long quantity=buy?PortfolioCalculations.shares(s.cash.multiply(r.allocationRate(),MC),PortfolioCalculations.buyUnit(reference,r.slippageRate(),r.commissionRate())):s.position.quantity();
        if(quantity==0) {s.dataStatus="INSUFFICIENT_CASH";s.log(now,s.dataStatus,"1주를 매수할 현금이 부족합니다.",date(bar));return;}
        BigDecimal amount=price.multiply(BigDecimal.valueOf(quantity),MC),fee=amount.multiply(r.commissionRate(),MC),tax=buy?BigDecimal.ZERO:amount.multiply(r.taxRate(),MC);
        if(buy) {
            evaluation.atrStopPrice(index,price);
            BigDecimal cost=amount.add(fee,MC);s.cash=s.cash.subtract(cost,MC);
            s.position=new Position(index,quantity,price,cost,price);
        } else {
            BigDecimal proceeds=amount.subtract(fee,MC).subtract(tax,MC);
            s.cash=s.cash.add(proceeds,MC);s.realizedPnl=s.realizedPnl.add(proceeds.subtract(s.position.costBasis(),MC),MC);s.position=null;
        }
        s.totalCosts=s.totalCosts.add(fee,MC).add(tax,MC);
        s.trades.add(new Trade(order.side(),order.signalDate(),date(bar),now,quantity,price,fee,tax,order.reason(),order.evidence(),s.cash));
    }
    private void mark(PaperState s,PaperDefinition r,StrategyBar b) {
        BigDecimal holding=s.position==null?BigDecimal.ZERO:b.close().multiply(BigDecimal.valueOf(s.position.quantity()),MC);
        s.unrealizedPnl=s.position==null?BigDecimal.ZERO:holding.subtract(s.position.costBasis(),MC);
        s.equity=s.cash.add(holding,MC);s.returnRate=s.equity.divide(r.initialCapital(),MC).subtract(BigDecimal.ONE);
        s.peakEquity=s.peakEquity.max(s.equity);BigDecimal drawdown=s.peakEquity.subtract(s.equity,MC).divide(s.peakEquity,MC);
        s.maxDrawdown=s.maxDrawdown.max(drawdown);s.curve.add(new Equity(date(b),s.equity,s.cash,s.unrealizedPnl,s.returnRate,drawdown));
    }
    public static LocalDate date(StrategyBar b) {return UserStrategyBacktestEngine.date(b.timestamp());}
    private static void valid(StrategyBar b) {
        if(b.open()==null||b.close()==null||b.high()==null||b.low()==null||b.volume()==null || b.open().signum()<=0 || b.close().signum()<=0 || b.low().signum()<=0 || b.high().compareTo(b.open().max(b.close()))<0 || b.low().compareTo(b.open().min(b.close()))>0 || b.volume().signum()<0) throw data("일봉 OHLCV 값이 올바르지 않습니다.");
    }
    private static boolean same(StrategyBar a,StrategyBar b) {return a.open().compareTo(b.open())==0&&a.close().compareTo(b.close())==0&&a.high().compareTo(b.high())==0&&a.low().compareTo(b.low())==0&&a.volume().compareTo(b.volume())==0;}
    private static IllegalArgumentException data(String message) {return new IllegalArgumentException(message);}
    private static String dataHash(List<StrategyBar> bars) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for(var b:bars) digest.update((b.timestamp()+","+b.open().stripTrailingZeros().toPlainString()+","+b.high().stripTrailingZeros().toPlainString()+","+b.low().stripTrailingZeros().toPlainString()+","+b.close().stripTrailingZeros().toPlainString()+","+b.volume().stripTrailingZeros().toPlainString()+"\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
}
