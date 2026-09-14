// HTTP fixtures exercise the real DOM renderer; no server or Codex is called.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const asset = name => path.resolve(__dirname, '../../src/main/resources/static/', name);
const source = fs.existsSync(asset('js/strategy-results.js')) ? fs.readFileSync(asset('js/strategy-results.js'), 'utf8') : '';
const styles = fs.readFileSync(asset('css/strategy-workbench.css'), 'utf8');
const start = Date.parse('2025-01-30T06:30:00Z');
const finish = Date.parse('2025-02-03T06:30:00Z');
const trade = { entry: { signalTimestamp: start, executionTimestamp: start, signalBarTimestamp: start, executionBarTimestamp: start, price: 100, reason: 'ENTRY_CONDITIONS', evidence: [{ path: 'entry.conditions[0]', type: 'MA_CROSS', actualValue: 102, referenceValue: 100, previousActualValue: 98, previousReferenceValue: 99, matched: true }] }, exit: { signalTimestamp: finish, executionTimestamp: finish, signalBarTimestamp: finish, executionBarTimestamp: finish, price: 94, reason: 'STOP_LOSS', evidence: [{ path: 'risk.stopLoss', type: 'STOP_LOSS', actualValue: -0.06, referenceValue: -0.05, previousActualValue: null, previousReferenceValue: null, matched: true }] }, holdingBars: 2, returnRate: -0.06 };
const metrics = { closedTrades: 1, winRate: 0, averageTradeReturnRate: -0.06, sumTradeReturnRate: -0.06, tradeReturnMaxDrawdown: -0.06, tradeSharpeRatio: 0, averageHoldingBars: 2 };
const run = id => ({ id, status: 'COMPLETED', snapshot: { result: { trades: [trade], metrics } } });
const analysis = id => ({ runId: id, status: 'COMPLETED', metrics, prices: [{ timestamp: start, close: 100 }, { timestamp: finish, close: 94 }], curve: [{ tradeNumber: 0, timestamp: start, sumReturnRate: 0, drawdown: 0 }, { tradeNumber: 1, timestamp: finish, sumReturnRate: -0.06, drawdown: 0.06 }], trades: [{ number: 1, trade }], months: [{ key: '2025-02', closedTrades: 1, winRate: 0, sumReturnRate: -0.06, tradeNumbers: [1] }], exitReasons: [{ key: 'STOP_LOSS', closedTrades: 1, winRate: 0, sumReturnRate: -0.06, tradeNumbers: [1] }], facts: [] });
const explanation = (id, status = 'NOT_GENERATED') => ({ runId: id, status, generatedAt: status === 'READY' ? '2026-09-11T00:00:00Z' : null, model: status === 'READY' ? 'test-model' : null, dataSha256: status === 'READY' ? 'abc123' : null, highlights: status === 'READY' ? [{ id: 'reason:STOP_LOSS', value: -0.06, unit: 'RATE', text: '손절 합계 -6% <img src=x onerror=alert(1)>', tradeNumbers: [1] }] : [] });
async function setup(handler) {
  const browser = await chromium.launch({ headless: true, channel: process.env.SW_BROWSER_CHANNEL || 'msedge' });
  const page = await browser.newPage(); page.setDefaultTimeout(5000);
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.route('**/*', async route => {
    if (new URL(route.request().url()).pathname === '/') return route.fulfill({ contentType: 'text/html; charset=utf-8', body: '<html lang="ko"><div id="strategyWorkbench"><fieldset><input aria-label="전략 원문"><p>기존 수익률 -6%</p><div id="results"></div></fieldset></div></html>' });
    await handler(route);
  });
  await page.goto('http://results.test/');
  await page.addStyleTag({ content: '* { box-sizing: border-box; }' + styles });
  if (source) await page.addScriptTag({ content: source });
  const available = await page.evaluate(() => typeof window.StrategyResults?.mount);
  if (available !== 'function') await browser.close();
  assert.equal(available, 'function', 'result renderer must be available');
  return { browser, page, errors };
}
const json = (route, data, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
const mount = (page, id) => page.evaluate(item => window.StrategyResults.mount(document.getElementById('results'), item), run(id));

test('charts and aggregates navigate to readable evidence; explanation retries independently and escapes text', async () => {
  let attempts = 0;
  const { browser, page, errors } = await setup(async route => {
    const req = route.request();
    if (req.url().endsWith('/analysis')) return json(route, analysis(12));
    if (req.method() === 'GET') return json(route, explanation(12));
    assert.equal(req.headers()['x-strategy-local'], '1');
    attempts++;
    await new Promise(resolve => setTimeout(resolve, 150));
    return attempts === 1 ? json(route, { code: 'CODEX_UNAVAILABLE', message: '설명 생성 실패' }, 503) : json(route, explanation(12, 'READY'));
  });
  try {
    await mount(page, 12);
    await page.getByRole('img', { name: '종가와 체결점' }).waitFor();
    assert.equal(await page.getByRole('img').count(), 3);
    assert.match(await page.locator('#results').innerText(), /계좌.*아닙니다/);
    assert.match(await page.locator('#results').innerText(), /%p/);
    await page.getByRole('button', { name: 'AI 설명 생성', exact: true }).click();
    assert.equal(await page.getByLabel('전략 원문').isEnabled(), true);
    assert.equal(await page.getByRole('button', { name: '설명 생성 중…' }).isDisabled(), true);
    await page.getByText('CODEX_UNAVAILABLE · 설명 생성 실패').waitFor();
    assert.equal(await page.getByText('기존 수익률 -6%').isVisible(), true);
    await page.getByRole('button', { name: 'AI 설명 다시 시도' }).click();
    await page.getByText('손절 합계 -6% <img src=x onerror=alert(1)>', { exact: true }).waitFor();
    assert.equal(await page.locator('img').count(), 0);
    await page.locator('.sr-explanation').getByRole('button', { name: '거래 #1 근거 보기' }).focus();
    await page.keyboard.press('Enter');
    const detail = page.locator('details[data-trade-number="1"]');
    assert.equal(await detail.getAttribute('open'), '');
    assert.match(await detail.innerText(), /102[\s\S]*100[\s\S]*98[\s\S]*99[\s\S]*충족/);
    assert.match(await detail.innerText(), /-0\.06[\s\S]*-0\.05/);
    assert.equal(await detail.locator('summary').evaluate(el => el === document.activeElement), true);
    assert.equal(await page.locator('.sr-aggregates').getByRole('button', { name: '거래 #1 근거 보기' }).count(), 2);
    assert.equal(await page.locator('svg [data-trade-number]').count(), 4);
    await page.setViewportSize({ width: 390, height: 844 });
    const width = await page.locator('#strategyWorkbench').evaluate(el => ({ content: el.scrollWidth, box: el.clientWidth }));
    assert.ok(width.content <= width.box, JSON.stringify(width));
    assert.deepEqual(errors, []);
  } finally { await browser.close(); }
});

test('slow old requests cannot replace a new run and analysis errors preserve trades and explanation', async () => {
  let release; const pending = new Promise(resolve => { release = resolve; });
  const { browser, page, errors } = await setup(async route => {
    const old = route.request().url().includes('/12/');
    if (old) await pending;
    if (route.request().url().endsWith('/analysis')) return old ? json(route, analysis(12)) : json(route, { message: '분석 조회 실패' }, 500);
    return json(route, explanation(old ? 12 : 13, 'READY'));
  });
  try {
    await mount(page, 12); await mount(page, 13);
    await page.getByText('분석 조회 실패', { exact: false }).waitFor();
    release();
    await page.getByText('손절 합계 -6% <img src=x onerror=alert(1)>', { exact: true }).waitFor();
    await page.waitForTimeout(100);
    assert.equal(await page.getByRole('img').count(), 0);
    assert.equal(await page.locator('[data-trade-number="1"]').count(), 1);
    assert.equal(await page.locator('.sr-results').getAttribute('data-run-id'), '13');
    assert.equal(await page.getByText('기존 수익률 -6%').isVisible(), true);
    assert.deepEqual(errors, []);
  } finally { release(); await browser.close(); }
});

test('no-data run gives an explicit empty chart state and cannot generate an inapplicable explanation', async () => {
  const { browser, page } = await setup(route => route.request().url().endsWith('/analysis')
    ? json(route, { ...analysis(14), status: 'INSUFFICIENT_DATA', prices: [], curve: [], trades: [], months: [], exitReasons: [] })
    : json(route, explanation(14, 'NOT_APPLICABLE')));
  try {
    await page.evaluate(() => StrategyResults.mount(document.getElementById('results'), { id: 14, status: 'INSUFFICIENT_DATA', snapshot: { result: { trades: [] } } }));
    await page.getByText('차트에 표시할 데이터가 없습니다.').waitFor();
    await page.getByText('청산된 거래가 없어 이 실행에는 AI 설명을 생성할 수 없습니다.').waitFor();
    assert.equal(await page.getByRole('button', { name: 'AI 설명 생성', exact: true }).count(), 0);
  } finally { await browser.close(); }
});

test('daily fill markers align with their own candle date while keeping actual execution time in tooltips', async () => {
  const firstBar = Date.parse('2025-01-30T15:00:00Z'); // Jan 31, 00:00 KST
  const lastBar = Date.parse('2025-02-02T15:00:00Z'); // Feb 3, 00:00 KST
  const dailyTrade = structuredClone(trade);
  dailyTrade.entry.executionBarTimestamp = firstBar;
  dailyTrade.entry.executionTimestamp = Date.parse('2025-01-31T06:30:00Z');
  dailyTrade.exit.executionBarTimestamp = lastBar;
  dailyTrade.exit.executionTimestamp = Date.parse('2025-02-03T06:30:00Z');
  const dailyAnalysis = {...analysis(15), prices: [{timestamp:firstBar,close:100},{timestamp:lastBar,close:94}],
    trades:[{number:1,trade:dailyTrade}]};
  const {browser,page} = await setup(route => json(route, route.request().url().endsWith('/analysis')
    ? dailyAnalysis : explanation(15)));
  try {
    await page.evaluate(item => StrategyResults.mount(document.getElementById('results'),item),
      {id:15,status:'COMPLETED',snapshot:{result:{trades:[dailyTrade],metrics}}});
    const chart = page.getByRole('img',{name:'종가와 체결점'});
    await chart.waitFor();
    const closes = chart.locator('circle:not([data-trade-number])');
    assert.equal(await chart.locator('.sr-entry').getAttribute('cx'),await closes.nth(0).getAttribute('cx'));
    assert.equal(await chart.locator('.sr-exit').getAttribute('cx'),await closes.nth(1).getAttribute('cx'));
    assert.match(await chart.locator('.sr-entry').getAttribute('aria-label'),/3:30:00/);
  } finally { await browser.close(); }
});
