package com.tossinvest.tossinvestbackend.scalping;

import java.util.List;

/**
 * 1분봉 단타 시뮬레이션 대상 종목.
 * 코스피 시가총액 상위 10개 (2026-06-29 기준, FinanceDataReader 조회 결과).
 * 유동성이 높은 대형주라 1분봉 신호의 신뢰도가 상대적으로 높다.
 */
public final class ScalpingUniverse {

    private ScalpingUniverse() {}

    public static final List<String> SYMBOLS = List.of(
            /*"005930", // 삼성전자
            "000660", // SK하이닉스
            "402340", // SK스퀘어
            "005935", // 삼성전자우
            "009150", // 삼성전기
            "005380", // 현대차
            "373220", // LG에너지솔루션
            "032830", // 삼성생명
            "028260", // 삼성물산
            "207940"  // 삼성바이오로직스*/
    );
}
