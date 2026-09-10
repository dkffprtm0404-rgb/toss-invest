package com.tossinvest.tossinvestbackend.strategy;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavedStrategyVersionRepository extends JpaRepository<SavedStrategyVersionEntity, Long> {
    Optional<SavedStrategyVersionEntity> findByStrategyIdAndVersion(long strategyId, int version);
    List<SavedStrategyVersionEntity> findByStrategyIdOrderByVersionDesc(long strategyId, Pageable pageable);
}
