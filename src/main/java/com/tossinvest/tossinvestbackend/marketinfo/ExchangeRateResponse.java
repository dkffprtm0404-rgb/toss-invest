package com.tossinvest.tossinvestbackend.marketinfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExchangeRateResponse {

    private Result result;

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Result {
        private String baseCurrency;
        private String quoteCurrency;
        private String rate;
        private String midRate;
        private String basisPoint;
        private String rateChangeType;
        private String validFrom;
        private String validUntil;
    }
}
