package com.tossinvest.tossinvestbackend.paper;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaperRunLogRepository extends JpaRepository<PaperRunLog, Long> {
    List<PaperRunLog> findTop30ByOrderByRunAtDesc();
}
