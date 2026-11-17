const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '../../src/main/resources/static');

async function dashboard(run, setup) {
  const browser = await chromium.launch({ headless: true, channel: 'msedge' });
  const state = { accounts: [], accountReads: [], totalTrades: 0, posts: [], dialogs: [], errors: [], respond: async route => route.fulfill({ json: { bought: ['005930'], sold: [] } }) };
  try {
    const page = await browser.newPage();
    page.setDefaultTimeout(5000);
    await page.clock.install();
    page.on('pageerror', error => state.errors.push(error.message));
    page.on('dialog', async dialog => { state.dialogs.push(dialog.message()); await dialog.accept(); });
    await page.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url());
      if (url.hostname !== 'dashboard.test') return route.fulfill({ body: '' });
      if (request.method() === 'POST') { state.posts.push(url.pathname); return state.respond(route); }
      if (['/api/accounts', '/api/holdings'].includes(url.pathname)) state.accountReads.push(url.pathname);
      if (url.pathname === state.pendingPath) {
        state.markPending();
        await state.pendingResponse;
      }
      if (url.pathname === '/' || url.pathname.startsWith('/css/') || url.pathname === '/js/dashboard.js') {
        return route.fulfill({ body: fs.readFileSync(path.join(root, url.pathname === '/' ? 'index.html' : url.pathname)),
          contentType: url.pathname === '/' ? 'text/html' : url.pathname.endsWith('.css') ? 'text/css' : 'application/javascript' });
      }
      if (url.pathname.endsWith('.js')) return route.fulfill({ body: '', contentType: 'application/javascript' });
      let data = {};
      if (url.pathname === '/api/accounts') data = { result: state.accounts };
      if (url.pathname === '/api/holdings') data = { result: {
        totalPurchaseAmount: { krw: 1500000, usd: 0 },
        items: [{ symbol: '005930', name: '테스트 보유종목', quantity: 20, currency: 'KRW',
          averagePurchasePrice: 75000, marketValue: { amount: 1600000 }, profitLoss: { rate: 0.0667 } }]
      } };
      if (url.pathname === '/api/health/exchange-rate') data = { result: { rate: 1300, validUntil: '2026-10-12T00:00:00Z' } };
      if (url.pathname === '/api/paper/summary') data = { totalTrades: state.totalTrades, winRate: 0, avgReturn: 0, cumReturn: 0,
        openPositions: [{ id: 1, symbol: '005930', entryDate: '2026-10-08', entryPrice: 71234.5, peakRate: 0 }], closedPositions: [] };
      if (url.pathname === '/api/scalping/stats') data = { totalTrades: 0, winRate: 0, cumReturn: 0 };
      return route.fulfill({ json: data });
    });
    if (setup) await setup(page, state);
    await page.goto('http://dashboard.test/');
    await page.locator('#accountTab').click();
    await page.waitForFunction(() => document.getElementById('ptTotalTrades').textContent === '0');
    await run(page, state);
    assert.deepEqual(state.errors, []);
  } finally { await browser.close(); }
}

function withAccount(page, state) {
  state.accounts = [{ accountNo: '1234-TEST-5678', accountType: '테스트 계좌', accountSeq: 'fixture-account' }];
}

async function assertPresentationBlank(page) {
  assert.equal(await page.locator('#account-overview').isVisible(), true);
  assert.equal(await page.getByRole('heading', { name: '계좌 정보', exact: true }).isVisible(), true);
  assert.equal(await page.getByRole('heading', { name: '보유 종목', exact: true }).isVisible(), true);
  assert.equal(await page.locator('#accountBody').getByText('계좌번호', { exact: true }).isVisible(), true);
  assert.equal(await page.locator('.holdings-panel thead th').count(), 5);
  assert.equal((await page.locator('#holdingsBody').textContent()).trim(), '');
  assert.equal((await page.locator('#totalPurchaseKrw').textContent()).trim(), '');
  assert.equal((await page.locator('#totalPurchaseUsd').textContent()).trim(), '');
  assert.equal(await page.locator('#holdingsBody tr[data-symbol]').count(), 0);
  assert.doesNotMatch(await page.locator('#account-overview').textContent(), /1234-TEST-5678|테스트 계좌|fixture-account|테스트 보유종목|005930|1,500,000|1,600,000|6\.67/);
  assert.equal(await page.locator('#quotePanel').isVisible(), false);
  assert.equal(await page.getByRole('button', { name: '실제 데이터 표시', exact: true }).getAttribute('aria-pressed'), 'true');
}

test('presentation blanks personal account data without removing the cards and survives refresh and reload', async () => dashboard(async (page, state) => {
  await page.getByText('1234-TEST-5678', { exact: true }).waitFor();
  await page.locator('#holdingsBody tr[data-symbol]').click();
  assert.equal(await page.locator('#quotePanel').isVisible(), true);
  await page.getByRole('button', { name: '발표용 공백 처리', exact: true }).click();
  await assertPresentationBlank(page);
  const readsBeforeRefresh = state.accountReads.length;
  await page.locator('#refreshBtn').click();
  await page.waitForFunction(() => document.getElementById('lastUpdated').textContent.startsWith('마지막 갱신'));
  await page.clock.fastForward(60000);
  await assertPresentationBlank(page);
  assert.equal(state.accountReads.length, readsBeforeRefresh);
  await page.getByRole('button', { name: '계좌·보유종목 숨기기', exact: true }).click();
  await page.getByRole('button', { name: '계좌·보유종목 보기', exact: true }).click();
  await assertPresentationBlank(page);
  await page.reload();
  await page.getByRole('tab', { name: '계좌·모의매매', exact: true }).click();
  await assertPresentationBlank(page);
  assert.equal(state.accountReads.length, readsBeforeRefresh);
  await page.setViewportSize({ width: 375, height: 844 });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), true);
  await page.locator('#accountPanel').screenshot({ path: 'build/dashboard-presentation-mobile.png' });
  await page.getByRole('button', { name: '실제 데이터 표시', exact: true }).click();
  await page.getByText('1234-TEST-5678', { exact: true }).waitFor();
  await page.getByText('테스트 보유종목', { exact: true }).waitFor();
  assert.equal(await page.locator('#totalPurchaseKrw').textContent(), '1,500,000 원');
  assert.equal(await page.locator('#quotePanel').isVisible(), false);
  await page.reload();
  await page.getByRole('tab', { name: '계좌·모의매매', exact: true }).click();
  await page.getByText('1234-TEST-5678', { exact: true }).waitFor();
}, withAccount));

for (const endpoint of ['/api/accounts', '/api/holdings']) {
  test(`presentation stays blank when an in-flight ${endpoint} response finishes`, async () => dashboard(async (page, state) => {
    await state.pendingStarted;
    try {
      await page.getByRole('button', { name: '발표용 공백 처리', exact: true }).click();
      await assertPresentationBlank(page);
    } finally { state.releaseResponse(); }
    await page.waitForFunction(() => document.getElementById('lastUpdated').textContent.startsWith('마지막 갱신'));
    await assertPresentationBlank(page);
  }, (page, state) => {
    withAccount(page, state);
    state.pendingPath = endpoint;
    state.pendingStarted = new Promise(resolve => { state.markPending = resolve; });
    state.pendingResponse = new Promise(resolve => { state.releaseResponse = resolve; });
  }));
}

test('restored presentation mode never places personal values in the initial document or requests account data', async () => dashboard(async (page, state) => {
  await assertPresentationBlank(page);
  assert.deepEqual(state.accountReads, []);
}, async (page, state) => {
  withAccount(page, state);
  await page.addInitScript(() => localStorage.setItem('tossinvest.accountPresentation', 'true'));
}));

test('presentation blanks and restores data with blocked storage and keyboard controls', async () => dashboard(async (page) => {
  await page.getByText('1234-TEST-5678', { exact: true }).waitFor();
  await page.getByRole('button', { name: '발표용 공백 처리', exact: true }).focus();
  await page.keyboard.press('Enter');
  await assertPresentationBlank(page);
  assert.equal(await page.locator('#paper-trading').isVisible(), true);
  assert.equal(await page.locator('#scalping-simulation').isVisible(), true);
  await page.getByRole('button', { name: '실제 데이터 표시', exact: true }).focus();
  await page.keyboard.press('Enter');
  await page.getByText('1234-TEST-5678', { exact: true }).waitFor();
  await page.getByText('테스트 보유종목', { exact: true }).waitFor();
}, async (page, state) => {
  withAccount(page, state);
  await page.addInitScript(() => {
    Object.defineProperty(window, 'localStorage', { get() { throw new DOMException('Storage blocked', 'SecurityError'); } });
  });
}));

test('account visibility conceals holdings and open quotes through refresh, tab changes and reload', async () => dashboard(async (page) => {
  await page.getByText('1234-TEST-5678', { exact: true }).waitFor();
  await page.locator('#holdingsBody tr[data-symbol]').click();
  assert.equal(await page.locator('#quotePanel').isVisible(), true);
  await page.getByRole('button', { name: '계좌·보유종목 숨기기', exact: true }).click();
  const show = page.getByRole('button', { name: '계좌·보유종목 보기', exact: true });
  assert.equal(await show.getAttribute('aria-expanded'), 'false');
  assert.equal(await page.getByText('1234-TEST-5678', { exact: true }).isVisible(), false);
  assert.equal(await page.getByText('테스트 보유종목', { exact: true }).isVisible(), false);
  assert.equal(await page.locator('#totalPurchaseKrw').isVisible(), false);
  assert.equal(await page.locator('#quotePanel').isVisible(), false);
  assert.equal(await page.locator('#paper-trading').isVisible(), true);
  assert.equal(await page.locator('#scalping-simulation').isVisible(), true);
  await page.locator('#refreshBtn').click();
  await page.waitForFunction(() => document.getElementById('lastUpdated').textContent.startsWith('마지막 갱신'));
  await page.clock.fastForward(60000);
  await page.getByRole('tab', { name: '투자 전략', exact: true }).click();
  await page.getByRole('tab', { name: '계좌·모의매매', exact: true }).click();
  assert.equal(await page.locator('#account-overview').isVisible(), false);
  await page.reload();
  await page.getByRole('tab', { name: '계좌·모의매매', exact: true }).click();
  await page.waitForFunction(() => document.getElementById('accountBody').textContent.includes('1234-TEST-5678'));
  assert.equal(await show.getAttribute('aria-expanded'), 'false');
  assert.equal(await page.locator('#account-overview').isVisible(), false);
  await page.setViewportSize({ width: 375, height: 844 });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), true);
  await page.locator('#accountPanel').screenshot({ path: 'build/dashboard-accounts-hidden-mobile.png' });
  await show.click();
  await page.getByText('1234-TEST-5678', { exact: true }).waitFor();
  await page.getByText('테스트 보유종목', { exact: true }).waitFor();
  assert.equal(await page.locator('#quotePanel').isVisible(), false);
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), true);
  await page.reload();
  await page.getByRole('tab', { name: '계좌·모의매매', exact: true }).click();
  await page.getByText('1234-TEST-5678', { exact: true }).waitFor();
}, withAccount));

test('account visibility works when storage is blocked', async () => dashboard(async (page) => {
  await page.getByText('1234-TEST-5678', { exact: true }).waitFor();
  await page.getByRole('button', { name: '계좌·보유종목 숨기기', exact: true }).click();
  assert.equal(await page.locator('#account-overview').isVisible(), false);
  await page.getByRole('button', { name: '계좌·보유종목 보기', exact: true }).focus();
  await page.keyboard.press('Enter');
  assert.equal(await page.locator('#account-overview').isVisible(), true);
}, async (page, state) => {
  withAccount(page, state);
  await page.addInitScript(() => {
    Object.defineProperty(window, 'localStorage', { get() { throw new DOMException('Storage blocked', 'SecurityError'); } });
  });
}));

for (const [action, selector, failure] of [
  ['paper run', '#runPaperBtn', '실행 실패'],
  ['manual close', '#ptOpenBody button', '청산 실패'],
  ['scalping scan', '#scalpTickBtn', '스캔 실패']
]) {
  test(`${action} surfaces HTTP failure and allows successful retry`, async () => dashboard(async (page, state) => {
    state.respond = route => route.fulfill({ status: 503, contentType: 'application/json', body: '{"message":"unavailable"}' });
    const failureAlert = page.waitForEvent('dialog', { predicate: dialog => dialog.type() === 'alert' });
    await page.locator(selector).click();
    await failureAlert;
    await page.waitForFunction(() => !document.querySelector('#runPaperBtn').textContent.includes('중') && !document.querySelector('#scalpTickBtn').textContent.includes('중'));
    // A failed action must produce a failure message, never a completion alert.
    assert.ok(state.dialogs.some(message => message.includes(failure) && message.includes('503')), JSON.stringify(state.dialogs));
    assert.ok(!state.dialogs.some(message => message.startsWith('완료')));
    state.respond = route => route.fulfill({ json: { bought: ['005930'], sold: [] } });
    const retried = page.waitForResponse(response => response.request().method() === 'POST');
    await page.locator(selector).click();
    assert.equal((await retried).status(), 200);
    await page.waitForFunction(() => !document.querySelector('#runPaperBtn').disabled && !document.querySelector('#scalpTickBtn').disabled);
    assert.equal(state.posts.length, 2);
  }));
}

for (const selector of ['#runPaperBtn', '#scalpTickBtn']) {
  test(`${selector} submits only once while a request is pending`, async () => dashboard(async (page, state) => {
    let release;
    const pending = new Promise(resolve => { release = resolve; });
    state.respond = async route => { await pending; return route.fulfill({ json: { bought: [], sold: [] } }); };
    try {
      await page.locator(selector).evaluate(button => { button.click(); button.click(); });
      assert.equal(await page.locator(selector).isDisabled(), true);
    } finally { release(); }
    await page.waitForFunction(selector => !document.querySelector(selector).disabled, selector);
    assert.equal(state.posts.length, 1);
  }));
}

test('paper summary refreshes automatically after a scheduled trade', async () => dashboard(async (page, state) => {
  state.totalTrades = 1;
  await page.clock.fastForward(60000);
  await page.waitForFunction(() => document.getElementById('ptTotalTrades').textContent === '1');
}));

test('non-JSON server errors retain the HTTP status in the failure message', async () => dashboard(async (page, state) => {
  state.respond = route => route.fulfill({ status: 502, contentType: 'text/html', body: '<h1>Bad Gateway</h1>' });
  const alert = page.waitForEvent('dialog', { predicate: dialog => dialog.type() === 'alert' });
  await page.locator('#runPaperBtn').click();
  assert.match((await alert).message(), /실행 실패.*502/);
}));
