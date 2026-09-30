package com.tossinvest.tossinvestbackend.strategypaper;

import com.tossinvest.tossinvestbackend.marketdata.*;
import com.tossinvest.tossinvestbackend.stockinfo.StockInfoService;
import com.tossinvest.tossinvestbackend.strategy.StrategyBar;
import com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestEngine;
import org.springframework.stereotype.Component;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;

/** Fetches a private, untrimmed input series. Never calls the shared cache's destructive rolling collector. */
@Component
public class PaperMarketData {
    private final MarketDataService market;
    private final StockInfoService stocks;
    public PaperMarketData(MarketDataService market,StockInfoService stocks){this.market=market;this.stocks=stocks;}
    public String verifyStock(String symbol) {
        var response=stocks.getStocks(symbol);
        if(response!=null && response.getResult()!=null) for(var stock:response.getResult()) {
            if(symbol.equals(stock.getSymbol()) && "KRW".equals(stock.getCurrency()) && Set.of("KOSPI","KOSDAQ").contains(stock.getMarket()==null?"":stock.getMarket())
                    && (Boolean.TRUE.equals(stock.getIsCommonShare()) || "STOCK".equals(stock.getSecurityType()))) return stock.getMarket();
        }
        throw new IllegalArgumentException("시세 제공업체에서 국내 주식·원화·시장을 확인하지 못했습니다.");
    }
    public List<StrategyBar> fetch(String symbol,LocalDate lastDate,Instant now) {
        var today=now.atZone(UserStrategyBacktestEngine.MARKET_ZONE).toLocalDate();
        var result=new TreeMap<LocalDate,StrategyBar>();LocalDate before=null;
        for(int page=0;page<15;page++) {
            CandleResponse response=before==null?market.getCandles(symbol,"1d",200):market.getCandlesWithDateBefore(symbol,"1d",200,before+"T00:00:00+09:00");
            if(response==null || response.getResult()==null || response.getResult().getCandles()==null) throw new IllegalArgumentException("일봉 응답이 비어 있습니다.");
            var candles=response.getResult().getCandles();
            if(candles.isEmpty()) break;
            LocalDate oldest=null;
            for(var c:candles) {
                long ts=parse(c.getTimestamp());LocalDate day=UserStrategyBacktestEngine.date(ts);
                if(oldest==null || day.isBefore(oldest)) oldest=day;
                if(!day.isBefore(today)) continue;
                if(!"KRW".equals(c.getCurrency())) throw new IllegalArgumentException("일봉 통화를 원화로 확인할 수 없습니다.");
                long canonical=day.atStartOfDay(UserStrategyBacktestEngine.MARKET_ZONE).toInstant().toEpochMilli();
                var b=new StrategyBar(canonical,decimal(c.getOpenPrice()),decimal(c.getClosePrice()),decimal(c.getVolume()),decimal(c.getHighPrice()),decimal(c.getLowPrice()));
                var old=result.putIfAbsent(day,b);
                if(old!=null && !old.equals(b)) throw new IllegalArgumentException("겹치는 일봉의 가격이 다릅니다.");
            }
            if(lastDate!=null && result.containsKey(lastDate)) break;
            if(oldest==null || before!=null && !oldest.isBefore(before)) throw new IllegalArgumentException("일봉 페이지가 과거 방향으로 진행하지 않습니다.");
            before=oldest;
            // Short pages are exhausted, regardless of provider cursor formatting.
            if(candles.size()<200) break;
        }
        if(lastDate!=null && !result.isEmpty() && result.lastKey().isAfter(lastDate) && !result.containsKey(lastDate))
            throw new IllegalArgumentException("이전 처리 날짜까지 일봉을 받지 못했습니다. 누락 구간을 확인해 주세요.");
        return List.copyOf(result.values());
    }
    private static long parse(String text) {try{return OffsetDateTime.parse(text).toInstant().toEpochMilli();}catch(Exception e){return Long.parseLong(text);}}
    private static BigDecimal decimal(String text){if(text==null)throw new IllegalArgumentException("일봉 값이 누락되었습니다.");return new BigDecimal(text);}
}
