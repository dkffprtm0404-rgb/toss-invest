"""
백테스트용 KRX 일봉 캔들 데이터 수집 스크립트.
pykrx 라이브러리로 한국거래소(KRX) 공식 데이터를 가져온 뒤
Spring Boot의 H2 backtest_candle 테이블에 직접 INSERT한다.

사전 준비:
    pip install pykrx
    pip install jaydebeapi  # H2 JDBC 연결용

실행:
    python scripts/fetch_candles.py

완료 후 Spring Boot 서버를 재시작하면 /api/backtest/candle-status 에서 개수를 확인할 수 있다.
"""

import os
import time
from datetime import datetime, timedelta
from pykrx import stock

# ── 설정 ──────────────────────────────────────────────────────────────────────
SYMBOLS = [
    # KOSPI
    "005930",  # 삼성전자
    "000660",  # SK하이닉스
    "005380",  # 현대차
    "051910",  # LG화학
    "035420",  # NAVER
    "012330",  # 현대모비스
    # KOSDAQ
    "102940",  # 코오롱생명과학
    "086520",  # 에코프로
    "247540",  # 에코프로비엠
    "091990",  # 셀트리온헬스케어
]

START_DATE = "20231001"   # 약 2.5년치 (2023-10 ~ 현재)
END_DATE   = datetime.today().strftime("%Y%m%d")

# H2 파일DB 경로 (Spring Boot application.yaml과 동일한 경로)
H2_DB_PATH = os.path.join(os.path.dirname(__file__), "..", "data", "tossinvest")
H2_JAR     = os.path.join(os.path.dirname(__file__), "h2.jar")  # 아래 참고

OUTPUT_CSV = os.path.join(os.path.dirname(__file__), "candles.csv")

# ── 데이터 수집 ───────────────────────────────────────────────────────────────
def fetch_all():
    rows = []
    for symbol in SYMBOLS:
        print(f"[수집] {symbol} ({START_DATE} ~ {END_DATE})")
        try:
            df = stock.get_market_ohlcv(START_DATE, END_DATE, symbol)
            if df.empty:
                print(f"  → 데이터 없음")
                continue

            for date, row in df.iterrows():
                # pykrx 반환 컬럼: 시가, 고가, 저가, 종가, 거래량, 거래대금, 등락률
                ts = int(datetime.strptime(str(date.date()), "%Y-%m-%d").timestamp() * 1000)
                # KST(+09:00) 기준 오전 9시 epoch millis로 저장 (토스 캔들 timestamp와 동일 기준)
                ts_kst = int((datetime.strptime(str(date.date()), "%Y-%m-%d")
                              .replace(hour=9) - timedelta(hours=9)).timestamp() * 1000)
                rows.append({
                    "symbol":     symbol,
                    "timestamp":  ts_kst,
                    "open_price": int(row["시가"]),
                    "high_price": int(row["고가"]),
                    "low_price":  int(row["저가"]),
                    "close_price":int(row["종가"]),
                    "volume":     int(row["거래량"]),
                })
            print(f"  → {len(df)}개 수집")
            time.sleep(0.3)   # KRX 서버 부하 방지
        except Exception as e:
            print(f"  → 에러: {e}")

    return rows


def save_csv(rows):
    """CSV로 저장 (H2 직접 연결이 어려울 경우 CSV Import 방식으로 대체)."""
    import csv
    with open(OUTPUT_CSV, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=["symbol","timestamp","open_price","high_price","low_price","close_price","volume"])
        writer.writeheader()
        writer.writerows(rows)
    print(f"\n[완료] {len(rows)}개 행 저장 → {OUTPUT_CSV}")
    print("\n다음 단계: Spring Boot 서버가 켜진 상태에서 아래 명령을 실행하세요.")
    print(f"  curl -X POST http://localhost:8090/api/backtest/import-csv")


if __name__ == "__main__":
    rows = fetch_all()
    save_csv(rows)
