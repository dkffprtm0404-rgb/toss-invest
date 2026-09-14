package com.tossinvest.tossinvestbackend.strategy.assistant;

/** Deliberately never includes provider output, credentials, or local paths. */
public class AssistantException extends RuntimeException {
    private final String code;
    public AssistantException(String code) {
        super(switch (code) {
            case "CODEX_NOT_AVAILABLE" -> "Codex를 실행할 수 없습니다. 설치와 실행 경로를 확인해 주세요.";
            case "CODEX_LOGIN_REQUIRED" -> "터미널에서 codex login으로 ChatGPT 계정에 로그인해 주세요.";
            case "CODEX_API_KEY_NOT_ALLOWED" -> "API 키 인증은 사용할 수 없습니다. ChatGPT 구독 로그인만 허용합니다.";
            case "CODEX_TIMEOUT" -> "Codex 응답 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.";
            case "CODEX_BUSY" -> "다른 AI 요청을 처리 중입니다. 완료 후 다시 시도해 주세요.";
            case "EXPLANATION_TOO_LARGE" -> "이 실행은 설명 입력 크기 제한을 초과했습니다. 수치와 거래 근거는 계속 조회할 수 있습니다.";
            case "CODEX_LIMIT_REACHED" -> "Codex 구독 사용 한도에 도달했습니다. 한도 복구 후 다시 시도해 주세요. API 과금으로 전환하지 않습니다.";
            case "CODEX_INVALID_RESPONSE" -> "Codex 응답 형식이 올바르지 않습니다. 입력을 구체화해 다시 시도해 주세요.";
            case "LOCAL_ONLY" -> "이 기능은 본인 PC의 동일 출처 화면에서만 사용할 수 있습니다.";
            default -> "Codex 호출에 실패했습니다. 구독 한도·모델 사용 가능 여부·연결 상태를 확인해 주세요.";
        });
        this.code = code;
    }
    public String code() { return code; }
}
