package com.tossinvest.tossinvestbackend.scalping;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ScalpingPositionRepository extends JpaRepository<ScalpingPosition, Long> {

    List<ScalpingPosition> findByStatus(String status);

    List<ScalpingPosition> findBySymbolAndStatus(String symbol, String status);

    List<ScalpingPosition> findTop50ByOrderByEntryTimeDesc();

    @Query("SELECT p FROM ScalpingPosition p WHERE p.status = 'CLOSED' ORDER BY p.exitTime DESC")
    List<ScalpingPosition> findClosedOrderByExitTimeDesc();

    long countByStatus(String status);
}
