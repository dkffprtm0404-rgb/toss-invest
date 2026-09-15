const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require('playwright');
const source=fs.readFileSync(path.resolve(__dirname,'../../src/main/resources/static/js/strategy-workbench.js'),'utf8');
const style=fs.readFileSync(path.resolve(__dirname,'../../src/main/resources/static/css/strategy-workbench.css'),'utf8');
test('portfolio draft edits and execution use separate routes and show account and rank evidence',async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try {
  const page=await browser.newPage();page.setDefaultTimeout(7000);const errors=[];page.on('pageerror',e=>errors.push(e.message));let saved,execution;
  const strategy={schemaVersion:3,name:'상대강도',originalPrompt:'상대강도 원문',entry:null,exit:null,risk:{stopLoss:{rate:-0.08}},portfolio:{market:'KOSPI_KOSDAQ',lookbackMonths:6,topN:10,smaPeriod:200,selectionOrder:'FILTER_THEN_RANK',rebalanceTiming:'WEEK_END',weighting:'EQUAL_SLOTS'}};
  const result={status:'COMPLETED',initialCapital:1000000,finalEquity:1100000,totalReturn:0.1,maxDrawdown:0.03,cash:10000,equity:[{date:'2025-07-01',equity:1000000,cash:1000000},{date:'2025-07-02',equity:1100000,cash:10000}],trades:[{date:'2025-07-02',signalDate:'2025-07-01',symbol:'005930',side:'BUY',quantity:10,price:99000,fee:0,tax:0,reason:'REBALANCE',cashAfter:10000}],selections:[{date:'2025-07-01',ranked:[{symbol:'005930',rank:1,returnRate:0.3,close:99000,sma:90000,selected:true}],excluded:[]}],holdings:[],pending:[],assumptions:['정수 주수·입력 비용 적용']};
  let detail;
  await page.route('**/*',async route=>{
   const req=route.request(),url=new URL(req.url()),body=req.postData()?JSON.parse(req.postData()):null;let data=[];
   if(url.pathname==='/')return route.fulfill({contentType:'text/html',body:'<!doctype html><html lang="ko"><div id="strategyWorkbench"></div></html>'});
   if(url.pathname.endsWith('/status'))data={ready:true};
   else if(url.pathname.endsWith('/interpret-batch'))data={items:[{title:'상대강도',prompt:'상대강도 원문',draft:{strategy,ready:true,issues:[],questions:[],unsupported:[]}}],questions:[]};
   else if(url.pathname.endsWith('/validate'))data={strategy:body,ready:true,issues:[]};
   else if(url.pathname==='/api/strategies'&&req.method()==='POST')data=saved={id:20,version:1,strategy:body};
   else if(url.pathname==='/api/strategies')data=saved?[saved]:[];
   else if(url.pathname.endsWith('/versions'))data=[saved];
   else if(url.pathname.endsWith('/portfolio-backtests')&&req.method()==='POST'){execution=body;data=detail={id:40,status:'COMPLETED',snapshot:{strategy:saved,execution:body,result,data:{source:'test'}}};}
   else if(url.pathname.endsWith('/portfolio-backtests'))data=detail?[{id:40,version:1,status:'COMPLETED'}]:[];
   else if(url.pathname==='/api/portfolio/runs/40')data=detail;
   return route.fulfill({contentType:'application/json',body:JSON.stringify(data)});
  });
  await page.goto('http://portfolio.test/');await page.addStyleTag({content:'*{box-sizing:border-box}'+style});await page.addScriptTag({content:source});
  await page.getByLabel('전략 원문',{exact:true}).fill('상대강도 원문');await page.getByRole('button',{name:'Codex로 조건 해석',exact:true}).click();
  assert.equal(await page.getByLabel('수익률 기간 (개월)',{exact:true}).inputValue(),'6');
  await page.getByLabel('상위 종목 수',{exact:true}).fill('8');await page.getByRole('button',{name:'입력 조건 검증',exact:true}).click();
  await page.getByLabel('원문·조건·위험 관리 설정을 검토했으며 이 내용으로 저장합니다.',{exact:true}).check();await page.getByRole('button',{name:'새 전략으로 저장',exact:true}).click();
  await page.getByLabel('초기자금 (원)',{exact:true}).fill('1000000');
  for(const label of ['수수료율 (%)','매도세율 (%)','슬리피지율 (%)'])await page.getByLabel(label,{exact:true}).fill('0');
  await page.getByLabel('시장·거래일 자료 (JSON)',{exact:true}).fill(JSON.stringify({source:'검증 자료',tradingDates:['2025-01-01','2025-08-01'],members:[{symbol:'005930',market:'KOSPI',from:'2025-01-01',to:null}]}));
  await page.getByLabel('시작일',{exact:true}).fill('2025-07-01');await page.getByLabel('종료일',{exact:true}).fill('2025-07-02');await page.getByLabel('체결 방식',{exact:true}).selectOption('NEXT_DAY_OPEN');
  await page.getByRole('button',{name:'선택 버전 백테스트 실행',exact:true}).click();
  await page.getByText('최종 평가액 (원)',{exact:true}).waitFor();assert.equal(saved.strategy.portfolio.topN,8);assert.equal(execution.initialCapital,1000000);assert.equal(execution.commissionRate,0);
  await page.getByText('종목 선정 근거',{exact:true}).waitFor();assert.equal(await page.getByText('청산된 거래 단위 통계입니다.',{exact:false}).isVisible(),false);
  await page.setViewportSize({width:390,height:844});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth));
  await page.screenshot({path:'build/strategy-portfolio-mobile.png',fullPage:true});assert.deepEqual(errors,[]);
 }finally{await browser.close();}
});
