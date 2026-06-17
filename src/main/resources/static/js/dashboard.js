const els = {
  clock: document.getElementById('clock'),
  marketStatus: document.getElementById('marketStatus'),
  fxRate: document.getElementById('fxRate'),
  fxDelta: document.getElementById('fxDelta'),
  fxValidUntil: document.getElementById('fxValidUntil'),
  accountBody: document.getElementById('accountBody'),
  totalPurchaseKrw: document.getElementById('totalPurchaseKrw'),
  totalPurchaseUsd: document.getElementById('totalPurchaseUsd'),
  holdingsBody: document.getElementById('holdingsBody'),
  lastUpdated: document.getElementById('lastUpdated'),
  refreshBtn: document.getElementById('refreshBtn'),
  quotePanel: document.getElementById('quotePanel'),
  quoteTitle: document.getElementById('quoteTitle'),
  closeQuoteBtn: document.getElementById('closeQuoteBtn'),
  priceBody: document.getElementById('priceBody'),
  orderbookBody: document.getElementById('orderbookBody'),
  tradesBody: document.getElementById('tradesBody'),
  signalBody: document.getElementById('signalBody'),
};

let activeSymbol = null;
let activeName = null;
let activeIsHolding = false;
let activeAvgPrice = null;
let activeHoldingDays = null;
let candleChartInstance = null;

function tickClock() {
  const now = new Date();
  els.clock.textContent = now.toLocaleTimeString('ko-KR', { hour12: false });
}
setInterval(tickClock, 1000);
tickClock();

function formatNumber(value) {
  if (value === null || value === undefined) return '—';
  const num = Number(value);
  if (Number.isNaN(num)) return value;
  return num.toLocaleString('ko-KR', { maximumFractionDigits: 4 });
}

async function fetchJson(url) {
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`${res.status} ${res.statusText}`);
  }
  return res.json();
}

async function loadExchangeRate() {
  try {
    const data = await fetchJson('/api/health/exchange-rate?baseCurrency=USD&quoteCurrency=KRW');
    const r = data.result;
    els.fxRate.textContent = formatNumber(r.rate);
    els.fxDelta.textContent = r.rateChangeType === 'UP' ? '▲ 상승' : r.rateChangeType === 'DOWN' ? '▼ 하락' : '· 변동없음';
    els.fxDelta.className = 'fx-delta ' + (r.rateChangeType === 'UP' ? 'up' : r.rateChangeType === 'DOWN' ? 'down' : '');
    const until = new Date(r.validUntil);
    els.fxValidUntil.textContent = `유효기간 ${until.toLocaleTimeString('ko-KR', { hour12: false })} 까지`;
    els.marketStatus.textContent = '연결됨';
  } catch (err) {
    els.fxRate.textContent = '오류';
    els.fxValidUntil.textContent = '환율 조회 실패: ' + err.message;
    els.marketStatus.textContent = '오류';
  }
}

async function loadAccountsAndHoldings() {
  try {
    const accountData = await fetchJson('/api/accounts');
    const accounts = accountData.result || [];

    if (accounts.length === 0) {
      els.accountBody.innerHTML = '<div class="account-row"><span class="label">계좌 없음</span></div>';
      els.holdingsBody.innerHTML = '<tr><td colspan="3" class="loading-cell">계좌가 없어 보유 종목을 조회할 수 없습니다</td></tr>';
      return;
    }

    const account = accounts[0];
    els.accountBody.innerHTML = `
      <div class="account-row"><span class="label">계좌번호</span><span>${account.accountNo ?? '—'}</span></div>
      <div class="account-row"><span class="label">계좌유형</span><span>${account.accountType ?? '—'}</span></div>
      <div class="account-row"><span class="label">accountSeq</span><span>${account.accountSeq ?? '—'}</span></div>
    `;

    await loadHoldings(account.accountSeq);
  } catch (err) {
    els.accountBody.innerHTML = `<div class="account-row error-cell">계좌 조회 실패: ${err.message}</div>`;
    els.holdingsBody.innerHTML = '<tr><td colspan="3" class="error-cell">계좌 정보 없이 조회 불가</td></tr>';
  }
}

async function loadHoldings(accountSeq) {
  try {
    const data = await fetchJson(`/api/holdings?accountSeq=${accountSeq}`);
    const result = data.result;

    els.totalPurchaseKrw.textContent = result.totalPurchaseAmount?.krw
      ? formatNumber(result.totalPurchaseAmount.krw) + ' 원'
      : '—';
    els.totalPurchaseUsd.textContent = result.totalPurchaseAmount?.usd
      ? '$' + formatNumber(result.totalPurchaseAmount.usd)
      : '—';

    const items = result.items || [];
    if (items.length === 0) {
      els.holdingsBody.innerHTML = '<tr><td colspan="5" class="loading-cell">보유 종목이 없습니다</td></tr>';
      return;
    }

    els.holdingsBody.innerHTML = items.map(item => {
      const marketAmount = item.marketValue?.amount;
      const rate = item.profitLoss?.rate;
      const ratePct = rate !== undefined && rate !== null ? (Number(rate) * 100).toFixed(2) + '%' : '—';
      const rateClass = rate !== undefined && rate !== null ? (Number(rate) > 0 ? 'up' : Number(rate) < 0 ? 'down' : '') : '';
      const currencyLabel = item.currency === 'USD' ? '$' : '';

      return `
        <tr data-symbol="${item.symbol ?? ''}" data-name="${item.name ?? ''}" data-avgprice="${item.averagePurchasePrice ?? ''}">
          <td>${item.name ?? '—'}</td>
          <td><span class="symbol-tag">${item.symbol ?? '—'}</span></td>
          <td class="num">${formatNumber(item.quantity)}</td>
          <td class="num">${currencyLabel}${formatNumber(marketAmount)}</td>
          <td class="num"><span class="rate-tag ${rateClass}">${ratePct}</span></td>
        </tr>
      `;
    }).join('');

    attachRowClickHandlers();
    markActiveRow();
  } catch (err) {
    els.holdingsBody.innerHTML = `<tr><td colspan="5" class="error-cell">보유 종목 조회 실패: ${err.message}</td></tr>`;
  }
}

function attachRowClickHandlers() {
  els.holdingsBody.querySelectorAll('tr[data-symbol]').forEach(row => {
    row.addEventListener('click', () => {
      const symbol = row.dataset.symbol;
      const name = row.dataset.name;
      const avgPrice = row.dataset.avgprice;
      openQuotePanel(symbol, name, true, avgPrice);
    });
  });
}

function markActiveRow() {
  els.holdingsBody.querySelectorAll('tr[data-symbol]').forEach(row => {
    row.classList.toggle('active-row', row.dataset.symbol === activeSymbol);
  });
}

function openQuotePanel(symbol, name, isHolding, avgPrice) {
  activeSymbol = symbol;
  activeName = name;
  activeIsHolding = !!isHolding;
  activeAvgPrice = avgPrice || null;
  activeHoldingDays = null;
  els.quotePanel.hidden = false;
  els.quoteTitle.textContent = `시세 상세 — ${name} (${symbol})`;
  markActiveRow();
  loadQuoteDetail();
  els.quotePanel.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function closeQuotePanel() {
  activeSymbol = null;
  activeName = null;
  activeIsHolding = false;
  activeAvgPrice = null;
  activeHoldingDays = null;
  els.quotePanel.hidden = true;
  markActiveRow();
  if (candleChartInstance) {
    candleChartInstance.destroy();
    candleChartInstance = null;
  }
}

els.closeQuoteBtn.addEventListener('click', closeQuotePanel);

async function loadQuoteDetail() {
  if (!activeSymbol) return;
  await Promise.all([
    loadPrice(activeSymbol),
    loadOrderbook(activeSymbol),
    loadTrades(activeSymbol),
    loadCandleChart(activeSymbol),
    loadSignal(activeSymbol),
  ]);
}

async function loadPrice(symbol) {
  try {
    const data = await fetchJson(`/api/prices?symbols=${encodeURIComponent(symbol)}`);
    const price = (data.result || [])[0];
    if (!price || !price.lastPrice) {
      els.priceBody.textContent = '데이터 없음';
      return;
    }
    const time = price.timestamp ? new Date(price.timestamp).toLocaleTimeString('ko-KR', { hour12: false }) : '';
    els.priceBody.innerHTML = `
      ${formatNumber(price.lastPrice)}
      <span class="price-change">${price.currency ?? ''} · ${time} 기준</span>
    `;
  } catch (err) {
    els.priceBody.textContent = '조회 실패: ' + err.message;
  }
}

async function loadOrderbook(symbol) {
  try {
    const data = await fetchJson(`/api/orderbook?symbol=${encodeURIComponent(symbol)}`);
    const result = data.result;
    const asks = (result.asks || []).slice(0, 5).reverse();
    const bids = (result.bids || []).slice(0, 5);

    const askRows = asks.map(level => `
      <div class="orderbook-row ask">
        <span class="ob-price">${formatNumber(level.price)}</span>
        <span class="ob-qty">${formatNumber(level.volume)}</span>
      </div>
    `).join('');

    const bidRows = bids.map(level => `
      <div class="orderbook-row bid">
        <span class="ob-price">${formatNumber(level.price)}</span>
        <span class="ob-qty">${formatNumber(level.volume)}</span>
      </div>
    `).join('');

    els.orderbookBody.innerHTML = askRows + '<div class="orderbook-divider"></div>' + bidRows;
  } catch (err) {
    els.orderbookBody.innerHTML = `<div class="error-cell">호가 조회 실패: ${err.message}</div>`;
  }
}

async function loadTrades(symbol) {
  try {
    const data = await fetchJson(`/api/trades?symbol=${encodeURIComponent(symbol)}&count=20`);
    const trades = data.result || [];
    if (trades.length === 0) {
      els.tradesBody.innerHTML = '<div class="loading-cell">체결 내역 없음</div>';
      return;
    }
    els.tradesBody.innerHTML = trades.map(t => {
      const time = t.timestamp ? new Date(t.timestamp).toLocaleTimeString('ko-KR', { hour12: false }) : '';
      return `
        <div class="trade-row">
          <span class="trade-price">${formatNumber(t.price)}</span>
          <span>${formatNumber(t.volume)}</span>
          <span>${time}</span>
        </div>
      `;
    }).join('');
  } catch (err) {
    els.tradesBody.innerHTML = `<div class="error-cell">체결 내역 조회 실패: ${err.message}</div>`;
  }
}

async function loadCandleChart(symbol) {
  const canvas = document.getElementById('candleChart');
  try {
    const data = await fetchJson(`/api/candles?symbol=${encodeURIComponent(symbol)}&interval=1d&count=60`);
    const candles = (data.result?.candles || []).slice().reverse(); // 오래된 -> 최신

    const labels = candles.map(c => {
      const d = new Date(c.timestamp);
      return `${d.getMonth() + 1}/${d.getDate()}`;
    });
    const closes = candles.map(c => Number(c.closePrice));

    if (candleChartInstance) {
      candleChartInstance.destroy();
    }

    candleChartInstance = new Chart(canvas, {
      type: 'line',
      data: {
        labels,
        datasets: [{
          label: '종가',
          data: closes,
          borderColor: '#c45b3e',
          backgroundColor: 'rgba(196, 91, 62, 0.08)',
          borderWidth: 1.5,
          pointRadius: 0,
          fill: true,
          tension: 0.15,
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { display: false } },
        scales: {
          x: {
            ticks: { color: '#8a8478', maxTicksLimit: 10, font: { family: 'IBM Plex Mono', size: 10 } },
            grid: { color: 'rgba(255,255,255,0.04)' },
          },
          y: {
            ticks: {
              color: '#8a8478',
              font: { family: 'IBM Plex Mono', size: 10 },
              callback: (v) => Number(v).toLocaleString('ko-KR'),
            },
            grid: { color: 'rgba(255,255,255,0.04)' },
          },
        },
      },
    });
  } catch (err) {
    console.error('캔들 차트 로드 실패', err);
  }
}

function signalTagLabel(type) {
  switch (type) {
    case 'BUY_CANDIDATE': return '매수 후보';
    case 'SELL_STOP_LOSS': return '손절';
    case 'SELL_TAKE_PROFIT': return '트레일링 스탑 매도';
    case 'SELL_TREND_REVERSAL': return '추세전환 매도';
    default: return '보유 유지';
  }
}

function signalBannerClass(type) {
  if (type === 'BUY_CANDIDATE') return 'buy';
  if (type === 'SELL_STOP_LOSS' || type === 'SELL_TAKE_PROFIT' || type === 'SELL_TREND_REVERSAL') return 'sell';
  return 'hold';
}

function renderSignal(signal) {
  const cls = signalBannerClass(signal.signalType);
  const scoreText = signal.score !== null && signal.score !== undefined
    ? `점수 ${signal.score} / ${signal.scoreThreshold}`
    : '';

  const metaItems = [];
  if (signal.ema5 !== undefined && signal.ema5 !== null) metaItems.push(['EMA5', formatNumber(signal.ema5)]);
  if (signal.ema20 !== undefined && signal.ema20 !== null) metaItems.push(['EMA20', formatNumber(signal.ema20)]);
  if (signal.rsi7 !== undefined && signal.rsi7 !== null) metaItems.push(['RSI(7)', signal.rsi7]);
  if (signal.volumeSurge !== undefined && signal.volumeSurge !== null) metaItems.push(['거래량 서지', signal.volumeSurge ? 'YES' : 'NO']);
  if (signal.trailingStopPrice !== undefined && signal.trailingStopPrice !== null) metaItems.push(['트레일링 기준가', formatNumber(signal.trailingStopPrice)]);

  const metaHtml = metaItems.map(([label, value]) => `
    <div class="signal-meta-item">
      <span class="signal-meta-label">${label}</span>
      <span class="signal-meta-value">${value}</span>
    </div>
  `).join('');

  const conditionsHtml = (signal.matchedConditions || [])
    .map(c => `<div>✓ ${c}</div>`).join('')
    + (signal.excludedReasons || []).map(c => `<div>✕ ${c}</div>`).join('');

  return `
    <div class="signal-banner ${cls}">
      <span class="signal-tag">${signalTagLabel(signal.signalType)}</span>
      <span>${scoreText}</span>
    </div>
    ${metaItems.length ? `<div class="signal-meta-grid">${metaHtml}</div>` : ''}
    <div class="signal-conditions">${conditionsHtml || signal.summary || ''}</div>
  `;
}

async function loadSignal(symbol) {
  try {
    let url;
    if (activeIsHolding) {
      const avgPrice = activeAvgPrice ?? document.getElementById('signalAvgPriceInput')?.value;
      if (!avgPrice) {
        els.signalBody.innerHTML = `
          <div class="signal-conditions">매도 신호 판단을 위해 평균 매수가를 입력해주세요. (보유일수는 선택)</div>
          <div class="signal-sell-form">
            <span>평균 매수가</span>
            <input type="number" id="signalAvgPriceInput" placeholder="예: 340000" />
            <span>보유일수</span>
            <input type="number" id="signalHoldingDaysInput" placeholder="예: 3" style="width:70px" />
            <button class="refresh-btn" id="signalAvgPriceSubmit">판단하기</button>
          </div>
        `;
        document.getElementById('signalAvgPriceSubmit').addEventListener('click', () => {
          const v = document.getElementById('signalAvgPriceInput').value;
          if (v) {
            activeAvgPrice = v;
            activeHoldingDays = document.getElementById('signalHoldingDaysInput').value || null;
            loadSignal(symbol);
          }
        });
        return;
      }
      const daysParam = activeHoldingDays ? `&holdingDays=${activeHoldingDays}` : '';
      url = `/api/signals/sell?symbol=${encodeURIComponent(symbol)}&avgPrice=${avgPrice}${daysParam}`;
    } else {
      url = `/api/signals/buy?symbol=${encodeURIComponent(symbol)}`;
    }

    const signal = await fetchJson(url);
    els.signalBody.innerHTML = renderSignal(signal);

    if (activeIsHolding) {
      els.signalBody.innerHTML += `
        <div class="signal-sell-form">
          <span>평균 매수가</span>
          <input type="number" id="signalAvgPriceInput" value="${activeAvgPrice ?? ''}" placeholder="예: 340000" />
          <span>보유일수</span>
          <input type="number" id="signalHoldingDaysInput" value="${activeHoldingDays ?? ''}" placeholder="예: 3" style="width:70px" />
          <button class="refresh-btn" id="signalAvgPriceSubmit">다시 판단</button>
        </div>
      `;
      document.getElementById('signalAvgPriceSubmit').addEventListener('click', () => {
        const v = document.getElementById('signalAvgPriceInput').value;
        if (v) {
          activeAvgPrice = v;
          activeHoldingDays = document.getElementById('signalHoldingDaysInput').value || null;
          loadSignal(symbol);
        }
      });
    }
  } catch (err) {
    els.signalBody.innerHTML = `<div class="error-cell">신호 판단 실패: ${err.message}</div>`;
  }
}

async function loadAll() {
  els.lastUpdated.textContent = '갱신 중…';
  const tasks = [loadExchangeRate(), loadAccountsAndHoldings()];
  if (activeSymbol) {
    tasks.push(loadQuoteDetail());
  }
  await Promise.all(tasks);
  els.lastUpdated.textContent = '마지막 갱신 ' + new Date().toLocaleTimeString('ko-KR', { hour12: false });
}

els.refreshBtn.addEventListener('click', loadAll);

// 1분(60초)마다 자동 갱신
setInterval(loadAll, 60 * 1000);

loadAll();
