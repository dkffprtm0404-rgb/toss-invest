package com.tossinvest.tossinvestbackend.portfolio;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;

public interface PortfolioRunRepository extends JpaRepository<PortfolioRunEntity,Long> {
    interface Summary {Long getId();Integer getVersion();Instant getCreatedAt();String getStatus();}
    @Query("select p.id as id,p.strategyVersion.version as version,p.createdAt as createdAt,p.status as status from PortfolioRunEntity p where p.strategyVersion.strategy.id=:id order by p.id desc")
    List<Summary> summaries(@Param("id")long id,Pageable pageable);
    @Modifying(flushAutomatically=true,clearAutomatically=true) @Query("delete from PortfolioRunEntity p where p.id=:id")
    int deleteRun(@Param("id")long id);
}
