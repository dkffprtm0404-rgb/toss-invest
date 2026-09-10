package com.tossinvest.tossinvestbackend.backtest;

import com.tossinvest.tossinvestbackend.strategy.SavedStrategyVersionEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

@Entity
@Immutable
@Table(name = "saved_backtest", indexes = @Index(name = "idx_backtest_revision", columnList = "strategy_version_id, id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedBacktestEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "strategy_version_id", nullable = false, updatable = false)
    private SavedStrategyVersionEntity strategyVersion;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false, updatable = false, length = 32)
    private String status;
    @Lob @Column(nullable = false, updatable = false)
    private String snapshotJson;

    public SavedBacktestEntity(SavedStrategyVersionEntity strategyVersion, Instant createdAt, String status, String snapshotJson) {
        this.strategyVersion = strategyVersion;
        this.createdAt = createdAt;
        this.status = status;
        this.snapshotJson = snapshotJson;
    }
}
