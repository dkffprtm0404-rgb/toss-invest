package com.tossinvest.tossinvestbackend.comparison;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity @Immutable @Table(name="strategy_comparison")
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class ComparisonEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,updatable=false) private Instant createdAt;
    @Column(nullable=false,updatable=false,length=24) private String type;
    @Column(nullable=false,updatable=false,length=8000) private String strategyNamesJson;
    @Lob @Column(nullable=false,updatable=false) private String snapshotJson;
    @ElementCollection
    @CollectionTable(name="strategy_comparison_member",joinColumns=@JoinColumn(name="comparison_id"),
            indexes=@Index(name="idx_comparison_strategy",columnList="strategy_id"))
    @Column(name="strategy_id",nullable=false) private Set<Long> strategyIds=new LinkedHashSet<>();

    public ComparisonEntity(Instant createdAt,String type,String names,String snapshot,Set<Long> ids) {
        this.createdAt=createdAt;this.type=type;this.strategyNamesJson=names;this.snapshotJson=snapshot;
        this.strategyIds=new LinkedHashSet<>(ids);
    }
}
