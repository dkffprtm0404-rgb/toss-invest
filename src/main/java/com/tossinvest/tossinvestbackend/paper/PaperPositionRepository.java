package com.tossinvest.tossinvestbackend.paper;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PaperPositionRepository extends JpaRepository<PaperPosition, Long> {

    List<PaperPosition> findByStatus(String status);

    List<PaperPosition> findBySymbolAndStatus(String symbol, String status);

    List<PaperPosition> findAllByOrderByEntryDateDesc();

    @Query("SELECT p FROM PaperPosition p WHERE p.status = 'CLOSED' ORDER BY p.exitDate DESC")
    List<PaperPosition> findClosedOrderByExitDateDesc();

    long countByStatus(String status);
}
