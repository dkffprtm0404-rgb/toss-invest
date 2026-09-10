package com.tossinvest.tossinvestbackend.strategy;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SavedStrategyRepository extends JpaRepository<SavedStrategyEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SavedStrategyEntity s where s.id = :id")
    Optional<SavedStrategyEntity> findForUpdate(@Param("id") long id);
}
