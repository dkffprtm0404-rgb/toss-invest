package com.tossinvest.tossinvestbackend.asset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 보유 주식 조회 응답.
 * symbol 미지정 시 전체 보유 종목 기준 요약, 지정 시 해당 종목 기준으로 재계산됨.
 * items 필드는 개별 종목 상세 (보유 종목 없으면 빈 배열) - 정확한 필드는 추가 캡처로 보정 필요.
 */
@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class HoldingResponse {

    private Result result;

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Result {
        private CurrencyAmount totalPurchaseAmount;
        private CurrencyAmount marketValue;
        private CurrencyAmount amountAfterCost;
        private List<HoldingItem> items;
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CurrencyAmount {
        private String krw;
        private String usd;
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HoldingItem {
        private String symbol;
        private String name;
        private String quantity;
    }
}
