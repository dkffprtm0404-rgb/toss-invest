// Run with NODE_PATH pointing to the bundled runtime's node_modules (Playwright).
// HTTP is intercepted in-process; this test does not call Codex or any server.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const source = fs.readFileSync(path.resolve(__dirname, '../../src/main/resources/static/js/strategy-workbench.js'), 'utf8');
const styles = fs.readFileSync(path.resolve(__dirname, '../../src/main/resources/static/css/strategy-workbench.css'), 'utf8');
const strategy = { schemaVersion: 1, name: '<img src=x onerror=alert(1)>', originalPrompt: '거래량 전략', entry: { operator: null, conditions: [{ type: 'VOLUME', period: 20, multiplier: 1.5, comparison: 'GTE' }] }, exit: null,
  risk: { stopLoss: { rate: -0.05 }, takeProfit: null, timeExit: null, trailing: null } };
const saved = { id: 7, version: 1, createdAt: '2026-09-10T00:00:00Z', versionCreatedAt: '2026-09-10T00:00:00Z', strategy };

test('browser completes explicit save and run flow, locks edits, preserves missing fields and history snapshot', async () => {
  const browser = await chromium.launch({ headless: true, channel: process.env.SW_BROWSER_CHANNEL || 'msedge' });
  try {
    const page = await browser.newPage();
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    const requests = []; let hasSaved = false; let hasRun = false; let failDelete = true;
    const snapshot = { schemaVersion: 1, engineVersion: 'USER_STRATEGY_DAILY_V1', strategy: saved,
      execution: { strategy, symbol: '005930', startDate: '2025-01-01', endDate: '2025-12-31', executionMode: 'NEXT_DAY_OPEN' },
      data: { source: 'LOCAL_CANDLE_CACHE', interval: '1d', timezone: 'Asia/Seoul', sha256: 'test-hash', candles: [] },
      costs: { model: 'NOT_MODELED', capitalModel: 'NOT_MODELED' }, error: null,
      result: { status: 'NO_DATA', actualStartDate: null, actualEndDate: null, candleCount: 0, warmupBars: 0, requiredWarmupBars: 20, metrics: { closedTrades: 0, winRate: 0, averageTradeReturnRate: 0, sumTradeReturnRate: 0, tradeReturnMaxDrawdown: 0, tradeSharpeRatio: 0, averageHoldingBars: 0 }, trades: [], assumptions: ['KST_DAILY_TIMESTAMPS_ASSUMED_OPEN_09_00_CLOSE_15_30'] } };
    const run = { id: 12, createdAt: '2026-09-10T00:00:00Z', status: 'NO_DATA', snapshot };
    await page.route('**/*', async route => {
      const req = route.request(); const url = new URL(req.url());
      if (url.pathname === '/') return route.fulfill({ contentType: 'text/html', body: '<html lang="ko"><div id="strategyWorkbench"></div></html>' });
      const body = req.postData() ? JSON.parse(req.postData()) : null;
      requests.push({ path: url.pathname, method: req.method(), body, headers: req.headers() });
      if (req.method() === 'DELETE') {
        if (failDelete) return route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ message: '삭제 실패 테스트' }) });
        if (url.pathname === '/api/backtest/runs/12') hasRun = false;
        else if (url.pathname === '/api/strategies/7') { hasSaved = false; hasRun = false; }
        else throw new Error('Unexpected deletion: ' + url.pathname);
        return route.fulfill({ status: 204 });
      }
      let data;
      if (url.pathname.endsWith('/status')) data = { ready: true, message: 'ChatGPT 준비 완료', model: 'test' };
      else if (url.pathname.endsWith('/interpret')) {
        await new Promise(resolve => setTimeout(resolve, 150));
        data = { strategy: structuredClone(strategy), ready: true, issues: [], questions: [], unsupported: [] };
      } else if (url.pathname.endsWith('/validate')) data = { strategy: body, ready: true, issues: [], questions: [], unsupported: [] };
      else if (url.pathname === '/api/strategies' && req.method() === 'POST') { hasSaved = true; saved.strategy = body; data = saved; }
      else if (url.pathname === '/api/strategies') data = hasSaved ? [saved] : [];
      else if (url.pathname.endsWith('/versions')) data = [saved];
      else if (url.pathname.endsWith('/backtests') && req.method() === 'POST') { hasRun = true; data = run; }
      else if (url.pathname.endsWith('/backtests')) data = hasRun ? [{ id: 12, version: 1, status: 'NO_DATA', createdAt: run.createdAt }] : [];
      else if (url.pathname === '/api/backtest/runs/12') data = run;
      else data = saved;
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) });
    });
    await page.goto('http://workbench.test/'); await page.addStyleTag({ content: '* { box-sizing: border-box; }' + styles }); await page.addScriptTag({ content: source });
    await page.getByText('저장된 전략이 없습니다.', { exact: false }).waitFor();
    await page.getByLabel('전략 원문', { exact: true }).fill('거래량 전략');
    await page.getByRole('button', { name: 'Codex로 조건 해석', exact: true }).click();
    assert.equal(await page.getByLabel('전략 원문', { exact: true }).isDisabled(), true);
    await page.getByLabel('전략 이름', { exact: true }).waitFor();
    assert.equal(await page.locator('img').count(), 0, 'untrusted model name must remain text');
    const saveButton = page.getByRole('button', { name: '새 전략으로 저장', exact: true });
    assert.equal(await saveButton.isDisabled(), true);
    assert.equal(await page.getByLabel('손절 수익률 (%) · 음수, 예: -5', { exact: true }).inputValue(), '-5');
    await page.getByLabel('손절 수익률 (%) · 음수, 예: -5', { exact: true }).fill('-0.29');
    await page.getByRole('button', { name: '입력 조건 검증', exact: true }).click();
    const confirm = page.getByLabel('원문·조건·위험 관리 설정을 검토했으며 이 내용으로 저장합니다.', { exact: true });
    await confirm.check();
    assert.equal(await saveButton.isEnabled(), true);
    await page.getByLabel('전략 이름', { exact: true }).fill('거래량 전략 수정');
    assert.equal(await confirm.isChecked(), false); assert.equal(await saveButton.isDisabled(), true);
    await page.getByRole('button', { name: '입력 조건 검증', exact: true }).click(); await confirm.check(); await saveButton.click();
    await page.getByText('실행 대상: 전략 #7', { exact: false }).waitFor();
    const creation = requests.find(r => r.path === '/api/strategies' && r.method === 'POST');
    assert.equal(creation.body.risk.stopLoss.rate, -0.0029);
    assert.equal(creation.body.risk.takeProfit, null);
    assert.equal(creation.body.entry.operator, null);
    await page.getByLabel('종목 코드', { exact: true }).fill('005930');
    await page.getByLabel('시작일', { exact: true }).fill('2025-01-01');
    await page.getByLabel('종료일', { exact: true }).fill('2025-12-31');
    await page.getByLabel('체결 방식', { exact: true }).selectOption('NEXT_DAY_OPEN');
    await page.getByRole('button', { name: '선택 버전 백테스트 실행', exact: true }).click();
    await page.getByRole('heading', { name: '실행 #12 · 데이터 없음', exact: true }).waitFor();
    const execution = requests.find(r => r.path === '/api/strategies/7/backtests' && r.method === 'POST');
    assert.deepEqual(execution.body, { version: 1, symbol: '005930', startDate: '2025-01-01', endDate: '2025-12-31', executionMode: 'NEXT_DAY_OPEN' });
    await page.getByRole('button', { name: '#12 · v1 · 데이터 없음', exact: false }).click();
    await page.getByText('당시 저장된 전략·데이터·결과 스냅샷을 표시합니다.', { exact: true }).waitFor();
    assert.equal(requests.filter(r => r.path.endsWith('/backtests') && r.method === 'POST').length, 1, 'history reads cannot rerun backtests');
    assert.equal(requests.some(r => r.path === '/api/backtest/runs/12' && r.method === 'GET'), true);
    for (const req of requests.filter(r => r.method === 'POST')) assert.equal(req.headers['x-strategy-local'], '1');
    await page.setViewportSize({ width: 390, height: 844 });
    const width = await page.locator('#strategyWorkbench').evaluate(el => ({ content: el.scrollWidth, box: el.clientWidth }));
    assert.ok(width.content <= width.box, `workbench must wrap long assumption codes on mobile: ${JSON.stringify(width)}`);
    assert.equal(await page.getByText('일봉의 시가 시각은 한국 시간 09:00, 종가 시각은 15:30으로 가정합니다.', { exact: true }).count(), 1);
    const deleteRun = page.getByRole('button', { name: '실행 #12 삭제', exact: true });
    page.once('dialog', dialog => dialog.dismiss());
    await deleteRun.click({ timeout: 3000 });
    assert.equal(requests.filter(r => r.method === 'DELETE').length, 0);
    page.once('dialog', dialog => dialog.accept());
    await deleteRun.click();
    await page.getByText('삭제 실패 테스트', { exact: true }).waitFor();
    assert.equal(await page.getByRole('heading', { name: '실행 #12 · 데이터 없음', exact: true }).count(), 1);
    failDelete = false;
    page.once('dialog', dialog => dialog.accept());
    await deleteRun.click();
    await page.getByText('실행 이력이 없습니다.', { exact: true }).waitFor();
    assert.equal(await page.getByRole('heading', { name: '실행 #12 · 데이터 없음', exact: true }).count(), 0);
    const deleteStrategy = page.getByRole('button', { name: '전략 #7 삭제', exact: true });
    page.once('dialog', dialog => dialog.dismiss());
    await deleteStrategy.click();
    assert.equal(requests.filter(r => r.method === 'DELETE' && r.path === '/api/strategies/7').length, 0);
    page.once('dialog', async dialog => {
      assert.match(dialog.message(), /모든 버전/);
      assert.match(dialog.message(), /실행 이력/);
      await dialog.accept();
    });
    await deleteStrategy.click();
    await page.getByText('저장된 전략이 없습니다.', { exact: false }).waitFor();
    assert.equal(await page.getByRole('button', { name: '선택 버전 백테스트 실행', exact: true }).isDisabled(), true);
    assert.equal(await page.getByText('선택한 전략 없음', { exact: true }).count(), 1);
    assert.deepEqual(errors, []);
  } finally { await browser.close(); }
});

test('questions and unsupported rules need reinterpretation; failures clear confirmation and unlock retry', async () => {
  const browser = await chromium.launch({ headless: true, channel: process.env.SW_BROWSER_CHANNEL || 'msedge' });
  try {
    const page = await browser.newPage();
    let interpretations = 0; let failValidation = false;
    await page.route('**/*', async route => {
      const req = route.request(); const url = new URL(req.url());
      if (url.pathname === '/') return route.fulfill({ contentType: 'text/html', body: '<div id="strategyWorkbench"></div>' });
      let data; let status = 200;
      if (url.pathname.endsWith('/status')) data = { ready: true };
      else if (url.pathname === '/api/strategies') data = [];
      else if (url.pathname.endsWith('/interpret')) {
        interpretations++;
        const draft = structuredClone(strategy);
        if (interpretations === 1) draft.entry.conditions[0].period = null;
        data = { strategy: draft, ready: interpretations > 1, issues: [], questions: interpretations === 1 ? ['기간은 몇 봉인가요?'] : [], unsupported: interpretations === 1 ? ['점수 필터'] : [] };
      } else if (url.pathname.endsWith('/validate')) {
        status = failValidation ? 503 : 200;
        data = failValidation ? { code: 'TEST_UNAVAILABLE', message: '잠시 후 재시도하세요.' } : { ready: true, issues: [] };
      }
      await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
    });
    await page.goto('http://workbench.test/'); await page.addScriptTag({ content: source });
    await page.getByText('저장된 전략이 없습니다.', { exact: false }).waitFor();
    await page.getByLabel('전략 원문', { exact: true }).fill('조건과 점수 필터');
    await page.getByRole('button', { name: 'Codex로 조건 해석', exact: true }).click();
    await page.getByText('보완 질문: 기간은 몇 봉인가요?', { exact: true }).waitFor();
    assert.equal(await page.getByLabel('평균 거래량 기간 (봉)', { exact: true }).inputValue(), '');
    await page.getByLabel('평균 거래량 기간 (봉)', { exact: true }).fill('20');
    await page.getByRole('button', { name: '입력 조건 검증', exact: true }).click();
    const confirm = page.getByLabel('원문·조건·위험 관리 설정을 검토했으며 이 내용으로 저장합니다.', { exact: true });
    assert.equal(await confirm.isDisabled(), true);
    await page.getByLabel('보완 입력 · 질문의 답이나 미지원 조건 수정', { exact: true }).fill('20봉이며 점수 필터는 제거');
    await page.getByRole('button', { name: 'Codex로 조건 해석', exact: true }).click();
    await confirm.check();
    failValidation = true;
    await page.getByRole('button', { name: '입력 조건 검증', exact: true }).click();
    await page.getByText('TEST_UNAVAILABLE · 잠시 후 재시도하세요.', { exact: true }).waitFor();
    assert.equal(await confirm.isChecked(), false);
    assert.equal(await page.getByRole('button', { name: '새 전략으로 저장', exact: true }).isDisabled(), true);
    assert.equal(await page.getByLabel('전략 이름', { exact: true }).isEnabled(), true);
    failValidation = false;
    await page.getByRole('button', { name: '입력 조건 검증', exact: true }).click(); await confirm.check();
    assert.equal(await page.getByRole('button', { name: '새 전략으로 저장', exact: true }).isEnabled(), true);
  } finally { await browser.close(); }
});
