const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require('playwright');
const root=path.resolve(__dirname,'../../src/main/resources/static');

test('unsupported paper strategies show their warning in the visible account tab',async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try {
  const page=await browser.newPage();page.setDefaultTimeout(5000);const errors=[],posts=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/*',async route=>{
   const req=route.request(),url=new URL(req.url());
   if(req.method()==='POST')posts.push(url.pathname);
   if(url.hostname!=='paper.test')return route.fulfill({body:''});
   if(url.pathname==='/'||url.pathname.startsWith('/css/')||['/js/dashboard.js','/js/strategy-paper.js'].includes(url.pathname))
    return route.fulfill({body:fs.readFileSync(path.join(root,url.pathname==='/'?'index.html':url.pathname)),contentType:url.pathname==='/'?'text/html':url.pathname.endsWith('.css')?'text/css':'application/javascript'});
   if(url.pathname.endsWith('.js'))return route.fulfill({body:'',contentType:'application/javascript'});
   return route.fulfill({json:[]});
  });
  await page.goto('http://paper.test/');
  for(const strategy of [{schemaVersion:3,portfolio:{}},{schemaVersion:4,composition:{}}]){
   await page.getByRole('tab',{name:'투자 전략',exact:true}).click();
   await page.evaluate(strategy=>window.StrategyPaper.select({id:4,version:1,strategy}),strategy);
   await page.locator('#strategyPaper').getByRole('status').filter({hasText:'단일 종목 전략(형식 1·2)만 지원'}).waitFor();
   assert.equal(await page.getByRole('tab',{name:'계좌·모의매매',exact:true}).getAttribute('aria-selected'),'true');
  }
  assert.deepEqual(posts,[]);assert.deepEqual(errors,[]);
 }finally{await browser.close();}
});
test('saved version, explicit inputs, retry identity, safe text and graceful stop',async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try {
  const page=await browser.newPage();page.setDefaultTimeout(5000);const errors=[],posts=[];let run=null;
  page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/*',async route=>{
   const req=route.request(),url=new URL(req.url());
   if(url.pathname==='/')return route.fulfill({contentType:'text/html',body:'<!doctype html><html lang="ko"><main><button id="accountTab">계좌</button><section id="strategyPaper"></section></main></html>'});
   let data=[];
   if(req.method()==='POST'&&url.pathname==='/api/strategy-paper/runs'){
    const body=JSON.parse(req.postData());posts.push(body);
    if(posts.length===1)return route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({message:'연결 실패. 다시 시도해 주세요.'})});
    run={id:7,status:'RUNNING',market:'KOSPI',definition:body,strategy:{id:3,version:2,strategy:{name:'<img src=x onerror=alert(1)>',entry:{conditions:[]},risk:{stopLoss:{rate:-.05}}}},state:{status:'RUNNING',firstTradingDate:'2026-09-29',dataStatus:'WAITING_DATA',cash:1000,equity:1000,realizedPnl:0,unrealizedPnl:0,totalCosts:0,returnRate:0,maxDrawdown:0,trades:[],logs:[],curve:[]}};data=run;
   }else if(url.pathname.endsWith('/stop')){run.status='STOPPED';run.state.status='STOPPED';data=run;}
   else if(url.pathname==='/api/strategy-paper/runs/7')data=run;
   else if(url.pathname==='/api/strategy-paper/runs')data=run?[run]:[];
   return route.fulfill({contentType:'application/json',body:JSON.stringify(data)});
  });
  await page.goto('http://paper.test/');
  await page.addScriptTag({content:fs.readFileSync(path.join(root,'js/strategy-paper.js'),'utf8')});
  await page.evaluate(()=>window.StrategyPaper.select({id:3,version:2,strategy:{schemaVersion:1,name:'손절 전략'}}));
  await page.getByLabel('종목 코드', {exact:true}).fill('005930');
  await page.getByLabel('체결 방식',{exact:true}).selectOption('NEXT_DAY_OPEN');
  for(const [label,value] of [['초기자금 (원)','1000'],['매수 비중 (%)','100'],['수수료율 (%)','0'],['매도세율 (%)','0'],['슬리피지율 (%)','0']]) await page.getByLabel(label,{exact:true}).fill(value);
  await page.getByRole('button',{name:'모의매매 시작',exact:true}).click();
  assert.deepEqual(errors,[],`시작 버튼 오류: ${errors.join('; ')}`);
  await page.getByRole('status').filter({hasText:'연결 실패'}).waitFor();
  await page.getByRole('button',{name:'모의매매 시작',exact:true}).click();await page.getByRole('heading',{name:'모의매매 #7',exact:true}).waitFor();
  assert.equal(posts.length,2);assert.equal(posts[0].requestId,posts[1].requestId);assert.equal(posts[1].version,2);assert.equal(posts[1].allocationRate,1);
  assert.equal(await page.locator('#strategyPaper img').count(),0);
  page.on('dialog',dialog=>dialog.accept());await page.getByRole('button',{name:'신규 매수 중지',exact:true}).click();
  await page.locator('.sp-detail').getByText('종료',{exact:true}).waitFor();
  await page.evaluate(()=>window.StrategyPaper.select({id:4,version:1,strategy:{schemaVersion:3,name:'포트폴리오',portfolio:{}}}));
  await page.getByRole('status').filter({hasText:'단일 종목'}).waitFor();assert.deepEqual(errors,[]);
 }finally{await browser.close();}
});

test('refresh shows actionable provider failure and clears it after successful recovery',async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try {
  const page=await browser.newPage();page.setDefaultTimeout(5000);let refreshes=0;
  const run={id:8,status:'RUNNING',market:'KOSPI',definition:{symbol:'005930',initialCapital:1000,allocationRate:1,commissionRate:0,taxRate:0,slippageRate:0,executionMode:'NEXT_DAY_OPEN'},strategy:{id:3,version:2,strategy:{name:'수신 검증'}},state:{status:'RUNNING',firstTradingDate:'2026-09-30',dataStatus:'WAITING_DATA',cash:1000,equity:1000,logs:[],trades:[],curve:[]}};
  await page.route('**/*',async route=>{
   const url=new URL(route.request().url());
   if(url.pathname==='/')return route.fulfill({contentType:'text/html',body:'<!doctype html><html lang="ko"><section id="strategyPaper" aria-label="저장 전략 모의매매"></section></html>'});
   if(url.pathname.endsWith('/refresh')) {
    refreshes++;
    run.state.dataStatus=refreshes===1?'MARKET_REQUEST_REJECTED':'WAITING_DATA';
    run.state.logs.push({code:run.state.dataStatus,message:refreshes===1?'일봉 조회 요청 파라미터를 확인해야 합니다. (HTTP 400)':'지표 준비 일봉을 받았습니다. 첫 판단 가능일 이후 확정 일봉을 기다립니다.'});
    if(refreshes>1)run.storedBarCount=200;
   }
   return route.fulfill({contentType:'application/json',body:JSON.stringify(url.pathname==='/api/strategy-paper/runs'?[run]:run)});
  });
  await page.goto('http://paper.test/');
  await page.addScriptTag({content:fs.readFileSync(path.join(root,'js/strategy-paper.js'),'utf8')});
  await page.getByRole('button',{name:'#8 · 수신 검증 v2 · 005930 · 실행 중',exact:true}).click();
  await page.getByRole('button',{name:'일봉 갱신 및 처리',exact:true}).click();
  await page.getByRole('status').filter({hasText:'HTTP 400'}).waitFor();
  assert.match(await page.locator('.sp-data-message').innerText(),/파라미터.*HTTP 400/);
  await page.getByRole('button',{name:'일봉 갱신 및 처리',exact:true}).click();
  await page.getByRole('status').filter({hasText:'지표 준비 일봉'}).waitFor();
  assert.doesNotMatch(await page.locator('.sp-data-message').innerText(),/HTTP 400/);
  assert.match(await page.locator('.sp-detail').innerText(),/보존한 일봉 200개/);
 }finally{await browser.close();}
});

async function isolatedPaperPage(browser, respond) {
 const page=await browser.newPage();page.setDefaultTimeout(5000);const errors=[];
 page.on('pageerror',error=>errors.push(error.message));
 await page.route('**/*',async route=>{
  const url=new URL(route.request().url());
  if(url.pathname==='/')return route.fulfill({contentType:'text/html',body:'<!doctype html><html lang="ko"><main><button id="accountTab">계좌</button><section id="strategyPaper"></section></main></html>'});
  return respond(route,url);
 });
 await page.goto('http://paper.test/');
 await page.addScriptTag({content:fs.readFileSync(path.join(root,'js/strategy-paper.js'),'utf8')});
 await page.waitForFunction(()=>!document.getElementById('strategyPaper').hasAttribute('aria-busy'));
 return {page,errors};
}

async function selectAndFillPaperInputs(page) {
 await page.evaluate(()=>window.StrategyPaper.select({id:31,version:2,strategy:{schemaVersion:2,name:'단일 전략 A'}}));
 await page.getByLabel('종목 코드',{exact:true}).fill('005930');
 await page.getByLabel('체결 방식',{exact:true}).selectOption('NEXT_DAY_OPEN');
 for(const [label,value] of [['초기자금 (원)','1000'],['매수 비중 (%)','100'],['수수료율 (%)','0'],['매도세율 (%)','0'],['슬리피지율 (%)','0']])
  await page.getByLabel(label,{exact:true}).fill(value);
}

test('unsupported selection clears a previous paper target and direct submits cannot reuse it',async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try {
  const posts=[];
  const {page,errors}=await isolatedPaperPage(browser,async(route,url)=>{
   if(route.request().method()==='POST'&&url.pathname==='/api/strategy-paper/runs'){
    posts.push(route.request().postDataJSON());return route.fulfill({status:503,json:{message:'합성 시작 실패'}});
   }
   return route.fulfill({json:[]});
  });
  const start=page.getByRole('button',{name:'모의매매 시작',exact:true});
  assert.equal(await start.isDisabled(),true);
  await selectAndFillPaperInputs(page);await start.click();
  await page.getByRole('status').filter({hasText:'합성 시작 실패'}).waitFor();
  assert.equal(posts.length,1);
  for(const strategy of [{schemaVersion:3,portfolio:{}},{schemaVersion:4,composition:{}},{schemaVersion:3},{schemaVersion:4}]){
   await page.evaluate(strategy=>window.StrategyPaper.select({id:32,version:1,strategy}),strategy);
   assert.equal(await start.isDisabled(),true);
   assert.equal(await page.locator('.sp-target').innerText(),'선택된 전략 없음');
   await page.locator('#strategyPaper form').evaluate(form=>form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})));
   assert.equal(posts.length,1,'a direct submit after B must not start the former A');
  }
  await selectAndFillPaperInputs(page);await start.click();
  await page.getByRole('status').filter({hasText:'합성 시작 실패'}).waitFor();
  assert.equal(posts.length,2);assert.notEqual(posts[0].requestId,posts[1].requestId);
  assert.equal(posts[1].strategyId,31);assert.equal(posts[1].version,2);assert.deepEqual(errors,[]);
 }finally{await browser.close();}
});

test('late paper start success or failure cannot restore an unsupported selection',async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try {
  for(const outcome of ['success','failure']){
   let release;const gate=new Promise(resolve=>{release=resolve;});const posts=[];
   const {page,errors}=await isolatedPaperPage(browser,async(route,url)=>{
    if(route.request().method()==='POST'&&url.pathname==='/api/strategy-paper/runs'){
     const body=route.request().postDataJSON();posts.push(body);await gate;
     if(outcome==='failure')return route.fulfill({status:503,json:{message:'합성 늦은 실패'}});
     return route.fulfill({json:{id:41,status:'RUNNING',market:'KOSPI',definition:body,
      strategy:{id:31,version:2,strategy:{schemaVersion:2,name:'단일 전략 A'}},
      state:{firstTradingDate:'2026-03-03',dataStatus:'WAITING_DATA',cash:1000,equity:1000,trades:[],logs:[],curve:[]}}});
    }
    return route.fulfill({json:[]});
   });
   try {
    await selectAndFillPaperInputs(page);
    const sent=page.waitForRequest(request=>request.method()==='POST'&&new URL(request.url()).pathname==='/api/strategy-paper/runs');
    await page.getByRole('button',{name:'모의매매 시작',exact:true}).click();await sent;
    await page.evaluate(()=>window.StrategyPaper.select({id:32,version:1,strategy:{schemaVersion:3,name:'미지원 B',portfolio:{}}}));
    release();await page.waitForFunction(()=>!document.getElementById('strategyPaper').hasAttribute('aria-busy'));
    assert.equal(await page.getByRole('button',{name:'모의매매 시작',exact:true}).isDisabled(),true);
    assert.equal(await page.locator('.sp-target').innerText(),'선택된 전략 없음');
    assert.match(await page.locator('.sp-notice').innerText(),/단일 종목 전략/);
    assert.doesNotMatch(await page.locator('.sp-notice').innerText(),/합성 늦은 실패|#41.*시작/);
    assert.equal(await page.getByRole('heading',{name:'모의매매 #41',exact:true}).count(),0);
    await page.locator('#strategyPaper form').evaluate(form=>form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})));
    assert.equal(posts.length,1,'the already sent A request is not cancelled, but B must not add a start');
    assert.deepEqual(errors,[]);
   }finally{release();await page.close();}
  }
 }finally{await browser.close();}
});
