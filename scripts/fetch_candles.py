"""
백테스트용 KRX 일봉 캔들 데이터 수집 스크립트.
코스피 시가총액 상위 100 + 코스닥 시가총액 상위 100 종목을 자동으로 수집한다.
(코오롱생명과학 102940은 개인 보유종목이라 제외)

사전 준비:
    pip install pykrx

실행:
    python scripts/fetch_candles.py

완료 후:
    curl.exe -X POST http://localhost:8090/api/backtest/import-csv
"""

import os
import time
from datetime import datetime
from pykrx import stock

# ── 설정 ──────────────────────────────────────────────────────────────────────
TOP_N = 100          # 코스피/코스닥 각 상위 N개
EXCLUDE = {"102940"} # 코오롱생명과학 제외 (보유종목, 별도 관리)

START_DATE = "20231001"
END_DATE   = datetime.today().strftime("%Y%m%d")

OUTPUT_CSV = os.path.join(os.path.dirname(__file__), "candles.csv")

# ── 상위 종목 추출 ─────────────────────────────────────────────────────────────
def get_top_symbols():
    base_date = datetime.strptime(END_DATE, "%Y%m%d")

    # 최근 거래일 기준으로 시가총액 정렬 (주말이면 며칠 전으로 후퇴)
    for delta in range(7):
        try_date = base_date
        from datetime import timedelta
        try_date = base_date - timedelta(days=delta)
        date_str = try_date.strftime("%Y%m%d")
        try:
            kospi  = stock.get_market_cap(date_str, market="KOSPI").sort_values("시가총액", ascending=False)
            kosdaq = stock.get_market_cap(date_str, market="KOSDAQ").sort_values("시가총액", ascending=False)
            if not kospi.empty and not kosdaq.empty:
                print(f"[기준일] {date_str}")
                break
        except Exception:
            continue

    kospi_top  = [t for t in kospi.head(TOP_N + 10).index.tolist() if t not in EXCLUDE][:TOP_N]
    kosdaq_top = [t for t in kosdaq.head(TOP_N + 10).index.tolist() if t not in EXCLUDE][:TOP_N]

    print(f"[종목] 코스피 {len(kospi_top)}개 + 코스닥 {len(kosdaq_top)}개 = {len(kospi_top)+len(kosdaq_top)}개")
    return kospi_top, kosdaq_top

# ── 데이터 수집 ────────────────────────────────────────────────────────────────
def fetch_all(symbols, market_label):
    rows = []
    for idx, symbol in enumerate(symbols, 1):
        print(f"[{market_label}] ({idx}/{len(symbols)}) {symbol} 수집 중...", end=" ")
        try:
            df = stock.get_market_ohlcv(START_DATE, END_DATE, symbol)
            if df.empty:
                print("데이터 없음")
                continue
            for date, row in df.iterrows():
                # KST 오전 9시 기준 epoch millis
                ts_kst = int((datetime.strptime(str(date.date()), "%Y-%m-%d")
                              .replace(hour=0, minute=0, second=0).timestamp() * 1000))
                rows.append({
                    "symbol":      symbol,
                    "timestamp":   ts_kst,
                    "open_price":  int(row["시가"]),
                    "high_price":  int(row["고가"]),
                    "low_price":   int(row["저가"]),
                    "close_price": int(row["종가"]),
                    "volume":      int(row["거래량"]),
                })
            print(f"{len(df)}개")
            time.sleep(0.2)
        except Exception as e:
            print(f"에러: {e}")
    return rows

# ── CSV 저장 ──────────────────────────────────────────────────────────────────
def save_csv(rows):
    import csv
    with open(OUTPUT_CSV, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=["symbol","timestamp","open_price","high_price","low_price","close_price","volume"])
        writer.writeheader()
        writer.writerows(rows)
    print(f"\n[완료] {len(rows)}행 저장 → {OUTPUT_CSV}")
    print(f"종목 수: {len(set(r['symbol'] for r in rows))}개")
    print("\n다음 단계: Spring Boot 서버 켜진 상태에서 아래 실행")
    print("  curl.exe -X POST http://localhost:8090/api/backtest/import-csv")

# ── 심볼 목록 저장 (BacktestUniverse 업데이트용) ──────────────────────────────
def save_symbol_list(kospi_top, kosdaq_top):
    symbol_file = os.path.join(os.path.dirname(__file__), "symbols.txt")
    with open(symbol_file, "w", encoding="utf-8") as f:
        f.write("KOSPI=" + ",".join(kospi_top) + "\n")
        f.write("KOSDAQ=" + ",".join(kosdaq_top) + "\n")
    print(f"[심볼 목록] {symbol_file} 저장됨 (BacktestUniverse 확인용)")

if __name__ == "__main__":
    kospi_top, kosdaq_top = get_top_symbols()
    save_symbol_list(kospi_top, kosdaq_top)

    all_rows = []
    all_rows += fetch_all(kospi_top,  "KOSPI ")
    all_rows += fetch_all(kosdaq_top, "KOSDAQ")
    save_csv(all_rows)

