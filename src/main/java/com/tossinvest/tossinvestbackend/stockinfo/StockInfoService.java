package com.tossinvest.tossinvestbackend.stockinfo;

import com.tossinvest.tossinvestbackend.client.TossApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 종목 정보 조회 서비스.
 * TODO: 실제 가이드 문서 확인 후 경로/쿼리파라미터 보정 필요.
 */
@Service
@RequiredArgsConstructor
public class StockInfoService {

    private final TossApiClient apiClient;

    public StockResponse getStocks(String code) {
        return apiClient.get(
                uriBuilder -> {
                    var builder = uriBuilder.path("/api/v1/stocks");
                    if (code != null && !code.isBlank()) {
                        builder.queryParam("code", code);
                    }
                    return builder.build();
                },
                StockResponse.class
        );
    }
}
