package com.tossinvest.tossinvestbackend.strategypaper;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface PaperRunRepository extends JpaRepository<PaperRunEntity,Long> {
    Optional<PaperRunEntity> findByRequestId(String requestId);
    boolean existsByActiveKey(String activeKey);
    boolean existsByStrategyIdAndStatusNot(long strategyId,String status);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from PaperRunEntity r where r.id=:id")
    Optional<PaperRunEntity> findForUpdate(@Param("id") long id);
    @Query("select r from PaperRunEntity r where (:strategyId is null or r.strategyId=:strategyId) and (:symbol is null or r.symbol=:symbol) and (:status is null or r.status=:status) order by r.id desc")
    List<PaperRunEntity> search(@Param("strategyId") Long strategyId,@Param("symbol") String symbol,@Param("status") String status,Pageable page);
    @Query("select r.id from PaperRunEntity r where r.status <> 'STOPPED' order by r.id")
    List<Long> activeIds();
}
