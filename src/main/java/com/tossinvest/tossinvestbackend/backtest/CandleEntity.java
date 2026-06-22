package com.tossinvest.tossinvestbackend.backtest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * 백테스트용 일봉 캔들 캐시. 토스증권 캔들 API에서 수집한 데이터를 영구 저장해
 * 반복적인 백테스트(그리드서치) 실행 시 매번 API를 다시 호출하지 않도록 한다.
 * 복합키: symbol + timestamp (같은 종목·같은 일자 중복 저장 방지)
 */
@Entity
@Table(name = "backtest_candle", indexes = {
        @jakarta.persistence.Index(name = "idx_candle_symbol_ts", columnList = "symbol, timestamp")
})
@IdClass(CandleEntity.CandleId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CandleEntity {

    @Id
    @Column(nullable = false)
    private String symbol;

    @Id
    @Column(nullable = false)
    private Long timestamp; // epoch millis, 토스 API 캔들 timestamp 그대로 저장

    @Column(nullable = false)
    private BigDecimal openPrice;

    @Column(nullable = false)
    private BigDecimal highPrice;

    @Column(nullable = false)
    private BigDecimal lowPrice;

    @Column(nullable = false)
    private BigDecimal closePrice;

    @Column(nullable = false)
    private BigDecimal volume;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CandleId implements Serializable {
        private String symbol;
        private Long timestamp;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CandleId)) return false;
            CandleId that = (CandleId) o;
            return Objects.equals(symbol, that.symbol) && Objects.equals(timestamp, that.timestamp);
        }

        @Override
        public int hashCode() {
            return Objects.hash(symbol, timestamp);
        }
    }
}
