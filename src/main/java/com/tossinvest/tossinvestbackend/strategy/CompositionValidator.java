package com.tossinvest.tossinvestbackend.strategy;

import java.util.*;
import static com.tossinvest.tossinvestbackend.strategy.CompositionDefinition.*;
import static com.tossinvest.tossinvestbackend.strategy.StrategyDefinition.*;
import static com.tossinvest.tossinvestbackend.strategy.StrategyValidator.Issue;

/** Validates execution rules separately from the preserved, possibly incomplete source drafts. */
final class CompositionValidator {
    List<Issue> validate(StrategyDefinition s) {
        List<Issue> out = new ArrayList<>();
        var c = s.composition();
        if (c == null) { issue(out,"composition","통합 조건을 입력해 주세요."); return List.copyOf(out); }
        if (s.entry()!=null || s.exit()!=null || s.portfolio()!=null)
            issue(out,"composition","버전 4는 composition의 조건과 선정 규칙만 사용합니다.");
        if (!Boolean.TRUE.equals(c.riskConfirmed())) issue(out,"composition.riskConfirmed","공통 위험 관리의 적용·제외와 추가 매수 시 기준을 확인해 주세요.");
        if (!Boolean.TRUE.equals(c.exitConfirmed())) issue(out,"composition.exitConfirmed","청산 조건을 모든 보유분에 적용할지 확인해 주세요.");
        if (c.allocation()==null) issue(out,"composition.allocation","최대 보유 수와 자금 배분 방식을 명시해 주세요.");
        else {
            if(c.allocation().maxPositions()==null || c.allocation().maxPositions()<1 || c.allocation().maxPositions()>100)
                issue(out,"composition.allocation.maxPositions","최대 보유 수는 1~100입니다.");
            if(c.allocation().rebalance()==null) issue(out,"composition.allocation.rebalance","신규 진입 시 배분 또는 주간 동일 비중을 선택해 주세요.");
            if(c.allocation().rebalance()==Rebalance.WEEKLY_EQUAL && c.selection()==null)
                issue(out,"composition.allocation.rebalance","주간 재조정은 선정 규칙의 주간 계산일이 필요합니다. 특정 종목 조합은 신규 진입 시 배분을 선택해 주세요.");
        }
        Map<String,Source> sources=new LinkedHashMap<>();
        if(c.sources()==null || c.sources().isEmpty() || c.sources().size()>10) issue(out,"composition.sources","원문 스냅샷을 1~10개 포함해 주세요.");
        else for(int i=0;i<c.sources().size();i++) {
            Source source=c.sources().get(i); String p="composition.sources["+i+"]";
            if(source==null) {issue(out,p,"원문이 없습니다.");continue;}
            if(!id(source.id()) || sources.putIfAbsent(source.id(),source)!=null) issue(out,p+".id","중복 없는 출처 ID가 필요합니다.");
            if(blank(source.title()) || source.title().length()>200 || blank(source.prompt()) || source.prompt().length()>12050) issue(out,p,"원문과 200자 이하 제목을 보존해 주세요.");
            if(source.included()==null) issue(out,p+".included","포함 여부를 선택해 주세요.");
            if(source.resolution()!=null && source.resolution().length()>6000) issue(out,p+".resolution","변경 사유는 6000자 이하입니다.");
            if(!messages(source.questions()) || !messages(source.unsupported())) issue(out,p,"원문의 질문·미지원 내역 형식을 확인해 주세요.");
            var d=source.definition();
            if(d!=null && (d.composition()!=null || d.schemaVersion()==null || d.schemaVersion()<1 || d.schemaVersion()>3)) issue(out,p+".definition","출처에는 기존 버전 1~3 초안만 보존할 수 있습니다.");
            if(Boolean.TRUE.equals(source.included())) {
                if(d==null) issue(out,p+".definition","구조화되지 않은 원문입니다. 개별 초안을 보완해 재조합하거나 제외 사유를 입력해 주세요.");
                if(source.unsupported()!=null && !source.unsupported().isEmpty()) issue(out,p+".unsupported","미지원 원문은 개별 초안을 보완해 재조합하거나 명시적으로 제외해 주세요.");
                if(source.questions()!=null && !source.questions().isEmpty() && blank(source.resolution())) issue(out,p+".resolution","원문 보완 질문의 답과 최종 적용 내용을 기록해 주세요.");
            } else if(blank(source.resolution())) issue(out,p+".resolution","원문 제외 사유를 기록해 주세요.");
        }
        if(sources.values().stream().noneMatch(x->Boolean.TRUE.equals(x.included()))) issue(out,"composition.sources","적어도 한 원문을 포함해 주세요.");
        Set<String> ids=new HashSet<>(); int[] leaves={0};
        if(c.entry()==null) issue(out,"composition.entry","신규 진입 조건이 필요합니다.");
        else node(c.entry(),"composition.entry",1,sources,ids,leaves,out);
        if(c.exit()!=null) node(c.exit(),"composition.exit",1,sources,ids,leaves,out);
        if(leaves[0]>30) issue(out,"composition","진입·청산 말단 조건은 합계 30개 이하입니다.");
        if(c.selection()!=null) {
            Source source=sources.get(c.selectionSourceId());
            if(source==null || !Boolean.TRUE.equals(source.included()) || source.definition()==null || source.definition().portfolio()==null)
                issue(out,"composition.selectionSourceId","포함한 상대강도 원문을 연결해 주세요.");
            if(c.rankExit()==null) issue(out,"composition.rankExit","주간 순위 이탈 시 청산 여부를 선택해 주세요.");
            relay(new StrategyDefinition(3,"selection",null,null,null,null,c.selection()),"portfolio","composition.selection",out);
        } else if(c.selectionSourceId()!=null || Boolean.TRUE.equals(c.rankExit())) issue(out,"composition.selection","순위 이탈 청산에는 선정 규칙이 필요합니다.");
        // Changes to original leaves, grouping or selection require a reviewable reason, not a silent deletion.
        for(var source:sources.values()) {
            if(!Boolean.TRUE.equals(source.included()) || source.definition()==null || !blank(source.resolution())) continue;
            var d=source.definition();
            if(!preserved(d.entry(),c.entry(),source.id()) || !preserved(d.exit(),c.exit(),source.id())
                    || d.portfolio()!=null && (!source.id().equals(c.selectionSourceId()) || !d.portfolio().equals(c.selection())))
                issue(out,"composition.sources["+c.sources().indexOf(source)+"].resolution","원문 조건·그룹·선정 규칙의 변경 또는 제외 사유를 기록해 주세요.");
        }
        // Reuse the established v2 risk validation without introducing a second definition of stop policies.
        var dummy=new ConditionGroup(null,List.of(new PriceMovingAverage(AverageType.SMA,1,Comparison.GTE)));
        relay(new StrategyDefinition(2,"risk",null,dummy,null,s.risk()),"risk","risk",out);
        if(s.risk()!=null && s.risk().trailing()!=null) issue(out,"risk.trailing","통합 전략은 고점 대비 비율 추적손절을 사용해 주세요. 기존 단계식 정책은 개별 버전 1~2에서 유지합니다.");
        return List.copyOf(out);
    }
    private void node(RuleNode n,String path,int depth,Map<String,Source> sources,Set<String> ids,int[] leaves,List<Issue> out) {
        if(n==null){issue(out,path,"빈 조건은 사용할 수 없습니다.");return;}
        if(!id(n.id()) || !ids.add(n.id())) issue(out,path+".id","조건·그룹 ID는 전체에서 중복되지 않아야 합니다.");
        if(n.sourceId()!=null && (!sources.containsKey(n.sourceId()) || !Boolean.TRUE.equals(sources.get(n.sourceId()).included())))
            issue(out,path+".sourceId","포함한 원문 ID를 연결해 주세요.");
        if(n.condition()!=null) {
            leaves[0]++;
            if(n.sourceId()==null) issue(out,path+".sourceId","말단 조건의 원문 출처가 필요합니다.");
            if(n.operator()!=null || n.children()!=null && !n.children().isEmpty()) issue(out,path,"말단 조건과 그룹을 혼용할 수 없습니다.");
            relay(new StrategyDefinition(2,"leaf",null,new ConditionGroup(null,List.of(n.condition())),null,null),"entry.conditions[0]",path+".condition",out);
        } else {
            if(depth>3) {issue(out,path,"조건 그룹은 최대 3단계입니다.");return;}
            if(n.children()==null || n.children().isEmpty() || n.children().size()>30) {issue(out,path+".children","그룹에 1~30개 조건을 입력해 주세요.");return;}
            if(n.children().size()>1 && n.operator()==null) issue(out,path+".operator","모두 충족(AND) 또는 하나 이상 충족(OR)을 선택해 주세요.");
            for(int i=0;i<n.children().size();i++) node(n.children().get(i),path+".children["+i+"]",depth+1,sources,ids,leaves,out);
        }
    }
    private boolean preserved(ConditionGroup original,RuleNode tree,String source) {
        List<RuleNode> nodes=new ArrayList<>(); collect(tree,source,nodes,0);
        var conditions=nodes.stream().filter(n->n.condition()!=null).map(RuleNode::condition).toList();
        if(original==null) return conditions.isEmpty();
        if(original.conditions()==null || !frequencies(original.conditions()).equals(frequencies(conditions))) return false;
        if(original.conditions().size()<=1) return true;
        return nodes.stream().anyMatch(n->n.condition()==null && n.operator()==original.operator() && n.children()!=null
                && n.children().stream().allMatch(x->x!=null && source.equals(x.sourceId()) && x.condition()!=null)
                && frequencies(n.children().stream().map(RuleNode::condition).toList()).equals(frequencies(original.conditions())));
    }
    private Map<Condition,Integer> frequencies(List<Condition> list) {Map<Condition,Integer> m=new HashMap<>();for(var x:list)m.merge(x,1,Integer::sum);return m;}
    private void collect(RuleNode n,String s,List<RuleNode> out,int depth){if(n==null || depth>4)return;if(s.equals(n.sourceId()))out.add(n);if(n.children()!=null)n.children().forEach(x->collect(x,s,out,depth+1));}
    private void relay(StrategyDefinition s,String from,String to,List<Issue> out){for(var i:new StrategyValidator().validate(s))if(i.path().startsWith(from))out.add(new Issue(to+i.path().substring(from.length()),i.code(),i.message()));}
    private boolean id(String s){return s!=null && s.matches("[A-Za-z0-9_-]{1,80}");}
    private boolean blank(String s){return s==null || s.isBlank();}
    private boolean messages(List<String> x){return x!=null && x.size()<=50 && x.stream().allMatch(v->v!=null && !v.isBlank() && v.length()<=2000);}
    private void issue(List<Issue> out,String path,String message){out.add(new Issue(path,"INVALID",message));}
}
