package com.tossinvest.tossinvestbackend.paper;

import com.tossinvest.tossinvestbackend.signal.TradeSignal;
import com.tossinvest.tossinvestbackend.signal.TradeSignalService;
import com.tossinvest.tossinvestbackend.signal.SignalType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 페이퍼 트레이딩 서비스.
 * 실제 주문 없이 TradeSignalService의 신호를 기반으로 가상 포지션을 관리한다.
 * 1종목 1포지션 원칙 (이미 보유 중인 종목은 추가 매수 안 함).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaperTradingService {

    private final PaperPositionRepository repo;
    private final TradeSignalService signalService;

    /**
     * 종목 목록에 대해 신호를 평가하고 가상 매수/매도를 수행한다.
     * 매일 장 마감 후 스케줄러에서 호출한다.
     */
    @Transactional
    public DailyRunResult runDaily(List<String> symbols) {
        List<String> bought = new ArrayList<>(), sold = new ArrayList<>(), held = new ArrayList<>();

        // 현재 보유 중인 가상 포지션 맵 (symbol → position)
        Map<String, PaperPosition> openPositions = repo.findByStatus("OPEN").stream()
                .collect(Collectors.toMap(PaperPosition::getSymbol, p -> p));

        for (String symbol : symbols) {
            try {
                if (openPositions.containsKey(symbol)) {
                    // ── 매도 판단 ──────────────────────────────────────────
                    PaperPosition pos = openPositions.get(symbol);
                    BigDecimal peakRate = pos.getPeakRate();
                    int holdingDays = (int) (LocalDate.now().toEpochDay() - pos.getEntryDate().toEpochDay());

                    TradeSignal signal = signalService.evaluateForSell(
                            symbol, pos.getEntryPrice(), peakRate, holdingDays);

                    // peakRate 갱신
                    if (signal.getChangeRate() != null && signal.getChangeRate().compareTo(peakRate) > 0) {
                        pos.setPeakRate(signal.getChangeRate());
                    }

                    if (signal.getSignalType() != SignalType.HOLD) {
                        pos.setStatus("CLOSED");
                        pos.setExitDate(LocalDate.now());
                        pos.setExitPrice(signal.getCurrentPrice());
                        BigDecimal ret = signal.getCurrentPrice().subtract(pos.getEntryPrice())
                                .divide(pos.getEntryPrice(), 6, RoundingMode.HALF_UP);
                        pos.setReturnRate(ret);
                        pos.setExitReason(signal.getSignalType().name());
                        repo.save(pos);
                        sold.add(symbol + "(" + String.format("%.2f%%", ret.doubleValue() * 100) + ")");
                        log.info("[페이퍼] 매도: {} @ {} ({})", symbol, signal.getCurrentPrice(), signal.getSignalType());
                    } else {
                        held.add(symbol);
                    }
                } else {
                    // ── 매수 판단 ──────────────────────────────────────────
                    // 4장 ON/OFF 스위치: 최근 20거래 승률 확인
                    if (!isStrategyOn()) {
                        log.info("[페이퍼] ON/OFF 스위치 OFF - {} 매수 스킵", symbol);
                        continue;
                    }

                    TradeSignal signal = signalService.evaluateForBuy(symbol);
                    if (signal.getSignalType() == SignalType.BUY_CANDIDATE) {
                        PaperPosition pos = PaperPosition.builder()
                                .symbol(symbol)
                                .entryDate(LocalDate.now())
                                .entryPrice(signal.getCurrentPrice())
                                .build();
                        repo.save(pos);
                        bought.add(symbol + "@" + signal.getCurrentPrice());
                        log.info("[페이퍼] 매수: {} @ {}", symbol, signal.getCurrentPrice());
                    }
                }
            } catch (Exception e) {
                log.warn("[페이퍼] {} 처리 중 에러: {}", symbol, e.getMessage());
            }
        }

        return new DailyRunResult(bought, sold, held);
    }

    /** 4장 ON/OFF 스위치: 최근 20거래 승률 < 35% 또는 최근 10거래일 누적 -7% 이하면 false */
    private boolean isStrategyOn() {
        List<PaperPosition> closed = repo.findClosedOrderByExitDateDesc();
        if (closed.size() >= 20) {
            List<PaperPosition> recent20 = closed.subList(0, 20);
            long wins = recent20.stream().filter(p -> p.getReturnRate() != null && p.getReturnRate().signum() > 0).count();
            if ((double) wins / 20 < 0.35) {
                log.warn("[페이퍼] ON/OFF: 최근 20거래 승률 {}% < 35%, 신규매수 중단", wins * 5);
                return false;
            }
        }
        LocalDate cutoff = LocalDate.now().minusDays(10);
        double cum = closed.stream()
                .filter(p -> p.getExitDate() != null && p.getExitDate().isAfter(cutoff))
                .mapToDouble(p -> p.getReturnRate() == null ? 0 : p.getReturnRate().doubleValue())
                .sum();
        if (cum <= -0.07) {
            log.warn("[페이퍼] ON/OFF: 최근 10거래일 누적 {:.2f}% <= -7%, 신규매수 중단", cum * 100);
            return false;
        }
        return true;
    }

    /** 현재 가상 포지션 요약 (대시보드용) */
    public PortfolioSummary getSummary() {
        List<PaperPosition> open   = repo.findByStatus("OPEN");
        List<PaperPosition> closed = repo.findClosedOrderByExitDateDesc();

        int totalTrades = closed.size();
        long wins = closed.stream().filter(p -> p.getReturnRate() != null && p.getReturnRate().signum() > 0).count();
        double winRate = totalTrades == 0 ? 0 : (double) wins / totalTrades;
        double avgReturn = closed.stream()
                .filter(p -> p.getReturnRate() != null)
                .mapToDouble(p -> p.getReturnRate().doubleValue())
                .average().orElse(0);
        double cumReturn = closed.stream()
                .filter(p -> p.getReturnRate() != null)
                .mapToDouble(p -> p.getReturnRate().doubleValue())
                .sum();

        return new PortfolioSummary(open, closed, totalTrades, winRate, avgReturn, cumReturn);
    }

    public record DailyRunResult(List<String> bought, List<String> sold, List<String> held) {}

    public record PortfolioSummary(
            List<PaperPosition> openPositions,
            List<PaperPosition> closedPositions,
            int totalTrades,
            double winRate,
            double avgReturn,
            double cumReturn
    ) {}
}
