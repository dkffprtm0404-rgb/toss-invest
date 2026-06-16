package com.tossinvest.tossinvestbackend.stockinfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 종목 기본 정보 조회 응답.
 */
@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StockResponse {

    private List<Stock> result;

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Stock {
        private String symbol;
        private String name;
        private String englishName;
        private String isinCode;
        private String market;
        private String securityType;
        private Boolean isCommonShare;
        private String status;
        private String currency;
        private String listDate;
        private String delistDate;
    }
}
