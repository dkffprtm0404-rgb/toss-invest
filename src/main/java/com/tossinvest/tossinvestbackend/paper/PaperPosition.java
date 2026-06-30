package com.tossinvest.tossinvestbackend.paper;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 페이퍼 트레이딩 가상 포지션.
 * 실제 주문 없이 신호 발생 시 가상으로 매수하고 청산 조건 도달 시 가상 매도한다.
 */
@Entity
@Table(name = "paper_position")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class PaperPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private LocalDate entryDate;

    @Column(nullable = false)
    private BigDecimal entryPrice;

    /** 보유 기간 중 최고수익률 (트레일링 스탑 계산용) */
    @Builder.Default
    @Column(nullable = false)
    private BigDecimal peakRate = BigDecimal.ZERO;

    /** OPEN: 보유 중 / CLOSED: 청산 완료 */
    @Builder.Default
    @Column(nullable = false)
    private String status = "OPEN";

    private LocalDate exitDate;
    private BigDecimal exitPrice;
    private BigDecimal returnRate;
    private String exitReason; // STOP_LOSS / TRAILING_STOP / TIME_EXIT / DEAD_CROSS / MANUAL

    /**
     * 9차 보정(MD 8장): B안(즉시익절 +5%) 비교용 가상 기록.
     * 실제 매매(A안, 트레일링)와 별개로, "만약 +5% 도달 시 즉시 팔았다면" 시나리오를 함께 추적한다.
     */
    @Builder.Default
    private Boolean quick5Reached = false;   // peakRate가 +5% 이상 도달한 적 있는지
    private LocalDate quick5ExitDate;        // +5% 최초 도달일 (가상 청산일)
    private BigDecimal quick5ExitPrice;      // +5% 도달 시점 가격 (가상 청산가)
}
