package com.tossinvest.tossinvestbackend.marketdata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 현재가 조회 응답 (추정 구조). 다건 조회 가능.
 */
@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PriceResponse {

    private List<Price> result;

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Price {
        private String symbol;
        private String currentPrice;
        private String changePrice;
        private String changeRate;
        private String changeType;
        private String volume;
    }
}
