// Fixed responses only. No application server, collection, import or Codex invocation.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const source = fs.readFileSync(path.resolve(__dirname, '../../src/main/resources/static/js/strategy-workbench.js'), 'utf8');
const styles = fs.readFileSync(path.resolve(__dirname, '../../src/main/resources/static/css/strategy-workbench.css'), 'utf8');
const reviewLabel = '저장 데이터 현황과 주의사항을 확인했습니다.';

function responseFor(url, overrides = {}) {
  return { status: 'READY', symbol: url.searchParams.get('symbol'), startDate: url.searchParams.get('startDate'),
    endDate: url.searchParams.get('endDate'), totalCandleCount: 260, availableStartDate: '2024-01-02', availableEndDate: '2025-01-31',
    candleCount: 20, actualStartDate: '2025-01-02', actualEndDate: '2025-01-31', warmupBars: 240, requiredWarmupBars: 20,
    calendarBasedWarmup: false, firstReadyDate: '2025-01-02', readyCandleCount: 20, invalidDataDate: null,
    warnings: ['ENTRY_READINESS_ONLY', 'TRADING_CALENDAR_NOT_CHECKED', 'CACHE_CAN_CHANGE'], ...overrides };
}

async function withWorkbench(action, schemaVersion = 2, entry = null) {
  const browser = await chromium.launch({ headless: true, channel: process.env.SW_BROWSER_CHANNEL || 'msedge' });
  try {
    const page = await browser.newPage(); page.setDefaultTimeout(5000);
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    const strategy = { schemaVersion, name: '준비 상태 전략', originalPrompt: '거래량 조건',
      entry: entry || { operator: 'AND', conditions: [{ type: 'VOLUME', period: 20, multiplier: 1.5, comparison: 'GTE' }] }, exit: null, risk: null };
    const saved = { id: 7, version: 1, strategy, createdAt: '2025-01-01T00:00:00Z', versionCreatedAt: '2025-01-01T00:00:00Z' };
    const second = { ...saved, id: 8, strategy: { ...strategy, name: '다른 준비 전략' } };
    const state = { requests: [], respond: (route, url) => route.fulfill({ json: responseFor(url) }) };
    await page.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url());
      if (url.pathname === '/') return route.fulfill({ contentType: 'text/html', body: '<!doctype html><html lang="ko"><meta name="viewport" content="width=device-width, initial-scale=1"><div id="strategyWorkbench"></div></html>' });
      state.requests.push({ path: url.pathname, query: Object.fromEntries(url.searchParams), method: request.method() });
      if (url.pathname.endsWith('/data-readiness')) return state.respond(route, url);
      let data;
      if (url.pathname.endsWith('/status')) data = { ready: false, message: '이 테스트는 Codex를 사용하지 않습니다.' };
      else if (url.pathname === '/api/strategies') data = [saved, second];
      else if (url.pathname === '/api/strategies/7') data = saved;
      else if (url.pathname === '/api/strategies/8') data = second;
      else if (url.pathname === '/api/strategies/8/versions') data = [second];
      else if (url.pathname.endsWith('/versions')) data = [saved, { ...saved, version: 2 }];
      else if (url.pathname.endsWith('/versions/2')) data = { ...saved, version: 2 };
      else if (url.pathname.endsWith('/backtests')) data = [];
      else return route.fulfill({ status: 404, json: { message: '예상하지 않은 테스트 요청' } });
      return route.fulfill({ json: data });
    });
    await page.goto('http://readiness.test/');
    await page.addStyleTag({ content: '* { box-sizing: border-box; }' + styles });
    await page.addScriptTag({ content: source });
    await page.getByRole('button', { name: / · #7 · 최신 v1/ }).click();
    await page.getByLabel('종목 코드', { exact: true }).fill('005930');
    await page.getByLabel('시작일', { exact: true }).fill('2025-01-01');
    await page.getByLabel('종료일', { exact: true }).fill('2025-01-31');
    await page.getByLabel('체결 방식', { exact: true }).selectOption('SAME_DAY_CLOSE');
    const panel = page.getByRole('region', { name: '실행 전 데이터 준비 상태', exact: true });
    const check = panel.getByRole('button', { name: '데이터 준비 상태 확인', exact: true });
    const run = page.getByRole('button', { name: '선택 버전 백테스트 실행', exact: true });
    await action({ page, panel, check, run, state });
    assert.deepEqual(errors, []);
    assert.equal(state.requests.some(r => r.method !== 'GET'), false, 'checking and recovery must have no write or collection requests');
    assert.equal(state.requests.some(r => /collect|import-csv|refresh-daily|interpret/.test(r.path)), false);
  } finally { await browser.close(); }
}

test('single schema 1 and 2 show exact request data and require review before running', async () => {
  for (const schema of [1, 2]) await withWorkbench(async ({ panel, check, run, state }) => {
    assert.equal(await run.isDisabled(), true);
    await check.click(); await panel.getByText('진입 판단 가능', { exact: true }).waitFor();
    assert.equal(await run.isDisabled(), true);
    await panel.getByLabel(reviewLabel, { exact: true }).check();
    assert.equal(await run.isEnabled(), true);
    assert.deepEqual(state.requests.find(r => r.path.endsWith('/data-readiness')).query,
      { version: '1', symbol: '005930', startDate: '2025-01-01', endDate: '2025-01-31', executionMode: 'SAME_DAY_CLOSE' });
    assert.match(await panel.innerText(), /2024-01-02 ~ 2025-01-31/);
    assert.match(await panel.innerText(), /260봉/);
    assert.match(await panel.innerText(), /거래일 누락·매도 조건·실제 체결·수익을 보장하지 않습니다/);
  }, schema);
});

test('partial calendar-based OR readiness uses the server decision and never invents a fixed warmup count', () => withWorkbench(async ({ panel, check, run, state }) => {
  state.respond = (route, url) => route.fulfill({ json: responseFor(url, { status: 'PARTIAL', requiredWarmupBars: null,
    calendarBasedWarmup: true, readyCandleCount: 3, firstReadyDate: '2025-01-29',
    warnings: ['START_OUTSIDE_CACHE', 'END_OUTSIDE_CACHE', 'CALENDAR_WARMUP'] }) });
  await check.click(); await panel.getByText('일부 구간 확인 필요', { exact: true }).waitFor();
  assert.match(await panel.innerText(), /달력 기준 · 고정 봉 수로 표시할 수 없음/);
  assert.match(await panel.innerText(), /3봉/);
  assert.match(await panel.innerText(), /휴장일인지 데이터 누락인지는 판단하지 않습니다/);
  await panel.getByLabel(reviewLabel, { exact: true }).check(); assert.equal(await run.isEnabled(), true);
}, 2, { operator: 'OR', conditions: [
  { type: 'RANGE_RATIO', period: 52, periodUnit: 'CALENDAR_WEEKS', priceField: 'HIGH', multiplier: 0.75, comparison: 'GTE' },
  { type: 'VOLUME', period: 20, multiplier: 1.5, comparison: 'GTE' }
] }));

test('no data, insufficient history and invalid data stay distinct and block execution', () => withWorkbench(async ({ panel, check, run, state }) => {
  for (const [status, label] of [['NO_DATA', '요청 기간 데이터 없음'], ['INSUFFICIENT_DATA', '지표 준비 이력 부족'], ['INVALID_DATA', '저장 데이터 오류']]) {
    state.respond = (route, url) => route.fulfill({ json: responseFor(url, { status, candleCount: status === 'NO_DATA' ? 0 : status === 'INVALID_DATA' ? null : 20,
      warmupBars: status === 'INVALID_DATA' ? null : 0, requiredWarmupBars: status === 'INVALID_DATA' ? null : 20,
      readyCandleCount: status === 'INVALID_DATA' ? null : 0, firstReadyDate: null,
      actualStartDate: status === 'NO_DATA' || status === 'INVALID_DATA' ? null : '2025-01-02',
      actualEndDate: status === 'NO_DATA' || status === 'INVALID_DATA' ? null : '2025-01-31', invalidDataDate: status === 'INVALID_DATA' ? '2025-01-10' : null }) });
    await check.click(); await panel.getByText(label, { exact: true }).waitFor();
    assert.equal(await run.isDisabled(), true);
    assert.equal(await panel.getByLabel(reviewLabel, { exact: true }).isVisible(), false);
    if (status === 'INVALID_DATA') {
      assert.match(await panel.innerText(), /2025-01-10/);
      for (const label of ['요청 구간 봉 수', '시작 전 준비 봉 수', '필요 준비 봉 수', '진입 판단 가능 봉 수'])
        assert.equal(await panel.locator('dl > div').filter({ hasText: label }).locator('dd').innerText(), '계산 불가');
    }
  }
}));

test('input and saved-version changes invalidate the result and its review', () => withWorkbench(async ({ page, panel, check, run, state }) => {
  async function ready() { await check.click(); await panel.getByText('진입 판단 가능', { exact: true }).waitFor(); await panel.getByLabel(reviewLabel, { exact: true }).check(); }
  for (const change of [() => page.getByLabel('종목 코드', { exact: true }).fill('000660'),
    () => page.getByLabel('시작일', { exact: true }).fill('2025-01-02'),
    () => page.getByLabel('종료일', { exact: true }).fill('2025-01-30'),
    () => page.getByLabel('체결 방식', { exact: true }).selectOption('NEXT_DAY_OPEN'),
    () => page.getByRole('button', { name: /^v2 ·/ }).click()]) {
    await ready(); await change(); assert.equal(await run.isDisabled(), true);
    assert.equal(await panel.getByLabel(reviewLabel, { exact: true }).isVisible(), false);
    assert.match(await panel.innerText(), /다시 확인/);
  }
  await ready();
  assert.equal(state.requests.filter(r => r.path.endsWith('/data-readiness')).at(-1).query.version, '2');
  await page.getByRole('button', { name: / · #8 · 최신 v1/ }).click();
  assert.equal(await run.isDisabled(), true);
  await ready();
  assert.equal(state.requests.filter(r => r.path.endsWith('/data-readiness')).at(-1).path, '/api/strategies/8/data-readiness');
}));

test('late success and late HTTP error cannot overwrite a newer result', () => withWorkbench(async ({ page, panel, check, run, state }) => {
  for (const lateFailure of [false, true]) {
    let release, arrived;
    const pending = new Promise(resolve => { arrived = resolve; });
    state.respond = async (route, url) => {
      if (url.searchParams.get('symbol') === '005930') {
        arrived(); await new Promise(resolve => { release = resolve; });
        return lateFailure ? route.fulfill({ status: 503, json: { message: '늦은 조회 실패' } }) : route.fulfill({ json: responseFor(url) });
      }
      return route.fulfill({ json: responseFor(url, { status: 'INSUFFICIENT_DATA', readyCandleCount: 0, firstReadyDate: null }) });
    };
    await page.getByLabel('종목 코드', { exact: true }).fill('005930'); await check.click(); await pending;
    await page.getByLabel('종목 코드', { exact: true }).fill('000660'); await check.click();
    await panel.getByText('지표 준비 이력 부족', { exact: true }).waitFor();
    const late = page.waitForResponse(r => r.url().includes('/data-readiness') && r.url().includes('005930'));
    release(); await (await late).finished();
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    assert.equal(await panel.getByText('진입 판단 가능', { exact: true }).count(), 0);
    assert.equal(await panel.getByText(/늦은 조회 실패/).count(), 0);
    assert.equal(await run.isDisabled(), true);
  }
}));

test('failed or malformed refresh clears permission and recovery stays local and keyboard accessible', () => withWorkbench(async ({ page, panel, check, run, state }) => {
  await check.click(); await panel.getByLabel(reviewLabel, { exact: true }).check();
  assert.equal(await run.isEnabled(), true);
  state.respond = route => route.fulfill({ status: 503, json: { message: '잠시 후 다시 시도하세요.' } });
  await check.click(); await panel.getByText('조회 실패 · 준비 상태 미확인', { exact: true }).waitFor();
  assert.equal(await run.isDisabled(), true);
  assert.equal(await panel.getByText('요청 기간 데이터 없음', { exact: true }).count(), 0);
  for (const overrides of [null, { symbol: '다른종목' }, { status: 'UNKNOWN' }]) {
    state.respond = (route, url) => route.fulfill({ json: overrides ? responseFor(url, overrides) : { status: 'READY' } });
    await check.click(); await panel.getByText('조회 실패 · 준비 상태 미확인', { exact: true }).waitFor();
    assert.equal(await run.isDisabled(), true);
  }
  const period = panel.getByRole('button', { name: '기간 수정', exact: true }); await period.focus(); await page.keyboard.press('Enter');
  assert.equal(await page.getByLabel('시작일', { exact: true }).evaluate(el => el === document.activeElement), true);
  await panel.getByRole('button', { name: '종목 변경', exact: true }).click();
  assert.equal(await page.getByLabel('종목 코드', { exact: true }).evaluate(el => el === document.activeElement), true);
  await panel.getByText('데이터 준비 방법 보기', { exact: true }).click();
  assert.match(await panel.innerText(), /python scripts\/fetch_candles.py/);
  assert.match(await panel.innerText(), /외부 자료 조회/);
  await page.setViewportSize({ width: 360, height: 800 });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
}));

test('schema 3 and 4 show separate preparation scope without a readiness call', async () => {
  for (const schema of [3, 4]) await withWorkbench(async ({ panel, state }) => {
    assert.match(await panel.innerText(), /포트폴리오·조합 전략은 별도 데이터 준비 확인이 필요합니다/);
    assert.equal(await panel.getByRole('button', { name: '데이터 준비 상태 확인', exact: true }).isVisible(), false);
    assert.equal(state.requests.some(r => r.path.endsWith('/data-readiness')), false);
  }, schema);
});

test('English date-validation errors remain a Korean recovery state with execution blocked', () => withWorkbench(async ({ page, panel, check, run, state }) => {
  await check.click(); await panel.getByLabel(reviewLabel, { exact: true }).check();
  await page.getByLabel('시작일', { exact: true }).fill('1899-01-01');
  state.respond = route => route.fulfill({ status: 400, json: { code: 'STRATEGY_VALIDATION_FAILED',
    message: 'Strategy validation failed.', issues: [{ path: 'startDate', message: 'Use a date between years 1900 and 9998.' }] } });
  await check.click(); await panel.getByText('조회 실패 · 준비 상태 미확인', { exact: true }).waitFor();
  assert.match(await panel.innerText(), /서버 연결/);
  assert.match(await panel.innerText(), /종목 코드·기간·체결 방식/);
  assert.doesNotMatch(await panel.innerText(), /Strategy validation|Use a date|STRATEGY_VALIDATION_FAILED/);
  assert.equal(await panel.getByText('진입 판단 가능', { exact: true }).count(), 0);
  assert.equal(await panel.getByText('요청 기간 데이터 없음', { exact: true }).count(), 0);
  assert.equal(await run.isDisabled(), true);
}));
