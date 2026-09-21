package com.tossinvest.tossinvestbackend.composite;

import com.tossinvest.tossinvestbackend.backtest.*;
import com.tossinvest.tossinvestbackend.portfolio.*;
import com.tossinvest.tossinvestbackend.strategy.*;
import org.springframework.stereotype.Component;
import java.math.*;
import java.time.LocalDate;
import java.util.*;
import static com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestRequest.ExecutionMode.NEXT_DAY_OPEN;
import static com.tossinvest.tossinvestbackend.composite.CompositeResult.*;
import static com.tossinvest.tossinvestbackend.portfolio.PortfolioCalculations.*;
import static com.tossinvest.tossinvestbackend.portfolio.PortfolioResult.*;
import static com.tossinvest.tossinvestbackend.strategy.StrategyDefinition.*;
import static com.tossinvest.tossinvestbackend.strategy.CompositionDefinition.*;

/** One deterministic account: completed-bar decisions, exit precedence, and explicit allocation. */
@Component
public class CompositeEngine {
    private static final MathContext MC=MathContext.DECIMAL128;
    private static final BigDecimal ZERO=BigDecimal.ZERO,ONE=BigDecimal.ONE;
    public CompositeResult run(StrategyDefinition strategy,CompositeRequest request,List<CandleEntity> candles) {
        request.requireValid(strategy);
        return new Run(strategy,request,candles).execute();
    }
    private static final class Position {
        long quantity;BigDecimal average,peak;final BigDecimal atrStop;final int entryIndex;
        Position(long quantity,BigDecimal price,BigDecimal atrStop,int index){this.quantity=quantity;this.average=price;this.peak=price;this.atrStop=atrStop;this.entryIndex=index;}
    }
    private record Signal(LocalDate date,Map<String,String> exits,List<String> entries,boolean rebalance,Map<String,BigDecimal> entryAtr) { }
    private static final class Run {
        final StrategyDefinition strategy;final CompositionDefinition rule;final Risk risk;final CompositeRequest request;
        final List<LocalDate> calendar;final Map<String,Map<LocalDate,CandleEntity>> prices=new TreeMap<>();
        final Map<String,List<PortfolioRequest.Member>> members=new TreeMap<>();
        final Map<String,CompositionEvaluator> evaluators=new HashMap<>();
        final Map<String,Map<LocalDate,Integer>> indices=new HashMap<>();
        final Map<String,Position> positions=new TreeMap<>();
        final List<EquityPoint> equity=new ArrayList<>();final List<Trade> trades=new ArrayList<>();
        final List<Selection> selections=new ArrayList<>();final List<Evaluation> evaluations=new ArrayList<>();
        List<String> candidates=List.of();BigDecimal cash,peak,maxDrawdown=ZERO;Signal pending;
        Run(StrategyDefinition strategy,CompositeRequest request,List<CandleEntity> input) {
            this.strategy=strategy;this.rule=strategy.composition();this.risk=strategy.risk();this.request=request;
            cash=request.initialCapital();peak=cash;
            if(rule.selection()!=null)request.universe().members().forEach(m->members.computeIfAbsent(m.symbol(),s->new ArrayList<>()).add(m));
            var dates=new TreeSet<LocalDate>();
            for(var candle:input) {
                if(candle==null||candle.getTimestamp()==null)throw data(null,"캔들 시각이 없습니다.");
                String symbol=candle.getSymbol();
                if(rule.selection()==null?!request.symbol().equals(symbol):!members.containsKey(symbol))continue;
                LocalDate day=UserStrategyBacktestEngine.date(candle.getTimestamp());dates.add(day);
                if(day.isAfter(request.endDate()))continue;
                if(!positive(candle.getOpenPrice())||!positive(candle.getHighPrice())||!positive(candle.getLowPrice())||!positive(candle.getClosePrice())
                        ||candle.getHighPrice().compareTo(candle.getOpenPrice().max(candle.getClosePrice()))<0||candle.getLowPrice().compareTo(candle.getOpenPrice().min(candle.getClosePrice()))>0
                        ||candle.getVolume()==null||candle.getVolume().signum()<0)throw data(day,"OHLC 또는 거래량이 올바르지 않습니다: "+symbol);
                if(prices.computeIfAbsent(symbol,s->new TreeMap<>()).put(day,candle)!=null)throw data(day,"종목의 동일 거래일 캔들이 중복됩니다: "+symbol);
            }
            calendar=rule.selection()==null?List.copyOf(dates):request.universe().tradingDates();
            prices.forEach((symbol,series)->{
                var bars=new ArrayList<StrategyBar>();var index=new HashMap<LocalDate,Integer>();
                series.forEach((day,c)->{index.put(day,bars.size());bars.add(new StrategyBar(c.getTimestamp(),c.getOpenPrice(),c.getClosePrice(),c.getVolume(),c.getHighPrice(),c.getLowPrice()));});
                indices.put(symbol,index);evaluators.put(symbol,new CompositionEvaluator(rule,risk,bars));
            });
            if(rule.selection()==null)candidates=List.of(request.symbol());
        }
        CompositeResult execute() {
            LocalDate last=null;
            for(int i=0;i<calendar.size();i++) {
                LocalDate day=calendar.get(i);if(day.isBefore(request.startDate())||day.isAfter(request.endDate()))continue;last=day;
                var soldToday=new HashSet<String>();
                if(pending!=null){fill(pending,day,true,soldToday);pending=null;}
                valuation(day,false); // A missing held candle must not silently become stale valuation.
                boolean selectionDay=rule.selection()!=null&&PortfolioSelection.scheduled(calendar,i,rule.selection().rebalanceTiming());
                if(selectionDay) {
                    Selection selection=PortfolioSelection.rank(rule.selection(),calendar,members,prices,i);selections.add(selection);
                    candidates=selection.ranked().stream().filter(Ranked::selected).map(Ranked::symbol).toList();
                }
                var exits=new LinkedHashMap<String,String>();
                for(var held:positions.entrySet()) {
                    String symbol=held.getKey();Position position=held.getValue();var bar=bar(symbol,day);
                    boolean high=risk!=null&&risk.trailingStop()!=null&&risk.trailingStop().peakBasis()==PeakBasis.HIGH;
                    position.peak=position.peak.max(high?bar.getHighPrice():bar.getClosePrice());
                    String reason=risk(symbol,day,position);
                    var exit=evaluators.get(symbol).evaluate(rule.exit(),index(symbol,day));
                    if(rule.exit()!=null)evaluations.add(new Evaluation(day,symbol,"EXIT",exit.ready(),exit.matched(),exit.matched()?"EXIT_CONDITIONS":exit.ready()?"CONDITIONS_NOT_MET":"INSUFFICIENT_HISTORY",exit.nodes()));
                    if(reason==null&&exit.matched())reason="EXIT_CONDITIONS";
                    if(selectionDay&&Boolean.TRUE.equals(rule.rankExit())) {
                        boolean out=!candidates.contains(symbol);
                        evaluations.add(new Evaluation(day,symbol,"EXIT",true,out,out?"RANK_EXIT":"RANK_RETAINED",List.of()));
                        if(reason==null&&out)reason="RANK_EXIT";
                    }
                    if(reason!=null)exits.put(symbol,reason);
                }
                var entries=new ArrayList<String>();var atr=new HashMap<String,BigDecimal>();
                for(String symbol:candidates) {
                    if(request.symbol()!=null&&!request.symbol().equals(symbol))continue;
                    var bar=prices.getOrDefault(symbol,Map.of()).get(day);
                    if(bar==null){event(day,symbol,"ENTRY",false,false,"NO_SIGNAL_BAR");continue;}
                    var entry=evaluators.get(symbol).evaluate(rule.entry(),index(symbol,day));
                    String reason=!entry.ready()?"INSUFFICIENT_HISTORY":!entry.matched()?"CONDITIONS_NOT_MET":"ENTRY_CONDITIONS";
                    boolean eligible=entry.matched();
                    if(exits.containsKey(symbol)||soldToday.contains(symbol)){reason="SAME_DAY_EXIT";eligible=false;}
                    else if(positions.containsKey(symbol)){reason="ALREADY_HELD";eligible=false;}
                    else if(bar.getVolume().signum()==0){reason="ZERO_VOLUME";eligible=false;}
                    if(eligible&&risk!=null&&risk.atrStop()!=null) {
                        BigDecimal value=evaluators.get(symbol).entryAtr(index(symbol,day),request.executionMode()==NEXT_DAY_OPEN);
                        if(value==null){reason="INSUFFICIENT_ATR_HISTORY";eligible=false;}else atr.put(symbol,value);
                    }
                    evaluations.add(new Evaluation(day,symbol,"ENTRY",entry.ready(),entry.matched(),reason,entry.nodes()));
                    if(eligible)entries.add(symbol);
                }
                if(rule.selection()!=null&&request.symbol()!=null&&!candidates.contains(request.symbol()))event(day,request.symbol(),"ENTRY",true,false,"NOT_SELECTED");
                boolean rebalance=selectionDay&&rule.allocation().rebalance()==Rebalance.WEEKLY_EQUAL;
                if(!exits.isEmpty()||!entries.isEmpty()||rebalance&&!positions.isEmpty()) {
                    Signal signal=new Signal(day,Collections.unmodifiableMap(exits),List.copyOf(entries),rebalance,Map.copyOf(atr));
                    if(request.executionMode()==NEXT_DAY_OPEN)pending=signal;else fill(signal,day,false,soldToday);
                }
                BigDecimal value=valuation(day,false);peak=peak.max(value);maxDrawdown=maxDrawdown.max(ONE.subtract(value.divide(peak,MC)));
                equity.add(new EquityPoint(day,value,cash));
            }
            var holdings=new ArrayList<Holding>();
            if(last!=null)for(var held:positions.entrySet()){BigDecimal close=price(held.getKey(),last,false);var p=held.getValue();holdings.add(new Holding(held.getKey(),p.quantity,p.average,close,close.multiply(BigDecimal.valueOf(p.quantity),MC)));}
            var unfilled=new ArrayList<Pending>();
            if(pending!=null) {
                pending.exits().forEach((s,r)->unfilled.add(new Pending(pending.date(),s,"SELL",r+" / NO_NEXT_BAR")));
                pending.entries().forEach(s->unfilled.add(new Pending(pending.date(),s,"BUY","ENTRY_CONDITIONS / NO_NEXT_BAR")));
                if(pending.rebalance())positions.keySet().stream().filter(s->!pending.exits().containsKey(s)).forEach(s->unfilled.add(new Pending(pending.date(),s,"TARGET","REBALANCE / NO_NEXT_BAR")));
            }
            BigDecimal end=equity.isEmpty()?cash:equity.get(equity.size()-1).equity();
            String status=equity.isEmpty()?"NO_DATA":trades.isEmpty()&&unfilled.isEmpty()?"NO_TRADES":"COMPLETED";
            return new CompositeResult(new PortfolioResult(status,request.initialCapital(),end,end.divide(request.initialCapital(),MC).subtract(ONE),maxDrawdown,cash,List.copyOf(equity),List.copyOf(trades),List.copyOf(selections),List.copyOf(holdings),List.copyOf(unfilled),List.of(
                    "완성 일봉 조건으로 한 계좌를 계산합니다. 같은 날 전량 청산한 종목은 재진입하지 않습니다.",
                    "종목별 배분은 계좌 평가액 / 최대 보유 수입니다. 미진입 몫과 정수 주수 잔액은 현금입니다.",
                    "고정 손절은 평균 매입가, 추적 고점은 최초 진입 이후 누적값, ATR 손절은 최초 진입 직전 완성 봉으로 고정합니다.",
                    "부분 조정은 추적 고점과 최초 ATR 손절을 초기화하지 않습니다. 미청산 보유분을 평가하며 종료 강제 청산은 없습니다.",
                    "입력 이력과 시장 캘린더만 사용하며 생존편향·배당·기업행위·장중 체결·유동성 제약은 보정하지 않습니다.",
                    request.executionMode()==NEXT_DAY_OPEN?"다음 입력 거래일 시가로 수량을 결정합니다. 체결일 종가와 고가는 수량 결정에 사용하지 않습니다.":"신호 당일 종가 체결은 시뮬레이션 가정입니다.")),List.copyOf(evaluations));
        }
        String risk(String symbol,LocalDate day,Position position) {
            if(risk==null)return null;
            BigDecimal close=price(symbol,day,false);String reason=null;
            if(risk.stopLoss()!=null){BigDecimal threshold=position.average.multiply(ONE.add(risk.stopLoss().rate()),MC);if(riskEvent(day,symbol,"STOP_LOSS",close,threshold,close.compareTo(threshold)<=0))reason="STOP_LOSS";}
            if(position.atrStop!=null&&riskEvent(day,symbol,"ATR_STOP_LOSS",close,position.atrStop,close.compareTo(position.atrStop)<=0)&&reason==null)reason="ATR_STOP_LOSS";
            if(risk.takeProfit()!=null){BigDecimal threshold=position.average.multiply(ONE.add(risk.takeProfit().rate()),MC);if(riskEvent(day,symbol,"TAKE_PROFIT",close,threshold,close.compareTo(threshold)>=0)&&reason==null)reason="TAKE_PROFIT";}
            if(risk.timeExit()!=null){BigDecimal bars=BigDecimal.valueOf(index(symbol,day)-position.entryIndex),threshold=BigDecimal.valueOf(risk.timeExit().days());if(riskEvent(day,symbol,"TIME_EXIT",bars,threshold,bars.compareTo(threshold)>0)&&reason==null)reason="TIME_EXIT";}
            if(risk.trailingStop()!=null){BigDecimal threshold=position.peak.multiply(ONE.add(risk.trailingStop().rate()),MC);if(riskEvent(day,symbol,"TRAILING_STOP",close,threshold,close.compareTo(threshold)<=0)&&reason==null)reason="TRAILING_STOP";}
            return reason;
        }
        boolean riskEvent(LocalDate day,String symbol,String reason,BigDecimal actual,BigDecimal reference,boolean matched) {
            evaluations.add(new Evaluation(day,symbol,"RISK",true,matched,reason,List.of(new NodeEvidence(reason,null,true,matched,List.of(new StrategyEvaluator.Evidence("risk."+reason,reason,actual,reference,null,null,matched))))));return matched;
        }
        void fill(Signal signal,LocalDate day,boolean open,Set<String> soldToday) {
            // Only already-held positions participate in opening valuation. Entry prices are read below at the same open.
            BigDecimal budget=valuation(day,open).divide(BigDecimal.valueOf(rule.allocation().maxPositions()),MC);
            for(var exit:signal.exits().entrySet())if(positions.containsKey(exit.getKey())) {
                trade(exit.getKey(),positions.get(exit.getKey()).quantity,false,day,signal,open,exit.getValue());soldToday.add(exit.getKey());
            }
            var desired=new LinkedHashMap<String,Long>();
            for(var held:positions.entrySet())desired.put(held.getKey(),signal.rebalance()?shares(budget,unit(held.getKey(),day,open)):held.getValue().quantity);
            for(String symbol:signal.entries()) {
                if(soldToday.contains(symbol)){event(day,symbol,"EXECUTION",true,false,"SAME_DAY_EXIT");continue;}
                if(desired.size()>=rule.allocation().maxPositions()){event(day,symbol,"EXECUTION",true,false,"POSITION_LIMIT");continue;}
                var candle=prices.getOrDefault(symbol,Map.of()).get(day);
                if(candle==null||candle.getVolume().signum()==0){event(day,symbol,"EXECUTION",false,false,candle==null?"NO_EXECUTION_BAR":"ZERO_VOLUME");continue;}
                desired.put(symbol,shares(budget,unit(symbol,day,open)));
            }
            for(String symbol:new ArrayList<>(positions.keySet())) {
                long sell=positions.get(symbol).quantity-desired.get(symbol);
                if(sell>0){trade(symbol,sell,false,day,signal,open,"REBALANCE");if(!positions.containsKey(symbol))soldToday.add(symbol);}
            }
            for(var target:desired.entrySet()) {
                String symbol=target.getKey();long owned=positions.containsKey(symbol)?positions.get(symbol).quantity:0;
                long needed=target.getValue()-owned;
                if(needed<=0){if(owned==0)event(day,symbol,"EXECUTION",true,false,"INSUFFICIENT_CASH");continue;}
                long buy=Math.min(needed,shares(cash,unit(symbol,day,open)));
                if(buy>0)trade(symbol,buy,true,day,signal,open,owned==0?"ENTRY_CONDITIONS":"REBALANCE");
                else event(day,symbol,"EXECUTION",true,false,"INSUFFICIENT_CASH");
            }
        }
        void trade(String symbol,long quantity,boolean buy,LocalDate day,Signal signal,boolean open,String reason) {
            if(bar(symbol,day).getVolume().signum()==0)throw data(day,"거래량 0인 봉에 체결할 수 없습니다: "+symbol);
            BigDecimal price=executionPrice(price(symbol,day,open),request.slippageRate(),buy),amount=price.multiply(BigDecimal.valueOf(quantity),MC),fee=amount.multiply(request.commissionRate(),MC),tax=buy?ZERO:amount.multiply(request.taxRate(),MC);
            if(buy) {
                cash=cash.subtract(amount.add(fee),MC);Position held=positions.get(symbol);
                if(held==null) {
                    BigDecimal stop=null;
                    if(risk!=null&&risk.atrStop()!=null){BigDecimal atr=signal.entryAtr().get(symbol);if(atr==null)throw data(day,"진입 직전 ATR 이력이 없습니다.");stop=price.subtract(atr.multiply(risk.atrStop().multiplier(),MC));if(stop.signum()<=0)throw data(day,"ATR 손절 기준가가 0 이하입니다.");}
                    positions.put(symbol,new Position(quantity,price,stop,index(symbol,day)));
                } else {held.average=held.average.multiply(BigDecimal.valueOf(held.quantity),MC).add(amount).divide(BigDecimal.valueOf(held.quantity+quantity),MC);held.quantity+=quantity;}
            } else {cash=cash.add(amount.subtract(fee).subtract(tax),MC);Position held=positions.get(symbol);held.quantity-=quantity;if(held.quantity==0)positions.remove(symbol);}
            if(cash.signum()<0)throw data(day,"체결 후 현금이 음수입니다.");
            trades.add(new Trade(day,signal.date(),symbol,buy?"BUY":"SELL",quantity,price,fee,tax,reason,cash));
            event(day,symbol,"EXECUTION",true,true,reason);
        }
        void event(LocalDate date,String symbol,String phase,boolean ready,boolean matched,String reason){evaluations.add(new Evaluation(date,symbol,phase,ready,matched,reason,List.of()));}
        int index(String symbol,LocalDate day){return indices.get(symbol).get(day);}
        BigDecimal unit(String symbol,LocalDate day,boolean open){return buyUnit(price(symbol,day,open),request.slippageRate(),request.commissionRate());}
        BigDecimal valuation(LocalDate day,boolean open){BigDecimal value=cash;for(var held:positions.entrySet())value=value.add(price(held.getKey(),day,open).multiply(BigDecimal.valueOf(held.getValue().quantity),MC),MC);return value;}
        BigDecimal price(String symbol,LocalDate day,boolean open){var bar=bar(symbol,day);return open?bar.getOpenPrice():bar.getClosePrice();}
        CandleEntity bar(String symbol,LocalDate day){var bar=prices.getOrDefault(symbol,Map.of()).get(day);if(bar==null)throw data(day,"보유 평가 또는 체결에 필요한 캔들이 없습니다: "+symbol);return bar;}
    }
    private static boolean positive(BigDecimal price){return price!=null&&price.signum()>0;}
    private static UserStrategyBacktestEngine.DataException data(LocalDate day,String message){return new UserStrategyBacktestEngine.DataException(day==null?null:day.atStartOfDay(UserStrategyBacktestEngine.MARKET_ZONE).toInstant().toEpochMilli(),message);}
}
