package com.tossinvest.tossinvestbackend.marketbriefing;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.Optional;

public interface MarketBriefingRepository extends JpaRepository<MarketBriefingEntity,LocalDate> {
    Optional<MarketBriefingEntity> findTopByOrderByBriefingDateDesc();
}
