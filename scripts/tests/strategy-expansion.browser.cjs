const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const source = fs.readFileSync(path.resolve(__dirname, '../../src/main/resources/static/js/strategy-workbench.js'), 'utf8');
const styles = fs.readFileSync(path.resolve(__dirname, '../../src/main/resources/static/css/strategy-workbench.css'), 'utf8');

test('extended condition and risk editors preserve all explicit values and missing inputs', async () => {
  const browser = await chromium.launch({ headless: true, channel: process.env.SW_BROWSER_CHANNEL || 'msedge' });
  try {
    const page = await browser.newPage();
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    let submitted;
    const strategy = { schemaVersion: 2, name: '20일 신고가', originalPrompt: '종가 신고가',
      entry: { operator: 'AND', conditions: [
        { type: 'RANGE_BREAKOUT', period: 20, periodUnit: 'BARS', priceField: 'HIGH', comparison: 'GT' },
        { type: 'MA_COMPARE', averageType: 'SMA', shortPeriod: 20, longPeriod: 200, comparison: 'GT' },
        { type: 'PRICE_MA', averageType: 'SMA', period: 200, comparison: 'GT' },
        { type: 'ATR_BREAKOUT', method: 'WILDER', period: 14, multiplier: 0.5, comparison: 'GT' },
        { type: 'MA_TREND', averageType: 'SMA', period: 200, lookbackMonths: 1, direction: 'UP' },
        { type: 'RANGE_RATIO', period: 52, periodUnit: 'CALENDAR_WEEKS', priceField: 'HIGH', multiplier: 0.75, comparison: 'GTE' },
        { type: 'RANGE_RATIO', period: 52, periodUnit: 'CALENDAR_WEEKS', priceField: 'LOW', multiplier: 1.30, comparison: 'GTE' }
      ] }, exit: null, risk: { stopLoss: { rate: -0.05 }, trailingStop: { rate: -0.2, peakBasis: 'HIGH' },
        atrStop: { method: 'WILDER', period: 14, multiplier: 1.5 } } };
    await page.route('**/*', async route => {
      const req = route.request(); const url = new URL(req.url());
      if (url.pathname === '/') return route.fulfill({ contentType: 'text/html', body: '<html lang="ko"><div id="strategyWorkbench"></div></html>' });
      let data = [];
      if (url.pathname.endsWith('/status')) data = { ready: true, model: 'test' };
      if (url.pathname.endsWith('/interpret-batch')) data = {items:[{title:strategy.name,prompt:strategy.originalPrompt,draft:{ strategy, ready: true, issues: [], questions: [], unsupported: [] }}],questions:[]};
      if (url.pathname.endsWith('/validate')) {
        submitted = JSON.parse(req.postData());
        data = { strategy: submitted, ready: false, issues: [], questions: [], unsupported: [] };
      }
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) });
    });
    await page.goto('http://expansion.test/');
    await page.addStyleTag({content:'* { box-sizing: border-box; }' + styles});
    await page.addScriptTag({content: source});
    await page.getByLabel('전략 원문', {exact:true}).fill('종가 신고가');
    await page.getByRole('button', {name:'Codex로 조건 해석', exact:true}).click();
    const summary = await page.evaluate(value => {
      const host = document.createElement('section');
      window.StrategyWorkbenchPresentation.snapshotRules(host, value);
      return host.textContent;
    }, strategy);
    assert.match(summary, /달력상 1개월 전 값/);
    assert.match(summary, /최고 고가 × 75%/);
    assert.match(summary, /최저 저가 × 130%/);
    await page.getByLabel('추세 비교 기간 (달력 개월)', {exact:true}).fill('2');
    await page.getByLabel('추세 방향', {exact:true}).selectOption('DOWN');
    assert.equal(await page.getByLabel('구간 가격 대비 비율 (%)', {exact:true}).nth(0).inputValue(), '75');
    assert.equal(await page.getByLabel('구간 가격 대비 비율 (%)', {exact:true}).nth(1).inputValue(), '130');
    await page.getByLabel('구간 가격 대비 비율 (%)', {exact:true}).nth(0).fill('80');
    await page.getByLabel('구간 가격 대비 비율 (%)', {exact:true}).nth(1).fill('');
    await page.getByLabel('추적손절 수익률 (%) · 음수, 예: -20', {exact:true}).fill('-18');
    await page.getByLabel('추적 고점 기준', {exact:true}).selectOption('CLOSE');
    await page.getByLabel('돌파 구간 단위', {exact:true}).selectOption('CALENDAR_WEEKS');
    await page.getByLabel('돌파 구간 길이', {exact:true}).fill('52');
    await page.getByLabel('ATR 손절 배수', {exact:true}).fill('');
    await Promise.all([page.waitForResponse(r => r.url().endsWith('/validate')),
      page.getByRole('button', {name:'입력 조건 검증', exact:true}).click()]);
    assert.equal(submitted.entry.conditions[0].periodUnit, 'CALENDAR_WEEKS');
    assert.equal(submitted.entry.conditions[0].period, 52);
    assert.equal(submitted.entry.conditions[1].longPeriod, 200);
    assert.equal(submitted.entry.conditions[2].period, 200);
    assert.equal(submitted.entry.conditions[3].method, 'WILDER');
    assert.equal(submitted.entry.conditions[4].period, 200);
    assert.equal(submitted.entry.conditions[4].lookbackMonths, 2);
    assert.equal(submitted.entry.conditions[4].direction, 'DOWN');
    assert.equal(submitted.entry.conditions[5].multiplier, 0.80);
    assert.equal(submitted.entry.conditions[6].multiplier, null);
    assert.equal(submitted.risk.trailingStop.rate, -0.18);
    assert.equal(submitted.risk.trailingStop.peakBasis, 'CLOSE');
    assert.equal(submitted.risk.atrStop.multiplier, null);
    await page.screenshot({path:path.resolve(__dirname, '../../build/strategy-expansion-desktop.png'), fullPage:true});
    await page.locator('.sw-condition').nth(4).screenshot({path:path.resolve(__dirname, '../../build/strategy-trend-desktop.png')});
    await page.locator('.sw-condition').nth(5).screenshot({path:path.resolve(__dirname, '../../build/strategy-ratio-desktop.png')});
    await page.setViewportSize({width:390, height:844});
    const width = await page.locator('#strategyWorkbench').evaluate(el => ({content:el.scrollWidth, box:el.clientWidth}));
    assert.ok(width.content <= width.box, 'expanded form should fit a narrow viewport');
    await page.screenshot({path:path.resolve(__dirname, '../../build/strategy-expansion-mobile.png'), fullPage:true});
    await page.getByLabel('추세 비교 기간 (달력 개월)', {exact:true}).focus();
    await page.keyboard.press('Tab');
    assert.equal(await page.getByLabel('추세 방향', {exact:true}).evaluate(el => el === document.activeElement), true);
    await page.locator('.sw-condition').nth(4).scrollIntoViewIfNeeded();
    await page.screenshot({path:path.resolve(__dirname, '../../build/strategy-trend-mobile.png')});
    assert.deepEqual(errors, []);
  } finally { await browser.close(); }
});
