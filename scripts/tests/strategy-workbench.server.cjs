// Opt-in real Spring Boot HTTP/H2/engine integration. The Java fixture replaces only Codex.
const test = require('node:test');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
test('real server saves a confirmed draft, runs seeded candles, and reloads immutable history', async () => {
  const base = process.env.SW_BASE_URL;
  assert.ok(base);
  const browser = await chromium.launch({headless:true, channel:'msedge'});
  try {
    const page = await browser.newPage();
    page.setDefaultTimeout(process.env.RUN_CODEX_LIVE === 'true' ? 150000 : 15000);
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    // No market/account requests or remote fonts/CDNs during this isolated integration test.
    await page.route('**/*', route => {
      const url = new URL(route.request().url());
      if (!url.href.startsWith(base) || (url.pathname.startsWith('/api/')
          && !/^\/api\/(strategies|strategy-assistant|backtest\/runs)(\/|$)/.test(url.pathname))) {
        const resource = route.request().resourceType();
        if (resource === 'script') return route.fulfill({status:200,contentType:'application/javascript',body:''});
        if (resource === 'stylesheet') return route.fulfill({status:200,contentType:'text/css',body:''});
        return route.fulfill({status:200,contentType:'application/json',body:'{"result":[]}'});
      }
      return route.continue();
    });
    await page.goto(base);
    const root = page.locator('#strategyWorkbench');
    await root.getByText('연결 준비 완료', {exact:false}).waitFor();
    await root.getByLabel('전략 원문', {exact:true}).fill('SMA 5일/20일 골든크로스에 매수하고 5% 손절한다.');
    await root.getByRole('button', {name:'Codex로 조건 해석'}).click();
    await root.getByLabel('전략 이름').waitFor();
    await root.getByLabel('원문·조건·위험 관리 설정을 검토했으며 이 내용으로 저장합니다.').check();
    await root.getByRole('button', {name:'새 전략으로 저장'}).click();
    await root.getByText('실행 대상: 전략 #', {exact:false}).waitFor();
    await root.getByLabel('종목 코드').fill('005930');
    await root.getByLabel('시작일').fill('2025-01-01');
    await root.getByLabel('종료일').fill('2025-03-01');
    await root.getByLabel('체결 방식').selectOption('NEXT_DAY_OPEN');
    const responseWait = page.waitForResponse(r => r.request().method()==='POST' && /\/strategies\/\d+\/backtests$/.test(r.url()));
    await root.getByRole('button', {name:'선택 버전 백테스트 실행'}).click();
    const response = await responseWait; assert.equal(response.status(),201);
    const run = await response.json();
    assert.equal(run.status,'COMPLETED');
    assert.ok(run.snapshot.result.metrics.closedTrades > 0);
    assert.equal(run.snapshot.strategy.strategy.entry.conditions.length,1);
    assert.equal(run.snapshot.costs.model,'NOT_MODELED');
    await root.getByText(`실행 #${run.id} · 완료`, {exact:true}).waitFor();
    await root.getByRole('img', {name:'종가와 체결점'}).waitFor();
    assert.equal(await root.locator('.sr-results svg').count(),3, 'all result charts work without remote Chart.js');
    await root.getByRole('button', {name:'AI 설명 생성',exact:true}).click();
    await root.getByText('CODEX_TIMEOUT', {exact:false}).waitFor();
    assert.equal(await root.getByRole('img', {name:'종가와 체결점'}).isVisible(),true);
    const explanationWait = page.waitForResponse(r => r.request().method() === 'POST' && /\/explanation$/.test(r.url()));
    await root.getByRole('button', {name:'AI 설명 다시 시도'}).click();
    const explanationResponse = await explanationWait;
    assert.equal(explanationResponse.status(),200,await explanationResponse.text());
    await root.getByText('저장된 AI 설명', {exact:false}).waitFor();
    const savedExplanation = await page.request.get(`${base}/api/backtest/runs/${run.id}/explanation`);
    assert.equal(savedExplanation.status(),200);
    const explanation = await savedExplanation.json();
    assert.equal(explanation.status,'READY');
    assert.equal(explanation.dataSha256,run.snapshot.data.sha256);
    const explanationText = await root.locator('.sr-explanation .sr-highlight').allTextContents();
    assert.ok(explanationText.length > 0);
    await root.locator('.sr-explanation').getByRole('button', {name:'거래 #1 근거 보기'}).click();
    assert.equal(await root.locator('.sr-trade[data-trade-number="1"]').getAttribute('open'),'');
    await page.screenshot({path:'build/strategy-workbench-desktop.png',fullPage:true});
    await root.locator('.sr-results').screenshot({path:'build/strategy-results-desktop.png'});
    await page.reload();
    await root.getByRole('button', {name:/ · #\d+ · 최신 v1/}).click();
    await root.getByRole('button', {name:new RegExp('^#'+run.id+' · v1')}).click();
    await root.getByText(`실행 #${run.id} · 완료`, {exact:true}).waitFor();
    await root.getByText('저장된 AI 설명', {exact:false}).waitFor();
    assert.deepEqual(await root.locator('.sr-explanation .sr-highlight').allTextContents(),explanationText);
    await page.setViewportSize({width:390,height:844});
    await page.screenshot({path:'build/strategy-workbench-mobile.png',fullPage:true});
    const overflow = await page.evaluate(() => [...document.querySelectorAll('#strategyWorkbench, #strategyWorkbench *')]
      .filter(el => el.getBoundingClientRect().right > innerWidth + 1 && !el.closest('.sw-table-scroll'))
      .map(el => ({tag:el.tagName, cls:el.className, right:el.getBoundingClientRect().right})));
    assert.deepEqual(overflow, [], 'strategy workbench should fit a mobile viewport');
    assert.deepEqual(errors,[]);
  } finally { await browser.close(); }
});
