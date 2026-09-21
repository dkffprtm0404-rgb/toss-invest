// Real HTTP, validator, persistence, account engine and UI. Only Codex output is supplied by the Java fixture.
const test=require('node:test'),assert=require('node:assert/strict');
const {chromium}=require('playwright');
test('real six plus total, subset save, account execution and immutable history',async()=>{
 const base=process.env.SW_BASE_URL;assert.ok(base);
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try {
  const page=await browser.newPage();page.setDefaultTimeout(20000);const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/*',route=>{
   const url=new URL(route.request().url());
   if(!url.href.startsWith(base)||(url.pathname.startsWith('/api/')&&!/^\/api\/(strategies|strategy-assistant|composite\/runs|backtest\/runs)(\/|$)/.test(url.pathname)))
    return route.fulfill({contentType:route.request().resourceType()==='script'?'application/javascript':'application/json',body:route.request().resourceType()==='script'?'':'{}'});
   return route.continue();
  });
  await page.goto(base);const root=page.locator('#strategyWorkbench');await root.getByText('연결 준비 완료',{exact:false}).waitFor();
  await root.getByLabel('전략 원문',{exact:true}).fill(['20일 고가 돌파','52주 신고가','SMA 골든크로스 + 장기 필터','상대강도 상위주','ATR 변동성 돌파','50/200일 골든크로스'].join('\n\n'));
  await root.getByRole('button',{name:'Codex로 조건 해석',exact:true}).click();await root.getByRole('button',{name:/전체 토탈 · 6개/}).waitFor();
  assert.equal(await root.getByRole('button',{name:/^초안 [1-6] ·/}).count(),6);
  // The unresolved total must not block an already valid individual draft.
  await root.getByLabel('원문·조건·위험 관리 설정을 검토했으며 이 내용으로 저장합니다.',{exact:true}).check();
  await root.getByRole('button',{name:'새 전략으로 저장',exact:true}).click();await root.getByText('실행 대상: 전략 #',{exact:false}).waitFor();
  await root.getByLabel('조합 선택 · 20일 고가 돌파',{exact:true}).check();await root.getByLabel('조합 선택 · SMA 골든크로스 + 장기 필터',{exact:true}).check();
  await root.getByRole('button',{name:'선택한 전략 조합',exact:true}).click();
  await root.getByLabel('진입 결합 · entry-root',{exact:true}).selectOption('OR');
  await root.getByRole('button',{name:'공통 위험 후보 가져오기 · 20일 고가 돌파',exact:true}).click();
  await root.getByLabel('최대 보유 종목 수',{exact:true}).fill('1');await root.getByLabel('비중 조정 방식',{exact:true}).selectOption('ENTRY_ONLY');
  await root.getByLabel('공통 위험 관리 적용과 기준을 확인했습니다.',{exact:true}).check();await root.getByLabel('공통 청산 적용 범위를 확인했습니다.',{exact:true}).check();
  await root.getByRole('button',{name:'입력 조건 검증',exact:true}).click();
  await root.getByLabel('원문·조건·위험 관리 설정을 검토했으며 이 내용으로 저장합니다.',{exact:true}).check();
  const savedWait=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname==='/api/strategies');
  await root.getByRole('button',{name:'새 전략으로 저장',exact:true}).click();const savedResponse=await savedWait;assert.equal(savedResponse.status(),201);const saved=await savedResponse.json();
  assert.equal(saved.strategy.composition.entry.children[1].operator,'AND');assert.equal(saved.strategy.composition.sources.length,2);
  await root.getByLabel('종목 코드',{exact:true}).fill('005930');await root.getByLabel('시작일',{exact:true}).fill('2025-04-17');await root.getByLabel('종료일',{exact:true}).fill('2025-05-07');
  await root.getByLabel('초기자금 (원)',{exact:true}).fill('1000000');for(const label of ['수수료율 (%)','매도세율 (%)','슬리피지율 (%)'])await root.getByLabel(label,{exact:true}).fill('0');
  await root.getByLabel('체결 방식',{exact:true}).selectOption('NEXT_DAY_OPEN');assert.equal(await root.getByLabel('시장·거래일 자료 (JSON)',{exact:true}).isVisible(),false);
  const runWait=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/composite-backtests'));
  await root.getByRole('button',{name:'선택 버전 백테스트 실행',exact:true}).click();const response=await runWait;assert.equal(response.status(),200,await response.text());const run=await response.json();
  assert.equal(run.status,'COMPLETED');assert.ok(run.snapshot.result.account.trades.length>0);assert.ok(run.snapshot.result.evaluations.length>0);
  await root.getByText(`통합 전략 실행 #${run.id} · 완료`,{exact:true}).waitFor();
  const original=await (await page.request.get(`${base}/api/composite/runs/${run.id}`)).json();assert.deepEqual(original,run);
  await page.reload();await root.getByRole('button',{name:new RegExp(` · #${saved.id} · 최신 v1$`)}).click();await root.getByRole('button',{name:new RegExp(`^#${run.id} · v1`)}).click();
  await root.getByText(`통합 전략 실행 #${run.id} · 완료`,{exact:true}).waitFor();await root.getByText('조건별 판정 근거',{exact:true}).waitFor();
  await page.screenshot({path:'build/strategy-composition-server.png',fullPage:true});assert.deepEqual(errors,[]);
 } finally {await browser.close();}
});
