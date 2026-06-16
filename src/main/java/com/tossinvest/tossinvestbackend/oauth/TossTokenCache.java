package com.tossinvest.tossinvestbackend.oauth;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 발급받은 access token을 메모리에 캐싱한다.
 * 만료 60초 전을 만료로 간주해 race condition을 줄인다.
 */
@Component
public class TossTokenCache {

    private static final long EXPIRY_BUFFER_SECONDS = 60L;

    private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();

    public String getValidTokenOrNull() {
        CachedToken token = cachedToken.get();
        if (token == null) {
            return null;
        }
        if (Instant.now().isAfter(token.expiresAt())) {
            return null;
        }
        return token.accessToken();
    }

    public void put(String accessToken, long expiresInSeconds) {
        Instant expiresAt = Instant.now().plusSeconds(expiresInSeconds - EXPIRY_BUFFER_SECONDS);
        cachedToken.set(new CachedToken(accessToken, expiresAt));
    }

    public void clear() {
        cachedToken.set(null);
    }

    private record CachedToken(String accessToken, Instant expiresAt) {
    }
}
