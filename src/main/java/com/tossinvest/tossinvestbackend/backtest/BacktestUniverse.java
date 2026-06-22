package com.tossinvest.tossinvestbackend.backtest;

import java.util.List;

/**
 * 백테스트 대상 종목 유니버스. 코스피/코스닥 대표 종목으로 시장 대표성을 확보한다.
 * 보유 종목(005930, 102940) 포함 + 시가총액/업종 다양성을 고려해 구성했다.
 */
public final class BacktestUniverse {

    private BacktestUniverse() {}

    /** 코스피 종목 (대형주 중심) */
    public static final List<String> KOSPI_SYMBOLS = List.of(
            "005930", // 삼성전자
            "000660", // SK하이닉스
            "005380", // 현대차
            "051910", // LG화학
            "035420", // NAVER
            "012330"  // 현대모비스
    );

    /** 코스닥 종목 (중소형주, 기존 보유종목 포함) */
    public static final List<String> KOSDAQ_SYMBOLS = List.of(
            "950160", // 코오롱티슈진
            "086520", // 에코프로
            "247540", // 에코프로비엠
            "091990"  // 셀트리온헬스케어
    );

    public static List<String> all() {
        List<String> combined = new java.util.ArrayList<>(KOSPI_SYMBOLS);
        combined.addAll(KOSDAQ_SYMBOLS);
        return combined;
    }
}
