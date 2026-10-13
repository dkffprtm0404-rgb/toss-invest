package com.tossinvest.tossinvestbackend.scalping;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ScalpingPersistenceTests {
    @Autowired ScalpingPositionRepository repo;
    @Autowired EntityManager entityManager;

    @Test
    void subPercentStopLossAndTrailingPeakSurviveDatabaseRoundTrip() {
        var position = repo.saveAndFlush(ScalpingPosition.builder().symbol("005930")
                .entryTime(LocalDateTime.of(2026, 10, 8, 10, 0)).entryPrice(new BigDecimal("100"))
                .peakRate(new BigDecimal("0.0091")).returnRate(new BigDecimal("-0.006"))
                .status("CLOSED").build());
        entityManager.clear();

        var reloaded = repo.findById(position.getId()).orElseThrow();
        assertThat(reloaded.getPeakRate()).isEqualByComparingTo("0.0091");
        assertThat(reloaded.getReturnRate()).isEqualByComparingTo("-0.006");
    }
}
