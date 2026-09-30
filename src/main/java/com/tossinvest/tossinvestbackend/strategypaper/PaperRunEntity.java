package com.tossinvest.tossinvestbackend.strategypaper;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="strategy_paper_run",indexes=@Index(name="idx_strategy_paper_strategy",columnList="strategyId"))
public class PaperRunEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false) private long strategyId;
    @Column(nullable=false,length=80,unique=true) private String requestId;
    @Column(length=80,unique=true) private String activeKey;
    @Column(nullable=false,length=6) private String symbol;
    @Column(nullable=false,length=20) private String status;
    @Column(nullable=false) private Instant createdAt;
    @Column(nullable=false) private Instant updatedAt;
    @Lob @Column(nullable=false) private String definitionJson;
    @Lob @Column(nullable=false) private String strategyJson;
    @Lob @Column(nullable=false) private String stateJson;
    @Column(nullable=false,length=30) private String market;
    protected PaperRunEntity() { }
    public PaperRunEntity(PaperDefinition request,String market,String definition,String strategy,String state,Instant now) {
        strategyId=request.strategyId();requestId=request.requestId();symbol=request.symbol();activeKey=strategyId+":"+symbol;
        this.market=market;definitionJson=definition;strategyJson=strategy;stateJson=state;status="RUNNING";createdAt=now;updatedAt=now;
    }
    public Long getId(){return id;} public long getStrategyId(){return strategyId;} public String getSymbol(){return symbol;}
    public String getStatus(){return status;} public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
    public String getDefinitionJson(){return definitionJson;} public String getStrategyJson(){return strategyJson;}
    public String getStateJson(){return stateJson;} public String getMarket(){return market;}
    public void checkpoint(PaperState state,String json,Instant now){stateJson=json;status=state.status;updatedAt=now;if("STOPPED".equals(status))activeKey=null;}
}
