package com.tossinvest.tossinvestbackend.paper;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 페이퍼 트레이딩 일별 실행 로그.
 * 스케줄러가 돌 때마다 결과를 기록한다 (신호 없는 날도 기록).
 */
@Entity
@Table(name = "paper_run_log")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class PaperRunLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate runDate;

    @Column(nullable = false)
    private LocalDateTime runAt;

    private int universeSizez;  // 평가한 종목 수
    private int boughtCount;
    private int soldCount;
    private int heldCount;

    @Column(length = 1000)
    private String boughtSymbols;  // 매수 종목 (쉼표 구분)

    @Column(length = 1000)
    private String soldSymbols;    // 매도 종목 (쉼표 구분)

    private String trigger; // SCHEDULER or MANUAL
}
