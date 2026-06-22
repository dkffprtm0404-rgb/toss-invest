package com.tossinvest.tossinvestbackend.backtest;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CandleRepository extends JpaRepository<CandleEntity, CandleEntity.CandleId> {

    List<CandleEntity> findBySymbolOrderByTimestampAsc(String symbol);

    long countBySymbol(String symbol);

    List<CandleEntity> findTop1BySymbolOrderByTimestampAsc(String symbol);

    List<CandleEntity> findTop1BySymbolOrderByTimestampDesc(String symbol);
}
