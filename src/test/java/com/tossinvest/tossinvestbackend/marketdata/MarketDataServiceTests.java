package com.tossinvest.tossinvestbackend.marketdata;

import com.tossinvest.tossinvestbackend.client.TossApiClient;
import com.tossinvest.tossinvestbackend.oauth.TossOAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketDataServiceTests {
    private MarketDataService service(ExchangeFunction exchange) {
        var oauth = mock(TossOAuthService.class);
        when(oauth.getAccessToken()).thenReturn("test-token");
        return new MarketDataService(new TossApiClient(WebClient.builder()
                .baseUrl("https://market.test").exchangeFunction(exchange).build(), oauth));
    }

    @Test void paginationPreservesOffsetAfterOneQueryDecode() {
        var sent = new AtomicReference<URI>();
        var market = service(request -> {
            sent.set(request.url());
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                    .body("{\"result\":{\"candles\":[]}}").build());
        });
        market.getCandlesWithDateBefore("005930", "1d", 200, "2025-12-03T00:00:00+09:00");
        assertThat(URLDecoder.decode(sent.get().getRawQuery(), StandardCharsets.UTF_8))
                .isEqualTo("symbol=005930&interval=1d&count=200&adjusted=true&before=2025-12-03T00:00:00+09:00");
        assertThat(sent.get().getRawQuery()).doesNotContain("%252B");
    }

    @Test void providerHttpStatusIsRetainedForBothRequestPaths() {
        var market = service(request -> Mono.just(ClientResponse.create(HttpStatus.BAD_REQUEST)
                .header("Content-Type", "application/json")
                .body("{\"error\":{\"data\":{\"field\":\"before\",\"rule\":\"typeMismatch\"}}}").build()));
        assertThatThrownBy(() -> market.getCandles("005930", "1d", 200))
                .isInstanceOf(WebClientResponseException.BadRequest.class);
        assertThatThrownBy(() -> market.getCandlesWithDateBefore("005930", "1d", 200, "2025-12-03T00:00:00+09:00"))
                .isInstanceOf(WebClientResponseException.BadRequest.class);
    }
}
