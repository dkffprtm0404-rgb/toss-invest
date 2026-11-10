const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const asset = file => fs.readFileSync(path.resolve(__dirname, '../../src/main/resources/static/', file), 'utf8');

test('MACD and Bollinger editors preserve missing inputs, explicit values and readable snapshots', async () => {
  const browser = await chromium.launch({ headless: true, channel: process.env.SW_BROWSER_CHANNEL || 'msedge' });
  try {
    const page = await browser.newPage(); page.setDefaultTimeout(3000);
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    let submitted;
    const strategy = { schemaVersion: 2, name: '새 지표', originalPrompt: 'MACD와 볼린저밴드', entry: { operator: 'AND', conditions: [
      { type: 'MACD', fastPeriod: null, slowPeriod: null, signalPeriod: null, value: null, reference: null, threshold: null, comparison: null },
      { type: 'BOLLINGER', period: null, multiplier: null, band: null, comparison: null }
    ] }, exit: null, risk: null };
    await page.route('**/*', route => {
      const request = route.request(); const url = new URL(request.url());
      if (url.pathname === '/') return route.fulfill({ contentType: 'text/html', body: '<html lang="ko"><div id="strategyWorkbench"></div></html>' });
      let data = [];
      if (url.pathname.endsWith('/status')) data = { ready: true, model: 'test' };
      if (url.pathname.endsWith('/interpret-batch')) data = { items: [{ title: strategy.name, prompt: strategy.originalPrompt, draft: { strategy, ready: false, issues: [], questions: ['지표 기간과 비교 대상을 입력해 주세요.'], unsupported: [] } }], questions: [] };
      if (url.pathname.endsWith('/validate')) { submitted = JSON.parse(request.postData()); data = { strategy: submitted, ready: false, issues: [], questions: [], unsupported: [] }; }
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) });
    });
    await page.goto('http://indicators.test/');
    await page.addStyleTag({ content: '* { box-sizing: border-box; }' + asset('css/strategy-workbench.css') });
    await page.addScriptTag({ content: asset('js/strategy-workbench.js') });
    await page.getByLabel('전략 원문', { exact: true }).fill(strategy.originalPrompt);
    await page.getByRole('button', { name: 'Codex로 조건 해석', exact: true }).click();
    assert.equal(await page.getByLabel('MACD 단기 EMA 기간 (봉)', { exact: true }).inputValue(), '');
    assert.equal(await page.getByLabel('볼린저 표준편차 배수', { exact: true }).inputValue(), '');
    await page.getByLabel('MACD 단기 EMA 기간 (봉)', { exact: true }).fill('12');
    await page.getByLabel('MACD 장기 EMA 기간 (봉)', { exact: true }).fill('26');
    await page.getByLabel('MACD 시그널 EMA 기간 (봉)', { exact: true }).fill('9');
    await page.getByLabel('MACD 값', { exact: true }).selectOption('HISTOGRAM');
    await page.getByLabel('MACD 비교 대상', { exact: true }).selectOption('THRESHOLD');
    await page.getByLabel('MACD 숫자 기준 (시그널 비교 시 비움)', { exact: true }).fill('-0.25');
    await page.getByLabel('비교 방식', { exact: true }).nth(0).selectOption('CROSS_ABOVE');
    await page.getByLabel('볼린저 기간 (봉)', { exact: true }).fill('20');
    await page.getByLabel('볼린저 표준편차 배수', { exact: true }).fill('2');
    await page.getByLabel('볼린저 비교 밴드', { exact: true }).selectOption('LOWER');
    await page.getByLabel('비교 방식', { exact: true }).nth(1).selectOption('LTE');
    await Promise.all([page.waitForResponse(response => response.url().endsWith('/validate')), page.getByRole('button', { name: '입력 조건 검증', exact: true }).click()]);
    assert.deepEqual(submitted.entry.conditions, [
      { type: 'MACD', fastPeriod: 12, slowPeriod: 26, signalPeriod: 9, value: 'HISTOGRAM', reference: 'THRESHOLD', threshold: -0.25, comparison: 'CROSS_ABOVE' },
      { type: 'BOLLINGER', period: 20, multiplier: 2, band: 'LOWER', comparison: 'LTE' }
    ]);
    const snapshot = await page.evaluate(value => { const host = document.createElement('section'); window.StrategyWorkbenchPresentation.snapshotRules(host, value); return host.textContent; }, submitted);
    assert.match(snapshot, /MACD.*12.*26.*9.*히스토그램.*-0\.25/);
    assert.match(snapshot, /볼린저.*20.*2.*하단/);
    await page.getByLabel('MACD 숫자 기준 (시그널 비교 시 비움)', { exact: true }).fill('');
    await page.getByLabel('MACD 값', { exact: true }).selectOption('LINE');
    await page.getByLabel('MACD 비교 대상', { exact: true }).selectOption('SIGNAL');
    await page.getByLabel('볼린저 표준편차 배수', { exact: true }).fill('');
    await Promise.all([page.waitForResponse(response => response.url().endsWith('/validate')), page.getByRole('button', { name: '입력 조건 검증', exact: true }).click()]);
    assert.equal(submitted.entry.conditions[0].threshold, null);
    assert.equal(submitted.entry.conditions[0].reference, 'SIGNAL');
    assert.equal(submitted.entry.conditions[1].multiplier, null);
    await page.setViewportSize({ width: 390, height: 844 });
    const width = await page.locator('#strategyWorkbench').evaluate(element => ({ content: element.scrollWidth, box: element.clientWidth }));
    assert.ok(width.content <= width.box, 'new indicator inputs must fit a narrow viewport');
    assert.deepEqual(errors, []);
  } finally { await browser.close(); }
});

test('saved trade evidence displays MACD and Bollinger names with actual and prior values', async () => {
  const browser = await chromium.launch({ headless: true, channel: process.env.SW_BROWSER_CHANNEL || 'msedge' });
  try {
    const page = await browser.newPage();
    await page.route('**/*', route => {
      if (new URL(route.request().url()).pathname === '/') return route.fulfill({ contentType: 'text/html', body: '<div id="results"></div>' });
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify(route.request().url().endsWith('/analysis') ? { prices: [], curve: [], months: [], exitReasons: [] } : { status: 'NOT_GENERATED' }) });
    });
    await page.goto('http://indicator-results.test/');
    await page.addScriptTag({ content: asset('js/strategy-results.js') });
    await page.evaluate(() => {
      const fill = type => ({ reason: 'ENTRY_CONDITIONS', evidence: [{ type, path: 'entry.conditions[0]', actualValue: 1.5, referenceValue: 1.25, previousActualValue: 1, previousReferenceValue: 1.1, matched: true }] });
      window.StrategyResults.mount(document.getElementById('results'), { id: 1, snapshot: { result: { trades: [{ entry: fill('MACD'), exit: fill('BOLLINGER'), returnRate: 0, holdingBars: 1 }] } } });
    });
    const headings = await page.locator('.sr-evidence h6').allTextContents();
    assert.match(headings[0], /MACD/); assert.match(headings[1], /볼린저밴드/);
    await page.locator('.sr-trade summary').click();
    assert.match(await page.locator('.sr-trade').innerText(), /1\.5[\s\S]*1\.25[\s\S]*1[\s\S]*1\.1/);
  } finally { await browser.close(); }
});
