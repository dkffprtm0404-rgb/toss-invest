package com.tossinvest.tossinvestbackend.portfolio;

import com.tossinvest.tossinvestbackend.backtest.*;
import com.tossinvest.tossinvestbackend.strategy.StrategyDefinition;
import org.springframework.stereotype.Component;
import java.math.*;
import java.time.*;
import java.util.*;
import static com.tossinvest.tossinvestbackend.portfolio.PortfolioResult.*;
import static com.tossinvest.tossinvestbackend.strategy.StrategyDefinition.*;
import static com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestRequest.ExecutionMode.*;

/** Deterministic daily accounting on the supplied historical universe and exchange calendar. */
@Component
public class PortfolioEngine {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal ONE = BigDecimal.ONE, ZERO = BigDecimal.ZERO;
    private static final class Position {
        long quantity; BigDecimal average;
        Position(long quantity, BigDecimal average) { this.quantity=quantity; this.average=average; }
    }
    private record Signal(LocalDate date, List<String> targets, Set<String> stops) { }
    private record Candidate(String symbol, BigDecimal momentum, BigDecimal close, BigDecimal sma) { }

    public PortfolioResult run(StrategyDefinition strategy, PortfolioRequest request, List<CandleEntity> input) {
        request.requireValid(strategy);
        return new Run(strategy, request, input).execute();
    }
    private static final class Run {
        final StrategyDefinition strategy; final RelativeStrength rule; final PortfolioRequest request;
        final List<LocalDate> calendar; final Map<String,Map<LocalDate,CandleEntity>> prices = new HashMap<>();
        final Map<String,List<PortfolioRequest.Member>> members = new TreeMap<>();
        final Map<String,Position> positions = new TreeMap<>();
        final List<EquityPoint> equity = new ArrayList<>(); final List<Trade> trades = new ArrayList<>();
        final List<Selection> selections = new ArrayList<>();
        BigDecimal cash, peak, maxDrawdown = ZERO; Signal pending;

        Run(StrategyDefinition strategy, PortfolioRequest request, List<CandleEntity> input) {
            this.strategy=strategy; this.rule=strategy.portfolio(); this.request=request; this.calendar=request.universe().tradingDates();
            cash=request.initialCapital(); peak=cash;
            request.universe().members().forEach(m -> members.computeIfAbsent(m.symbol(), k -> new ArrayList<>()).add(m));
            for (var c : input) {
                if (c == null || c.getTimestamp() == null) throw data(null,"캔들 시각이 없습니다.");
                LocalDate day = UserStrategyBacktestEngine.date(c.getTimestamp());
                if (day.isAfter(request.endDate()) || !members.containsKey(c.getSymbol())) continue;
                if (!positive(c.getOpenPrice()) || !positive(c.getClosePrice()) || !positive(c.getHighPrice()) || !positive(c.getLowPrice())
                        || c.getHighPrice().compareTo(c.getOpenPrice().max(c.getClosePrice())) < 0 || c.getLowPrice().compareTo(c.getOpenPrice().min(c.getClosePrice())) > 0
                        || c.getVolume() == null || c.getVolume().signum() < 0) throw data(day,"OHLC 또는 거래량이 올바르지 않습니다: " + c.getSymbol());
                if (prices.computeIfAbsent(c.getSymbol(), k -> new HashMap<>()).put(day,c) != null) throw data(day,"종목의 동일 거래일 캔들이 중복됩니다: " + c.getSymbol());
            }
        }
        PortfolioResult execute() {
            LocalDate last = null;
            for (int i=0;i<calendar.size();i++) {
                LocalDate day = calendar.get(i);
                if (day.isBefore(request.startDate()) || day.isAfter(request.endDate())) continue;
                last=day; Set<String> stoppedToday = new HashSet<>();
                if (pending != null) {
                    stoppedToday.addAll(pending.stops()); fill(pending,day,true); pending=null;
                }
                // Required held prices are checked even on non-rebalance dates.
                valuation(day,false);
                Set<String> stops = new HashSet<>();
                if (strategy.risk() != null && strategy.risk().stopLoss() != null) {
                    for (var e : positions.entrySet()) {
                        BigDecimal threshold = e.getValue().average.multiply(ONE.add(strategy.risk().stopLoss().rate()),MC);
                        if (bar(e.getKey(),day).getClosePrice().compareTo(threshold) <= 0) stops.add(e.getKey());
                    }
                }
                List<String> targets = null;
                if (rebalance(i)) {
                    Selection ranked = rank(i,day);
                    Set<String> blocked = new HashSet<>(stops); blocked.addAll(stoppedToday);
                    List<Excluded> excluded = new ArrayList<>(ranked.excluded());
                    List<Ranked> effective = ranked.ranked().stream().map(candidate -> {
                        if (candidate.selected() && blocked.contains(candidate.symbol())) {
                            excluded.add(new Excluded(candidate.symbol(),"당일 손절 우선 · 재매수 제외"));
                            return new Ranked(candidate.symbol(),candidate.rank(),candidate.returnRate(),candidate.close(),candidate.sma(),false);
                        }
                        return candidate;
                    }).toList();
                    Selection selection = new Selection(day,effective,List.copyOf(excluded)); selections.add(selection);
                    targets = selection.ranked().stream().filter(Ranked::selected).map(Ranked::symbol)
                            .filter(s -> !stops.contains(s) && !stoppedToday.contains(s)).toList();
                }
                if (targets != null || !stops.isEmpty()) {
                    Signal signal = new Signal(day,targets,Set.copyOf(stops));
                    if (request.executionMode() == NEXT_DAY_OPEN) pending=signal; else fill(signal,day,false);
                }
                BigDecimal value=valuation(day,false); peak=peak.max(value);
                maxDrawdown=maxDrawdown.max(ONE.subtract(value.divide(peak,MC)));
                equity.add(new EquityPoint(day,value,cash));
            }
            List<Holding> held = new ArrayList<>();
            if (last != null) for (var e : positions.entrySet()) {
                BigDecimal close=bar(e.getKey(),last).getClosePrice();
                held.add(new Holding(e.getKey(),e.getValue().quantity,e.getValue().average,close,close.multiply(BigDecimal.valueOf(e.getValue().quantity),MC)));
            }
            List<Pending> unfilled = new ArrayList<>();
            if (pending != null) {
                for (String s : pending.stops()) unfilled.add(new Pending(pending.date(),s,"SELL","STOP_LOSS / NO_NEXT_BAR"));
                if (pending.targets()!=null) {
                    for (String s : pending.targets()) unfilled.add(new Pending(pending.date(),s,"TARGET","REBALANCE / NO_NEXT_BAR"));
                    for (String s : positions.keySet()) if (!pending.targets().contains(s) && !pending.stops().contains(s)) unfilled.add(new Pending(pending.date(),s,"SELL","RANK_EXIT / NO_NEXT_BAR"));
                }
            }
            BigDecimal end=equity.isEmpty()?cash:equity.get(equity.size()-1).equity();
            String status=equity.isEmpty()?"NO_DATA":trades.isEmpty()&&unfilled.isEmpty()?"NO_TRADES":"COMPLETED";
            return new PortfolioResult(status,request.initialCapital(),end,end.divide(request.initialCapital(),MC).subtract(ONE),maxDrawdown,cash,
                    List.copyOf(equity),List.copyOf(trades),List.copyOf(selections),List.copyOf(held),List.copyOf(unfilled),List.of(
                    "입력한 시장별 편입 이력과 거래 캘린더 범위만 계산합니다. 전체 시장 자료의 완전성과 생존편향 제거를 보증하지 않습니다.",
                    "상대강도는 달력 개월 수익률, 필터는 종가 > SMA입니다. 동률은 종목 코드 순서입니다.",
                    "종목별 목표 비중은 1 / 상위 종목 수입니다. 부족한 후보 몫과 정수 주수 잔액은 현금입니다.",
                    "매도 후 순위 순서로 현금 범위에서 매수합니다. 수수료·매도세·슬리피지는 입력값만 적용합니다.",
                    "손절은 평균 매입가격 대비 종가 확인입니다. 장중 손절·배당·기업행위·상하한가·유동성 규모 제약은 모델링하지 않습니다.",
                    "평가는 미청산 보유분을 포함합니다. 종료일 강제 청산은 하지 않습니다.",
                    request.executionMode()==NEXT_DAY_OPEN?"신호 다음 입력 거래일 시가 체결입니다. 이후 종가는 수량 결정에 사용하지 않습니다.":"신호 당일 종가 체결은 시뮬레이션 가정입니다."));
        }
        boolean rebalance(int i) { return PortfolioSelection.scheduled(calendar,i,rule.rebalanceTiming()); }
        Selection rank(int index,LocalDate day) { return PortfolioSelection.rank(rule,calendar,members,prices,index); }
        void fill(Signal signal,LocalDate day,boolean open) {
            Map<String,Long> desired=new LinkedHashMap<>();
            if(signal.targets()==null) positions.forEach((s,p)->desired.put(s,p.quantity));
            else {
                BigDecimal budget=valuation(day,open).divide(BigDecimal.valueOf(rule.topN()),MC);
                for(String symbol:signal.targets()) {
                    BigDecimal price=PortfolioCalculations.buyUnit(price(symbol,day,open),request.slippageRate(),request.commissionRate());
                    desired.put(symbol,shares(budget,price));
                }
            }
            signal.stops().forEach(s->desired.put(s,0L));
            for(String symbol:new ArrayList<>(positions.keySet())) {
                long sell=positions.get(symbol).quantity-desired.getOrDefault(symbol,0L);
                if(sell>0) trade(symbol,sell,false,day,signal.date(),open,signal.stops().contains(symbol)?"STOP_LOSS":desired.getOrDefault(symbol,0L)==0?"RANK_EXIT":"REBALANCE");
            }
            for(var e:desired.entrySet()) {
                long owned=positions.containsKey(e.getKey())?positions.get(e.getKey()).quantity:0;
                long buy=e.getValue()-owned;
                if(buy>0) {
                    BigDecimal unit=PortfolioCalculations.buyUnit(price(e.getKey(),day,open),request.slippageRate(),request.commissionRate());
                    buy=Math.min(buy,shares(cash,unit));
                    if(buy>0) trade(e.getKey(),buy,true,day,signal.date(),open,"REBALANCE");
                }
            }
        }
        void trade(String symbol,long quantity,boolean buy,LocalDate day,LocalDate signal,boolean open,String reason) {
            var candle=bar(symbol,day); if(candle.getVolume().signum()==0) throw data(day,"거래량 0인 봉에 체결할 수 없습니다: "+symbol);
            BigDecimal price=PortfolioCalculations.executionPrice(price(symbol,day,open),request.slippageRate(),buy);
            BigDecimal amount=price.multiply(BigDecimal.valueOf(quantity),MC), fee=amount.multiply(request.commissionRate(),MC), tax=buy?ZERO:amount.multiply(request.taxRate(),MC);
            if(buy) {
                cash=cash.subtract(amount.add(fee),MC); Position p=positions.get(symbol);
                if(p==null) positions.put(symbol,new Position(quantity,price));
                else {p.average=p.average.multiply(BigDecimal.valueOf(p.quantity),MC).add(amount).divide(BigDecimal.valueOf(p.quantity+quantity),MC);p.quantity+=quantity;}
            } else { cash=cash.add(amount.subtract(fee).subtract(tax),MC);var p=positions.get(symbol);p.quantity-=quantity;if(p.quantity==0)positions.remove(symbol); }
            if(cash.signum()<0) throw data(day,"체결 후 현금이 음수입니다.");
            trades.add(new Trade(day,signal,symbol,buy?"BUY":"SELL",quantity,price,fee,tax,reason,cash));
        }
        BigDecimal valuation(LocalDate day,boolean open) {BigDecimal value=cash;for(var e:positions.entrySet())value=value.add(price(e.getKey(),day,open).multiply(BigDecimal.valueOf(e.getValue().quantity),MC),MC);return value;}
        BigDecimal price(String symbol,LocalDate day,boolean open) {var b=bar(symbol,day);return open?b.getOpenPrice():b.getClosePrice();}
        CandleEntity bar(String symbol,LocalDate day) {var b=prices.getOrDefault(symbol,Map.of()).get(day);if(b==null)throw data(day,"보유 평가 또는 체결에 필요한 캔들이 없습니다: "+symbol);return b;}
    }
    private static boolean positive(BigDecimal value) {return value!=null&&value.signum()>0;}
    private static long shares(BigDecimal budget,BigDecimal price) {return PortfolioCalculations.shares(budget,price);}
    private static LocalDate week(LocalDate day) {return day.minusDays(day.getDayOfWeek().getValue()-1);}
    private static UserStrategyBacktestEngine.DataException data(LocalDate day,String message) {return new UserStrategyBacktestEngine.DataException(day==null?null:day.atStartOfDay(UserStrategyBacktestEngine.MARKET_ZONE).toInstant().toEpochMilli(),message);}
}
