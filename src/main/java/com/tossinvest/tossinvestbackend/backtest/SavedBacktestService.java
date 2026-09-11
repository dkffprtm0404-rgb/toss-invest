package com.tossinvest.tossinvestbackend.backtest;

import com.tossinvest.tossinvestbackend.strategy.SavedStrategyService;
import com.tossinvest.tossinvestbackend.strategy.StrategyJson;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class SavedBacktestService {
    private final SavedStrategyService strategies;
    private final CandleRepository candles;
    private final UserStrategyBacktestEngine engine;
    private final SavedBacktestRepository runs;
    private final StrategyJson json;

    public SavedBacktestService(SavedStrategyService strategies, CandleRepository candles,
                                 UserStrategyBacktestEngine engine, SavedBacktestRepository runs, StrategyJson json) {
        this.strategies = strategies;
        this.candles = candles;
        this.engine = engine;
        this.runs = runs;
        this.json = json;
    }

    public record RunRequest(Integer version, String symbol, LocalDate startDate, LocalDate endDate,
                             UserStrategyBacktestRequest.ExecutionMode executionMode) { }
    public record CandleSnapshot(String symbol, long timestamp, BigDecimal open, BigDecimal high,
                                 BigDecimal low, BigDecimal close, BigDecimal volume) {
        static CandleSnapshot from(CandleEntity c) {
            return new CandleSnapshot(c.getSymbol(), c.getTimestamp(), c.getOpenPrice(), c.getHighPrice(),
                    c.getLowPrice(), c.getClosePrice(), c.getVolume());
        }
    }
    public record DataSnapshot(String source, String interval, String timezone, String sha256, List<CandleSnapshot> candles) {
        public DataSnapshot { candles = List.copyOf(candles); }
    }
    public record CostSnapshot(String model, BigDecimal commissionRate, BigDecimal taxRate, BigDecimal slippageRate,
                               String capitalModel, BigDecimal initialCapital) { }
    public record RunError(String code, String message, Long timestamp) { }
    public record Snapshot(int schemaVersion, String engineVersion, Instant capturedAt,
                           SavedStrategyService.SavedVersion strategy, UserStrategyBacktestRequest execution,
                           DataSnapshot data, CostSnapshot costs, UserStrategyBacktestResult result, RunError error) { }
    public record RunDetail(long id, Instant createdAt, String status, Snapshot snapshot) { }
    public record RunSummary(long id, int version, Instant createdAt, String status) { }

    @Transactional
    public RunDetail run(long strategyId, RunRequest request) {
        if (request == null) throw new StrategyJson.InvalidRequestException();
        SavedStrategyService.requirePositive(request.version(), "version");
        // Serialize execution with strategy update/deletion so no run is saved against a removed version.
        strategies.requireForUpdate(strategyId);
        var revision = strategies.versionEntity(strategyId, request.version());
        var strategy = strategies.view(revision);
        var execution = new UserStrategyBacktestRequest(strategy.strategy(), request.symbol(), request.startDate(),
                request.endDate(), request.executionMode());
        execution.requireValid();
        long endExclusive = execution.endDate().plusDays(1).atStartOfDay(UserStrategyBacktestEngine.MARKET_ZONE)
                .toInstant().toEpochMilli();
        // One read supplies both the engine and the immutable input snapshot, including indicator history.
        var input = candles.findBySymbolAndTimestampLessThanOrderByTimestampAsc(execution.symbol(), endExclusive);
        Instant capturedAt = SavedStrategyService.now();
        var captured = input.stream().map(CandleSnapshot::from).toList();
        var data = new DataSnapshot("LOCAL_CANDLE_CACHE", "1d", UserStrategyBacktestEngine.MARKET_ZONE.getId(),
                sha256(json.write(captured)), captured);
        var costs = new CostSnapshot("NOT_MODELED", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "NOT_MODELED", null);
        UserStrategyBacktestResult result = null;
        RunError error = null;
        String status;
        try {
            result = engine.run(execution, input);
            status = result.status();
        } catch (UserStrategyBacktestEngine.DataException e) {
            status = "FAILED";
            error = new RunError("INVALID_DATA", e.getMessage(), e.getTimestamp());
        }
        var snapshot = new Snapshot(1, "USER_STRATEGY_DAILY_V1", capturedAt, strategy, execution, data, costs, result, error);
        var saved = runs.save(new SavedBacktestEntity(revision, capturedAt, status, json.write(snapshot)));
        return new RunDetail(saved.getId(), saved.getCreatedAt(), saved.getStatus(), snapshot);
    }

    @Transactional
    public void delete(long id) {
        if (runs.deleteRun(id) == 0) throw new SavedStrategyService.NotFoundException("Backtest run not found.");
    }

    public RunDetail get(long id) {
        var run = runs.findById(id).orElseThrow(() -> new SavedStrategyService.NotFoundException("Backtest run not found."));
        // Never recalculate from today's strategy or today's candle cache on a history read.
        return new RunDetail(run.getId(), run.getCreatedAt(), run.getStatus(), json.stored(run.getSnapshotJson(), Snapshot.class));
    }

    public List<RunSummary> list(long strategyId, int page, int size) {
        var pageable = SavedStrategyService.page(page, size);
        strategies.requireExists(strategyId);
        return runs.summaries(strategyId, pageable).stream()
                .map(s -> new RunSummary(s.getId(), s.getVersion(), s.getCreatedAt(), s.getStatus())).toList();
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
