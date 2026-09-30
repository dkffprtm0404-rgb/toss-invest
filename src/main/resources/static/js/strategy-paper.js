(() => {
  'use strict';
  const root = document.getElementById('strategyPaper');
  if (!root) return;
  let selected = null, busy = false, requestKey = null, requestSignature = null, listPage = 0;
  const statuses = {RUNNING:'실행 중', STOPPING:'청산 관리 중', STOPPED:'종료'};
  const dataStatuses = {WAITING_DATA:'확정 일봉 대기', READY:'처리 완료', INSUFFICIENT_DATA:'지표 준비 데이터 부족', INSUFFICIENT_CASH:'매수 자금 부족', INVALID_DATA:'일봉 데이터 오류', MARKET_REQUEST_REJECTED:'시세 요청 오류', MARKET_AUTH_FAILED:'시세 인증·권한 오류', MARKET_RATE_LIMITED:'시세 호출 한도 초과', MARKET_UNAVAILABLE:'시세 연결·서버 오류', MARKET_ERROR:'일봉 응답 처리 오류'};
  const reasons = {ENTRY_CONDITIONS:'진입 조건 충족', EXIT_CONDITIONS:'청산 조건 충족', STOP_LOSS:'손절', TAKE_PROFIT:'익절', TIME_EXIT:'보유기간 초과', TRAILING_STOP:'추적손절', ATR_STOP_LOSS:'ATR 손절'};
  const number = value => value == null ? '—' : Number(value).toLocaleString('ko-KR', {maximumFractionDigits:4});
  const percent = value => value == null ? '—' : `${number(Number(value) * 100)}%`;
  const time = value => value ? new Date(value).toLocaleString('ko-KR') : '—';
  function el(tag, text, cls) { const n = document.createElement(tag); if (text != null) n.textContent = text; if (cls) n.className = cls; return n; }
  function button(text, action) { const b = el('button', text); b.type = 'button'; b.addEventListener('click', () => operation(action)); return b; }
  function message(text, error = false) { notice.textContent = text; notice.classList.toggle('sp-error', error); }
  function dataMessage(state) { return [...(state.logs || [])].reverse().find(row => row.code === state.dataStatus)?.message || ''; }
  async function api(path, method = 'GET', body) {
    const response = await fetch(`/api/strategy-paper/runs${path}`, {method, headers: body ? {'Content-Type':'application/json'} : {}, body: body ? JSON.stringify(body) : undefined});
    const result = response.status === 204 ? null : await response.json();
    if (!response.ok) throw new Error(result?.message || `요청 실패 (${response.status})`);
    return result;
  }
  async function operation(action) {
    if (busy) return;
    busy = true; root.setAttribute('aria-busy', 'true');
    const controls = [...root.querySelectorAll('button,input,select')].map(n => [n, n.disabled]);
    controls.forEach(([n]) => { n.disabled = true; });
    try { await action(); } catch (error) { message(error.message || '요청을 처리하지 못했습니다.', true); }
    finally { controls.forEach(([n, disabled]) => { n.disabled = disabled; }); busy = false; root.removeAttribute('aria-busy'); }
  }
  const heading = el('h2', '저장 전략 모의매매');
  const notice = el('p', '저장 전략에서 실행할 버전을 선택해 주세요.', 'sp-notice'); notice.setAttribute('role', 'status');
  const target = el('p', '선택된 전략 없음', 'sp-target');
  const form = el('form', null, 'sp-form');
  const fields = el('div', null, 'sp-fields');
  function input(label, name, options = {}) {
    const wrapper = el('label', label); const n = el('input'); n.name = name; n.type = options.type || 'number';
    n.required = true;
    if (n.type === 'number') { n.step = 'any'; n.min = options.positive ? '0.00000001' : '0'; }
    if (options.max != null) n.max = options.max;
    if (options.pattern) n.pattern = options.pattern;
    wrapper.append(n); fields.append(wrapper); return n;
  }
  const symbol = input('종목 코드', 'symbol', {type:'text', pattern:'[0-9]{6}'}); symbol.maxLength = 6; symbol.placeholder = '예: 005930';
  const modeLabel = el('label', '체결 방식'); const mode = el('select'); mode.required = true; mode.setAttribute('aria-label', '체결 방식');
  for (const [value, label] of [['','선택해 주세요'], ['SAME_DAY_CLOSE','당일 종가 (가상 체결)'], ['NEXT_DAY_OPEN','다음 일봉 시가 (확정 후 반영)']]) { const option = el('option', label); option.value = value; mode.append(option); }
  modeLabel.append(mode); fields.append(modeLabel);
  const capital = input('초기자금 (원)', 'initialCapital', {positive:true, max:1000000000000});
  const allocation = input('매수 비중 (%)', 'allocationRate', {positive:true, max:100});
  const commission = input('수수료율 (%)', 'commissionRate', {max:25});
  const tax = input('매도세율 (%)', 'taxRate', {max:25});
  const slippage = input('슬리피지율 (%)', 'slippageRate', {max:25});
  [allocation, commission, tax, slippage].forEach(n => { n.step = '0.00000001'; });
  const startButton = el('button', '모의매매 시작'); startButton.type = 'submit';
  form.append(target, fields, el('p', '비용을 적용하지 않으려면 0을 입력하세요. 매수 비중은 진입 시 가용 현금에 적용합니다.', 'sp-note'), startButton);
  form.addEventListener('submit', event => {
    event.preventDefault();
    if (!selected) { message('저장된 단일 종목 전략과 버전을 먼저 선택해 주세요.', true); return; }
    // Capture values before disabling form controls.
    const body = {strategyId:selected.id, version:selected.version, symbol:symbol.value.trim(), executionMode:mode.value,
      initialCapital:Number(capital.value), allocationRate:Number((Number(allocation.value)/100).toFixed(10)), commissionRate:Number((Number(commission.value)/100).toFixed(10)),
      taxRate:Number((Number(tax.value)/100).toFixed(10)), slippageRate:Number((Number(slippage.value)/100).toFixed(10))};
    const signature = JSON.stringify(body);
    if (signature !== requestSignature) { requestKey = [...crypto.getRandomValues(new Uint8Array(16))].map(x => x.toString(16).padStart(2, '0')).join(''); requestSignature = signature; }
    body.requestId = requestKey;
    operation(async () => { const d = await api('', 'POST', body); render(d); requestSignature = null; await list(false); message(`모의매매 #${d.id}를 시작했습니다. 첫 판단 가능일: ${d.state.firstTradingDate}`); });
  });
  const listArea = el('div', null, 'sp-list');
  const detailArea = el('div', null, 'sp-detail');
  const filters = el('div', null, 'sp-filters');
  const filterLabel = el('label', '실행 상태'); const filter = el('select'); filter.setAttribute('aria-label', '실행 상태');
  for (const [value,label] of [['','전체'], ...Object.entries(statuses)]) {const option=el('option',label);option.value=value;filter.append(option);}
  filterLabel.append(filter); filters.append(filterLabel, button('실행 목록 새로고침', () => list(false)));
  const more = button('실행 20개 더 보기', () => list(true)); more.hidden = true;
  root.classList.add('sp-panel');
  root.append(heading, el('p', '국내 주식 한 종목을 저장한 조건으로 모의매매합니다. 오늘 이전의 확정 일봉을 사용하므로 체결 기준일보다 하루 이상 늦게 반영될 수 있습니다. 실제 주문은 보내지 않습니다.', 'sp-note'), notice, form, el('h3','실행 이력'), filters, listArea, more, detailArea);
  async function list(append) {
    const page = append ? listPage + 1 : 0;
    const entries = await api(`?page=${page}&size=20${filter.value ? `&status=${filter.value}` : ''}`);
    if (!append) listArea.replaceChildren();
    if (!entries.length && !append) listArea.append(el('p','저장된 모의매매 실행이 없습니다.'));
    entries.forEach(d => listArea.append(button(`#${d.id} · ${d.strategy.strategy.name} v${d.strategy.version} · ${d.definition.symbol} · ${statuses[d.status] || d.status}`, async () => render(await api(`/${d.id}`)))));
    listPage = page; more.hidden = entries.length < 20;
  }
  function table(parent, headers, rows) {
    const wrap=el('div',null,'sp-table-wrap'),t=el('table'),thead=el('thead'),tr=el('tr'),tbody=el('tbody');
    headers.forEach(h=>tr.append(el('th',h)));thead.append(tr);
    rows.forEach(row=>{const line=el('tr');row.forEach(value=>line.append(el('td',value ?? '—')));tbody.append(line);});
    t.append(thead,tbody);wrap.append(t);parent.append(wrap);
    if(!rows.length)parent.append(el('p','기록이 없습니다.','sp-note'));
  }
  function metrics(parent, pairs) { const dl=el('dl',null,'sp-metrics');pairs.forEach(([name,value])=>{const group=el('div');group.append(el('dt',name),el('dd',value));dl.append(group);});parent.append(dl); }
  function render(d) {
    const s=d.state, config=d.definition;
    detailArea.replaceChildren(el('h3',`모의매매 #${d.id}`),el('p',`${d.strategy.strategy.name} · 저장 버전 ${d.strategy.version} · ${config.symbol} (${d.market})`),el('p', statuses[d.status] || d.status,'sp-status'));
    detailArea.append(el('p',`${dataStatuses[s.dataStatus] || s.dataStatus} · 첫 판단 가능일 ${s.firstTradingDate} · 마지막 처리 일봉 ${s.lastProcessedDate || '없음'} · 마지막 데이터 수신 ${time(s.lastObservedAt)}`,'sp-note'));
    const explanation=dataMessage(s);
    if(explanation)detailArea.append(el('p',explanation,'sp-data-message'));
    metrics(detailArea,[['평가자산 (원)',number(s.equity)],['현금 (원)',number(s.cash)],['계좌 수익률',percent(s.returnRate)],['실현손익 (원)',number(s.realizedPnl)],['미실현손익 (원)',number(s.unrealizedPnl)],['누적 수수료·세금 (원)',number(s.totalCosts)],['최대 낙폭',percent(s.maxDrawdown)]]);
    detailArea.append(el('p',`초기자금 ${number(config.initialCapital)}원 · 매수 비중 ${percent(config.allocationRate)} · 수수료 ${percent(config.commissionRate)} · 매도세 ${percent(config.taxRate)} · 슬리피지 ${percent(config.slippageRate)} · ${config.executionMode === 'NEXT_DAY_OPEN' ? '다음 일봉 시가' : '당일 종가'}`, 'sp-note'));
    const original=el('details');original.append(el('summary','실행 당시 전략 조건'));original.append(el('pre',JSON.stringify(d.strategy.strategy,null,2)));detailArea.append(original);
    const source=el('details');source.append(el('summary',`보존한 일봉 ${d.storedBarCount || 0}개 · 데이터 출처`),el('p','토스 API 수정주가 일봉 · 한국 날짜 기준 오늘 이전 봉만 처리'),el('pre',`SHA-256: ${s.dataSha256 || '수신 전'}`));detailArea.append(source);
    detailArea.append(el('h4','보유 포지션'));
    table(detailArea,['수량','매수가 (원)','매수 비용 포함 원가 (원)','보유 중 최고가 (원)'],s.position?[[number(s.position.quantity),number(s.position.entryPrice),number(s.position.costBasis),number(s.position.peakPrice)]]:[]);
    detailArea.append(el('p',s.pending?`대기 주문: ${s.pending.side==='BUY'?'매수':'매도'} · 신호일 ${s.pending.signalDate} · ${reasons[s.pending.reason]||s.pending.reason}`:'대기 주문 없음'));
    const controls=el('div',null,'sp-actions');
    if(d.status!=='STOPPED')controls.append(button('일봉 갱신 및 처리',async()=>{const next=await api(`/${d.id}/refresh`,'POST');render(next);await list(false);message([dataStatuses[next.state.dataStatus],dataMessage(next.state)].filter(Boolean).join(' · ') || '갱신했습니다.',next.state.dataStatus.startsWith('MARKET_') || next.state.dataStatus==='INVALID_DATA');}));
    if(d.status==='RUNNING')controls.append(button('신규 매수 중지',async()=>{
      if(!window.confirm('대기 매수와 신규 매수를 중단합니다. 보유 종목은 기존 청산 조건으로 관리합니다. 청산 조건이 없는 전략은 보유 상태가 계속될 수 있습니다. 진행할까요?'))return;
      render(await api(`/${d.id}/stop`,'POST'));await list(false);message('중지 요청을 반영했습니다.');
    }));
    if(d.status==='STOPPED')controls.append(button('종료 이력 삭제',async()=>{if(!window.confirm('이 모의매매 계좌와 거래 이력을 삭제할까요? 저장 전략은 유지됩니다.'))return;await api(`/${d.id}`,'DELETE');detailArea.replaceChildren();await list(false);message('종료 이력을 삭제했습니다.');}));
    detailArea.append(controls);
    history(d.id,'trades','거래 내역',['신호일','체결 기준일','반영 시각','구분','수량','가격','수수료','세금','사유·근거'],row=>[row.signalDate,row.executionDate,time(row.processedAt),row.side==='BUY'?'매수':'매도',number(row.quantity),number(row.price),number(row.fee),number(row.tax),`${reasons[row.reason]||row.reason} ${(row.evidence||[]).map(e=>`${e.path}: ${number(e.actualValue)} / 기준 ${number(e.referenceValue)}`).join('; ')}`]);
    history(d.id,'equity','일별 평가자산',['일자','평가액','현금','미실현손익','수익률','낙폭'],row=>[row.date,number(row.value),number(row.cash),number(row.unrealizedPnl),percent(row.returnRate),percent(row.drawdown)]);
    history(d.id,'logs','실행 로그',['기록 시각','일봉 날짜','상태','내용'],row=>[time(row.at),row.barDate,dataStatuses[row.code]||statuses[row.code]||row.code,row.message]);
    function history(id,type,title,headers,values) {
      const section=el('section'),body=el('div');let page=0;
      const load=async more=>{const next=more?page+1:0;const rows=await api(`/${id}/${type}?page=${next}&size=20`);if(!more)body.replaceChildren();table(body,headers,rows.map(values));page=next;loadMore.hidden=rows.length<20;};
      const loadMore=button(`${title} 20개 더 보기`,()=>load(true));loadMore.hidden=true;
      section.append(el('h4',title),button(`${title} 조회`,()=>load(false)),body,loadMore);detailArea.append(section);
      // Initial preview comes from the same checkpoint as the account figures.
      const initial=type==='trades'?s.trades:type==='logs'?s.logs:s.curve;
      table(body,headers,[...(initial||[])].slice(-20).reverse().map(values));
      loadMore.hidden=(type==='trades'?d.tradeCount:type==='logs'?d.logCount:d.equityCount)<=20;
    }
  }
  window.StrategyPaper = {select(saved) {
    if (saved.strategy.portfolio || saved.strategy.composition || saved.strategy.schemaVersion > 2) {message('현재 단일 종목 전략(형식 1·2)만 지원합니다.',true);return;}
    selected=structuredClone(saved);requestSignature=null;
    target.textContent=`실행 대상: ${saved.strategy.name || '이름 없음'} · 전략 #${saved.id} / 저장 버전 ${saved.version}`;
    document.getElementById('accountTab')?.click();root.scrollIntoView({behavior:'smooth',block:'start'});
    message('실행 종목·자금·비용을 입력해 주세요. 편집 중인 초안은 반영되지 않습니다.');
  }};
  operation(()=>list(false));
})();
