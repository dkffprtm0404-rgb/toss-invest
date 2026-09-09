package com.tossinvest.tossinvestbackend.backtest;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 백테스트 및 페이퍼 트레이딩 대상 종목 유니버스.
 * scripts/fetch_candles.py로 수집한 종목들을 DB에서 동적으로 읽어온다.
 * 고정 리스트 대신 캔들 데이터가 충분한(MIN_CANDLES 이상) 종목을 자동으로 포함한다.
 *
 * 제외 종목:
 * - 102940(코오롱생명과학): 커스텀 가능 테스트 : 바이오주 제외
 * - 091990(셀트리온헬스케어): 2024년 합병으로 심볼 폐지
 * - 950160(코오롱티슈진): 커스텀 가능 테스트 : 바이오주 제외
 */
@Component
public class BacktestUniverse {

    private static final int MIN_CANDLES = 100;
    private static final List<String> EXCLUDE = List.of("102940", "091990", "950160");

    private final CandleRepository candleRepository;

    public BacktestUniverse(CandleRepository candleRepository) {
        this.candleRepository = candleRepository;
    }

    /** DB에 캔들 데이터가 MIN_CANDLES 이상 있는 종목 전체 반환 */
    public List<String> all() {
        return candleRepository.findSymbolsWithMinCandles(MIN_CANDLES).stream()
                .filter(s -> !EXCLUDE.contains(s))
                .sorted()
                .toList();
    }

    /** 하위 호환용 정적 메서드 (기존 코드에서 BacktestUniverse.all() 직접 호출하던 곳) */
    public static List<String> defaultSymbols() {
        return List.of(
            "005930","000660","005380","051910","035420","012330",
            "086520","247540"
        );
    }
}
