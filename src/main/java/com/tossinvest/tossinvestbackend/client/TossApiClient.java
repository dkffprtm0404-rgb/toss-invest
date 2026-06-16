package com.tossinvest.tossinvestbackend.client;

import com.tossinvest.tossinvestbackend.oauth.TossOAuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 토스증권 Open API 호출 래퍼.
 * 모든 요청에 발급된 access token을 Bearer로 첨부한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TossApiClient {

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
     */
    public <T> T get(java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI> uriFunction, Class<T> responseType) {
        String accessToken = oAuthService.getAccessToken();

        return tossWebClient.get()
                .uri(uriFunction)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(responseType)
                .block();
    }
}
