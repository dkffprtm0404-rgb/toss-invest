const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '../../src/main/resources/static');

function briefing(overrides = {}) {
  return {
    date: '2026-11-04', status: 'READY', cutoffAt: '2026-11-04T08:30:00+09:00',
    collectedAt: '2026-11-04T08:30:03+09:00', generatedAt: '2026-11-04T08:30:10+09:00', model: 'fixture',
    items: [{ title: '국내 지수 확인', summary: '전일 코스피는 1% 하락했습니다.', sourceIds: ['KOSPI', 'news-1'] }],
    sources: [
      { id: 'KOSPI', kind: 'QUOTE', label: '코스피', title: '코스피 종가', url: 'https://quotes.test/kospi', observedAt: '2026-11-03T15:30:00+09:00', value: 2500, changeRate: -0.01, unit: 'pt' },
      { id: 'news-1', kind: 'NEWS', label: '경제 뉴스', title: '밤사이 경제 소식', url: 'https://news.test/article', observedAt: '2026-11-04T07:50:00+09:00' }
    ], warnings: [], error: null, ...overrides
  };
}

async function dashboard(initial, run, clockTime) {
  const browser = await chromium.launch({ headless: true, channel: 'msedge' });
  const state = { latest: initial, reads: [], posts: [], errors: [], respond: route => route.fulfill({ json: briefing({ date: '2026-11-05' }) }) };
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 1000 } });
    page.setDefaultTimeout(5000);
    if (clockTime) await page.clock.install({ time: new Date(clockTime) });
    else await page.clock.setFixedTime(new Date('2026-11-04T23:35:00Z'));
    page.on('pageerror', error => state.errors.push(error.message));
    await page.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url());
      if (url.hostname !== 'briefing.test') return route.fulfill({ body: '' });
      if (url.pathname === '/api/market-briefings/latest') {
        state.reads.push(request.method());
        if (typeof state.latest === 'function') return state.latest(route);
        return state.latest === null ? route.fulfill({ status: 404, json: { message: '저장된 브리핑 없음' } })
          : route.fulfill({ json: state.latest });
      }
      if (url.pathname === '/api/market-briefings/today') {
        state.posts.push({ method: request.method(), headers: request.headers() });
        return state.respond(route);
      }
      if (url.pathname === '/' || url.pathname.startsWith('/css/') || ['/js/dashboard.js', '/js/market-briefing.js'].includes(url.pathname)) {
        const file = path.join(root, url.pathname === '/' ? 'index.html' : url.pathname);
        return route.fulfill({ body: fs.existsSync(file) ? fs.readFileSync(file) : '',
          contentType: url.pathname === '/' ? 'text/html' : url.pathname.endsWith('.css') ? 'text/css' : 'application/javascript' });
      }
      if (url.pathname.endsWith('.js')) return route.fulfill({ body: '', contentType: 'application/javascript' });
      if (url.pathname === '/api/accounts') return route.fulfill({ json: { result: [] } });
      if (url.pathname === '/api/health/exchange-rate') return route.fulfill({ json: { result: { rate: 1300, validUntil: '2026-11-05T00:00:00Z' } } });
      if (url.pathname === '/api/paper/summary') return route.fulfill({ json: { totalTrades: 0, openPositions: [], closedPositions: [] } });
      if (url.pathname === '/api/scalping/stats') return route.fulfill({ json: { totalTrades: 0, winRate: 0, cumReturn: 0 } });
      return route.fulfill({ json: [] });
    });
    await page.goto('http://briefing.test/');
    const panel = page.getByRole('region', { name: '장전 AI 시장 요약', exact: true });
    await panel.getByRole('heading', { name: '장전 AI 시장 요약', exact: true }).waitFor();
    await run(page, panel, state);
    assert.deepEqual(state.errors, []);
  } finally { await browser.close(); }
}

test('saved dates, quotes and cited observations remain visible across tabs and at 375px', async () => {
  const snapshot = briefing();
  snapshot.sources[0].observedAt = '2026-11-03T06:30:00Z';
  snapshot.items[0].title = '<img src=x onerror=alert(1)> 시장 요약';
  snapshot.sources.push({ id: 'unsafe', kind: 'NEWS', label: '확인할 출처', title: '<script>bad()</script>', url: 'javascript:alert(1)', observedAt: '2026-11-04T07:00:00+09:00' });
  snapshot.sources.push({ id: 'credentials', kind: 'NEWS', label: '보호된 링크', url: 'https://user:pass@news.test/article', observedAt: null });
  snapshot.sources.push({ id: 'missing-value', kind: 'QUOTE', label: '코스닥', value: null, changeRate: null, observedAt: null });
  snapshot.items[0].sourceIds.push('unsafe', 'credentials', 'unknown');
  await dashboard(snapshot, async (page, panel, state) => {
    await panel.getByText('전일 코스피는 1% 하락했습니다.', { exact: true }).waitFor();
    assert.match(await panel.innerText(), /매 거래일.*08:30.*한국시간/);
    assert.match(await panel.locator('.mb-date').innerText(), /2026-11-04.*오늘 자료 아님/);
    assert.match(await panel.locator('.mb-quotes').innerText(), /2,500[\s\S]*-1\.00%/);
    assert.match(await panel.locator('.mb-items').innerText(), /2026-11-03 15:30[\s\S]*2026-11-04 07:50/);
    assert.match(await panel.locator('.mb-items').innerText(), /출처 ID 확인 필요: unknown/);
    assert.match(await panel.locator('.mb-quotes').innerText(), /코스닥[\s\S]*자료 없음[\s\S]*시각 확인 불가/);
    assert.equal(await panel.locator('img, script').count(), 0);
    assert.equal(await panel.getByRole('heading', { name: '<img src=x onerror=alert(1)> 시장 요약', exact: true }).count(), 1);
    const links = await panel.locator('a').evaluateAll(nodes => nodes.map(a => ({ href: a.href, rel: a.rel, target: a.target })));
    assert.ok(links.length >= 2);
    assert.ok(links.every(link => link.href.startsWith('https://') && link.rel.includes('noopener') && link.rel.includes('noreferrer') && link.target === '_blank'));
    assert.equal(await panel.evaluate(el => !!(el.compareDocumentPosition(document.querySelector('.dashboard-tabs')) & Node.DOCUMENT_POSITION_FOLLOWING)), true);
    await page.getByRole('tab', { name: '계좌·모의매매', exact: true }).click();
    assert.equal(await panel.isVisible(), true);
    await page.getByRole('tab', { name: '투자 전략', exact: true }).click();
    await page.setViewportSize({ width: 375, height: 844 });
    assert.equal(await panel.evaluate(el => el.scrollWidth <= el.clientWidth + 1), true);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), true);
    assert.deepEqual(state.posts, []);
    await panel.screenshot({ path: 'build/market-briefing-mobile.png' });
  });
});

test('no saved briefing and a failed generation can recover through an explicit retry', async () => {
  await dashboard(null, async (page, panel, state) => {
    await panel.getByText('저장된 브리핑이 없습니다.', { exact: true }).waitFor();
    assert.equal(await panel.locator('.mb-quotes, table').count(), 0);
    state.respond = route => route.fulfill({ json: briefing({ date: '2026-11-05', status: 'FAILED', items: [], sources: [], error: '요약 연결 실패', warnings: ['자료 수집을 확인해 주세요.'] }) });
    await panel.getByRole('button', { name: '오늘 브리핑 생성', exact: true }).click();
    await panel.getByText('요약 연결 실패', { exact: true }).waitFor();
    assert.match(await panel.innerText(), /생성 실패[\s\S]*자료 수집을 확인/);
    assert.equal(await panel.locator('.mb-quotes, table').count(), 0);
    state.respond = route => route.fulfill({ json: briefing({ date: '2026-11-05' }) });
    await panel.getByRole('button', { name: '오늘 브리핑 다시 시도', exact: true }).click();
    await panel.getByText('전일 코스피는 1% 하락했습니다.', { exact: true }).waitFor();
    assert.match(await panel.locator('.mb-date').innerText(), /2026-11-05/);
    assert.doesNotMatch(await panel.innerText(), /요약 연결 실패/);
    assert.equal(state.posts.length, 2);
    assert.ok(state.posts.every(request => request.method === 'POST' && request.headers['x-strategy-local'] === '1'));
  });
});

test('generation cannot double-submit and does not block other dashboard controls', async () => {
  await dashboard(briefing(), async (page, panel, state) => {
    await panel.getByText('전일 코스피는 1% 하락했습니다.', { exact: true }).waitFor();
    let release;
    const pending = new Promise(resolve => { release = resolve; });
    state.respond = async route => { await pending; return route.fulfill({ json: briefing({ date: '2026-11-05' }) }); };
    try {
      await panel.getByRole('button', { name: '오늘 브리핑 생성', exact: true }).evaluate(button => { button.click(); button.click(); });
      assert.equal(await panel.getByRole('button', { name: '생성 중…', exact: true }).isDisabled(), true);
      assert.equal(await panel.getByRole('button', { name: '저장본 새로고침', exact: true }).isDisabled(), true);
      assert.equal(await page.getByRole('tab', { name: '투자 전략', exact: true }).isEnabled(), true);
    } finally { release(); }
    await panel.getByRole('button', { name: '오늘 브리핑 생성', exact: true }).waitFor();
    assert.equal(state.posts.length, 1);
  });
});

test('HTTP calendar and server errors keep the saved snapshot and allow retry', async () => {
  await dashboard(briefing(), async (page, panel, state) => {
    await panel.getByText('전일 코스피는 1% 하락했습니다.', { exact: true }).waitFor();
    for (const status of [409, 503]) {
      state.respond = route => status === 409 ? route.fulfill({ status, json: { message: '08:30 이후 다시 시도해 주세요.' } })
        : route.fulfill({ status, contentType: 'text/html', body: '<h1>unavailable</h1>' });
      await panel.getByRole('button', { name: /오늘 브리핑 (생성|다시 시도)/ }).click();
      await panel.getByRole('status').filter({ hasText: String(status) }).waitFor();
      assert.equal(await panel.getByText('전일 코스피는 1% 하락했습니다.', { exact: true }).isVisible(), true);
      assert.equal(await panel.getByRole('button', { name: /오늘 브리핑 (생성|다시 시도)/ }).isEnabled(), true);
    }
    assert.equal(state.posts.length, 2);
  });
});

test('initial latest failure ends the loading state and recovers by reading the saved snapshot', async () => {
  await dashboard(route => route.fulfill({ status: 503, json: { message: '저장본 조회 연결 실패' } }), async (page, panel, state) => {
    await panel.getByRole('status').filter({ hasText: '503' }).waitFor();
    assert.equal(await panel.locator('.mb-date').innerText(), '저장본 확인 실패');
    assert.equal(await panel.locator('.mb-badge').innerText(), '조회 실패');
    assert.equal(await panel.getByRole('button', { name: '저장본 새로고침', exact: true }).isEnabled(), true);
    state.latest = briefing();
    await panel.getByRole('button', { name: '저장본 새로고침', exact: true }).click();
    await panel.getByText('전일 코스피는 1% 하락했습니다.', { exact: true }).waitFor();
    assert.doesNotMatch(await panel.getByRole('status').innerText(), /503/);
    assert.deepEqual(state.posts, []);
  });
});

test('partial, missing-data and closed-market states do not invent a quote table', async () => {
  await dashboard(briefing({ status: 'PARTIAL', warnings: ['코스닥 자료 수집 실패'] }), async (page, panel, state) => {
    await panel.getByText('코스닥 자료 수집 실패', { exact: true }).waitFor();
    assert.match(await panel.innerText(), /일부 자료 누락/);
    state.latest = briefing({ status: 'PARTIAL', sources: [briefing().sources[1]],
      items: [{ title: '경제 뉴스', summary: '뉴스 자료만 확인할 수 있습니다.', sourceIds: ['news-1'] }], warnings: ['시장 지표를 수집하지 못했습니다.'] });
    await panel.getByRole('button', { name: '저장본 새로고침', exact: true }).click();
    await panel.getByText('뉴스 자료만 확인할 수 있습니다.', { exact: true }).waitFor();
    assert.equal(await panel.locator('.mb-quotes, table').count(), 0);
    state.latest = briefing({ date: '2026-11-05', status: 'NO_DATA', items: [], sources: [], warnings: ['공개 시세와 뉴스 자료가 부족합니다.'] });
    await panel.getByRole('button', { name: '저장본 새로고침', exact: true }).click();
    await panel.getByText('공개 시세와 뉴스 자료가 부족합니다.', { exact: true }).waitFor();
    assert.equal(await panel.locator('.mb-quotes, table').count(), 0);
    state.latest = briefing({ date: '2026-11-05', status: 'CLOSED', items: [{ title: '노출하지 않을 요약', summary: '휴장일 잔여 데이터', sourceIds: ['KOSPI'] }], warnings: [] });
    await panel.getByRole('button', { name: '저장본 새로고침', exact: true }).click();
    await panel.getByText('휴장일에는 브리핑을 생성하지 않습니다.', { exact: true }).waitFor();
    assert.equal(await panel.locator('.mb-quotes, .mb-items, table').count(), 0);
    assert.doesNotMatch(await panel.innerText(), /노출하지 않을 요약|휴장일 잔여 데이터/);
  });
});

test('daily refresh stops labelling yesterday as today after KST midnight', async () => {
  await dashboard(briefing({ date: '2026-11-05' }), async (page, panel, state) => {
    await panel.getByText('전일 코스피는 1% 하락했습니다.', { exact: true }).waitFor();
    assert.equal(await panel.locator('.mb-date').innerText(), '2026-11-05 · 오늘');

    await page.clock.fastForward(120000);

    await panel.getByText('2026-11-05 저장 브리핑 · 오늘 자료 아님', { exact: true }).waitFor();
    assert.ok(state.reads.length >= 2);
    assert.ok(state.reads.every(method => method === 'GET'));
    assert.deepEqual(state.posts, []);
  }, '2026-11-05T14:59:00Z');
});

test('daily refresh reads the 08:30 snapshot and returns from hidden pages without overlapping reads', async () => {
  await dashboard(briefing(), async (page, panel, state) => {
    await panel.getByText('전일 코스피는 1% 하락했습니다.', { exact: true }).waitFor();
    const updated = summary => briefing({ date: '2026-11-05', generatedAt: '2026-11-05T08:30:05+09:00',
      items: [{ title: '새 장전 요약', summary, sourceIds: ['news-1'] }] });
    state.latest = updated('08:30 이후 저장된 새 요약');

    await page.clock.fastForward(60000);

    await panel.getByText('08:30 이후 저장된 새 요약', { exact: true }).waitFor();
    assert.equal(await panel.locator('.mb-date').innerText(), '2026-11-05 · 오늘');
    await page.evaluate(() => Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' }));
    const hiddenReads = state.reads.length;
    state.latest = updated('화면 복귀 후 저장본');
    await page.clock.fastForward(180000);
    assert.equal(state.reads.length, hiddenReads);

    await page.evaluate(() => {
      Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
      window.dispatchEvent(new Event('focus'));
    });
    await panel.getByText('화면 복귀 후 저장본', { exact: true }).waitFor();

    let release;
    const pending = new Promise(resolve => { release = resolve; });
    let markReadStarted;
    const readStarted = new Promise(resolve => { markReadStarted = resolve; });
    state.latest = async route => { markReadStarted(); await pending; return route.fulfill({ json: updated('지연 조회 완료') }); };
    try {
      await page.evaluate(() => window.dispatchEvent(new Event('focus')));
      await readStarted;
      const pendingReads = state.reads.length;
      await page.clock.fastForward(120000);
      await page.evaluate(() => window.dispatchEvent(new Event('focus')));
      assert.equal(state.reads.length, pendingReads);
    } finally { release(); }
    await panel.getByText('지연 조회 완료', { exact: true }).waitFor();
    assert.ok(state.reads.every(method => method === 'GET'));
    assert.deepEqual(state.posts, []);
  }, '2026-11-04T23:29:00Z');
});

test('today briefing distinguishes its date from older market and article reference dates', async () => {
  await dashboard(briefing({date:'2026-11-05',status:'PARTIAL',warnings:['코스피 시세 기준일 2026-11-03 · 직전 거래일 2026-11-04 자료가 없습니다.']}), async (page,panel) => {
    await panel.getByText('전일 코스피는 1% 하락했습니다.',{exact:true}).waitFor();
    assert.equal(await panel.locator('.mb-date').innerText(),'2026-11-05 · 오늘');
    assert.match(await panel.locator('.mb-quotes').innerText(),/시세 기준 2026-11-03 15:30/);
    assert.match(await panel.locator('.mb-items').innerText(),/기사 작성 2026-11-04 07:50/);
    assert.match(await panel.innerText(),/브리핑 날짜와 시세·기사 기준일은 다를 수 있습니다/);
    assert.match(await panel.locator('.mb-warnings').innerText(),/2026-11-03[\s\S]*2026-11-04/);
  });
});