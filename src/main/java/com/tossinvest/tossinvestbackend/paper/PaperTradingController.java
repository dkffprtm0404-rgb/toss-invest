package com.tossinvest.tossinvestbackend.paper;

import com.tossinvest.tossinvestbackend.backtest.BacktestUniverse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import java.util.List;

@RestController
@RequestMapping("/api/paper")
@RequiredArgsConstructor
public class PaperTradingController {

    private final PaperTradingService service;
    private final PaperPositionRepository repo;
    private final PaperRunLogRepository logRepo;
    private final BacktestUniverse universe;
    private final PaperTradingScheduler scheduler;

    /** 수동 트리거 - 로그 기록 포함 */
    @PostMapping("/run")
    public PaperTradingService.DailyRunResult runNow() {
        return scheduler.runManual();
    }

    /** 실행 로그 (최근 30회) - 신호 없는 날도 기록됨 */
    @GetMapping("/logs")
    public List<PaperRunLog> logs() {
        return logRepo.findTop30ByOrderByRunAtDesc();
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

    /** 특정 포지션 수동 청산 (MANUAL) - 현재가 조회 후 정확한 수익률/청산일 기록 */
    @PostMapping("/close/{id}")
    public PaperPosition closeManually(@PathVariable Long id) {
        return service.closeManually(id);
    }
}
