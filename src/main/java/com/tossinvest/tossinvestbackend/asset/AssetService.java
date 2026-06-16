package com.tossinvest.tossinvestbackend.asset;

import com.tossinvest.tossinvestbackend.client.TossApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 보유 종목(자산) 조회 서비스.
 * TODO: 실제 가이드 문서 확인 후 경로/헤더/응답구조 보정 필요.
 */
@Service
@RequiredArgsConstructor
public class AssetService {

    private final TossApiClient apiClient;

    public HoldingResponse getHoldings(String accountNumber) {
        return apiClient.getWithAccount(
                uriBuilder -> uriBuilder.path("/api/v1/holdings").build(),
                accountNumber,
                HoldingResponse.class
        );
    }
}
