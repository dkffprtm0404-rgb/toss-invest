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
        { type: 'ATR_BREAKOUT', method: 'WILDER', period: 14, multiplier: 0.5, comparison: 'GT' }
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
    assert.equal(submitted.risk.trailingStop.rate, -0.18);
    assert.equal(submitted.risk.trailingStop.peakBasis, 'CLOSE');
    assert.equal(submitted.risk.atrStop.multiplier, null);
    await page.screenshot({path:path.resolve(__dirname, '../../build/strategy-expansion-desktop.png'), fullPage:true});
    await page.setViewportSize({width:390, height:844});
    const width = await page.locator('#strategyWorkbench').evaluate(el => ({content:el.scrollWidth, box:el.clientWidth}));
    assert.ok(width.content <= width.box, 'expanded form should fit a narrow viewport');
    await page.screenshot({path:path.resolve(__dirname, '../../build/strategy-expansion-mobile.png'), fullPage:true});
    assert.deepEqual(errors, []);
  } finally { await browser.close(); }
});
