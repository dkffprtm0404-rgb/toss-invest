package com.tossinvest.tossinvestbackend.strategy;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

@Entity
@Immutable
@Table(name = "saved_strategy_version", uniqueConstraints =
        @UniqueConstraint(name = "uk_strategy_revision", columnNames = {"strategy_id", "version"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedStrategyVersionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "strategy_id", nullable = false, updatable = false)
    private SavedStrategyEntity strategy;
    @Column(nullable = false, updatable = false)
    private int version;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Lob @Column(nullable = false, updatable = false)
    private String definitionJson;

    public SavedStrategyVersionEntity(SavedStrategyEntity strategy, String definitionJson, Instant now) {
        this.strategy = strategy;
        this.version = strategy.getCurrentVersion();
        this.definitionJson = definitionJson;
        this.createdAt = now;
    }
}
