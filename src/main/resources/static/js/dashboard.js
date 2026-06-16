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
};

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
      els.holdingsBody.innerHTML = '<tr><td colspan="3" class="loading-cell">보유 종목이 없습니다</td></tr>';
      return;
    }

    els.holdingsBody.innerHTML = items.map(item => `
      <tr>
        <td>${item.name ?? '—'}</td>
        <td><span class="symbol-tag">${item.symbol ?? '—'}</span></td>
        <td class="num">${formatNumber(item.quantity)}</td>
      </tr>
    `).join('');
  } catch (err) {
    els.holdingsBody.innerHTML = `<tr><td colspan="3" class="error-cell">보유 종목 조회 실패: ${err.message}</td></tr>`;
  }
}

async function loadAll() {
  els.lastUpdated.textContent = '갱신 중…';
  await Promise.all([loadExchangeRate(), loadAccountsAndHoldings()]);
  els.lastUpdated.textContent = '마지막 갱신 ' + new Date().toLocaleTimeString('ko-KR', { hour12: false });
}

els.refreshBtn.addEventListener('click', loadAll);

loadAll();
