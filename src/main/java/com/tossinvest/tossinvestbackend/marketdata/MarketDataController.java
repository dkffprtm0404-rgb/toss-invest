package com.tossinvest.tossinvestbackend.marketdata;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MarketDataController {

    private final MarketDataService marketDataService;

    /**
     * symbols: 콤마로 구분된 종목 심볼. 예: ?symbols=005930,000660
     */
    @GetMapping("/api/prices")
    public PriceResponse getPrices(@RequestParam String symbols) {
        return marketDataService.getPrices(symbols);
    }

    @GetMapping("/api/orderbook")
    public OrderbookResponse getOrderbook(@RequestParam String symbol) {
        return marketDataService.getOrderbook(symbol);
    }

    @GetMapping("/api/trades")
    public TradeResponse getTrades(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "50") int count
    ) {
        return marketDataService.getTrades(symbol, count);
    }

    /**
     * interval: 1m(분봉) 또는 1d(일봉)
     */
    @GetMapping("/api/candles")
    public CandleResponse getCandles(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "1d") String interval,
            @RequestParam(defaultValue = "100") int count
    ) {
        return marketDataService.getCandles(symbol, interval, count);
    }
}
