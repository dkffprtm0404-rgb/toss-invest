package com.tossinvest.tossinvestbackend.marketbriefing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** A dated, immutable briefing with the exact evidence supplied to the model. */
public record MarketBriefing(LocalDate date,String status,Instant cutoffAt,Instant generatedAt,Instant collectedAt,
                             String model,List<Item> items,List<Source> sources,List<String> warnings,String error) {
    public record Item(String title,String summary,List<String> sourceIds) { }
    public record Source(String id,String kind,String label,String title,String url,Instant observedAt,
                         BigDecimal value,BigDecimal changeRate,String unit) { }
    public record Input(Instant collectedAt,List<Source> sources,List<String> warnings) { }
}
