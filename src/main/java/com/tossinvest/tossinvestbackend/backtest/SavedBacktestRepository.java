package com.tossinvest.tossinvestbackend.backtest;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface SavedBacktestRepository extends JpaRepository<SavedBacktestEntity, Long> {
    interface Summary {
        Long getId();
        Integer getVersion();
        Instant getCreatedAt();
        String getStatus();
    }

    // Lists do not load the potentially large candle/result CLOB.
    @Query("select b.id as id, b.strategyVersion.version as version, b.createdAt as createdAt, b.status as status "
            + "from SavedBacktestEntity b where b.strategyVersion.strategy.id = :strategyId order by b.id desc")
    List<Summary> summaries(@Param("strategyId") long strategyId, Pageable pageable);
}
