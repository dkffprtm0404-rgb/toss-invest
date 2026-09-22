// Opt-in real Spring HTTP/H2/engine integration, launched by ComparisonWebWorkflowTests.
const test = require('node:test');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');

test('stored versions use real comparison results and immutable history at desktop and mobile widths', async () => {
  const base = process.env.SW_BASE_URL;
  const firstId = Number(process.env.SC_FIRST_ID), secondId = Number(process.env.SC_SECOND_ID);
  assert.ok(base && firstId > 0 && secondId > 0);
  const browser = await chromium.launch({headless:true, channel:'msedge'});
  try {
    const page = await browser.newPage({viewport:{width:1440,height:1000}});
    page.setDefaultTimeout(15000);
    const errors = [], modelRequests = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/*', route => {
      const request = route.request(), url = new URL(request.url());
      const local = url.origin === new URL(base).origin;
      if (local && url.pathname === '/api/strategy-assistant/status') {
        return route.fulfill({contentType:'application/json',body:JSON.stringify({ready:true,code:'READY',message:'통합 검증 준비 완료',model:'test'})});
      }
      if (local && /^\/api\/strategy-assistant\//.test(url.pathname)) modelRequests.push(url.pathname);
      if (!local || (url.pathname.startsWith('/api/') && !/^\/api\/(strategies|comparisons)(\/|$)/.test(url.pathname))) {
        const resource = request.resourceType();
        if (resource === 'script') return route.fulfill({contentType:'application/javascript',body:''});
        if (resource === 'stylesheet') return route.fulfill({contentType:'text/css',body:''});
        return route.fulfill({contentType:'application/json',body:'{"result":[]}'});
      }
      return route.continue();
    });
    await page.goto(base);
    const workbench = page.locator('#strategyWorkbench'), comparison = page.locator('#strategyComparison');
    await workbench.getByText('연결 준비 완료', {exact:false}).waitFor();
    for (const [id,name] of [[firstId,'통합 손절 5%'],[secondId,'통합 손절 20%']]) {
      await workbench.getByRole('button',{name:`${name} · #${id} · 최신 v1`,exact:true}).click();
      await workbench.getByText(`선택: ${name} · 전략 #${id} / 버전 1`,{exact:true}).waitFor();
      await workbench.getByRole('button',{name:'선택 버전을 비교에 추가',exact:true}).click();
      await comparison.getByText(`전략 #${id} / v1을 비교에 추가했습니다.`,{exact:true}).waitFor();
    }
    await comparison.getByLabel('비교 종목 코드',{exact:true}).fill('005930');
    await comparison.getByLabel('비교 시작일',{exact:true}).fill('2025-01-02');
    await comparison.getByLabel('비교 종료일',{exact:true}).fill('2025-01-04');
    await comparison.getByLabel('비교 체결 방식',{exact:true}).selectOption('SAME_DAY_CLOSE');
    const savedResponse = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/comparisons');
    await comparison.getByRole('button',{name:'비교 실행 및 저장',exact:true}).click();
    const response = await savedResponse;
    assert.equal(response.status(),201,await response.text());
    const detail = await response.json(), snapshot = detail.snapshot;
    assert.deepEqual(snapshot.execution.strategies,[{id:firstId,version:1},{id:secondId,version:1}]);
    assert.equal(snapshot.type,'SINGLE');
    assert.equal(snapshot.data.candles.length,4);
    assert.match(snapshot.data.sha256,/^[0-9a-f]{64}$/);
    assert.equal(snapshot.entries[0].metrics.returnRate,-0.06);
    assert.equal(snapshot.entries[0].metrics.tradeCount,1);
    assert.equal(snapshot.entries[1].metrics.returnRate,0);
    assert.equal(snapshot.entries[1].metrics.tradeCount,0);
    await comparison.getByRole('heading',{name:`비교 #${detail.id}`,exact:true}).waitFor();
    const returnValues = comparison.locator('.sc-entry .sc-metrics > div').filter({has:page.locator('dt',{hasText:'청산 거래 수익률 합계'})}).locator('dd');
    assert.deepEqual(await returnValues.allTextContents(),['-6%','0%']);
    await comparison.getByRole('img',{name:'공통 날짜 기준 전략 성과 비교'}).waitFor();
    const originalResultText = await comparison.locator('.sc-result').innerText();
    await comparison.screenshot({path:'build/strategy-comparison-server-desktop.png'});

    await page.reload();
    const historyResponse = page.waitForResponse(response => response.request().method() === 'GET'
      && new URL(response.url()).pathname === `/api/comparisons/${detail.id}`);
    await comparison.getByRole('button',{name:new RegExp(`^비교 #${detail.id} 보기 ·`)}).click();
    const loadedResponse = await historyResponse;
    assert.equal(loadedResponse.status(),200);
    assert.deepEqual((await loadedResponse.json()).snapshot,snapshot);
    await comparison.getByRole('heading',{name:`비교 #${detail.id}`,exact:true}).waitFor();
    assert.equal(await comparison.locator('.sc-result').innerText(),originalResultText);
    assert.deepEqual(await returnValues.allTextContents(),['-6%','0%']);

    await page.setViewportSize({width:390,height:844});
    await comparison.screenshot({path:'build/strategy-comparison-server-mobile.png'});
    const overflow = await comparison.evaluate(root => [root,...root.querySelectorAll('*')]
      .filter(element => element.getClientRects().length && element.getBoundingClientRect().right > innerWidth + 1)
      .map(element => ({tag:element.tagName,className:String(element.className),right:element.getBoundingClientRect().right})));
    assert.deepEqual(overflow,[],'comparison should fit a mobile viewport');
    assert.deepEqual(modelRequests,[],'saved strategy comparison must not invoke the model');
    assert.deepEqual(errors,[]);
  } finally {
    await browser.close();
  }
});
