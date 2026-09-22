package com.tossinvest.tossinvestbackend.comparison;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ComparisonRepository extends JpaRepository<ComparisonEntity,Long> {
    interface Summary {
        Long getId(); Instant getCreatedAt(); String getType(); String getStrategyNamesJson();
    }
    @Query("select c.id as id,c.createdAt as createdAt,c.type as type,c.strategyNamesJson as strategyNamesJson from ComparisonEntity c order by c.id desc")
    List<Summary> summaries(Pageable page);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ComparisonEntity c join c.strategyIds s where s = :strategyId order by c.id")
    List<ComparisonEntity> forStrategy(long strategyId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ComparisonEntity c where c.id = :id")
    Optional<ComparisonEntity> findForUpdate(long id);
}
