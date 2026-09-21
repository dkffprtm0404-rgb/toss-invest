package com.tossinvest.tossinvestbackend.strategy.assistant;

import com.tossinvest.tossinvestbackend.strategy.*;
import java.util.*;
import static com.tossinvest.tossinvestbackend.strategy.StrategyDefinition.*;
import static com.tossinvest.tossinvestbackend.strategy.CompositionDefinition.*;

/** Deterministic composition of extracted drafts. Never calls the interpretation model. */
public final class StrategyComposer {
    public record Request(List<StrategyBatchService.Item> items, String name) { }
    public StrategyAssistantService.Draft compose(Request request) {
        if(request==null || request.items()==null || request.items().size()<2 || request.items().size()>10)
            throw invalid("items","조합할 개별 초안을 2~10개 선택해 주세요.");
        List<Source> sources=new ArrayList<>(); List<RuleNode> entries=new ArrayList<>(), exits=new ArrayList<>();
        RelativeStrength selection=null; String selectionSource=null; int selectors=0;
        List<Risk> risks=new ArrayList<>();
        for(int i=0;i<request.items().size();i++) {
            var item=request.items().get(i); String id="source-"+(i+1);
            if(item==null || item.draft()==null || item.title()==null || item.title().isBlank() || item.title().length()>200
                    || item.prompt()==null || item.prompt().isBlank() || item.prompt().length()>12050
                    || !StrategyAssistantService.validMessages(item.draft().questions()) || !StrategyAssistantService.validMessages(item.draft().unsupported()))
                throw invalid("items["+i+"]","제목·원문·개별 초안을 확인해 주세요.");
            var d=item.draft().strategy();
            if(d!=null && (d.composition()!=null || Integer.valueOf(4).equals(d.schemaVersion())))
                throw invalid("items["+i+"]","조합 결과를 다시 조합할 수 없습니다. 원래 개별 초안을 선택해 주세요.");
            sources.add(new Source(id,item.title(),item.prompt(),d,item.draft().questions(),item.draft().unsupported(),true,null));
            risks.add(d==null?null:d.risk());
            if(d==null) continue;
            if(d.entry()!=null) entries.add(branch(id,"entry",d.entry()));
            if(d.exit()!=null) exits.add(branch(id,"exit",d.exit()));
            if(d.portfolio()!=null) {selection=d.portfolio();selectionSource=id;selectors++;}
        }
        if(selectors!=1) {selection=null;selectionSource=null;}
        Risk risk=risks.stream().allMatch(r->Objects.equals(r,risks.get(0)))?risks.get(0):null;
        String name=request.name()==null || request.name().isBlank()?"선택 조합 · "+sources.size()+"개":request.name();
        if(name.length()>200) throw invalid("name","전략 이름은 200자 이하입니다.");
        var composition=new CompositionDefinition(sources,root("entry",entries),root("exit",exits),selection,selectionSource,null,null,false,false);
        String original=String.join("\n\n",sources.stream().map(Source::prompt).toList());
        var strategy=new StrategyDefinition(4,name,original,null,null,risk,null,composition);
        var issues=new StrategyValidator().validate(strategy);
        return new StrategyAssistantService.Draft(strategy,issues,List.of(),List.of(),issues.isEmpty());
    }
    private RuleNode branch(String source,String phase,ConditionGroup group) {
        List<RuleNode> children=new ArrayList<>();
        if(group.conditions()!=null) for(int i=0;i<group.conditions().size();i++)
            children.add(new RuleNode(source+"-"+phase+"-"+(i+1),source,group.conditions().get(i),null,null));
        return new RuleNode(source+"-"+phase,source,null,group.operator(),children);
    }
    private RuleNode root(String phase,List<RuleNode> children) {
        return children.isEmpty()?null:children.size()==1?children.get(0):new RuleNode(phase+"-root",null,null,null,children);
    }
    private StrategyValidationException invalid(String path,String message) {
        return new StrategyValidationException(List.of(new StrategyValidator.Issue(path,"INVALID",message)));
    }
}
