package com.tossinvest.tossinvestbackend.asset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 보유 종목(자산) 조회 응답 (추정 구조).
 */
@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class HoldingResponse {

    private List<Holding> result;

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Holding {
        private String stockCode;
        private String stockName;
        private String quantity;
        private String averagePrice;
        private String currentPrice;
        private String evaluationAmount;
        private String profitLossRate;
    }
}
