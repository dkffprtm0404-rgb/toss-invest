package com.tossinvest.tossinvestbackend.marketbriefing;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name="market_briefing")
public class MarketBriefingEntity {
    @Id private LocalDate briefingDate;
    @Lob @Column(nullable=false) private String snapshotJson;
    @Version private Long version;
    protected MarketBriefingEntity(){ }
    MarketBriefingEntity(LocalDate date,String json){briefingDate=date;snapshotJson=json;}
    public String getSnapshotJson(){return snapshotJson;}
    public void setSnapshotJson(String value){snapshotJson=value;}
}
