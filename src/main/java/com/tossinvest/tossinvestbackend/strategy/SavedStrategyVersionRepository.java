package com.tossinvest.tossinvestbackend.strategy;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SavedStrategyVersionRepository extends JpaRepository<SavedStrategyVersionEntity, Long> {
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from SavedStrategyVersionEntity v where v.strategy.id = :strategyId")
    int deleteForStrategy(@Param("strategyId") long strategyId);

    Optional<SavedStrategyVersionEntity> findByStrategyIdAndVersion(long strategyId, int version);
    List<SavedStrategyVersionEntity> findByStrategyIdOrderByVersionDesc(long strategyId, Pageable pageable);
}
