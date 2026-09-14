package com.tossinvest.tossinvestbackend.backtest;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** One validated explanation per immutable execution; bulk run/strategy deletion also removes it. */
@Entity
@Table(name = "backtest_explanation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BacktestExplanationEntity {
    @Id
    private Long id;
    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private SavedBacktestEntity run;
    @Lob @Column(nullable = false, updatable = false)
    private String explanationJson;

    public BacktestExplanationEntity(SavedBacktestEntity run, String explanationJson) {
        // @MapsId copies the run ID on persist. Keeping it null marks this as a new entity to Spring Data.
        this.run = run;
        this.explanationJson = explanationJson;
    }
}
