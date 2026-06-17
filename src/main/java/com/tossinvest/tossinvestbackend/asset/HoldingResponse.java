package com.tossinvest.tossinvestbackend.asset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 보유 주식 조회 응답 (실제 응답 기준 확정).
 * symbol 미지정 시 전체 보유 종목 기준 요약, 지정 시 해당 종목 기준으로 재계산됨.
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
        private ValueWithCost marketValue;
        private ProfitLoss profitLoss;
        private DailyProfitLoss dailyProfitLoss;
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
    public static class ValueWithCost {
        private CurrencyAmount amount;
        private CurrencyAmount amountAfterCost;
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProfitLoss {
        private CurrencyAmount amount;
        private CurrencyAmount amountAfterCost;
        private String rate;
        private String rateAfterCost;
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DailyProfitLoss {
        private CurrencyAmount amount;
        private String rate;
    }

    /**
     * 종목별 보유 상세. averagePurchasePrice(평단가) × quantity(수량) = purchaseAmount(매입금액),
     * lastPrice(현재가) × quantity = marketValue.amount(평가금액=현재 총금액).
     */
    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HoldingItem {
        private String symbol;
        private String name;
        private String marketCountry;
        private String currency;
        private String quantity;
        private String lastPrice;
        private String averagePurchasePrice;
        private ItemMarketValue marketValue;
        private ItemProfitLoss profitLoss;
        private ItemDailyProfitLoss dailyProfitLoss;
        private Cost cost;
    }

    /**
     * 종목 단위 dailyProfitLoss는 통화가 이미 고정되어 있어 amount/rate가 단순 문자열로 내려온다
     * (top-level result.dailyProfitLoss는 krw/usd 객체인 DailyProfitLoss와 다름).
     */
    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ItemDailyProfitLoss {
        private String amount;
        private String rate;
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ItemMarketValue {
        private String purchaseAmount;
        private String amount;
        private String amountAfterCost;
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ItemProfitLoss {
        private String amount;
        private String amountAfterCost;
        private String rate;
        private String rateAfterCost;
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Cost {
        private String commission;
        private String tax;
    }
}
