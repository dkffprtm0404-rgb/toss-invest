package com.tossinvest.tossinvestbackend.stockinfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 종목 마스터 조회 응답.
 * 정확한 필드명은 추정이며, 404/필드 누락 시 실제 가이드 캡처로 보정 예정.
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
        private String code;
        private String name;
        private String market;
        private String currency;
    }
}
