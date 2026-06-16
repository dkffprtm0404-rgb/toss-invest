package com.tossinvest.tossinvestbackend.asset;

import com.tossinvest.tossinvestbackend.client.TossApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 보유 종목(자산) 조회 서비스.
 */
@Service
@RequiredArgsConstructor
public class AssetService {

    private final TossApiClient apiClient;

    /**
     * accountSeq: /api/accounts 응답의 accountSeq 값. X-Tossinvest-Account 헤더로 전달된다.
     * symbol: 특정 종목으로 필터링 (선택). 미지정 시 전체 보유 종목 기준 요약.
     */
    public HoldingResponse getHoldings(Long accountSeq, String symbol) {
        return apiClient.getWithAccount(
                uriBuilder -> {
                    var builder = uriBuilder.path("/api/v1/holdings");
                    if (symbol != null && !symbol.isBlank()) {
                        builder.queryParam("symbol", symbol);
                    }
                    return builder.build();
                },
                String.valueOf(accountSeq),
                HoldingResponse.class
        );
    }
}
