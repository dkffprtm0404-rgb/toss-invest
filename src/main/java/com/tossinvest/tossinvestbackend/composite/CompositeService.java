package com.tossinvest.tossinvestbackend.composite;

import com.tossinvest.tossinvestbackend.backtest.*;
import com.tossinvest.tossinvestbackend.portfolio.PortfolioRequest;
import com.tossinvest.tossinvestbackend.portfolio.PortfolioService;
import com.tossinvest.tossinvestbackend.strategy.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;

/** Saved v4 runs never consult current rules or candles when read back. */
@Service @Transactional(readOnly=true)
public class CompositeService {
    private final SavedStrategyService strategies;
    private final CandleRepository candles;
    private final CompositeRunRepository runs;
    private final CompositeEngine engine;
    private final StrategyJson json;
    public CompositeService(SavedStrategyService strategies,CandleRepository candles,CompositeRunRepository runs,CompositeEngine engine,StrategyJson json) {
        this.strategies=strategies;this.candles=candles;this.runs=runs;this.engine=engine;this.json=json;
    }
    public record Snapshot(int schemaVersion,String engineVersion,Instant capturedAt,SavedStrategyService.SavedVersion strategy,
                           CompositeRequest execution,PortfolioService.Data data,CompositeResult result,SavedBacktestService.RunError error) { }
    public record Detail(long id,Instant createdAt,String status,Snapshot snapshot) { }
    public record Summary(long id,int version,Instant createdAt,String status) { }

    @Transactional public Detail run(long strategyId,CompositeRequest request) {
        if(request==null)throw new StrategyJson.InvalidRequestException();
        SavedStrategyService.requirePositive(request.version(),"version");strategies.requireForUpdate(strategyId);
        var revision=strategies.versionEntity(strategyId,request.version());var saved=strategies.view(revision);
        request.requireValid(saved.strategy());
        boolean selection=saved.strategy().composition().selection()!=null;
        var symbols=selection?request.universe().members().stream().map(PortfolioRequest.Member::symbol).distinct().sorted().toList():List.of(request.symbol());
        LocalDate firstDate=selection?request.universe().tradingDates().get(0):LocalDate.of(1900,1,1);
        long first=firstDate.atStartOfDay(UserStrategyBacktestEngine.MARKET_ZONE).toInstant().toEpochMilli();
        long end=request.endDate().plusDays(1).atStartOfDay(UserStrategyBacktestEngine.MARKET_ZONE).toInstant().toEpochMilli();
        if(candles.countBySymbolInAndTimestampGreaterThanEqualAndTimestampLessThan(symbols,first,end)>1000000)
            throw new StrategyValidationException(List.of(new StrategyValidator.Issue("data","TOO_LARGE","실행당 최대 100만 캔들입니다. 기간·종목 범위를 줄여 주세요.")));
        var input=candles.findBySymbolInAndTimestampGreaterThanEqualAndTimestampLessThanOrderBySymbolAscTimestampAsc(symbols,first,end);
        var captured=input.stream().map(c->new SavedBacktestService.CandleSnapshot(c.getSymbol(),c.getTimestamp(),c.getOpenPrice(),c.getHighPrice(),c.getLowPrice(),c.getClosePrice(),c.getVolume())).toList();
        Instant now=SavedStrategyService.now();
        var data=new PortfolioService.Data("LOCAL_CANDLE_CACHE"+(selection?" / "+request.universe().source():" / "+request.symbol()),hash(json.write(captured)),captured);
        CompositeResult result=null;SavedBacktestService.RunError error=null;String status;
        try {result=engine.run(saved.strategy(),request,input);status=result.account().status();}
        catch(UserStrategyBacktestEngine.DataException ex){status="FAILED";error=new SavedBacktestService.RunError("INVALID_DATA",ex.getMessage(),ex.getTimestamp());}
        var snapshot=new Snapshot(1,"COMPOSITE_DAILY_V1",now,saved,request,data,result,error);
        var row=runs.save(new CompositeRunEntity(revision,now,status,json.write(snapshot)));
        return new Detail(row.getId(),now,status,snapshot);
    }
    public Detail get(long id) {
        var row=runs.findById(id).orElseThrow(()->new SavedStrategyService.NotFoundException("Composite run not found."));
        return new Detail(row.getId(),row.getCreatedAt(),row.getStatus(),json.stored(row.getSnapshotJson(),Snapshot.class));
    }
    public List<Summary> list(long id,int page,int size) {
        strategies.requireExists(id);
        return runs.summaries(id,SavedStrategyService.page(page,size)).stream().map(r->new Summary(r.getId(),r.getVersion(),r.getCreatedAt(),r.getStatus())).toList();
    }
    @Transactional public void delete(long id){if(runs.deleteRun(id)==0)throw new SavedStrategyService.NotFoundException("Composite run not found.");}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
}
