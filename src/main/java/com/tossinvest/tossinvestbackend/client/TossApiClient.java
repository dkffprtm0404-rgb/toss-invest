package com.tossinvest.tossinvestbackend.client;

import com.tossinvest.tossinvestbackend.oauth.TossOAuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.util.function.Function;

/**
 * 토스증권 Open API 호출 래퍼.
 * 모든 요청에 발급된 access token을 Bearer로 첨부한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TossApiClient {

    private static final String ACCOUNT_HEADER = "X-Tossinvest-Account";

    private final WebClient tossWebClient;
    private final TossOAuthService oAuthService;

    /**
     * GET 요청 공통 처리. responseType으로 역직렬화할 클래스를 지정한다.
     */
    public <T> T get(String uri, Class<T> responseType) {
        String accessToken = oAuthService.getAccessToken();

        return tossWebClient.get()
                .uri(uri)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .onStatus(status -> status.is4xxClientError(), response ->
                        response.bodyToMono(String.class).defaultIfEmpty("(empty body)")
                                .flatMap(body -> {
                                    log.warn("[TossApiClient] {} 응답 - 요청: {}, 바디: {}", response.statusCode(), uri, body);
                                    return reactor.core.publisher.Mono.error(new RuntimeException(
                                            "토스 API " + response.statusCode() + " 응답 바디: " + body));
                                }))
                .bodyToMono(responseType)
                .block();
    }

    /**
     * 원본 JSON 문자열 그대로 받고 싶을 때 사용 (디버깅/연결 확인용).
     */
    public String getRaw(String uri) {
        String accessToken = oAuthService.getAccessToken();

        return tossWebClient.get()
                .uri(uri)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    /**
     * UriBuilder를 사용해 쿼리 파라미터가 필요한 GET 요청을 처리한다.
     * 4xx 응답 시 토스 API가 반환한 에러 응답 바디를 그대로 예외 메시지에 포함시켜 원인 파악이 쉽도록 한다.
     */
    public <T> T get(Function<UriBuilder, URI> uriFunction, Class<T> responseType) {
        String accessToken = oAuthService.getAccessToken();

        return tossWebClient.get()
                .uri(uriFunction)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .onStatus(status -> status.is4xxClientError(), response ->
                        response.bodyToMono(String.class).defaultIfEmpty("(empty body)")
                                .flatMap(body -> {
                                    log.warn("[TossApiClient] {} 응답 - 요청: {}, 바디: {}", response.statusCode(), uriFunction, body);
                                    return reactor.core.publisher.Mono.error(new RuntimeException(
                                            "토스 API " + response.statusCode() + " 응답 바디: " + body));
                                }))
                .bodyToMono(responseType)
                .block();
    }

    /**
     * 계좌 컨텍스트가 필요한 GET 요청 (X-Tossinvest-Account 헤더 첨부).
     */
    public <T> T getWithAccount(Function<UriBuilder, URI> uriFunction, String accountNumber, Class<T> responseType) {
        String accessToken = oAuthService.getAccessToken();

        return tossWebClient.get()
                .uri(uriFunction)
                .header("Authorization", "Bearer " + accessToken)
                .header(ACCOUNT_HEADER, accountNumber)
                .retrieve()
                .bodyToMono(responseType)
                .block();
    }
}
