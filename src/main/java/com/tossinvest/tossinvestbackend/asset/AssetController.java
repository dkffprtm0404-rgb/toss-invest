package com.tossinvest.tossinvestbackend.asset;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/holdings")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;

    /**
     * accountSeq: /api/accounts 응답의 accountSeq 값 (X-Tossinvest-Account 헤더로 전달됨)
     * symbol: 특정 종목으로 필터링 (선택)
     */
    @GetMapping
    public HoldingResponse getHoldings(
            @RequestParam Long accountSeq,
            @RequestParam(required = false) String symbol
    ) {
        return assetService.getHoldings(accountSeq, symbol);
    }
}
