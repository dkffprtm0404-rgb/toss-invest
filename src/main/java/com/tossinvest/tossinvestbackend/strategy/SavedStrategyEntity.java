package com.tossinvest.tossinvestbackend.strategy;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "saved_strategy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedStrategyEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private int currentVersion;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    public SavedStrategyEntity(Instant now) {
        currentVersion = 1;
        createdAt = now;
        updatedAt = now;
    }

    public void advance(Instant now) {
        currentVersion++;
        updatedAt = now;
    }
}
