package com.tossinvest.tossinvestbackend.paper;

import com.tossinvest.tossinvestbackend.signal.SignalType;
import com.tossinvest.tossinvestbackend.signal.TradeSignal;
import com.tossinvest.tossinvestbackend.signal.TradeSignalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaperTradingService {

    private final PaperPositionRepository repo;
    private final TradeSignalService signalService;
    private static final int THREADS = 10;

    @Transactional
    public DailyRunResult runDaily(List<String> symbols) {
        List<String> bought = new ArrayList<>(), sold = new ArrayList<>(), held = new ArrayList<>();
        Map<String, PaperPosition> open = repo.findByStatus("OPEN").stream()
                .collect(Collectors.toMap(PaperPosition::getSymbol, p -> p));
        boolean on = isStrategyOn();

        ExecutorService ex = Executors.newFixedThreadPool(THREADS);
        Map<String, Future<TradeSignal>> futures = new LinkedHashMap<>();

        for (String sym : symbols) {
            if (open.containsKey(sym)) {
                PaperPosition pos = open.get(sym);
                int days = (int)(LocalDate.now().toEpochDay() - pos.getEntryDate().toEpochDay());
                futures.put(sym, ex.submit(() ->
                    signalService.evaluateForSell(sym, pos.getEntryPrice(), pos.getPeakRate(), days)));
            } else if (on) {
                futures.put(sym, ex.submit(() -> signalService.evaluateForBuy(sym)));
            }
        }
        ex.shutdown();
        try { ex.awaitTermination(120, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}

        List<PaperPosition> toSave = new ArrayList<>();
        for (Map.Entry<String, Future<TradeSignal>> e : futures.entrySet()) {
            String sym = e.getKey();
            try {
                TradeSignal sig = e.getValue().get();
                if (sig == null) continue;

                if (open.containsKey(sym)) {
                    PaperPosition pos = open.get(sym);
                    if (sig.getChangeRate() != null && sig.getChangeRate().compareTo(pos.getPeakRate()) > 0)
                        pos.setPeakRate(sig.getChangeRate());

                    if (sig.getSignalType() != SignalType.HOLD) {
                        pos.setStatus("CLOSED");
                        pos.setExitDate(LocalDate.now());
                        pos.setExitPrice(sig.getCurrentPrice());
                        BigDecimal ret = sig.getCurrentPrice().subtract(pos.getEntryPrice())
                                .divide(pos.getEntryPrice(), 6, RoundingMode.HALF_UP);
                        pos.setReturnRate(ret);
                        pos.setExitReason(sig.getSignalType().name());
                        toSave.add(pos);
                        sold.add(sym + "(" + String.format("%.2f%%", ret.doubleValue() * 100) + ")");
                        log.info("[페이퍼] 매도: {} @ {} ({})", sym, sig.getCurrentPrice(), sig.getSignalType());
                    } else {
                        held.add(sym);
                    }
                } else if (sig.getSignalType() == SignalType.BUY_CANDIDATE) {
                    PaperPosition pos = PaperPosition.builder()
                            .symbol(sym).entryDate(LocalDate.now()).entryPrice(sig.getCurrentPrice()).build();
                    toSave.add(pos);
                    bought.add(sym + "@" + sig.getCurrentPrice());
                    log.info("[페이퍼] 매수: {} @ {}", sym, sig.getCurrentPrice());
                }
            } catch (Exception err) {
                log.warn("[페이퍼] {} 에러: {}", sym, err.getMessage());
            }
        }
        if (!toSave.isEmpty()) repo.saveAll(toSave);
        log.info("[페이퍼] 완료: 매수 {}건, 매도 {}건, 보유유지 {}건", bought.size(), sold.size(), held.size());
        return new DailyRunResult(bought, sold, held);
    }

    private boolean isStrategyOn() {
        List<PaperPosition> closed = repo.findClosedOrderByExitDateDesc();
        if (closed.size() >= 20) {
            long wins = closed.subList(0, 20).stream()
                    .filter(p -> p.getReturnRate() != null && p.getReturnRate().signum() > 0).count();
            if ((double) wins / 20 < 0.35) { log.warn("[페이퍼] ON/OFF OFF: 승률 부족"); return false; }
        }
        LocalDate cutoff = LocalDate.now().minusDays(10);
        double cum = closed.stream()
                .filter(p -> p.getExitDate() != null && p.getExitDate().isAfter(cutoff))
                .mapToDouble(p -> p.getReturnRate() == null ? 0 : p.getReturnRate().doubleValue()).sum();
        if (cum <= -0.07) { log.warn("[페이퍼] ON/OFF OFF: 누적 손실 초과"); return false; }
        return true;
    }

    public PortfolioSummary getSummary() {
        List<PaperPosition> openList   = repo.findByStatus("OPEN");
        List<PaperPosition> closedList = repo.findClosedOrderByExitDateDesc();
        int total = closedList.size();
        long wins = closedList.stream().filter(p -> p.getReturnRate() != null && p.getReturnRate().signum() > 0).count();
        double winRate = total == 0 ? 0 : (double) wins / total;
        double avgRet  = closedList.stream().filter(p -> p.getReturnRate() != null)
                .mapToDouble(p -> p.getReturnRate().doubleValue()).average().orElse(0);
        double cumRet  = closedList.stream().filter(p -> p.getReturnRate() != null)
                .mapToDouble(p -> p.getReturnRate().doubleValue()).sum();
        return new PortfolioSummary(openList, closedList, total, winRate, avgRet, cumRet);
    }

    public record DailyRunResult(List<String> bought, List<String> sold, List<String> held) {}
    public record PortfolioSummary(List<PaperPosition> openPositions, List<PaperPosition> closedPositions,
                                    int totalTrades, double winRate, double avgReturn, double cumReturn) {}
}