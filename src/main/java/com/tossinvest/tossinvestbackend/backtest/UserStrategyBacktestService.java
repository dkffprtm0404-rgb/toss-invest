package com.tossinvest.tossinvestbackend.backtest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads cached candles only; running a strategy never collects data or places an external order. */
@Service
public class UserStrategyBacktestService {
    private final CandleRepository repository;
    private final UserStrategyBacktestEngine engine;

    public UserStrategyBacktestService(CandleRepository repository, UserStrategyBacktestEngine engine) {
        this.repository = repository;
        this.engine = engine;
    }

    @Transactional(readOnly = true)
    public UserStrategyBacktestResult run(UserStrategyBacktestRequest request) {
        request.requireValid();
        long endExclusive = request.endDate().plusDays(1).atStartOfDay(UserStrategyBacktestEngine.MARKET_ZONE)
                .toInstant().toEpochMilli();
        // Include available history for stable EMA initialization, but never candles beyond the requested end.
        return engine.run(request, repository.findBySymbolAndTimestampLessThanOrderByTimestampAsc(request.symbol(), endExclusive));
    }
}
