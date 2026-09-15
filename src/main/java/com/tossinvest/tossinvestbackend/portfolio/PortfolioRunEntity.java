package com.tossinvest.tossinvestbackend.portfolio;

import com.tossinvest.tossinvestbackend.strategy.SavedStrategyVersionEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.Instant;

@Entity @Immutable @Table(name="portfolio_run",indexes=@Index(name="idx_portfolio_revision",columnList="strategy_version_id,id"))
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class PortfolioRunEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="strategy_version_id",nullable=false,updatable=false)
    @OnDelete(action=OnDeleteAction.CASCADE) private SavedStrategyVersionEntity strategyVersion;
    @Column(nullable=false,updatable=false) private Instant createdAt;
    @Column(nullable=false,updatable=false,length=32) private String status;
    @Lob @Column(nullable=false,updatable=false) private String snapshotJson;
    public PortfolioRunEntity(SavedStrategyVersionEntity version,Instant createdAt,String status,String snapshotJson) {
        this.strategyVersion=version;this.createdAt=createdAt;this.status=status;this.snapshotJson=snapshotJson;
    }
}
