package com.tossinvest.tossinvestbackend.asset;

import com.tossinvest.tossinvestbackend.client.TossApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 보유 종목(자산) 조회 서비스.
 * TODO: 정확한 경로/응답구조는 asset 가이드 문서 캡처로 보정 필요 (현재 holdings는 추정).
 */
@Service
@RequiredArgsConstructor
public class AssetService {

    private final TossApiClient apiClient;

    /**
     * accountSeq: /api/accounts 응답의 accountSeq 값. X-Tossinvest-Account 헤더로 전달된다.
     */
    public HoldingResponse getHoldings(Long accountSeq) {
        return apiClient.getWithAccount(
                uriBuilder -> uriBuilder.path("/api/v1/holdings").build(),
                String.valueOf(accountSeq),
                HoldingResponse.class
        );
    }
}
