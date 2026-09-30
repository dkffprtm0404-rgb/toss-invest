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
                .onStatus(status -> status.isError(), response -> response.createException()
                        .doOnNext(error -> log.warn("[TossApiClient] HTTP {}", error.getStatusCode().value())))
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
     * HTTP 상태를 보존하여 호출 측에서 요청·인증·호출 제한·서버 오류를 구분한다.
     * 인증 정보나 제공업체 응답 원문은 로그에 출력하지 않는다.
     */
    public <T> T get(Function<UriBuilder, URI> uriFunction, Class<T> responseType) {
        String accessToken = oAuthService.getAccessToken();

        return tossWebClient.get()
                .uri(uriFunction)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .onStatus(status -> status.isError(), response -> response.createException()
                        .doOnNext(error -> log.warn("[TossApiClient] HTTP {}", error.getStatusCode().value())))
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
