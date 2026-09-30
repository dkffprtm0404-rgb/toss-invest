package com.tossinvest.tossinvestbackend.strategy;

/** Called with the saved strategy's write lock, before dependent history is deleted. */
public interface StrategyDeletionGuard {
    void beforeDelete(long strategyId);
}
