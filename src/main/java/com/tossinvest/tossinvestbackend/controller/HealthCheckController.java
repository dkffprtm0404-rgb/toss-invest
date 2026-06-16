package com.tossinvest.tossinvestbackend.controller;

import com.tossinvest.tossinvestbackend.client.TossApiClient;
import com.tossinvest.tossinvestbackend.marketinfo.ExchangeRateResponse;
import com.tossinvest.tossinvestbackend.oauth.TossOAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * OAuth 연결 및 API 호출이 정상 동작하는지 확인하기 위한 임시 컨트롤러.
 * 추후 실제 도메인 컨트롤러로 대체/이동 예정.
 */
@RestController
@RequiredArgsConstructor
public class HealthCheckController {

    private final TossOAuthService oAuthService;
    private final TossApiClient apiClient;

    @GetMapping("/api/health/token")
    public Map<String, Object> checkToken() {
        String token = oAuthService.getAccessToken();
        return Map.of(
                "status", "ok",
                "tokenPrefix", token.substring(0, Math.min(10, token.length())) + "...",
                "tokenLength", token.length()
        );
    }

    @GetMapping("/api/health/exchange-rate")
    public ExchangeRateResponse checkExchangeRate(
            @RequestParam(defaultValue = "USD") String baseCurrency,
            @RequestParam(defaultValue = "KRW") String quoteCurrency
    ) {
        return apiClient.get(
                uriBuilder -> uriBuilder.path("/api/v1/exchange-rate")
                        .queryParam("baseCurrency", baseCurrency)
                        .queryParam("quoteCurrency", quoteCurrency)
                        .build(),
                ExchangeRateResponse.class
        );
    }
}

