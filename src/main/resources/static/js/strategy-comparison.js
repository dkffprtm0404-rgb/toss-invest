(function () {
  'use strict';
  const root = document.getElementById('strategyComparison');
  if (!root) return;
  const el = (tag, text, cls) => { const n=document.createElement(tag); if(text!=null)n.textContent=text; if(cls)n.className=cls; return n; };
  const button = (text, action) => { const b=el('button',text); b.type='button'; b.addEventListener('click',action); return b; };
  const typeOf = saved => saved.strategy.composition ? 'COMPOSITE' : saved.strategy.portfolio ? 'PORTFOLIO' : 'SINGLE';
  const types = {SINGLE:'단일 종목',PORTFOLIO:'포트폴리오',COMPOSITE:'조합 전략'};
  const statuses = {COMPLETED:'완료',NO_DATA:'데이터 없음',INSUFFICIENT_DATA:'준비 데이터 부족',NO_TRADES:'거래 없음',FAILED:'실패'};
  const number = value => value==null || !Number.isFinite(Number(value)) ? '—' : Number(value).toLocaleString('ko-KR',{maximumFractionDigits:2});
  const rate = (value,suffix='%') => value==null ? '—' : `${number(Number(value)*100)}${suffix}`;
  let selected=[],library=[],fieldId=0,resultRequest=0,historyRequest=0,historyPage=0,displayedId=null,running=false;
  root.classList.add('sc-comparison');
  root.append(el('h2','저장 전략 비교'),el('p','같은 유형의 서로 다른 저장 전략 2~6개를 공통 입력으로 실행합니다. 비교는 AI를 호출하지 않으며 별도 이력으로 저장됩니다.'));
  const feedback=el('p','저장된 전략과 버전을 선택한 뒤 비교에 추가하세요.','sc-feedback');feedback.setAttribute('role','status');
  const picks=el('div',null,'sc-picks'),form=el('div',null,'sc-fields'),accountFields=el('div',null,'sc-fields');
  const notify=(message,error=false)=>{feedback.textContent=message;feedback.classList.toggle('sc-error',error);};
  function field(parent,label,type='text',choices) {
    const wrap=el('label',label),input=el(choices?'select':type==='textarea'?'textarea':'input');input.id=`sc-field-${++fieldId}`;wrap.htmlFor=input.id;input.setAttribute('aria-label',label);
    if(choices){input.append(el('option','선택하세요'));input.firstChild.value='';choices.forEach(([value,text])=>{const option=el('option',text);option.value=value;input.append(option);});}
    else if(type!=='textarea'){input.type=type;if(type==='number')input.step='any';}
    wrap.append(input);parent.append(wrap);return input;
  }
  const symbol=field(form,'비교 종목 코드');symbol.maxLength=32;
  const start=field(form,'비교 시작일','date'),end=field(form,'비교 종료일','date');
  const mode=field(form,'비교 체결 방식','select',[['SAME_DAY_CLOSE','신호 당일 종가'],['NEXT_DAY_OPEN','신호 다음 거래일 시가']]);
  const capital=field(accountFields,'비교 초기자금 (원)','number'),commission=field(accountFields,'비교 수수료율 (%)','number'),tax=field(accountFields,'비교 매도세율 (%)','number'),slippage=field(accountFields,'비교 슬리피지율 (%)','number');
  accountFields.append(el('p','초기자금과 비용률을 직접 지정하세요. 비용을 적용하지 않을 때도 각각 0을 입력합니다.'));
  const universe=field(accountFields,'비교 시장·거래일 자료 (JSON)','textarea');universe.maxLength=1900000;
  universe.placeholder='{"source":"자료 출처와 범위","tradingDates":["2025-01-02"],"members":[{"symbol":"005930","market":"KOSPI","from":"2025-01-02","to":null}]}';
  const universeHint=el('p','시장 선정 전략이 하나라도 있으면 공통 시장 자료가 필요합니다. tradingDates는 준비 이력부터 종료일 7일 이후까지 포함하고, members에 종목의 시장과 편입 기간을 기록합니다.');accountFields.append(universeHint);
  const run=button('비교 실행 및 저장',execute),result=el('div',null,'sc-result'),history=el('div',null,'sc-history');
  const candidates=el('div',null,'sc-candidates'),requirement=el('p',null,'sc-caution');
  requirement.id='sc-run-requirement';requirement.setAttribute('aria-live','polite');run.setAttribute('aria-describedby',requirement.id);
  const more=button('비교 이력 20개 더 보기',()=>loadHistory(true));more.hidden=true;
  root.append(feedback,picks,candidates,form,accountFields,requirement,run,result,el('h3','저장된 비교 이력'),button('비교 이력 새로고침',()=>loadHistory(false)),history,more);
  function renderCandidates(){
    candidates.replaceChildren(el('h3','비교에 추가할 저장 전략'));
    candidates.append(el('p','불러온 전략 목록에서 같은 유형의 최신 버전을 바로 추가합니다. 과거 버전은 위 저장 전략 목록에서 선택하세요.'));
    const available=library.filter(s=>!selected.some(x=>x.id===s.id)&&(!selected.length||typeOf(s)===typeOf(selected[0])));
    if(!available.length)candidates.append(el('p',selected.length?'불러온 목록에 추가할 같은 유형의 전략이 없습니다. 다른 전략을 저장하거나 위 전략 목록을 새로고침·더 보기 하세요.':'위 전략 목록에서 저장된 전략을 불러오세요.'));
    available.forEach(s=>{const row=el('div',null,'sc-pick'),add=button(`${s.strategy.name||'이름 없음'} · #${s.id} / v${s.version} 비교에 추가`,()=>window.StrategyComparison.add(s));add.disabled=running||selected.length>=6;row.append(el('span',types[typeOf(s)]),add);candidates.append(row);});
  }
  function renderPicks(){
    picks.replaceChildren(el('p',`선택 ${selected.length}/6 · ${selected.length?types[typeOf(selected[0])]:'유형 미선택'}`));
    selected.forEach(s=>{const row=el('div',null,'sc-pick');row.append(el('span',`${s.strategy.name || '이름 없음'} · #${s.id} / v${s.version}`),button(`비교에서 #${s.id} 제거`,()=>{selected=selected.filter(x=>x.id!==s.id);renderPicks();}));picks.append(row);});
    const account=selected.length>0&&typeOf(selected[0])!=='SINGLE';accountFields.hidden=!account;
    symbol.parentElement.hidden=selected.length>0&&typeOf(selected[0])==='PORTFOLIO';
    const needsUniverse=selected.some(s=>s.strategy.portfolio||s.strategy.composition?.selection);
    universe.parentElement.hidden=!needsUniverse;universeHint.hidden=!needsUniverse;
    run.disabled=running || selected.length<2;
    requirement.textContent=running?'공통 데이터로 비교를 실행하고 있습니다.':selected.length===0?'비교하려면 같은 유형의 서로 다른 전략을 2개 이상 추가하세요.':selected.length===1?`${types[typeOf(selected[0])]} 1개를 더 추가해야 실행할 수 있습니다. 현재 1/6개 선택됨 · 자금과 비용 입력만으로는 실행되지 않습니다.`:`전략 ${selected.length}개 선택됨 · 아래 버튼으로 실행하기 전에 공통 종목·기간·체결 방식${account?'·자금·비용':''}을 확인하세요.`;
    renderCandidates();
  }
  window.StrategyComparison={setLibrary(entries,append=false){library=append?[...library.filter(s=>!entries.some(x=>x.id===s.id)),...structuredClone(entries)]:structuredClone(entries);renderCandidates();},add(saved){
    if(!saved?.strategy||!Number.isInteger(saved.id)||!Number.isInteger(saved.version)){notify('저장된 전략 버전을 선택하세요.',true);return;}
    if(selected.some(s=>s.id===saved.id)){notify('동일 전략은 버전이 달라도 중복 비교할 수 없습니다. 기존 선택을 제거한 뒤 추가하세요.',true);return;}
    if(selected.length&&typeOf(selected[0])!==typeOf(saved)){notify(`같은 유형의 전략만 비교할 수 있습니다. 현재 선택은 ${types[typeOf(selected[0])]}이고, 추가한 전략 #${saved.id}는 ${types[typeOf(saved)]}입니다.`,true);return;}
    if(selected.length>=6){notify('최대 6개 전략까지 비교할 수 있습니다.',true);return;}
    selected.push(structuredClone(saved));renderPicks();notify(`전략 #${saved.id} / v${saved.version}을 비교에 추가했습니다.`);
  }};
  async function api(path,method='GET',body){
    const response=await fetch(path,{method,headers:body?{'Content-Type':'application/json'}:undefined,body:body?JSON.stringify(body):undefined});
    if(response.status===204)return null;
    const data=await response.json();if(!response.ok)throw new Error(data.issues?.map(i=>`${i.path}: ${i.message}`).join(' / ') || data.message || data.error?.message || `요청 실패 (${response.status})`);return data;
  }
  function requestBody(){
    if(selected.length<2||selected.length>6)throw new Error('2~6개의 저장 전략을 선택하세요.');
    if(!start.value||!end.value||!mode.value)throw new Error('비교 시작일·종료일·체결 방식을 입력하세요.');
    if(start.value>end.value)throw new Error('비교 종료일은 시작일보다 빠를 수 없습니다.');
    const type=typeOf(selected[0]),body={strategies:selected.map(s=>({id:s.id,version:s.version})),symbol:type==='PORTFOLIO'?null:symbol.value.trim()||null,startDate:start.value,endDate:end.value,executionMode:mode.value,initialCapital:null,commissionRate:null,taxRate:null,slippageRate:null,universe:null};
    if(type!=='SINGLE'){
      if(!capital.value.trim()||[commission,tax,slippage].some(x=>!x.value.trim()))throw new Error('비교 초기자금과 비용률을 모두 입력하세요. 비용이 없으면 0을 입력하세요.');
      if(!(Number(capital.value)>0)||Number(capital.value)>1e12||[commission,tax,slippage].some(x=>!Number.isFinite(Number(x.value))||Number(x.value)<0||Number(x.value)>25))throw new Error('초기자금은 0 초과 1조 이하, 비용률은 0~25%로 입력하세요.');
      const cost=input=>window.StrategyWorkbenchPresentation.percentToRate(input.value);
      Object.assign(body,{initialCapital:Number(capital.value),commissionRate:cost(commission),taxRate:cost(tax),slippageRate:cost(slippage)});
      if(selected.some(s=>s.strategy.portfolio||s.strategy.composition?.selection)){
        try{body.universe=JSON.parse(universe.value);if(!body.universe||typeof body.universe!=='object'||Array.isArray(body.universe))throw new Error();}catch{throw new Error('비교 시장·거래일 자료를 올바른 JSON 객체로 입력하세요.');}
      }
    }
    const requiresSymbol=type==='SINGLE'||(type==='COMPOSITE'&&selected.some(s=>!s.strategy.composition.selection));
    if(requiresSymbol&&!body.symbol)throw new Error('공통 종목 코드를 입력하세요. 선정 기능 유무가 다른 조합도 공통 종목이 필요합니다.');
    if(body.symbol&&!/^[A-Za-z0-9]{1,32}$/.test(body.symbol))throw new Error('비교 종목 코드는 영문·숫자 1~32자로 입력하세요.');
    return body;
  }
  async function execute(){
    if(running)return;
    let body;try{body=requestBody();}catch(error){notify(error.message,true);return;}
    const token=++resultRequest;running=true;renderPicks();notify('공통 데이터로 비교 실행 중…');
    try{const item=await api('/api/comparisons','POST',body);if(token!==resultRequest)return;renderResult(item);notify(`비교 #${item.id}가 저장되었습니다. 각 전략의 상태를 확인하세요.`);await loadHistory(false);}
    catch(error){if(token===resultRequest)notify(error.message,true);}
    finally{running=false;renderPicks();}
  }
  async function loadHistory(append){
    const token=++historyRequest,page=append?historyPage+1:0;more.disabled=true;
    try{const rows=await api(`/api/comparisons?page=${page}&size=20`);if(token!==historyRequest)return;
      if(!append)history.replaceChildren();if(!rows.length&&!append)history.append(el('p','비교 이력이 없습니다.'));
      rows.forEach(item=>{const row=el('div',null,'sc-pick');row.append(button(`비교 #${item.id} 보기 · ${types[item.type] || item.type} · ${(item.strategyNames||[]).join(', ')} · ${item.createdAt||''}`,()=>openHistory(item.id)),button(`비교 #${item.id} 삭제`,()=>deleteHistory(item.id)));history.append(row);});historyPage=page;more.hidden=rows.length<20;
    }catch(error){if(token===historyRequest)notify(`비교 이력 조회 실패: ${error.message}`,true);}finally{if(token===historyRequest)more.disabled=false;}
  }
  async function openHistory(id){
    const token=++resultRequest;notify('저장된 비교를 불러오는 중…');
    try{const item=await api(`/api/comparisons/${id}`);if(token!==resultRequest)return;renderResult(item);notify('당시 저장된 조건·데이터·원본 결과를 표시합니다.');}
    catch(error){if(token===resultRequest)notify(error.message,true);}
  }
  async function deleteHistory(id){
    if(!window.confirm(`비교 #${id} 이력을 삭제합니다. 복구할 수 없습니다. 전략과 캔들은 보존됩니다. 삭제하시겠습니까?`))return;
    ++resultRequest;
    try{await api(`/api/comparisons/${id}`,'DELETE');if(displayedId===id){displayedId=null;result.replaceChildren();}await loadHistory(false);notify(`비교 #${id} 이력을 삭제했습니다.`);}catch(error){notify(error.message,true);}
  }
  function details(parent,title,value){const d=el('details');d.append(el('summary',title),el('pre',typeof value==='string'?value:JSON.stringify(value,null,2)));parent.append(d);}
  function metrics(parent,rows){const list=el('dl',null,'sc-metrics');rows.forEach(([label,value])=>{const cell=el('div');cell.append(el('dt',label),el('dd',value));list.append(cell);});parent.append(list);}
  function conditions(parent,strategy){
    const presentation=window.StrategyWorkbenchPresentation;
    if(presentation)presentation.snapshotRules(parent,strategy);
    else details(parent,'실행 당시 전략 조건',strategy);
    const selection=strategy.portfolio||strategy.composition?.selection;
    if(selection)parent.append(el('p',`시장 선정: ${selection.market} · ${selection.lookbackMonths}개월 수익률 · 상위 ${selection.topN}개 · SMA ${selection.smaPeriod}봉 · ${selection.selectionOrder==='FILTER_THEN_RANK'?'필터 후 순위':'순위 후 필터'}`));
    if(strategy.composition){
      const renderRule=rule=>{if(!rule)return '없음';if(rule.condition)return presentation?.ruleSummary(rule.condition)||rule.condition.type;return `(${(rule.children||[]).map(renderRule).join(rule.operator==='OR'?' 또는 ':' 그리고 ')})`;};
      parent.append(el('p',`조합 진입: ${renderRule(strategy.composition.entry)}`),el('p',`조합 청산: ${renderRule(strategy.composition.exit)}`),el('p',`최대 보유: ${strategy.composition.allocation?.maxPositions??'—'}종목 · 비중 조정: ${strategy.composition.allocation?.rebalance==='ENTRY_ONLY'?'진입 때 배정':strategy.composition.allocation?.rebalance||'—'}`));
    }
  }
  function renderResult(item){
    displayedId=item.id;result.replaceChildren();const s=item.snapshot,execution=s.execution,single=s.type==='SINGLE';
    result.append(el('h3',`비교 #${item.id}`),el('p',`${types[s.type]||s.type} · 요청 기간 ${execution.startDate} ~ ${execution.endDate} · ${execution.executionMode==='NEXT_DAY_OPEN'?'다음 거래일 시가':'당일 종가'} · 종목 ${execution.symbol||'시장 선정'}`),el('p',`공통 데이터 출처: ${execution.universe?.source || '로컬에 저장된 일봉'}`));
    result.append(el('p',single?'단일 종목: 청산 거래 수익률 합계·거래 낙폭(%p)·비연율화 샤프입니다. 계좌 수익률이 아니며 초기자금·수수료·세금·슬리피지는 반영하지 않습니다.':'계좌: 일별 평가액 기준 수익률·최대 낙폭입니다. 승률·샤프는 계산하지 않습니다.','sc-caution'));
    if(!single)result.append(el('p',`초기자금 ${number(execution.initialCapital)}원 · 수수료 ${rate(execution.commissionRate)} · 매도세 ${rate(execution.taxRate)} · 슬리피지 ${rate(execution.slippageRate)}`));
    chart(result,s.entries,execution,single);
    const cards=el('div',null,'sc-cards');
    s.entries.forEach(entry=>{
      const card=el('article',null,'sc-entry'),saved=entry.strategy,account=entry.account||entry.composite?.account,m=entry.metrics||{},failed=['FAILED','NO_DATA','INSUFFICIENT_DATA'].includes(entry.status);
      card.append(el('h4',`${saved.strategy.name||'이름 없음'} · #${saved.id} / v${saved.version}`),el('p',`상태: ${statuses[entry.status]||entry.status||'알 수 없음'}`));
      const dates=(account?.equity||[]).map(p=>p.date).sort(),first=entry.single?.actualStartDate||dates[0],last=entry.single?.actualEndDate||dates[dates.length-1];
      card.append(el('p',`실제 적용 기간: ${first&&last?`${first} ~ ${last}`:'없음'}`));
      if(entry.single?.openPosition)card.append(el('p','미청산 포지션이 있으며 위 청산 거래 통계에서 제외됩니다.'));
      if(entry.single?.pendingOrder||account?.pending?.length)card.append(el('p','기간 종료 시 미체결 주문이 있습니다. 원본 근거에서 확인하세요.'));
      if(account)card.append(el('p',`종료일 보유 종목: ${account.holdings?.length||0}개`));
      if(entry.error)card.append(el('p',`${entry.error.code||'실행 오류'}: ${entry.error.message||entry.error}`,'sc-error'));
      const val=key=>failed?null:m[key];
      metrics(card,[[single?'청산 거래 수익률 합계':'계좌 수익률',rate(val('returnRate'))],[single?'거래 최대 낙폭 (%p)':'일별 최대 낙폭',rate(val('maxDrawdown'),single?'%p':'%')],[single?'청산 거래 수':'매수·매도 체결 건수',number(val('tradeCount'))],['승률',single?rate(val('winRate')):'—'],['샤프 (비연율화)',single?number(val('sharpe')):'—'],['최종 평가액 (원)',single?'—':number(val('finalEquity'))]]);
      conditions(card,saved.strategy);details(card,'전략·체결·판정 원본 근거',entry);cards.append(card);
    });result.append(cards);details(result,'공통 실행 입력과 데이터 스냅샷', {execution:s.execution,data:s.data});
  }
  function chart(parent,entries,execution,single){
    const usable=entries.map(entry=>({...entry,points:['FAILED','NO_DATA','INSUFFICIENT_DATA'].includes(entry.status)?[]:(entry.curve||[]).filter(p=>p.value!=null&&Number.isFinite(Number(p.value))&&Number.isFinite(Date.parse(p.date)))}));
    if(!usable.some(e=>e.points.length)){parent.append(el('p','표시할 성과 곡선이 없습니다. 전략별 실행 상태를 확인하세요.'));return;}
    const ns='http://www.w3.org/2000/svg',svg=document.createElementNS(ns,'svg');svg.setAttribute('viewBox','0 0 800 280');svg.setAttribute('role','img');svg.setAttribute('aria-label','공통 날짜 기준 전략 성과 비교');
    const dates=usable.flatMap(e=>e.points.map(p=>Date.parse(p.date)));dates.push(Date.parse(execution.startDate),Date.parse(execution.endDate));const minDate=Math.min(...dates),maxDate=Math.max(...dates);
    const values=[0,...usable.flatMap(e=>e.points.map(p=>Number(p.value)))],low=Math.min(...values),high=Math.max(...values),span=high-low||0.01;
    const x=date=>65+(Date.parse(date)-minDate)/Math.max(86400000,maxDate-minDate)*700,y=value=>235-(Number(value)-low)/span*205;
    const shape=(name,attrs,text)=>{const n=document.createElementNS(ns,name);Object.entries(attrs).forEach(([k,v])=>n.setAttribute(k,String(v)));if(text!=null)n.textContent=text;svg.append(n);return n;};
    shape('line',{x1:65,x2:765,y1:y(0),y2:y(0),stroke:'#6b7280'});
    shape('text',{x:2,y:24},rate(high));shape('text',{x:2,y:235},rate(low));shape('text',{x:65,y:266},new Date(minDate).toISOString().slice(0,10));shape('text',{x:765,y:266,'text-anchor':'end'},new Date(maxDate).toISOString().slice(0,10));
    const colors=['#216eaf','#c85113','#6f42c1','#00866a','#bc366f','#776200'],legend=el('ul',null,'sc-legend');
    usable.forEach((entry,index)=>{const color=colors[index],points=[...entry.points].sort((a,b)=>a.date.localeCompare(b.date));if(points.length){shape('polyline',{points:points.map(p=>`${x(p.date)},${y(p.value)}`).join(' '),fill:'none',stroke:color,'stroke-width':2.5});if(points.length===1)shape('circle',{cx:x(points[0].date),cy:y(points[0].value),r:4,fill:color});}const label=el('li',`${entry.strategy.strategy.name} / v${entry.strategy.version}${points.length?'':' · 곡선 없음'}`);label.style.color=color;legend.append(label);});
    parent.append(el('p',single?'곡선: 청산 거래 누적 수익률 합계 (동일 날짜 축)':'곡선: 초기자금 대비 평가액 변화율 (동일 날짜 축)'),svg,legend);
  }
  renderPicks();loadHistory(false);
})();
