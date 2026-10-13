const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '../../src/main/resources/static');

async function dashboard(run) {
  const browser = await chromium.launch({ headless: true, channel: 'msedge' });
  const state = { totalTrades: 0, posts: [], dialogs: [], errors: [], respond: async route => route.fulfill({ json: { bought: ['005930'], sold: [] } }) };
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
      if (url.pathname === '/' || url.pathname.startsWith('/css/') || url.pathname === '/js/dashboard.js') {
        return route.fulfill({ body: fs.readFileSync(path.join(root, url.pathname === '/' ? 'index.html' : url.pathname)),
          contentType: url.pathname === '/' ? 'text/html' : url.pathname.endsWith('.css') ? 'text/css' : 'application/javascript' });
      }
      if (url.pathname.endsWith('.js')) return route.fulfill({ body: '', contentType: 'application/javascript' });
      let data = {};
      if (url.pathname === '/api/accounts') data = { result: [] };
      if (url.pathname === '/api/health/exchange-rate') data = { result: { rate: 1300, validUntil: '2026-10-12T00:00:00Z' } };
      if (url.pathname === '/api/paper/summary') data = { totalTrades: state.totalTrades, winRate: 0, avgReturn: 0, cumReturn: 0,
        openPositions: [{ id: 1, symbol: '005930', entryDate: '2026-10-08', entryPrice: 71234.5, peakRate: 0 }], closedPositions: [] };
      if (url.pathname === '/api/scalping/stats') data = { totalTrades: 0, winRate: 0, cumReturn: 0 };
      return route.fulfill({ json: data });
    });
    await page.goto('http://dashboard.test/');
    await page.locator('#accountTab').click();
    await page.waitForFunction(() => document.getElementById('ptTotalTrades').textContent === '0');
    await run(page, state);
    assert.deepEqual(state.errors, []);
  } finally { await browser.close(); }
}

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
