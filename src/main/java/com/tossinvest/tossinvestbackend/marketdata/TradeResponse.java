package com.tossinvest.tossinvestbackend.marketdata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 최근 체결 내역 조회 응답 (추정 구조).
 */
@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TradeResponse {

    private List<Trade> result;

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Trade {
        private String symbol;
        private String price;
        private String quantity;
        private String tradeTime;
        private String changeType;
    }
}
