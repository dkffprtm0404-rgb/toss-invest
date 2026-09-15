package com.tossinvest.tossinvestbackend.backtest;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CandleRepository extends JpaRepository<CandleEntity, CandleEntity.CandleId> {
    long countBySymbolInAndTimestampGreaterThanEqualAndTimestampLessThan(List<String> symbols,long start,long end);
    List<CandleEntity> findBySymbolInAndTimestampGreaterThanEqualAndTimestampLessThanOrderBySymbolAscTimestampAsc(List<String> symbols,long start,long end);

    List<CandleEntity> findBySymbolOrderByTimestampAsc(String symbol);

    List<CandleEntity> findBySymbolAndTimestampLessThanOrderByTimestampAsc(String symbol, long endExclusive);

    long countBySymbol(String symbol);

    List<CandleEntity> findTop1BySymbolOrderByTimestampAsc(String symbol);

    List<CandleEntity> findTop1BySymbolOrderByTimestampDesc(String symbol);

    /** 심볼별 캔들 전체를 최신순으로 조회 (롤링 윈도우 정리용) */
    List<CandleEntity> findBySymbolOrderByTimestampDesc(String symbol);

    /** 캔들 데이터가 있는 종목 코드 목록 (distinct, 전체 행 로드 없이 심볼만 조회) */
    @Query("SELECT DISTINCT c.symbol FROM CandleEntity c")
    List<String> findDistinctSymbols();

    /** 종목별 캔들 개수가 minCount 이상인 심볼 목록 */
    @Query("SELECT c.symbol FROM CandleEntity c GROUP BY c.symbol HAVING COUNT(c) >= :minCount")
    List<String> findSymbolsWithMinCandles(long minCount);
}
