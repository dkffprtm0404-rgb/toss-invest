package com.tossinvest.tossinvestbackend.strategy;

import com.tossinvest.tossinvestbackend.backtest.SavedBacktestRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class SavedStrategyService {
    private final SavedStrategyRepository strategies;
    private final SavedStrategyVersionRepository versions;
    private final StrategyValidator validator;
    private final StrategyJson json;
    private final SavedBacktestRepository runs;
    private final com.tossinvest.tossinvestbackend.comparison.ComparisonRepository comparisons;
    private final List<StrategyDeletionGuard> deletionGuards;

    public SavedStrategyService(SavedStrategyRepository strategies, SavedStrategyVersionRepository versions,
                                StrategyValidator validator, StrategyJson json, SavedBacktestRepository runs,
                                com.tossinvest.tossinvestbackend.comparison.ComparisonRepository comparisons,
                                List<StrategyDeletionGuard> deletionGuards) {
        this.strategies = strategies;
        this.versions = versions;
        this.validator = validator;
        this.json = json;
        this.runs = runs;
        this.comparisons = comparisons;
        this.deletionGuards = deletionGuards;
    }

    public record SavedVersion(long id, int version, Instant createdAt, Instant versionCreatedAt, StrategyDefinition strategy) { }
    public record UpdateRequest(Integer expectedVersion, StrategyDefinition strategy) { }

    @Transactional
    public void delete(long id) {
        requireForUpdate(id);
        deletionGuards.forEach(guard -> guard.beforeDelete(id));
        comparisons.deleteAll(comparisons.forStrategy(id));
        // Remove dependent rows in one transaction; candle data belongs to the shared cache.
        runs.deleteForStrategy(id);
        versions.deleteForStrategy(id);
        strategies.deleteById(id);
    }

    public void requireForUpdate(long id) {
        strategies.findForUpdate(id).orElseThrow(() -> new NotFoundException("Strategy not found."));
    }

    @Transactional
    public SavedVersion create(StrategyDefinition definition) {
        validate(definition);
        Instant now = now();
        var strategy = strategies.save(new SavedStrategyEntity(now));
        return view(versions.save(new SavedStrategyVersionEntity(strategy, json.write(definition), now)));
    }

    @Transactional
    public SavedVersion update(long id, UpdateRequest request) {
        if (request == null) throw new StrategyJson.InvalidRequestException();
        requirePositive(request.expectedVersion(), "expectedVersion");
        validate(request.strategy());
        var strategy = strategies.findForUpdate(id).orElseThrow(() -> new NotFoundException("Strategy not found."));
        if (strategy.getCurrentVersion() != request.expectedVersion()) throw new VersionConflictException();
        Instant now = now();
        strategy.advance(now);
        return view(versions.save(new SavedStrategyVersionEntity(strategy, json.write(request.strategy()), now)));
    }

    public SavedVersion get(long id) {
        var strategy = strategies.findById(id).orElseThrow(() -> new NotFoundException("Strategy not found."));
        return version(id, strategy.getCurrentVersion());
    }

    public SavedVersion version(long id, int version) { return view(versionEntity(id, version)); }

    public SavedStrategyVersionEntity versionEntity(long id, int version) {
        return versions.findByStrategyIdAndVersion(id, version)
                .orElseThrow(() -> new NotFoundException("Strategy version not found."));
    }

    public List<SavedVersion> list(int page, int size) {
        return strategies.findAll(page(page, size).withSort(Sort.by(Sort.Direction.DESC, "updatedAt", "id")))
                .stream().map(s -> version(s.getId(), s.getCurrentVersion())).toList();
    }

    public List<SavedVersion> versions(long id, int page, int size) {
        var pageable = page(page, size);
        requireExists(id);
        return versions.findByStrategyIdOrderByVersionDesc(id, pageable).stream().map(this::view).toList();
    }

    public void requireExists(long id) {
        if (!strategies.existsById(id)) throw new NotFoundException("Strategy not found.");
    }

    public SavedVersion view(SavedStrategyVersionEntity version) {
        return new SavedVersion(version.getStrategy().getId(), version.getVersion(), version.getStrategy().getCreatedAt(),
                version.getCreatedAt(), json.stored(version.getDefinitionJson(), StrategyDefinition.class));
    }

    private void validate(StrategyDefinition definition) {
        var issues = new ArrayList<>(validator.validate(definition));
        if (definition != null && (definition.name() == null || definition.name().isBlank() || definition.name().length() > 200))
            issues.add(new StrategyValidator.Issue("name", "INVALID", "Supply a non-blank name of at most 200 characters."));
        if (!issues.isEmpty()) throw new StrategyValidationException(issues);
    }

    public static void requirePositive(Integer value, String path) {
        if (value == null || value < 1) throw new StrategyValidationException(List.of(
                new StrategyValidator.Issue(path, "INVALID", "Supply a positive version number.")));
    }

    public static PageRequest page(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new StrategyValidationException(List.of(
                new StrategyValidator.Issue("page", "INVALID", "Use page >= 0 and size between 1 and 100.")));
        return PageRequest.of(page, size);
    }

    // H2 timestamps preserve microseconds; normalize before embedding the same value in JSON.
    public static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }
    public static class VersionConflictException extends RuntimeException {
        public VersionConflictException() { super("The strategy has changed. Reload its current version before updating."); }
    }
}
