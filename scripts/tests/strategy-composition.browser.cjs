const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require('playwright');
const source=fs.readFileSync(path.resolve(__dirname,'../../src/main/resources/static/js/strategy-workbench.js'),'utf8');
const style=fs.readFileSync(path.resolve(__dirname,'../../src/main/resources/static/css/strategy-workbench.css'),'utf8');
const draft=s=>({strategy:s,ready:false,issues:[],questions:[],unsupported:[]});
const items=Array.from({length:6},(_,i)=>({title:`개별 ${i+1}`,prompt:`원문 ${i+1}`,draft:{...draft({schemaVersion:2,name:`개별 ${i+1}`,originalPrompt:`원문 ${i+1}`,entry:{operator:'AND',conditions:[{type:'VOLUME',period:20,multiplier:2,comparison:'GTE'}]},risk:{stopLoss:{rate:-0.05}}}),ready:true}}));
function compose(items,name){return draft({schemaVersion:4,name,originalPrompt:items.map(x=>x.prompt).join('\n'),risk:null,composition:{sources:items.map((x,i)=>({id:`s${i+1}`,title:x.title,prompt:x.prompt,definition:structuredClone(x.draft.strategy),questions:[],unsupported:[],included:true,resolution:null})),entry:{id:'entry',operator:null,children:items.map((x,i)=>({id:`entry-s${i+1}`,sourceId:`s${i+1}`,operator:'AND',children:[{id:`leaf-s${i+1}`,sourceId:`s${i+1}`,condition:x.draft.strategy.entry.conditions[0]}]}))},exit:null,selection:null,selectionSourceId:null,rankExit:null,allocation:{maxPositions:null,rebalance:null},riskConfirmed:false,exitConfirmed:false}});}

test('six plus total, subset composition, nested edits, independent snapshots, confirmations and immutable execution history',async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try{
  const page=await browser.newPage();page.setDefaultTimeout(5000);const errors=[],requests=[];page.on('pageerror',e=>errors.push(e.message));let saved,detail;
  await page.route('**/*',async route=>{
   const r=route.request(),url=new URL(r.url()),body=r.postData()?JSON.parse(r.postData()):null;let data=[];
   if(url.pathname==='/')return route.fulfill({contentType:'text/html',body:'<!doctype html><html lang="ko"><div id="strategyWorkbench"></div></html>'});
   requests.push({path:url.pathname,method:r.method(),body});
   if(url.pathname.endsWith('/status'))data={ready:true};
   else if(url.pathname.endsWith('/interpret-batch'))data={items,total:{title:'전체 토탈 · 6개',prompt:'전체',draft:compose(items,'전체 토탈 · 6개')},questions:[]};
   else if(url.pathname.endsWith('/compose'))data=compose(body.items,body.name);
   else if(url.pathname.endsWith('/validate'))data={ready:body.composition?!!(body.composition.riskConfirmed&&body.composition.exitConfirmed):true,issues:[],strategy:body};
   else if(url.pathname==='/api/strategies'&&r.method()==='POST')data=saved={id:30,version:1,strategy:body};
   else if(url.pathname==='/api/strategies')data=saved?[saved]:[];
   else if(url.pathname.endsWith('/versions'))data=[saved];
   else if(url.pathname.endsWith('/composite-backtests')&&r.method()==='POST')data=detail={id:50,status:'COMPLETED',snapshot:{strategy:structuredClone(saved),execution:body,data:{source:'local'},result:{account:{initialCapital:1000,finalEquity:1100,totalReturn:0.1,maxDrawdown:0,cash:1100,trades:[],holdings:[],selections:[],pending:[],assumptions:[]},evaluations:[{date:'2025-07-01',symbol:'005930',phase:'ENTRY',ready:true,matched:true,reason:'ENTRY_SIGNAL',nodes:[{id:'leaf-s1',sourceId:'s1',ready:true,matched:true,evidence:[{type:'VOLUME',actual:200,threshold:100}]}]}]}}};
   else if(url.pathname.endsWith('/composite-backtests'))data=detail?[{id:50,version:1,status:'COMPLETED'}]:[];
   else if(url.pathname==='/api/composite/runs/50')data=detail;
   return route.fulfill({contentType:'application/json',body:JSON.stringify(data)});
  });
  await page.goto('http://composition.test/');await page.addStyleTag({content:'*{box-sizing:border-box}'+style});await page.addScriptTag({content:source});
  await page.getByLabel('전략 원문',{exact:true}).fill('여섯 전략');await page.getByRole('button',{name:'Codex로 조건 해석',exact:true}).click();
  await page.getByRole('button',{name:/전체 토탈 · 6개/}).waitFor();
  assert.equal(await page.getByRole('button',{name:/^초안 [1-6] · 개별/}).count(),6);
  const composeButton=page.getByRole('button',{name:'선택한 전략 조합',exact:true});assert.equal(await composeButton.isDisabled(),true);
  await page.getByLabel('조합 선택 · 개별 1',{exact:true}).check();await page.getByLabel('조합 선택 · 개별 3',{exact:true}).check();await composeButton.click();
  await page.getByLabel('진입 결합 · entry',{exact:true}).selectOption('OR');
  await page.getByLabel('묶기 선택 · entry-s1',{exact:true}).check();await page.getByLabel('묶기 선택 · entry-s2',{exact:true}).check();
  await page.getByRole('button',{name:'선택 조건 하위 그룹으로 묶기 · entry',exact:true}).click();
  const nested=page.getByLabel(/^진입 결합 · user-/);assert.equal(await nested.inputValue(),'');await nested.selectOption('OR');
  assert.equal(await page.getByLabel('진입 결합 · entry',{exact:true}).count(),0);
  await page.getByLabel('변경·제외·질문 해결 사유 · 개별 1',{exact:true}).fill('두 원문 그룹을 OR로 묶고 공통 손절 -8% 적용');
  await page.getByLabel('변경·제외·질문 해결 사유 · 개별 3',{exact:true}).fill('두 원문 그룹을 OR로 묶고 공통 손절 -8% 적용');
  assert.equal(requests.filter(r=>r.path.endsWith('/compose')).length,1);
  assert.deepEqual(requests.find(r=>r.path.endsWith('/compose')).body.items.map(x=>x.title),['개별 1','개별 3']);
  assert.equal(await page.getByRole('button',{name:'선택 전략 다시 해석',exact:true}).isDisabled(),true);
  assert.equal(await page.getByLabel('선택 전략 원문',{exact:true}).isEditable(),false);
  await page.getByLabel('최대 보유 종목 수',{exact:true}).fill('1');await page.getByLabel('비중 조정 방식',{exact:true}).selectOption('ENTRY_ONLY');
  await page.getByLabel('손절 사용',{exact:true}).check();await page.getByLabel('손절 수익률 (%) · 음수, 예: -5',{exact:true}).fill('-8');
  await page.getByLabel('공통 위험 관리 적용과 기준을 확인했습니다.',{exact:true}).check();await page.getByLabel('공통 청산 적용 범위를 확인했습니다.',{exact:true}).check();
  await page.getByRole('button',{name:'입력 조건 검증',exact:true}).click();
  const confirmation=page.getByLabel('원문·조건·위험 관리 설정을 검토했으며 이 내용으로 저장합니다.',{exact:true});await confirmation.check();
  await page.getByLabel('최대 보유 종목 수',{exact:true}).fill('2');
  assert.equal(await page.getByLabel('공통 위험 관리 적용과 기준을 확인했습니다.',{exact:true}).isChecked(),false);
  assert.equal(await page.getByRole('button',{name:'새 전략으로 저장',exact:true}).isDisabled(),true);
  await page.getByLabel('공통 위험 관리 적용과 기준을 확인했습니다.',{exact:true}).check();await page.getByLabel('공통 청산 적용 범위를 확인했습니다.',{exact:true}).check();
  await page.getByRole('button',{name:'입력 조건 검증',exact:true}).click();await confirmation.check();await page.getByRole('button',{name:'새 전략으로 저장',exact:true}).click();
  await page.getByLabel('초기자금 (원)',{exact:true}).fill('1000');for(const l of ['수수료율 (%)','매도세율 (%)','슬리피지율 (%)'])await page.getByLabel(l,{exact:true}).fill('0');
  assert.equal(await page.getByLabel('시장·거래일 자료 (JSON)',{exact:true}).isVisible(),false);
  await page.getByLabel('종목 코드',{exact:true}).fill('005930');await page.getByLabel('시작일',{exact:true}).fill('2025-07-01');await page.getByLabel('종료일',{exact:true}).fill('2025-07-02');await page.getByLabel('체결 방식',{exact:true}).selectOption('NEXT_DAY_OPEN');
  await page.getByRole('button',{name:'선택 버전 백테스트 실행',exact:true}).click();await page.getByText('최종 평가액 (원)',{exact:true}).waitFor();await page.getByText('조건별 판정 근거',{exact:true}).waitFor();
  assert.equal(requests.find(r=>r.path.endsWith('/composite-backtests')&&r.method==='POST').body.universe,undefined);
  assert.equal(saved.strategy.composition.entry.operator,'OR');assert.equal(saved.strategy.composition.entry.children[0].operator,'OR');assert.equal(saved.strategy.risk.stopLoss.rate,-0.08);assert.equal(saved.strategy.composition.sources[0].definition.risk.stopLoss.rate,-0.05);
  await page.getByRole('button',{name:/^#50 ·/}).click();await page.getByText('당시 저장된 전략·데이터·결과 스냅샷을 표시합니다.',{exact:true}).waitFor();assert.ok(requests.some(r=>r.path==='/api/composite/runs/50'));
  await page.getByRole('button',{name:/^초안 1 · 개별 1/}).click();await page.getByLabel('손절 수익률 (%) · 음수, 예: -5',{exact:true}).fill('-9');
  await page.getByRole('button',{name:/^초안 8 · 사용자 조합/}).click();assert.equal(await page.getByLabel('손절 수익률 (%) · 음수, 예: -5',{exact:true}).inputValue(),'-8');
  assert.equal(requests.filter(r=>r.path.endsWith('/interpret-batch')).length,1);assert.equal(requests.filter(r=>r.path.endsWith('/interpret')).length,0);
  await page.setViewportSize({width:390,height:844});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth));await page.screenshot({path:'build/strategy-composition-mobile.png',fullPage:true});assert.deepEqual(errors,[]);
 }finally{await browser.close();}
});

test('relative-strength composition requires comparison universe even for one symbol, and exclusion keeps source snapshot',async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try{
  const page=await browser.newPage();page.setDefaultTimeout(5000);const requests=[],errors=[];page.on('pageerror',e=>errors.push(e.message));
  const strategy=compose(items.slice(0,2),'시장 비교 조합').strategy;
  const rs={market:'KOSPI',lookbackMonths:6,topN:10,smaPeriod:200,selectionOrder:'FILTER_THEN_RANK',rebalanceTiming:'WEEK_END',weighting:'EQUAL_SLOTS'};
  strategy.composition.sources[1].definition={schemaVersion:3,name:'상대강도',portfolio:rs};strategy.composition.selection=structuredClone(rs);strategy.composition.selectionSourceId='s2';strategy.composition.rankExit=null;
  const saved={id:31,version:1,strategy};
  await page.route('**/*',async route=>{
   const r=route.request(),url=new URL(r.url()),body=r.postData()?JSON.parse(r.postData()):null;let data=[];
   if(url.pathname==='/')return route.fulfill({contentType:'text/html',body:'<!doctype html><div id="strategyWorkbench"></div>'});
   requests.push({path:url.pathname,method:r.method(),body});
   if(url.pathname.endsWith('/status'))data={ready:true};
   else if(url.pathname==='/api/strategies')data=[saved];
   else if(url.pathname==='/api/strategies/31')data=saved;
   else if(url.pathname.endsWith('/versions'))data=[saved];
   else if(url.pathname.endsWith('/validate'))data={strategy:body,ready:false,issues:[]};
   else if(url.pathname.endsWith('/composite-backtests')&&r.method()==='POST')data={id:51,status:'NO_TRADES',snapshot:{strategy:saved,execution:body,data:{},result:null}};
   return route.fulfill({contentType:'application/json',body:JSON.stringify(data)});
  });
  await page.goto('http://composition-rs.test/');await page.addScriptTag({content:source});
  await page.getByRole('button',{name:'시장 비교 조합 · #31 · 최신 v1',exact:true}).click();
  assert.equal(await page.getByLabel('주간 순위 이탈 청산',{exact:true}).inputValue(),'');
  await page.getByLabel('주간 순위 이탈 청산',{exact:true}).selectOption('false');
  await page.getByLabel('초기자금 (원)',{exact:true}).fill('1000');for(const l of ['수수료율 (%)','매도세율 (%)','슬리피지율 (%)'])await page.getByLabel(l,{exact:true}).fill('0');
  await page.getByLabel('종목 코드',{exact:true}).fill('005930');await page.getByLabel('시작일',{exact:true}).fill('2025-07-01');await page.getByLabel('종료일',{exact:true}).fill('2025-07-02');await page.getByLabel('체결 방식',{exact:true}).selectOption('SAME_DAY_CLOSE');
  await page.getByRole('button',{name:'선택 버전 백테스트 실행',exact:true}).click();await page.getByText('시장·거래일 자료를 올바른 JSON으로 입력하세요.',{exact:true}).waitFor();
  assert.equal(requests.filter(r=>r.path.endsWith('/composite-backtests')&&r.method==='POST').length,0);
  await page.getByLabel('시장·거래일 자료 (JSON)',{exact:true}).fill(JSON.stringify({source:'전체 비교 시장',tradingDates:['2025-07-01','2025-07-02'],members:[]}));
  await page.getByRole('button',{name:'선택 버전 백테스트 실행',exact:true}).click();await page.getByText('통합 전략 실행 #51 · 거래 없음',{exact:true}).waitFor();
  assert.equal(requests.find(r=>r.path.endsWith('/composite-backtests')&&r.method==='POST').body.symbol,'005930');
  await page.getByLabel('원문 포함 · 개별 2',{exact:true}).uncheck();await page.getByLabel('변경·제외·질문 해결 사유 · 개별 2',{exact:true}).fill('이번 조합은 시장 선정을 제외');
  await page.getByRole('button',{name:'입력 조건 검증',exact:true}).click();await page.getByText('보완 사항을 해결한 뒤 다시 확인해 주세요.',{exact:true}).waitFor();
  const edited=requests.find(r=>r.path.endsWith('/validate')).body.composition;
  assert.equal(edited.selection,null);assert.equal(edited.sources[1].included,false);assert.deepEqual(edited.sources[1].definition.portfolio,rs);assert.equal(edited.entry.children.length,1);
  assert.deepEqual(errors,[]);
 }finally{await browser.close();}
});

async function withAuditPage(action) {
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try {
  const page=await browser.newPage({viewport:{width:595,height:626}});page.setDefaultTimeout(5000);
  const strategy=compose(items.slice(0,2),'검증 조합').strategy;
  strategy.originalPrompt='첫 번째 원문\n두 번째 원문 <script>문자 그대로</script>';
  strategy.composition.entry.children.forEach(n=>n.operator=null);
  const saved={id:40,version:1,strategy};
  const detail={id:60,status:'COMPLETED',snapshot:{strategy:saved,execution:{startDate:'2025-01-02',endDate:'2025-12-30'},result:{
   account:{initialCapital:1000,finalEquity:1100,totalReturn:0.1,maxDrawdown:0,cash:1100,equity:[{date:'2025-11-21',equity:1000,cash:1000},{date:'2025-12-30',equity:1100,cash:1100}],trades:[],holdings:[],selections:[],pending:[],assumptions:[]},
   evaluations:[{date:'2025-11-21',symbol:'005930',phase:'ENTRY',ready:true,matched:true,nodes:[{id:'entry',ready:true},{id:'entry-s2',ready:false},{id:'leaf-s1',sourceId:'s1',ready:true},{id:'leaf-s2',sourceId:'s2',ready:false}]}]
  }}};
  await page.route('**/*',async route=>{
   const p=new URL(route.request().url()).pathname;let data=[];
   if(p==='/')return route.fulfill({contentType:'text/html',body:'<!doctype html><html lang="ko"><div id="strategyWorkbench"></div></html>'});
   if(p.endsWith('/status'))data={ready:true};
   else if(p==='/api/strategies')data=[saved];
   else if(p==='/api/strategies/40')data=saved;
   else if(p.endsWith('/versions'))data=[saved];
   else if(p.endsWith('/composite-backtests'))data=[{id:60,version:1,status:'COMPLETED'}];
   else if(p==='/api/composite/runs/60')data=detail;
   else if(p.endsWith('/validate'))data={ready:false,issues:[{path:'composition.entry.operator',message:'진입 결합을 선택해 주세요.'}]};
   return route.fulfill({contentType:'application/json',body:JSON.stringify(data)});
  });
  await page.goto('http://audit.test/');await page.addStyleTag({content:'*{box-sizing:border-box}'+style});await page.addScriptTag({content:source});
  await page.getByRole('button',{name:'검증 조합 · #40 · 최신 v1',exact:true}).click();
  await page.getByText('저장된 전략을 불러왔습니다. 수정한 내용은 검증 후 새 버전으로 저장하세요.',{exact:true}).waitFor();
  await action(page);
 } finally {await browser.close();}
}

test('validation errors appear next to validation controls and receive focus in a long editor',()=>withAuditPage(async page=>{
 await page.getByRole('button',{name:'입력 조건 검증',exact:true}).click();
 const feedback=page.getByRole('region',{name:'조건 검증 결과',exact:true});
 await feedback.getByText(/진입 결합을 선택/).waitFor();
 assert.equal(await feedback.evaluate(e=>e===document.activeElement),true);
 const box=await feedback.boundingBox();assert.ok(box.y>=0 && box.y+box.height<=626);
 assert.equal(await page.getByRole('button',{name:'새 전략으로 저장',exact:true}).isDisabled(),true);
}));

test('one-child groups need no operator but adding a second child exposes the required choice',()=>withAuditPage(async page=>{
 assert.equal(await page.getByLabel('진입 결합 · entry-s1',{exact:true}).count(),0);
 assert.equal(await page.getByLabel('진입 결합 · entry',{exact:true}).inputValue(),'');
 await page.getByRole('button',{name:'말단 조건 추가 · entry-s1',exact:true}).click();
 assert.equal(await page.getByLabel('진입 결합 · entry-s1',{exact:true}).inputValue(),'');
}));

test('original prompt preserves real line breaks and renders markup as plain text',()=>withAuditPage(async page=>{
 await page.getByText('저장될 통합 원문 확인',{exact:true}).click();
 const panel=page.locator('details').filter({has:page.getByText('저장될 통합 원문 확인',{exact:true})});
 assert.equal(await panel.locator('pre').innerText(),'첫 번째 원문\n두 번째 원문 <script>문자 그대로</script>');
 assert.equal(await panel.locator('script').count(),0);
}));

test('history shows actual range and unready OR branches before return metrics without duplicating groups',()=>withAuditPage(async page=>{
 await page.getByRole('button',{name:/^#60 ·/}).click();
 const coverage=page.getByRole('region',{name:'실행 데이터 확인',exact:true});
 await coverage.waitFor();
 assert.match(await coverage.innerText(),/2025-11-21 ~ 2025-12-30/);
 assert.match(await coverage.innerText(),/2거래일/);
 assert.match(await coverage.innerText(),/개별 2/);
 assert.match(await coverage.innerText(),/준비 이력 부족/);
 assert.equal(await coverage.locator('li').count(),1);
 assert.match(await page.getByRole('heading',{name:/통합 전략 실행 #60/}).innerText(),/확인 필요/);
 const warning=await coverage.boundingBox(),metric=await page.getByText('계좌 수익률',{exact:true}).boundingBox();
 assert.ok(warning.y<metric.y);
}));
