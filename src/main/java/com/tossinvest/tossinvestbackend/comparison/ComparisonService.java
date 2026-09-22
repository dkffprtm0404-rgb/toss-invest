package com.tossinvest.tossinvestbackend.comparison;

import com.tossinvest.tossinvestbackend.backtest.*;
import com.tossinvest.tossinvestbackend.composite.*;
import com.tossinvest.tossinvestbackend.portfolio.*;
import com.tossinvest.tossinvestbackend.strategy.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

/** Executes saved versions against one captured input. History reads never invoke an engine. */
@Service @Transactional(readOnly=true)
public class ComparisonService {
    private final SavedStrategyService strategies;
    private final CandleRepository candles;
    private final UserStrategyBacktestEngine singleEngine;
    private final PortfolioEngine portfolioEngine;
    private final CompositeEngine compositeEngine;
    private final ComparisonRepository comparisons;
    private final StrategyJson json;

    public ComparisonService(SavedStrategyService strategies,CandleRepository candles,UserStrategyBacktestEngine singleEngine,
            PortfolioEngine portfolioEngine,CompositeEngine compositeEngine,ComparisonRepository comparisons,StrategyJson json) {
        this.strategies=strategies;this.candles=candles;this.singleEngine=singleEngine;
        this.portfolioEngine=portfolioEngine;this.compositeEngine=compositeEngine;this.comparisons=comparisons;this.json=json;
    }
    public enum Type { SINGLE, PORTFOLIO, COMPOSITE }
    public record Selection(Long id,Integer version) { }
    public record Request(List<Selection> strategies,String symbol,LocalDate startDate,LocalDate endDate,
            UserStrategyBacktestRequest.ExecutionMode executionMode,BigDecimal initialCapital,BigDecimal commissionRate,
            BigDecimal taxRate,BigDecimal slippageRate,PortfolioRequest.Universe universe) {
        UserStrategyBacktestRequest single(SavedStrategyService.SavedVersion version) {
            return new UserStrategyBacktestRequest(version.strategy(),symbol,startDate,endDate,executionMode);
        }
        PortfolioRequest portfolio(int version) {
            return new PortfolioRequest(version,startDate,endDate,executionMode,initialCapital,commissionRate,taxRate,slippageRate,universe);
        }
        CompositeRequest composite(int version) {
            return new CompositeRequest(version,startDate,endDate,executionMode,initialCapital,commissionRate,taxRate,slippageRate,symbol,universe);
        }
    }
    public record Metrics(BigDecimal returnRate,BigDecimal maxDrawdown,int tradeCount,BigDecimal winRate,BigDecimal sharpe,BigDecimal finalEquity) { }
    public record Point(LocalDate date,BigDecimal value) { }
    public record Entry(SavedStrategyService.SavedVersion strategy,String engineVersion,String status,SavedBacktestService.RunError error,
            UserStrategyBacktestResult single,PortfolioResult account,CompositeResult composite,Metrics metrics,List<Point> curve) { }
    public record Snapshot(int schemaVersion,Type type,Request execution,PortfolioService.Data data,List<Entry> entries) { }
    public record Detail(long id,Instant createdAt,Snapshot snapshot) { }
    public record Summary(long id,Instant createdAt,String type,List<String> strategyNames) { }

    @Transactional
    public Detail run(Request request) {
        if(request==null)throw new StrategyJson.InvalidRequestException();
        if(request.strategies()==null||request.strategies().size()<2||request.strategies().size()>6)
            invalid("strategies","서로 다른 저장 전략을 2~6개 선택해 주세요.");
        Set<Long> ids=new TreeSet<>();
        for(var item:request.strategies()) {
            if(item==null||item.id()==null||item.id()<1)invalid("strategies","전략 번호를 확인해 주세요.");
            SavedStrategyService.requirePositive(item.version(),"strategies.version");
            if(!ids.add(item.id()))invalid("strategies","같은 전략을 중복 선택할 수 없습니다.");
        }
        // Consistent lock order also serializes creation with strategy deletion/update.
        ids.forEach(strategies::requireForUpdate);
        var versions=request.strategies().stream().map(s->strategies.version(s.id(),s.version())).toList();
        Type type=type(versions.get(0).strategy());
        if(versions.stream().anyMatch(s->type(s.strategy())!=type))invalid("strategies","같은 유형의 전략끼리 비교해 주세요.");
        boolean universe=versions.stream().anyMatch(s->needsUniverse(s.strategy()));
        if(type==Type.SINGLE) {
            if(request.initialCapital()!=null||request.commissionRate()!=null||request.taxRate()!=null||request.slippageRate()!=null)
                invalid("costs","단일 종목 비교는 자금·비용을 계산하지 않습니다. 해당 입력을 제거해 주세요.");
        }
        if(!universe&&request.universe()!=null)invalid("universe","이 비교 유형은 시장 자료를 사용하지 않습니다.");
        if(type==Type.PORTFOLIO&&request.symbol()!=null)invalid("symbol","포트폴리오는 공통 시장 자료의 종목군을 사용합니다. 단일 종목 입력을 제거해 주세요.");
        for(var saved:versions) switch(type) {
            case SINGLE -> request.single(saved).requireValid();
            case PORTFOLIO -> request.portfolio(saved.version()).requireValid(saved.strategy());
            case COMPOSITE -> request.composite(saved.version()).requireValid(saved.strategy());
        }
        // All input contracts have passed before accessing candle history or saving anything.
        var symbols=universe?request.universe().members().stream().map(PortfolioRequest.Member::symbol).distinct().sorted().toList():List.of(request.symbol());
        long first=universe?request.universe().tradingDates().get(0).atStartOfDay(UserStrategyBacktestEngine.MARKET_ZONE).toInstant().toEpochMilli():Long.MIN_VALUE;
        long end=request.endDate().plusDays(1).atStartOfDay(UserStrategyBacktestEngine.MARKET_ZONE).toInstant().toEpochMilli();
        if(candles.countBySymbolInAndTimestampGreaterThanEqualAndTimestampLessThan(symbols,first,end)>1_000_000)
            invalid("data","비교당 최대 100만 캔들입니다. 기간·종목 범위를 줄여 주세요.");
        var input=List.copyOf(candles.findBySymbolInAndTimestampGreaterThanEqualAndTimestampLessThanOrderBySymbolAscTimestampAsc(symbols,first,end));
        if(input.size()>1_000_000)invalid("data","비교당 최대 100만 캔들입니다.");
        var captured=input.stream().map(c->new SavedBacktestService.CandleSnapshot(c.getSymbol(),c.getTimestamp(),c.getOpenPrice(),
                c.getHighPrice(),c.getLowPrice(),c.getClosePrice(),c.getVolume())).toList();
        var data=new PortfolioService.Data("LOCAL_CANDLE_CACHE"+(universe?" / "+request.universe().source():" / "+request.symbol()),hash(json.write(captured)),captured);
        var entries=versions.stream().map(s->execute(type,s,request,input)).toList();
        var snapshot=new Snapshot(1,type,request,data,entries);
        var now=SavedStrategyService.now();
        var row=comparisons.save(new ComparisonEntity(now,type.name(),json.write(versions.stream().map(v->v.strategy().name()).toList()),json.write(snapshot),ids));
        return new Detail(row.getId(),now,snapshot);
    }

    private Entry execute(Type type,SavedStrategyService.SavedVersion saved,Request request,List<CandleEntity> input) {
        String engine=switch(type) {case SINGLE->"USER_STRATEGY_DAILY_V1";case PORTFOLIO->"RELATIVE_STRENGTH_DAILY_V1";case COMPOSITE->"COMPOSITE_DAILY_V1";};
        try {
            if(type!=Type.SINGLE&&input.stream().noneMatch(c->{
                var day=UserStrategyBacktestEngine.date(c.getTimestamp());
                return (request.symbol()==null||request.symbol().equals(c.getSymbol()))&&!day.isBefore(request.startDate())&&!day.isAfter(request.endDate());
            })) return new Entry(saved,engine,"NO_DATA",new SavedBacktestService.RunError("NO_DATA","요청 기간에 실행 대상의 가격 데이터가 없습니다.",null),null,null,null,null,List.of());
            if(type==Type.SINGLE) {
                var result=singleEngine.run(request.single(saved),input);
                var m=result.metrics();
                var metrics=(result.status().equals("NO_DATA")||result.status().equals("INSUFFICIENT_DATA"))?null:
                        new Metrics(m.sumTradeReturnRate(),m.tradeReturnMaxDrawdown(),m.closedTrades(),m.winRate(),m.tradeSharpeRatio(),null);
                return new Entry(saved,engine,result.status(),null,result,null,null,metrics,singleCurve(result));
            }
            CompositeResult composite=type==Type.COMPOSITE?compositeEngine.run(saved.strategy(),request.composite(saved.version()),input):null;
            var account=composite!=null?composite.account():portfolioEngine.run(saved.strategy(),request.portfolio(saved.version()),input);
            if(account.status().equals("NO_DATA")||account.status().equals("INSUFFICIENT_DATA"))
                return new Entry(saved,engine,account.status(),null,null,account,composite,null,List.of());
            if(account.trades().isEmpty()) {
                var entryChecks=composite==null?List.<CompositeResult.Evaluation>of():composite.evaluations().stream().filter(e->e.phase().equals("ENTRY")).toList();
                boolean unreadyRules=!entryChecks.isEmpty()&&entryChecks.stream().noneMatch(CompositeResult.Evaluation::ready);
                boolean unreadySelection=!account.selections().isEmpty()&&account.selections().stream().allMatch(s->s.ranked().isEmpty()&&!s.excluded().isEmpty()
                        &&s.excluded().stream().allMatch(e->e.reason().contains("준비 이력 부족")||e.reason().contains("캔들 누락")));
                if(unreadyRules||unreadySelection) return new Entry(saved,engine,"INSUFFICIENT_DATA",
                        new SavedBacktestService.RunError("INSUFFICIENT_DATA","진입 또는 종목 선정에 필요한 준비 데이터가 부족합니다. 원본 판정 근거를 확인하세요.",null),
                        null,account,composite,null,List.of());
            }
            var metrics=new Metrics(account.totalReturn(),account.maxDrawdown(),account.trades().size(),null,null,account.finalEquity());
            List<Point> curve=new ArrayList<>();
            curve.add(new Point(request.startDate(),BigDecimal.ZERO));
            account.equity().forEach(p->curve.add(new Point(p.date(),p.equity().divide(account.initialCapital(),MathContext.DECIMAL128).subtract(BigDecimal.ONE))));
            return new Entry(saved,engine,account.status(),null,null,account,composite,metrics,List.copyOf(curve));
        } catch(UserStrategyBacktestEngine.DataException ex) {
            return new Entry(saved,engine,"FAILED",new SavedBacktestService.RunError("INVALID_DATA",ex.getMessage(),ex.getTimestamp()),null,null,null,null,List.of());
        }
    }
    private static List<Point> singleCurve(UserStrategyBacktestResult result) {
        if(result.actualStartDate()==null||result.status().equals("INSUFFICIENT_DATA"))return List.of();
        var points=new ArrayList<Point>();BigDecimal sum=BigDecimal.ZERO;
        points.add(new Point(result.actualStartDate(),sum));
        for(var trade:result.trades()) {
            sum=sum.add(trade.returnRate());
            points.add(new Point(Instant.ofEpochMilli(trade.exit().executionTimestamp()).atZone(UserStrategyBacktestEngine.MARKET_ZONE).toLocalDate(),sum));
        }
        if(result.actualEndDate()!=null)points.add(new Point(result.actualEndDate(),sum));
        return List.copyOf(points);
    }
    public Detail get(long id) {
        var row=comparisons.findById(id).orElseThrow(()->new SavedStrategyService.NotFoundException("Comparison not found."));
        return new Detail(row.getId(),row.getCreatedAt(),json.stored(row.getSnapshotJson(),Snapshot.class));
    }
    public List<Summary> list(int page,int size) {
        return comparisons.summaries(SavedStrategyService.page(page,size)).stream()
                .map(r->new Summary(r.getId(),r.getCreatedAt(),r.getType(),List.of(json.stored(r.getStrategyNamesJson(),String[].class)))).toList();
    }
    @Transactional public void delete(long id) {
        var row=comparisons.findForUpdate(id).orElseThrow(()->new SavedStrategyService.NotFoundException("Comparison not found."));
        comparisons.delete(row);
    }
    private static Type type(StrategyDefinition strategy) {return strategy.composition()!=null?Type.COMPOSITE:strategy.portfolio()!=null?Type.PORTFOLIO:Type.SINGLE;}
    private static boolean needsUniverse(StrategyDefinition strategy) {return strategy.portfolio()!=null||(strategy.composition()!=null&&strategy.composition().selection()!=null);}
    private static void invalid(String path,String message) {throw new StrategyValidationException(List.of(new StrategyValidator.Issue(path,"INVALID",message)));}
    private static String hash(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
}
