package com.tossinvest.tossinvestbackend.marketdata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 호가 조회 응답 (추정 구조). 단건(symbol) 조회.
 */
@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderbookResponse {

    private Result result;

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Result {
        private String symbol;
        private List<OrderbookLevel> asks;
        private List<OrderbookLevel> bids;
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OrderbookLevel {
        private String price;
        private String quantity;
    }
}
