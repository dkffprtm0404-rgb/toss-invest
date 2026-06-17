package com.tossinvest.tossinvestbackend.marketdata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 캔들(OHLCV) 차트 조회 응답.
 */
@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CandleResponse {

    private Result result;

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Result {
        private List<Candle> candles;
        private String nextBefore;
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Candle {
        private String timestamp;
        private String openPrice;
        private String highPrice;
        private String lowPrice;
        private String closePrice;
        private String volume;
        private String currency;
    }
}
