"""
백테스트용 캔들 데이터 수집 스크립트 (FinanceDataReader 버전).
KRX 로그인 불필요. 코스피/코스닥 시가총액 상위 100개씩 자동 선정 후 캔들 수집.

실행: python scripts/fetch_candles.py
완료 후: curl.exe -X POST http://localhost:8090/api/backtest/import-csv
"""

import os, csv, time
from datetime import datetime
import FinanceDataReader as fdr

TOP_N      = 100
EXCLUDE    = {"102940", "091990", "950160"}
START_DATE = "2023-10-01"
END_DATE   = datetime.today().strftime("%Y-%m-%d")
OUTPUT_CSV = os.path.join(os.path.dirname(os.path.abspath(__file__)), "candles.csv")


def get_symbols():
    kospi_syms, kosdaq_syms = [], []

    print("[종목] 코스피 목록 조회...", end=" ", flush=True)
    try:
        df = fdr.StockListing("KOSPI").sort_values("Marcap", ascending=False)
        kospi_syms = [s for s in df["Code"].tolist() if s not in EXCLUDE][:TOP_N]
        print(f"{len(kospi_syms)}개")
    except Exception as e:
        print(f"실패: {e}")

    print("[종목] 코스닥 목록 조회...", end=" ", flush=True)
    try:
        df = fdr.StockListing("KOSDAQ").sort_values("Marcap", ascending=False)
        kosdaq_syms = [s for s in df["Code"].tolist() if s not in EXCLUDE][:TOP_N]
        print(f"{len(kosdaq_syms)}개")
    except Exception as e:
        print(f"실패: {e}")

    print(f"[종목] 총 {len(kospi_syms)+len(kosdaq_syms)}개\n")
    return kospi_syms, kosdaq_syms


def fetch(symbols, label):
    rows = []
    for i, sym in enumerate(symbols, 1):
        print(f"  [{label}] ({i:3d}/{len(symbols)}) {sym} ...", end=" ", flush=True)
        try:
            df = fdr.DataReader(sym, START_DATE, END_DATE)
            if df is None or df.empty:
                print("없음")
                continue
            for date, r in df.iterrows():
                ts = int(datetime.strptime(str(date.date()), "%Y-%m-%d").timestamp() * 1000)
                rows.append({
                    "symbol":      sym,
                    "timestamp":   ts,
                    "open_price":  int(r.get("Open",   0)),
                    "high_price":  int(r.get("High",   0)),
                    "low_price":   int(r.get("Low",    0)),
                    "close_price": int(r.get("Close",  0)),
                    "volume":      int(r.get("Volume", 0)),
                })
            print(f"{len(df)}개")
            time.sleep(0.15)
        except Exception as e:
            print(f"에러: {e}")
    return rows


if __name__ == "__main__":
    print(f"=== 캔들 수집 ({START_DATE} ~ {END_DATE}) ===\n")
    kospi_syms, kosdaq_syms = get_symbols()

    print("[캔들 수집]")
    rows  = fetch(kospi_syms,  "KOSPI ")
    rows += fetch(kosdaq_syms, "KOSDAQ")

    with open(OUTPUT_CSV, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["symbol","timestamp","open_price","high_price","low_price","close_price","volume"])
        w.writeheader(); w.writerows(rows)

    syms = sorted(set(r["symbol"] for r in rows))
    print(f"\n[완료] {len(rows):,}행, {len(syms)}개 종목")
    with open(os.path.join(os.path.dirname(OUTPUT_CSV), "symbols.txt"), "w") as f:
        f.write("\n".join(syms))
    print("다음: curl.exe -X POST http://localhost:8090/api/backtest/import-csv")