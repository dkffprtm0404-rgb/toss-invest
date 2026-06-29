package com.tossinvest.tossinvestbackend.scalping;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 1분봉 단타 시뮬레이션 가상 포지션.
 * 목표가(+2% or +3%) 또는 손절(-0.6%) 또는 당일 15:20 강제청산.
 */
@Entity
@Table(name = "scalping_position")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ScalpingPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private LocalDateTime entryTime;

    @Column(nullable = false)
    private BigDecimal entryPrice;

    /** OPEN / CLOSED */
    @Builder.Default
    @Column(nullable = false)
    private String status = "OPEN";

    private LocalDateTime exitTime;
    private BigDecimal exitPrice;
    private BigDecimal returnRate;

    /** TAKE_PROFIT_2 / TAKE_PROFIT_3 / STOP_LOSS / FORCE_EXIT */
    private String exitReason;

    /** 진입 시점의 EMA5, EMA20 (디버깅용) */
    private BigDecimal ema5AtEntry;
    private BigDecimal ema20AtEntry;
}
