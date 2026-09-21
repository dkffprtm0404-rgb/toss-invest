package com.tossinvest.tossinvestbackend.strategy;

import com.tossinvest.tossinvestbackend.composite.CompositeResult.NodeEvidence;
import java.math.BigDecimal;
import java.util.*;
import static com.tossinvest.tossinvestbackend.strategy.StrategyDefinition.*;
import static com.tossinvest.tossinvestbackend.strategy.CompositionDefinition.*;

/** Nested, three-state composition over the existing leaf evaluator and indicator implementation. */
public final class CompositionEvaluator {
    public record Decision(boolean ready,boolean matched,List<NodeEvidence> nodes) { }
    private final Map<Condition,StrategyEvaluator.Evaluation> leaves=new HashMap<>();
    private final List<BigDecimal> atr;
    public CompositionEvaluator(CompositionDefinition composition,Risk risk,List<StrategyBar> bars) {
        prepare(composition.entry(),bars);prepare(composition.exit(),bars);
        atr=risk==null||risk.atrStop()==null?null:StrategyIndicators.atr(bars,risk.atrStop().period(),risk.atrStop().method());
    }
    private void prepare(RuleNode node,List<StrategyBar> bars) {
        if(node==null)return;
        if(node.condition()!=null) leaves.computeIfAbsent(node.condition(),condition->{
            var group=new ConditionGroup(Operator.AND,List.of(condition));
            return new StrategyEvaluator().prepare(new StrategyDefinition(2,"leaf",null,group,group,null),bars);
        });
        else node.children().forEach(child->prepare(child,bars));
    }
    public Decision evaluate(RuleNode node,int index) {
        if(node==null)return new Decision(true,false,List.of());
        var nodes=new ArrayList<NodeEvidence>();
        boolean ready,matched;
        if(node.condition()!=null) {
            var leaf=leaves.get(node.condition());ready=leaf.entryReady(index);
            // Exit evaluation emits actual/reference values even when a leaf is not ready.
            var decision=leaf.exit(index,new StrategyEvaluator.PositionContext(BigDecimal.ONE,0,BigDecimal.ONE));
            matched=ready&&decision.matched();
            nodes.add(new NodeEvidence(node.id(),node.sourceId(),ready,matched,decision.evidence().stream()
                    .map(e->new StrategyEvaluator.Evidence(node.id(),e.type(),e.actualValue(),e.referenceValue(),e.previousActualValue(),e.previousReferenceValue(),e.matched())).toList()));
        } else {
            var children=node.children().stream().map(child->evaluate(child,index)).toList();
            boolean anyTrue=children.stream().anyMatch(Decision::matched);
            boolean anyFalse=children.stream().anyMatch(c->c.ready()&&!c.matched());
            boolean allReady=children.stream().allMatch(Decision::ready);
            ready=node.operator()==Operator.OR?anyTrue||allReady:anyFalse||allReady;
            matched=node.operator()==Operator.OR?anyTrue:children.stream().allMatch(c->c.ready()&&c.matched());
            nodes.add(new NodeEvidence(node.id(),node.sourceId(),ready,matched,List.of()));
            children.forEach(c->nodes.addAll(c.nodes()));
        }
        return new Decision(ready,matched,List.copyOf(nodes));
    }
    public BigDecimal entryAtr(int signalIndex,boolean nextOpen) {int index=nextOpen?signalIndex:signalIndex-1;return atr==null||index<0?null:atr.get(index);}
}
