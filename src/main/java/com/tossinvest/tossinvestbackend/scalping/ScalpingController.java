package com.tossinvest.tossinvestbackend.scalping;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/scalping")
@RequiredArgsConstructor
public class ScalpingController {

    private final ScalpingService service;
    private final ScalpingPositionRepository repo;

    /** 지금 즉시 1분봉 단타 신호 평가 (수동 트리거) */
    @PostMapping("/tick")
    public ScalpingService.ScalpingResult tickNow() {
        return service.tick();
    }

    /** 통계 요약 (대시보드용) */
    @GetMapping("/stats")
    public ScalpingService.ScalpingStats stats() {
        return service.getStats();
    }

    /** 현재 보유 중인 가상 포지션 */
    @GetMapping("/positions")
    public List<ScalpingPosition> openPositions() {
        return repo.findByStatus("OPEN");
    }

    /** 최근 청산 기록 (최신 50건) */
    @GetMapping("/history")
    public List<ScalpingPosition> history() {
        return repo.findClosedOrderByExitTimeDesc();
    }

    /** 대상 종목 목록 확인 */
    @GetMapping("/universe")
    public List<String> universe() {
        return ScalpingUniverse.SYMBOLS;
    }

    /** 1분봉 캔들 + 지표 조회 디버그 (신호 판단 원인 파악용) */
    @GetMapping("/debug/{symbol}")
    public Object debugSignal(@PathVariable String symbol) {
        return service.debugSignal(symbol);
    }
}
