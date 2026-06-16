package com.tossinvest.tossinvestbackend.account;

import com.tossinvest.tossinvestbackend.client.TossApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 계좌 조회 서비스.
 * TODO: 실제 가이드 문서 확인 후 경로/쿼리파라미터 보정 필요.
 */
@Service
@RequiredArgsConstructor
public class AccountService {

    private final TossApiClient apiClient;

    public AccountResponse getAccounts() {
        return apiClient.get(
                uriBuilder -> uriBuilder.path("/api/v1/accounts").build(),
                AccountResponse.class
        );
    }
}
