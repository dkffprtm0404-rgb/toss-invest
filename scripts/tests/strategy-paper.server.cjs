const test=require('node:test'),assert=require('node:assert/strict');
const {chromium}=require('playwright');
test('real HTTP saved strategy to paper account workflow',async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try {
  const page=await browser.newPage({viewport:{width:1300,height:980}});page.setDefaultTimeout(15000);const errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  const base=process.env.SW_BASE_URL,id=process.env.SP_STRATEGY_ID;
  await page.route('**/*',route=>{
   const url=new URL(route.request().url());
   if(url.origin!==base)return route.fulfill({contentType:'application/javascript',body:''});
   if(url.pathname.startsWith('/api/')&&!url.pathname.startsWith('/api/strategy-paper/')&&!url.pathname.startsWith('/api/strategies')&&!url.pathname.endsWith('/status'))
    return route.fulfill({contentType:'application/json',body:'[]'});
   return route.continue();
  });
  await page.goto(base);
  await page.getByRole('button',{name:new RegExp(`일봉 손절 · #${id} · 최신 v1`)}).click();
  await page.getByRole('button',{name:'이 버전으로 모의매매',exact:true}).click();
  const panel=page.locator('#strategyPaper');
  await panel.getByLabel('종목 코드',{exact:true}).fill('005930');
  await panel.getByLabel('체결 방식',{exact:true}).selectOption('NEXT_DAY_OPEN');
  for(const [label,value] of [['초기자금 (원)','1000000'],['매수 비중 (%)','50'],['수수료율 (%)','0.015'],['매도세율 (%)','0'],['슬리피지율 (%)','0']])await panel.getByLabel(label,{exact:true}).fill(value);
  await panel.getByRole('button',{name:'모의매매 시작',exact:true}).click();
  await panel.getByRole('heading',{name:'모의매매 #1',exact:true}).waitFor();
  await panel.getByRole('button',{name:'일봉 갱신 및 처리',exact:true}).click();
  await panel.getByRole('status').filter({hasText:/처리 완료|확정 일봉 대기/}).waitFor();
  await panel.getByRole('button',{name:'거래 내역 조회',exact:true}).click();
  assert.equal(await panel.locator('.sp-detail').getByText('1,000,000',{exact:true}).count(),2);
  page.on('dialog',dialog=>dialog.accept());
  await panel.getByRole('button',{name:'신규 매수 중지',exact:true}).click();
  await panel.locator('.sp-status').getByText('종료',{exact:true}).waitFor();
  await panel.screenshot({path:'build/strategy-paper-desktop.png'});
  await page.reload();await page.getByRole('tab',{name:'계좌·모의매매',exact:true}).click();
  await panel.getByRole('button',{name:/#1 · 일봉 손절 v1 · 005930 · 종료/}).click();
  await panel.locator('.sp-status').getByText('종료',{exact:true}).waitFor();
  await page.setViewportSize({width:390,height:844});
  await panel.screenshot({path:'build/strategy-paper-mobile.png'});
  const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>window.innerWidth+1);assert.equal(overflow,false);
  assert.deepEqual(errors,[]);
 }finally{await browser.close();}
});
