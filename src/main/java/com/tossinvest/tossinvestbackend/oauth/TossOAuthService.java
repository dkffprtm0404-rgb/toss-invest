package com.tossinvest.tossinvestbackend.oauth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 토스증권 Open API OAuth2 Client Credentials Grant 토큰 발급/캐싱.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TossOAuthService {

    private final WebClient tossWebClient;
    private final TossTokenCache tokenCache;

    @Value("${toss.api.oauth-token-uri}")
    private String oauthTokenUri;

    @Value("${toss.oauth.client-id}")
    private String clientId;

    @Value("${toss.oauth.client-secret}")
    private String clientSecret;

    /**
     * 캐시에 유효한 토큰이 있으면 그대로 반환하고, 없으면 새로 발급한다.
     */
    public synchronized String getAccessToken() {
        String cached = tokenCache.getValidTokenOrNull();
        if (cached != null) {
            return cached;
        }
        return issueNewToken();
    }

    private String issueNewToken() {
        log.info("토스증권 OAuth 토큰 발급 요청 시작");

        String basicAuth = encodeBasicAuth(clientId, clientSecret);

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "client_credentials");

        TossTokenResponse response = tossWebClient.post()
                .uri(oauthTokenUri)
                .header("Authorization", "Basic " + basicAuth)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(formData)
                .retrieve()
                .bodyToMono(TossTokenResponse.class)
                .block();

        if (response == null || response.getAccessToken() == null) {
            throw new IllegalStateException("토스증권 OAuth 토큰 발급 실패: 응답이 비어있음");
        }

        tokenCache.put(response.getAccessToken(), response.getExpiresIn());
        log.info("토스증권 OAuth 토큰 발급 성공 (만료까지 {}초)", response.getExpiresIn());

        return response.getAccessToken();
    }

    private String encodeBasicAuth(String id, String secret) {
        String raw = id + ":" + secret;
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
