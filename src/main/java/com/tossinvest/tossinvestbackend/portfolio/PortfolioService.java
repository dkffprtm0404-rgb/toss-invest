package com.tossinvest.tossinvestbackend.portfolio;

import com.tossinvest.tossinvestbackend.backtest.*;
import com.tossinvest.tossinvestbackend.strategy.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;

@Service @Transactional(readOnly=true)
public class PortfolioService {
    private final SavedStrategyService strategies;private final CandleRepository candles;private final PortfolioRunRepository runs;
    private final PortfolioEngine engine;private final StrategyJson json;
    public PortfolioService(SavedStrategyService strategies,CandleRepository candles,PortfolioRunRepository runs,PortfolioEngine engine,StrategyJson json) {
        this.strategies=strategies;this.candles=candles;this.runs=runs;this.engine=engine;this.json=json;
    }
    public record Data(String source,String sha256,List<SavedBacktestService.CandleSnapshot> candles) { }
    public record Snapshot(int schemaVersion,String engineVersion,Instant capturedAt,SavedStrategyService.SavedVersion strategy,
                           PortfolioRequest execution,Data data,PortfolioResult result,SavedBacktestService.RunError error) { }
    public record Detail(long id,Instant createdAt,String status,Snapshot snapshot) { }
    public record Summary(long id,int version,Instant createdAt,String status) { }

    @Transactional public Detail run(long strategyId,PortfolioRequest request) {
        if(request==null)throw new StrategyJson.InvalidRequestException();
        SavedStrategyService.requirePositive(request.version(),"version");strategies.requireForUpdate(strategyId);
        var revision=strategies.versionEntity(strategyId,request.version());var saved=strategies.view(revision);request.requireValid(saved.strategy());
        var symbols=request.universe().members().stream().map(PortfolioRequest.Member::symbol).distinct().sorted().toList();
        long first=request.universe().tradingDates().get(0).atStartOfDay(UserStrategyBacktestEngine.MARKET_ZONE).toInstant().toEpochMilli();
        long end=request.endDate().plusDays(1).atStartOfDay(UserStrategyBacktestEngine.MARKET_ZONE).toInstant().toEpochMilli();
        // Allows 3000 symbols with 200-session warmup plus an execution window.
        if(candles.countBySymbolInAndTimestampGreaterThanEqualAndTimestampLessThan(symbols,first,end)>1000000)
            throw new StrategyValidationException(List.of(new StrategyValidator.Issue("universe","TOO_LARGE","실행당 최대 100만 캔들입니다. 기간·종목 범위를 줄여 주세요.")));
        var input=candles.findBySymbolInAndTimestampGreaterThanEqualAndTimestampLessThanOrderBySymbolAscTimestampAsc(symbols,first,end);
        var captured=input.stream().map(c->new SavedBacktestService.CandleSnapshot(c.getSymbol(),c.getTimestamp(),c.getOpenPrice(),c.getHighPrice(),c.getLowPrice(),c.getClosePrice(),c.getVolume())).toList();
        Instant now=SavedStrategyService.now();var data=new Data("LOCAL_CANDLE_CACHE / "+request.universe().source(),hash(json.write(captured)),captured);
        PortfolioResult result=null;SavedBacktestService.RunError error=null;String status;
        try {result=engine.run(saved.strategy(),request,input);status=result.status();}
        catch(UserStrategyBacktestEngine.DataException e) {status="FAILED";error=new SavedBacktestService.RunError("INVALID_DATA",e.getMessage(),e.getTimestamp());}
        var snapshot=new Snapshot(1,"RELATIVE_STRENGTH_DAILY_V1",now,saved,request,data,result,error);
        var row=runs.save(new PortfolioRunEntity(revision,now,status,json.write(snapshot)));
        return new Detail(row.getId(),now,status,snapshot);
    }
    public Detail get(long id) {var row=runs.findById(id).orElseThrow(()->new SavedStrategyService.NotFoundException("Portfolio run not found."));return new Detail(row.getId(),row.getCreatedAt(),row.getStatus(),json.stored(row.getSnapshotJson(),Snapshot.class));}
    public List<Summary> list(long id,int page,int size) {strategies.requireExists(id);return runs.summaries(id,SavedStrategyService.page(page,size)).stream().map(r->new Summary(r.getId(),r.getVersion(),r.getCreatedAt(),r.getStatus())).toList();}
    @Transactional public void delete(long id) {if(runs.deleteRun(id)==0)throw new SavedStrategyService.NotFoundException("Portfolio run not found.");}
    private static String hash(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
