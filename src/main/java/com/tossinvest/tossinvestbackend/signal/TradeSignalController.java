package com.tossinvest.tossinvestbackend.signal;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/signals")
@RequiredArgsConstructor
public class TradeSignalController {

    private final TradeSignalService tradeSignalService;

    /**
     * 미보유 종목에 대한 매수 후보 판단 (점수제, v2.0).
     */
    @GetMapping("/buy")
    public TradeSignal evaluateBuy(@RequestParam String symbol) {
        return tradeSignalService.evaluateForBuy(symbol);
    }

    /**
     * 보유 종목에 대한 매도(손절/트레일링스탑/추세전환) 판단.
     * avgPrice: 평균 매수가, peakRate: 매수 이후 기록된 최고 수익률(소수, 예: 0.07 = 7%). 모르면 생략 가능.
     */
    @GetMapping("/sell")
    public TradeSignal evaluateSell(
            @RequestParam String symbol,
            @RequestParam BigDecimal avgPrice,
            @RequestParam(required = false) BigDecimal peakRate
    ) {
        return tradeSignalService.evaluateForSell(symbol, avgPrice, peakRate);
    }
}
