const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require('playwright');
const staticRoot=path.resolve(__dirname,'../../src/main/resources/static');
const single=(id,version=1)=>({id,version,strategy:{schemaVersion:2,name:`전략 ${id}`,originalPrompt:'<img src=x onerror=alert(1)>',entry:{operator:'AND',conditions:[{type:'VOLUME',period:20,multiplier:2,comparison:'GTE'}]},risk:{stopLoss:{rate:-0.05}}}});
async function setup(action,library=[single(1,2),single(2)]){
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try{
  const page=await browser.newPage();page.setDefaultTimeout(3500);const requests=[],errors=[];page.on('pageerror',e=>errors.push(e.message));let detail,deleted=false;
  await page.route('**/*',async route=>{
   const r=route.request(),url=new URL(r.url()),body=r.postData()?JSON.parse(r.postData()):null;let data=[];
   if(url.pathname==='/')return route.fulfill({contentType:'text/html',body:'<!doctype html><html lang="ko"><main><div id="strategyWorkbench"></div><section id="strategyComparison"></section></main></html>'});
   requests.push({path:url.pathname,method:r.method(),body});
   if(url.pathname.endsWith('/status'))data={ready:true};
   else if(url.pathname==='/api/strategies')data=library;
   else if(url.pathname==='/api/strategies/1')data=single(1,2);
   else if(url.pathname==='/api/strategies/1/versions')data=[single(1,1),single(1,2)];
   else if(url.pathname==='/api/strategies/1/versions/1')data=single(1,1);
   else if(url.pathname==='/api/comparisons'&&r.method()==='POST'){
    detail={id:77,createdAt:'2025-01-04T10:00:00',snapshot:{schemaVersion:1,type:'SINGLE',execution:body,data:{source:'일봉',sha256:'a'.repeat(64),candles:[]},entries:body.strategies.map((s,i)=>({strategy:single(s.id,s.version),engineVersion:'1',status:i?'FAILED':'NO_TRADES',error:i?{code:'TEST_FAILURE',message:'<script>unsafe</script>'}:null,single:i?null:{actualStartDate:'2025-01-02',actualEndDate:'2025-01-03'},account:null,composite:null,metrics:i?{returnRate:null,maxDrawdown:null,tradeCount:null,winRate:null,sharpe:null,finalEquity:null}:{returnRate:0,maxDrawdown:0,tradeCount:0,winRate:0,sharpe:0,finalEquity:null},curve:i?[]:[{date:'2025-01-02',value:0},{date:'2025-01-03',value:0}]}))}};data=detail;
   }else if(url.pathname==='/api/comparisons')data=detail&&!deleted?[{id:77,createdAt:detail.createdAt,type:'SINGLE',strategyNames:['전략 1','전략 2']}]:[];
   else if(url.pathname==='/api/comparisons/77'&&r.method()==='DELETE'){deleted=true;return route.fulfill({status:204});}
   else if(url.pathname==='/api/comparisons/77')data=detail;
   return route.fulfill({contentType:'application/json',body:JSON.stringify(data)});
  });
  await page.goto('http://comparison.test/');
  await page.addStyleTag({content:'*{box-sizing:border-box}'});
  for(const f of ['strategy-workbench.css','strategy-comparison.css'])if(fs.existsSync(path.join(staticRoot,'css',f)))await page.addStyleTag({content:fs.readFileSync(path.join(staticRoot,'css',f),'utf8')});
  for(const f of ['strategy-comparison.js','strategy-workbench.js'])if(fs.existsSync(path.join(staticRoot,'js',f)))await page.addScriptTag({content:fs.readFileSync(path.join(staticRoot,'js',f),'utf8')});
  await action(page,requests);assert.deepEqual(errors,[]);
 }finally{await browser.close();}
}
async function common(page){await page.getByLabel('비교 종목 코드',{exact:true}).fill('005930');await page.getByLabel('비교 시작일',{exact:true}).fill('2025-01-01');await page.getByLabel('비교 종료일',{exact:true}).fill('2025-01-03');await page.getByLabel('비교 체결 방식',{exact:true}).selectOption('NEXT_DAY_OPEN');}
test('one composite explains disabled action; refreshed compatible candidate enables comparison without clearing costs',async()=>{
 const composite=id=>({...single(id),strategy:{...single(id).strategy,composition:{selection:null}}});
 const library=[composite(8),single(7)];
 await setup(async(page)=>{
  await page.locator('#strategyWorkbench .sw-strategy-type').filter({hasText:'조합 전략'}).waitFor();
  await page.getByRole('button',{name:'전략 8 · #8 / v1 비교에 추가',exact:true}).click();
  const run=page.getByRole('button',{name:'비교 실행 및 저장',exact:true});
  assert.equal(await run.isDisabled(),true);
  assert.match(await page.locator('#sc-run-requirement').innerText(),/조합 전략 1개를 더/);
  assert.equal(await page.getByRole('button',{name:'전략 7 · #7 / v1 비교에 추가',exact:true}).count(),0);
  await page.getByLabel('비교 초기자금 (원)',{exact:true}).fill('1000000');
  library.push(composite(9));
  await page.getByRole('button',{name:'전략 목록 새로고침',exact:true}).click();
  await page.getByRole('button',{name:'전략 9 · #9 / v1 비교에 추가',exact:true}).click();
  assert.equal(await run.isEnabled(),true);
  assert.equal(await page.getByLabel('비교 초기자금 (원)',{exact:true}).inputValue(),'1000000');
  await page.getByRole('button',{name:'비교에서 #9 제거',exact:true}).click();
  assert.equal(await run.isDisabled(),true);
 },library);
});
test('selected historical version is frozen; mixed and duplicate strategies rejected; persisted failure remains missing, safe, and deletable',()=>setup(async(page,requests)=>{
 await page.getByRole('button',{name:'전략 1 · #1 · 최신 v2',exact:true}).click();
 await page.getByRole('button',{name:/^v1 ·/}).click();
 await page.getByRole('button',{name:'선택 버전을 비교에 추가',exact:true}).click();
 await page.evaluate(s=>window.StrategyComparison.add(s),single(1,2));
 await page.locator('#strategyComparison').getByText(/동일 전략/).waitFor();
 await page.evaluate(s=>window.StrategyComparison.add({...s,strategy:{...s.strategy,portfolio:{market:'KOSPI'}}}),single(3));
 await page.locator('#strategyComparison').getByRole('status').filter({hasText:'같은 유형'}).waitFor();
 await page.evaluate(s=>window.StrategyComparison.add(s),single(2));await common(page);
 await page.getByRole('button',{name:'비교 실행 및 저장',exact:true}).click();
 await page.getByRole('heading',{name:'비교 #77',exact:true}).waitFor();
 assert.deepEqual(requests.find(r=>r.path==='/api/comparisons'&&r.method==='POST').body.strategies,[{id:1,version:1},{id:2,version:1}]);
 const failed=page.locator('.sc-entry').filter({has:page.getByRole('heading',{name:/전략 2/})});
 assert.match(await failed.innerText(),/실패/);assert.equal(await failed.locator('dd').filter({hasText:'—'}).count(),6);
 assert.equal(await page.locator('#strategyComparison img, #strategyComparison script').count(),0);
 await page.getByRole('img',{name:'공통 날짜 기준 전략 성과 비교',exact:true}).waitFor();
 await page.getByRole('button',{name:/^비교 #77 보기/}).click();assert.ok(requests.some(r=>r.path==='/api/comparisons/77'&&r.method==='GET'));
 await page.setViewportSize({width:390,height:844});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),JSON.stringify(await page.evaluate(()=>[...document.querySelectorAll('*')].filter(e=>e.getClientRects().length&&e.getBoundingClientRect().right>innerWidth).slice(0,12).map(e=>({tag:e.tagName,id:e.id,cls:e.className,right:e.getBoundingClientRect().right})))));
 page.once('dialog',d=>d.dismiss());await page.getByRole('button',{name:'비교 #77 삭제',exact:true}).click();assert.equal(requests.filter(r=>r.method==='DELETE').length,0);
 page.once('dialog',d=>d.accept());await page.getByRole('button',{name:'비교 #77 삭제',exact:true}).click();await page.locator('#strategyComparison').getByText('비교 이력이 없습니다.',{exact:true}).waitFor();assert.equal(requests.filter(r=>r.method==='DELETE').length,1);
}));

test('switching single to portfolio omits stale symbol and enforces account cost range',()=>setup(async(page,requests)=>{
 for(const id of [1,2])await page.evaluate(s=>window.StrategyComparison.add(s),single(id));
 await common(page);
 for(const id of [1,2])await page.getByRole('button',{name:`비교에서 #${id} 제거`,exact:true}).click();
 for(const id of [3,4])await page.evaluate(s=>window.StrategyComparison.add({...s,strategy:{...s.strategy,portfolio:{market:'KOSPI'}}}),single(id));
 assert.equal(await page.getByLabel('비교 종목 코드',{exact:true}).isVisible(),false);
 await page.getByLabel('비교 초기자금 (원)',{exact:true}).fill('1000');
 for(const label of ['비교 수수료율 (%)','비교 매도세율 (%)','비교 슬리피지율 (%)'])await page.getByLabel(label,{exact:true}).fill('0');
 await page.getByLabel('비교 시장·거래일 자료 (JSON)',{exact:true}).fill('{"source":"자료","members":[],"tradingDates":[]}');
 await page.getByLabel('비교 수수료율 (%)',{exact:true}).fill('26');
 await page.getByRole('button',{name:'비교 실행 및 저장',exact:true}).click();
 await page.getByRole('status').filter({hasText:'0~25%'}).waitFor();assert.equal(requests.filter(r=>r.method==='POST').length,0);
 await page.getByLabel('비교 수수료율 (%)',{exact:true}).fill('0.29');await page.getByRole('button',{name:'비교 실행 및 저장',exact:true}).click();
 await page.getByRole('heading',{name:'비교 #77',exact:true}).waitFor();
 assert.equal(requests.find(r=>r.method==='POST').body.symbol,null);
 assert.equal(requests.find(r=>r.method==='POST').body.commissionRate,0.0029);
}));
test('account comparison requires explicit zero costs and universe; mixed-selection composite also requires common symbol',()=>setup(async(page,requests)=>{
 const pair=[1,2].map(id=>({...single(id),strategy:{...single(id).strategy,composition:{selection:id===1?{market:'KOSPI'}:null}}}));
 for(const s of pair)await page.evaluate(s=>window.StrategyComparison.add(s),s);
 await common(page);await page.getByLabel('비교 초기자금 (원)',{exact:true}).fill('1000');
 await page.getByRole('button',{name:'비교 실행 및 저장',exact:true}).click();await page.locator('#strategyComparison').getByText(/비용률을 모두/).waitFor();
 for(const label of ['비교 수수료율 (%)','비교 매도세율 (%)','비교 슬리피지율 (%)'])await page.getByLabel(label,{exact:true}).fill('0');
 await page.getByRole('button',{name:'비교 실행 및 저장',exact:true}).click();await page.locator('#strategyComparison').getByText(/올바른 JSON/).waitFor();
 await page.getByLabel('비교 시장·거래일 자료 (JSON)',{exact:true}).fill('{"source":"전체 시장","members":[],"tradingDates":[]}');
 await page.getByLabel('비교 종목 코드',{exact:true}).fill('');await page.getByRole('button',{name:'비교 실행 및 저장',exact:true}).click();await page.locator('#strategyComparison').getByRole('status').filter({hasText:'공통 종목'}).waitFor();assert.equal(requests.filter(r=>r.method==='POST').length,0);
 await page.getByLabel('비교 종목 코드',{exact:true}).fill('005930');await page.getByRole('button',{name:'비교 실행 및 저장',exact:true}).click();await page.getByRole('heading',{name:'비교 #77',exact:true}).waitFor();
 const request=requests.find(r=>r.method==='POST');assert.equal(request.body.commissionRate,0);assert.equal(request.body.taxRate,0);assert.equal(request.body.slippageRate,0);assert.equal(request.body.symbol,'005930');
}));
