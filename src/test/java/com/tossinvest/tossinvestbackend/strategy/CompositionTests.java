package com.tossinvest.tossinvestbackend.strategy;

import com.tossinvest.tossinvestbackend.strategy.assistant.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static com.tossinvest.tossinvestbackend.strategy.StrategyDefinition.*;
import static com.tossinvest.tossinvestbackend.strategy.CompositionDefinition.*;
import static org.assertj.core.api.Assertions.*;

class CompositionTests {
    private final StrategyValidator validator = new StrategyValidator();
    private StrategyBatchService.Item item(String name, String rate) {
        var strategy = new StrategyDefinition(2, name, name, new ConditionGroup(Operator.AND,
                List.of(new MovingAverageCross(AverageType.SMA,5,20,Direction.UP),
                        new MovingAverageCompare(AverageType.SMA,20,200,Comparison.GT))),
                null, new Risk(new StopLoss(new BigDecimal(rate)),null,null,null));
        return new StrategyBatchService.Item(name,name,new StrategyAssistantService.Draft(strategy,List.of(),List.of(),List.of(),true));
    }
    @Test void compositionCopiesBranchesAndLeavesDecisionsUnresolved() {
        var a=item("돌파","-0.05"); var b=item("추세","-0.08");
        var draft=new StrategyComposer().compose(new StrategyComposer.Request(List.of(a,b),"선택 조합"));
        var s=draft.strategy();
        assertThat(s.schemaVersion()).isEqualTo(4);
        assertThat(s.composition().sources()).hasSize(2);
        assertThat(s.composition().entry().operator()).isNull();
        assertThat(s.composition().entry().children().get(0).operator()).isEqualTo(Operator.AND);
        assertThat(s.composition().entry().children().get(0).children()).hasSize(2);
        assertThat(s.risk()).isNull();
        assertThat(draft.ready()).isFalse();
        assertThat(draft.issues()).extracting(StrategyValidator.Issue::path)
                .contains("composition.entry.operator","composition.riskConfirmed","composition.allocation");
        assertThat(a.draft().strategy().schemaVersion()).isEqualTo(2);
        assertThat(a.draft().strategy().risk().stopLoss().rate()).isEqualByComparingTo("-0.05");
    }
    @Test void unsupportedSourceDoesNotDisappearFromComposite() {
        var invalid=new StrategyBatchService.Item("외부 신호","외부 신호",new StrategyAssistantService.Draft(null,List.of(),List.of(),List.of("외부 신호 미지원"),false));
        var d=new StrategyComposer().compose(new StrategyComposer.Request(List.of(item("정상","-0.05"),invalid),null));
        assertThat(d.strategy().composition().sources()).hasSize(2);
        assertThat(d.ready()).isFalse();
        assertThat(d.issues()).anyMatch(i->i.path().startsWith("composition.sources[1]"));
    }
    private StrategyDefinition resolved() {
        var d=new StrategyComposer().compose(new StrategyComposer.Request(List.of(item("A","-0.05"),item("B","-0.08")),null)).strategy();
        var c=d.composition();
        var entry=new RuleNode(c.entry().id(),null,null,Operator.OR,c.entry().children());
        return with(d,new CompositionDefinition(c.sources(),entry,null,null,null,null,new Allocation(1,Rebalance.ENTRY_ONLY),true,true));
    }
    private StrategyDefinition with(StrategyDefinition d,CompositionDefinition c) {return new StrategyDefinition(4,d.name(),d.originalPrompt(),null,null,d.risk(),null,c);}
    @Test void explicitCommonPoliciesAllowIndependentResolvedSnapshotAndJsonRoundTrip() throws Exception {
        var d=resolved();assertThat(validator.validate(d)).isEmpty();
        var json=new StrategyJson(new com.fasterxml.jackson.databind.ObjectMapper());
        assertThat(json.read(json.write(d),StrategyDefinition.class)).isEqualTo(d);
        var list=new java.util.ArrayList<>(d.composition().sources());
        var c=new CompositionDefinition(list,d.composition().entry(),null,null,null,null,new Allocation(1,Rebalance.ENTRY_ONLY),true,true);
        list.clear();assertThat(c.sources()).hasSize(2);
    }
    @Test void silentConditionDeletionAndMovingAnOriginalFilterOutOfItsGroupRequireReason() {
        var d=resolved();var c=d.composition();var a=c.entry().children().get(0);
        var changed=new RuleNode(a.id(),a.sourceId(),null,Operator.OR,a.children());
        var entry=new RuleNode("entry-root",null,null,Operator.OR,List.of(changed,c.entry().children().get(1)));
        var bad=with(d,new CompositionDefinition(c.sources(),entry,null,null,null,null,c.allocation(),true,true));
        assertThat(validator.validate(bad)).extracting(StrategyValidator.Issue::path).contains("composition.sources[0].resolution");
        var editedSources=new java.util.ArrayList<>(c.sources());var s=editedSources.get(0);
        editedSources.set(0,new Source(s.id(),s.title(),s.prompt(),s.definition(),s.questions(),s.unsupported(),true,"원문 AND를 OR로 변경하여 필터를 선택 조건으로 적용"));
        assertThat(validator.validate(with(d,new CompositionDefinition(editedSources,entry,null,null,null,null,c.allocation(),true,true)))).isEmpty();
    }
    @Test void orphanDuplicateIdsAndOverdeepGroupsAreRejected() {
        var d=resolved();var c=d.composition();var leaf=new RuleNode("duplicate","missing",new PriceMovingAverage(AverageType.SMA,2,Comparison.GT),null,null);
        RuleNode tree=new RuleNode("root",null,null,Operator.OR,List.of(leaf,leaf));
        var bad=with(d,new CompositionDefinition(c.sources(),tree,null,null,null,null,c.allocation(),true,true));
        assertThat(validator.validate(bad)).extracting(StrategyValidator.Issue::path).contains("composition.entry.children[0].sourceId","composition.entry.children[1].id");
        for(int i=0;i<4;i++)tree=new RuleNode("group-"+i,null,null,Operator.AND,List.of(tree));
        assertThat(validator.validate(with(d,new CompositionDefinition(c.sources(),tree,null,null,null,null,c.allocation(),true,true))))
                .anyMatch(i->i.message().contains("최대 3단계"));
    }
    @Test void excludedSourceRemainsButItsConditionsCannotExecute() {
        var d=resolved();var c=d.composition();var sources=new java.util.ArrayList<>(c.sources());var s=sources.get(0);
        sources.set(0,new Source(s.id(),s.title(),s.prompt(),s.definition(),List.of(),List.of(),false,"이번 테스트 제외"));
        assertThat(validator.validate(with(d,new CompositionDefinition(sources,c.entry(),null,null,null,null,c.allocation(),true,true))))
                .anyMatch(i->i.path().endsWith("sourceId"));
        assertThat(validator.validate(with(d,new CompositionDefinition(sources,c.entry().children().get(1),null,null,null,null,c.allocation(),true,true)))).isEmpty();
    }
}
