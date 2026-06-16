package com.tossinvest.tossinvestbackend.stockinfo;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stocks")
@RequiredArgsConstructor
public class StockInfoController {

    private final StockInfoService stockInfoService;

    /**
     * symbols: 콤마로 구분된 종목 심볼 (필수). 예: ?symbols=005930,AAPL
     */
    @GetMapping
    public StockResponse getStocks(@RequestParam String symbols) {
        return stockInfoService.getStocks(symbols);
    }
}
