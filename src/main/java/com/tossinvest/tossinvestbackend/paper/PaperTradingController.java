package com.tossinvest.tossinvestbackend.paper;

import com.tossinvest.tossinvestbackend.backtest.BacktestUniverse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/paper")
@RequiredArgsConstructor
public class PaperTradingController {

    private final PaperTradingService service;
    private final PaperPositionRepository repo;
    private final BacktestUniverse universe;

    /** 수동 트리거 - 지금 즉시 신호 평가 및 가상 매매 실행 */
    @PostMapping("/run")
    public PaperTradingService.DailyRunResult runNow() {
        return service.runDaily(universe.all());
    }

    /** 포트폴리오 요약 (대시보드 메인용) */
    @GetMapping("/summary")
    public PaperTradingService.PortfolioSummary summary() {
        return service.getSummary();
    }

    /** 현재 보유 중인 가상 포지션 목록 */
    @GetMapping("/positions")
    public List<PaperPosition> openPositions() {
        return repo.findByStatus("OPEN");
    }

    /** 청산 완료된 거래 기록 (최신순) */
    @GetMapping("/history")
    public List<PaperPosition> history() {
        return repo.findClosedOrderByExitDateDesc();
    }

    /** 특정 포지션 수동 청산 (MANUAL) */
    @PostMapping("/close/{id}")
    public PaperPosition closeManually(@PathVariable Long id) {
        PaperPosition pos = repo.findById(id).orElseThrow();
        pos.setStatus("CLOSED");
        pos.setExitReason("MANUAL");
        return repo.save(pos);
    }
}
