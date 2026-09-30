package com.tossinvest.tossinvestbackend.strategypaper;

import com.tossinvest.tossinvestbackend.strategy.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Entire account checkpoint is atomically replaced under the run's database lock. */
public class PaperState {
    public String status="RUNNING", dataStatus="WAITING_DATA";
    public String engineVersion="SINGLE_PAPER_1", dataSource="TOSS_ADJUSTED_DAILY_PRIOR_DATE", dataSha256;
    public LocalDate firstTradingDate, lastProcessedDate;
    public Instant stoppedAt, lastObservedAt;
    public BigDecimal cash, realizedPnl=BigDecimal.ZERO, totalCosts=BigDecimal.ZERO,
            equity, unrealizedPnl=BigDecimal.ZERO, returnRate=BigDecimal.ZERO, peakEquity, maxDrawdown=BigDecimal.ZERO;
    public Position position;
    public Pending pending;
    public List<StrategyBar> bars=new ArrayList<>();
    public List<Trade> trades=new ArrayList<>();
    public List<Equity> curve=new ArrayList<>();
    public List<Log> logs=new ArrayList<>();
    public PaperState() { }
    public PaperState(BigDecimal capital,LocalDate first) {cash=capital;equity=capital;peakEquity=capital;firstTradingDate=first;}
    public record Position(int entryIndex, long quantity, BigDecimal entryPrice, BigDecimal costBasis, BigDecimal peakPrice) { }
    public record Pending(String side,LocalDate signalDate,String reason,List<StrategyEvaluator.Evidence> evidence) { }
    public record Trade(String side,LocalDate signalDate,LocalDate executionDate,Instant processedAt,long quantity,
                        BigDecimal price,BigDecimal fee,BigDecimal tax,String reason,List<StrategyEvaluator.Evidence> evidence,BigDecimal cashAfter) { }
    public record Equity(LocalDate date,BigDecimal value,BigDecimal cash,BigDecimal unrealizedPnl,BigDecimal returnRate,BigDecimal drawdown) { }
    public record Log(Instant at,String code,String message,LocalDate barDate) { }
    public void log(Instant at,String code,String message,LocalDate date) {
        logs.add(new Log(at,code,message,date));
    }
    public void stop(Instant now) {
        if(!"RUNNING".equals(status)) return;
        stoppedAt=now;
        if(pending!=null && "BUY".equals(pending.side())) {pending=null;log(now,"BUY_CANCELLED","중지 요청으로 대기 매수를 취소했습니다.",null);}
        status=position==null?"STOPPED":"STOPPING";
        log(now,status,position==null?"모의매매가 종료되었습니다.":"신규 매수를 중단했습니다. 보유분의 청산 조건을 계속 확인합니다.",null);
    }
}
