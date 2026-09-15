package com.tossinvest.tossinvestbackend.strategy.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.tossinvest.tossinvestbackend.strategy.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Splits independent strategy drafts while retaining exact source text and per-item validation. */
@Service
public class StrategyBatchService {
    static final String INSTRUCTIONS = StrategyAssistantService.RULES + """
            입력의 여러 독립 전략을 원래 순서로 모두 분리해 items에 반환한다. 하나만 선택하라고 질문하지 않는다.
            각 item은 title, sourceText, strategy, questions, unsupported를 가진다. 단일 전략이면 item 하나다.
            sourceText는 해당 전략의 제목과 본문을 원문에서 연속된 문자열 그대로 복사한다. 오타·기호·공백·줄바꿈을 고치지 않는다.
            sharedContext는 모든 전략에 공통 적용되는 원문 앞부분의 머리말만 정확히 복사한다. 없으면 빈 문자열이다.
            sharedContext와 순서대로 나열한 sourceText들이 원문의 공백을 제외한 모든 내용을 포함해야 한다. 제목/번호도 버리지 않는다.
            공통 조건은 각 전략에 적용한다. 다른 전략의 손절·지표·기간은 절대 섞지 않는다.
            각 strategy.originalPrompt는 null이다. 서버가 정확한 공통 머리말과 해당 원문을 넣는다.
            보완 질문과 미지원 사항은 반드시 해당 item에만 기록한다. 하나가 미지원이어도 다른 전략을 정상 구조화한다.
            모든 조건이 미지원이면 해당 item.strategy는 null이어도 title/sourceText와 이유는 유지한다.
            최상위 questions는 전체 입력을 나눌 수 없는 경우에만 사용한다. 그 경우 items=[]로 반환한다.
            10개를 넘는 독립 전략이면 items=[]와 10개 이하로 나눠 달라는 최상위 질문을 반환한다. 일부만 버리거나 임의로 합치지 않는다.
            전략 외 입력만 있으면 sharedContext="", items=[], questions에 전략을 입력해 달라는 질문을 반환한다.
            """;
    private final CodexClient codex;
    private final StrategyJson json;
    private final StrategyAssistantService assistant;

    public StrategyBatchService(CodexClient codex, StrategyJson json, StrategyAssistantService assistant) {
        this.codex = codex; this.json = json; this.assistant = assistant;
    }
    public record Request(String prompt) { }
    public record ItemOutput(String title, String sourceText, StrategyDefinition strategy, List<String> questions, List<String> unsupported) { }
    public record Output(String sharedContext, List<ItemOutput> items, List<String> questions) { }
    public record Item(String title, String prompt, StrategyAssistantService.Draft draft) { }
    public record Result(List<Item> items, List<String> questions) { }

    public Result interpret(Request request) {
        if (request == null) throw new StrategyJson.InvalidRequestException();
        if (request.prompt() == null || request.prompt().isBlank() || request.prompt().length() > 6000)
            throw new StrategyValidationException(List.of(new StrategyValidator.Issue("prompt", "INVALID", "전략을 1~6000자로 입력해 주세요.")));
        Output output;
        try { output = json.read(codex.interpretBatch(json.write(request)), Output.class); }
        catch (JsonProcessingException | StrategyJson.InvalidRequestException ex) { throw invalid(); }
        if (output.sharedContext() == null || output.items() == null || output.items().size() > 10
                || !StrategyAssistantService.validMessages(output.questions())) throw invalid();
        if (output.items().isEmpty()) {
            if (output.questions().isEmpty()) throw invalid();
            return new Result(List.of(), List.copyOf(output.questions()));
        }
        // Shared questions must never silently block otherwise valid individual drafts.
        if (!output.questions().isEmpty()) throw invalid();
        String original = request.prompt();
        String context = output.sharedContext();
        if (!original.startsWith(context)) throw invalid();
        int cursor = context.length();
        List<Item> items = new ArrayList<>();
        for (ItemOutput item : output.items()) {
            if (item == null || item.title() == null || item.title().isBlank() || item.title().length() > 200
                    || item.sourceText() == null || item.sourceText().isBlank()) throw invalid();
            int start = original.indexOf(item.sourceText(), cursor);
            if (start < cursor || !original.substring(cursor, start).isBlank()) throw invalid();
            String prompt = context + original.substring(cursor, start) + item.sourceText();
            cursor = start + item.sourceText().length();
            if (prompt.length() > 6000) throw invalid();
            var draft = assistant.fromOutput(new StrategyAssistantService.Output(item.strategy(), item.questions(), item.unsupported()), prompt);
            items.add(new Item(item.title(), prompt, draft));
        }
        if (!original.substring(cursor).isBlank()) throw invalid();
        return new Result(List.copyOf(items), List.of());
    }
    private static AssistantException invalid() { return new AssistantException("CODEX_INVALID_RESPONSE"); }
}
