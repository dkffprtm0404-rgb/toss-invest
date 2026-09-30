package com.tossinvest.tossinvestbackend.strategypaper;

import com.tossinvest.tossinvestbackend.strategy.*;
import com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import lombok.extern.slf4j.Slf4j;
import java.time.*;
import java.util.*;

@Service
@Slf4j
public class StrategyPaperService {
    private final PaperRunRepository runs;
    private final SavedStrategyService strategies;
    private final StrategyJson json;
    private final StrategyPaperEngine engine;
    private final PaperMarketData market;
    private final TransactionTemplate tx;
    public StrategyPaperService(PaperRunRepository runs,SavedStrategyService strategies,StrategyJson json,StrategyPaperEngine engine,PaperMarketData market,PlatformTransactionManager manager) {
        this.runs=runs;this.strategies=strategies;this.json=json;this.engine=engine;this.market=market;this.tx=new TransactionTemplate(manager);
    }
    public record Detail(long id,String status,Instant createdAt,Instant updatedAt,String market,PaperDefinition definition,
                         SavedStrategyService.SavedVersion strategy,PaperState state,int storedBarCount,int tradeCount,int equityCount,int logCount) { }
    public Detail create(PaperDefinition request) {
        request.validate();
        var existing=runs.findByRequestId(request.requestId());
        if(existing.isPresent()) return same(existing.get(),request);
        // Validate before making a remote request.
        request.validateStrategy(strategies.version(request.strategyId(),request.version()).strategy());
        String verifiedMarket;
        try{verifiedMarket=market.verifyStock(request.symbol());}
        catch(IllegalArgumentException ex){throw validation("symbol",ex.getMessage());}
        catch(Exception ex){throw new MarketUnavailable();}
        try {
            return tx.execute(status->{
                strategies.requireForUpdate(request.strategyId());
                var duplicate=runs.findByRequestId(request.requestId());if(duplicate.isPresent())return same(duplicate.get(),request);
                if(runs.existsByActiveKey(request.strategyId()+":"+request.symbol()))throw new PaperConflict("이 전략·종목에는 미종료 실행이 있습니다.");
                var strategy=strategies.version(request.strategyId(),request.version());request.validateStrategy(strategy.strategy());
                Instant now=SavedStrategyService.now();
                var state=new PaperState(request.initialCapital(),now.atZone(UserStrategyBacktestEngine.MARKET_ZONE).toLocalDate().plusDays(1));
                state.log(now,"CREATED","실행을 시작했습니다. 최초 판단 가능일 이후 확정 일봉을 기다립니다.",null);
                return detail(runs.saveAndFlush(new PaperRunEntity(request,verifiedMarket,json.write(request),json.write(strategy),json.write(state),now)));
            });
        } catch(DataIntegrityViolationException ex) {
            var duplicate=runs.findByRequestId(request.requestId());if(duplicate.isPresent())return same(duplicate.get(),request);
            throw new PaperConflict("같은 전략·종목의 시작 요청이 겹쳤습니다. 목록을 새로고침해 주세요.");
        }
    }
    public Detail get(long id){return tx.execute(status->detail(require(id,false)));}
    public List<Detail> list(Long strategyId,String symbol,String status,int page,int size) {
        if(status!=null&&!Set.of("RUNNING","STOPPING","STOPPED").contains(status))throw validation("status","지원하지 않는 상태입니다.");
        var pageable=SavedStrategyService.page(page,size);
        return tx.execute(t->runs.search(strategyId,symbol,status,pageable).stream().map(this::detail).toList());
    }
    public Detail refresh(long id) {
        var current=get(id);if("STOPPED".equals(current.status()))return current;
        List<StrategyBar> bars;
        try{bars=market.fetch(current.definition().symbol(),current.state().lastProcessedDate,Instant.now());}
        catch(IllegalArgumentException ex){return failure(id,"INVALID_DATA",ex.getMessage());}
        catch(WebClientResponseException ex){
            int status=ex.getStatusCode().value();
            log.warn("[전략 모의매매] 일봉 조회 실패 run={} symbol={} HTTP={}",id,current.definition().symbol(),status);
            if(status==401 || status==403) return failure(id,"MARKET_AUTH_FAILED","시세 API 인증·접근 권한을 확인해 주세요. (HTTP "+status+")");
            if(status==429) return failure(id,"MARKET_RATE_LIMITED","시세 API 호출 한도를 초과했습니다. 잠시 후 다시 갱신해 주세요. (HTTP 429)");
            if(status>=400 && status<500) return failure(id,"MARKET_REQUEST_REJECTED","시세 API가 일봉 조회 요청을 거절했습니다. 날짜 등 요청 파라미터를 확인해야 합니다. (HTTP "+status+")");
            return failure(id,"MARKET_UNAVAILABLE","시세 제공업체 응답 오류입니다. 잠시 후 다시 갱신해 주세요. (HTTP "+status+")");
        }
        catch(WebClientRequestException ex){
            log.warn("[전략 모의매매] 일봉 연결 실패 run={} symbol={} type={}",id,current.definition().symbol(),ex.getClass().getSimpleName());
            return failure(id,"MARKET_UNAVAILABLE","시세 API에 연결하지 못했습니다. 네트워크 상태를 확인하고 다시 갱신해 주세요.");
        }
        catch(Exception ex){
            log.warn("[전략 모의매매] 일봉 처리 실패 run={} symbol={} type={}",id,current.definition().symbol(),ex.getClass().getSimpleName());
            return failure(id,"MARKET_ERROR","일봉 응답 처리 중 오류가 발생했습니다. 서버 진단 로그를 확인해 주세요.");
        }
        try{return process(id,bars,Instant.now());}
        catch(IllegalArgumentException ex){return failure(id,"INVALID_DATA",ex.getMessage());}
    }
    public Detail process(long id,List<StrategyBar> bars,Instant observedAt) {
        return tx.execute(t->{var row=require(id,true);var s=state(row);
            var strategy=json.stored(row.getStrategyJson(),SavedStrategyService.SavedVersion.class);
            var definition=json.stored(row.getDefinitionJson(),PaperDefinition.class);
            engine.process(strategy.strategy(),definition,s,bars,observedAt);
            checkpoint(row,s,observedAt);return detail(row);});
    }
    public Detail stop(long id) {return tx.execute(t->{var row=require(id,true);var s=state(row);Instant now=SavedStrategyService.now();s.stop(now);checkpoint(row,s,now);return detail(row);});}
    public void delete(long id){tx.executeWithoutResult(t->{var row=require(id,true);if(!"STOPPED".equals(row.getStatus()))throw new PaperConflict("종료된 실행만 삭제할 수 있습니다.");runs.delete(row);});}
    public List<?> history(long id,String type,int page,int size) {
        SavedStrategyService.page(page,size);
        return tx.execute(t->{var s=state(require(id,false));List<?> values=switch(type){case "trades"->s.trades;case "equity"->s.curve;case "logs"->s.logs;default->throw validation("type","지원하지 않는 이력입니다.");};
            long offset=(long)page*size;int end=(int)Math.max(0,values.size()-offset),start=Math.max(0,end-size);
            var result=new ArrayList<>(values.subList(start,end));Collections.reverse(result);return result;});
    }
    private Detail failure(long id,String code,String message){return tx.execute(t->{var row=require(id,true);var s=state(row);if(!"STOPPED".equals(s.status)){Instant now=SavedStrategyService.now();s.dataStatus=code;s.log(now,code,message,s.lastProcessedDate);checkpoint(row,s,now);}return detail(row);});}
    private void checkpoint(PaperRunEntity row,PaperState state,Instant now){row.checkpoint(state,json.write(state),now);runs.saveAndFlush(row);}
    private PaperRunEntity require(long id,boolean lock){return (lock?runs.findForUpdate(id):runs.findById(id)).orElseThrow(()->new SavedStrategyService.NotFoundException("모의매매 실행을 찾을 수 없습니다."));}
    private PaperState state(PaperRunEntity row){return json.stored(row.getStateJson(),PaperState.class);}
    private Detail same(PaperRunEntity row,PaperDefinition request){
        var prior=json.stored(row.getDefinitionJson(),PaperDefinition.class);
        boolean equal=prior.strategyId().equals(request.strategyId()) && prior.version().equals(request.version())
                && prior.symbol().equals(request.symbol()) && prior.executionMode()==request.executionMode()
                && prior.initialCapital().compareTo(request.initialCapital())==0
                && prior.allocationRate().compareTo(request.allocationRate())==0
                && prior.commissionRate().compareTo(request.commissionRate())==0
                && prior.taxRate().compareTo(request.taxRate())==0
                && prior.slippageRate().compareTo(request.slippageRate())==0;
        if(!equal)throw new PaperConflict("같은 요청 식별자에 다른 설정을 사용할 수 없습니다.");return detail(row);
    }
    private Detail detail(PaperRunEntity row){
        var s=state(row);int barCount=s.bars.size(),tradeCount=s.trades.size(),equityCount=s.curve.size(),logCount=s.logs.size();
        s.bars=List.of();s.trades=tail(s.trades,20);s.curve=tail(s.curve,100);s.logs=tail(s.logs,20);
        return new Detail(row.getId(),row.getStatus(),row.getCreatedAt(),row.getUpdatedAt(),row.getMarket(),json.stored(row.getDefinitionJson(),PaperDefinition.class),
                json.stored(row.getStrategyJson(),SavedStrategyService.SavedVersion.class),s,barCount,tradeCount,equityCount,logCount);
    }
    private static <T> List<T> tail(List<T> list,int count){return List.copyOf(list.subList(Math.max(0,list.size()-count),list.size()));}
    private static StrategyValidationException validation(String path,String message){return new StrategyValidationException(List.of(new StrategyValidator.Issue(path,"INVALID",message)));}
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
    public static class MarketUnavailable extends RuntimeException {public MarketUnavailable(){super("종목 확인에 실패했습니다. 시세 API 인증·연결 상태를 확인해 주세요.");}}
}
