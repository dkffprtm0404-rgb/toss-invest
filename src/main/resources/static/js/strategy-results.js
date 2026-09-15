(function () {
  'use strict';
  const mounts = new WeakMap();
  let sequence = 0;
  const node = (tag, text, cls) => {
    const el = document.createElement(tag);
    if (text != null) el.textContent = String(text);
    if (cls) el.className = cls;
    return el;
  };
  const number = value => value == null ? '—' : Number(value).toLocaleString('ko-KR', { maximumFractionDigits: 8 });
  const rate = value => value == null ? '—' : number(Number(value) * 100) + '%';
  const date = value => value == null ? '—' : new Date(value).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' });
  const reason = value => ({ ENTRY_CONDITIONS: '진입 조건', EXIT_CONDITIONS: '청산 조건', STOP_LOSS: '손절', ATR_STOP_LOSS: 'ATR 손절', TAKE_PROFIT: '익절', TIME_EXIT: '보유 봉 수 초과', TRAILING_STOP: '트레일링 청산', END_OF_DATA: '데이터 종료' })[value] || value || '—';
  function pairs(parent, entries) {
    const dl = node('dl', null, 'sw-metrics');
    entries.forEach(([label, value]) => { const entry = node('div'); entry.append(node('dt', label), node('dd', value ?? '—')); dl.append(entry); });
    parent.append(dl);
  }
  function fillEvidence(parent, label, fill) {
    parent.append(node('h5', label + ' · ' + reason(fill.reason)));
    pairs(parent, [['신호 시각 (한국)', date(fill.signalTimestamp)], ['체결 시각 (한국)', date(fill.executionTimestamp)],
      ['신호 봉 시각 (한국)', date(fill.signalBarTimestamp)], ['체결 봉 시각 (한국)', date(fill.executionBarTimestamp)], ['체결가', number(fill.price)]]);
    if (!fill.evidence?.length) parent.append(node('p', '저장된 조건 근거가 없습니다.', 'sw-muted'));
    (fill.evidence || []).forEach(evidence => {
      const item = node('section', null, 'sr-evidence');
      item.append(node('h6', `${evidence.type} · ${evidence.path}`));
      pairs(item, [['실제 값', number(evidence.actualValue)], ['기준 값', number(evidence.referenceValue)],
        ['이전 실제 값', number(evidence.previousActualValue)], ['이전 기준 값', number(evidence.previousReferenceValue)],
        ['조건 일치', evidence.matched ? '충족' : '미충족']]);
      parent.append(item);
    });
  }
  function mount(container, item) {
    mounts.get(container)?.abort();
    const controller = new AbortController(); mounts.set(container, controller);
    const root = node('section', null, 'sr-results'); root.dataset.runId = item.id;
    const instance = ++sequence;
    container.replaceChildren(root);
    // Each mount owns its nodes and signal. Late responses cannot write into a new run.
    const current = () => mounts.get(container) === controller && root.isConnected;
    const base = `/api/backtest/runs/${encodeURIComponent(item.id)}`;
    async function request(part, method = 'GET') {
      const response = await fetch(base + '/' + part, { method, signal: controller.signal,
        headers: method === 'POST' ? { 'X-Strategy-Local': '1' } : {} });
      let data;
      try { data = await response.json(); } catch { throw new Error('서버 응답을 읽지 못했습니다.'); }
      if (!response.ok) throw new Error([data.code, data.message || '요청에 실패했습니다.'].filter(Boolean).join(' · '));
      return data;
    }
    const charts = node('section', null, 'sr-charts');
    charts.append(node('h4', '실행 스냅샷 차트'), node('p', '분석을 불러오는 중…', 'sw-muted'));
    const aggregates = node('section', null, 'sr-aggregates');
    const explanation = node('section', null, 'sr-explanation');
    explanation.append(node('h4', 'AI 결과 설명'));
    const explanationStatus = node('p', '저장된 설명을 확인하는 중…', 'sw-muted'); explanationStatus.setAttribute('role', 'status');
    const explanationBody = node('div');
    const generate = node('button', 'AI 설명 생성'); generate.type = 'button'; generate.hidden = true;
    explanation.append(explanationStatus, generate, explanationBody);
    const evidence = node('section', null, 'sr-trades');
    evidence.append(node('h4', '거래별 신호 시각과 조건 근거'),
      node('p', '근거의 값은 저장된 지표 원값입니다. 수익률 조건의 -0.06은 -6%이며, 이전 값이 없으면 —로 표시합니다.', 'sw-muted'));
    root.append(charts, aggregates, explanation, evidence);
    const tradeDetails = new Map();
    (item.snapshot.result?.trades || []).forEach((trade, index) => {
      const num = index + 1;
      const detail = node('details', null, 'sr-trade'); detail.dataset.tradeNumber = num; detail.id = `sr-${instance}-trade-${num}`;
      detail.append(node('summary', `거래 #${num} · ${rate(trade.returnRate)} · ${reason(trade.exit.reason)}`));
      pairs(detail, [['보유 봉 수', trade.holdingBars], ['거래 수익률', rate(trade.returnRate)]]);
      fillEvidence(detail, '진입', trade.entry); fillEvidence(detail, '청산', trade.exit);
      evidence.append(detail); tradeDetails.set(num, detail);
    });
    if (!tradeDetails.size) evidence.append(node('p', '청산된 거래 근거가 없습니다. 미청산 포지션은 별도 결과에서 확인하세요.', 'sw-muted'));
    function showTrade(num) {
      const detail = tradeDetails.get(num);
      if (!detail) return;
      detail.open = true; detail.querySelector('summary').focus(); detail.scrollIntoView({ block: 'nearest' });
    }
    function references(parent, numbers) {
      const links = node('div', null, 'sr-references');
      (numbers || []).forEach(num => {
        if (!tradeDetails.has(num)) return;
        const link = node('button', `거래 #${num} 근거 보기`); link.type = 'button';
        link.setAttribute('aria-controls', tradeDetails.get(num).id);
        link.addEventListener('click', () => showTrade(num)); links.append(link);
      });
      parent.append(links);
    }
    function svgNode(tag, attrs, text) {
      const el = document.createElementNS('http://www.w3.org/2000/svg', tag);
      Object.entries(attrs || {}).forEach(([key, value]) => el.setAttribute(key, String(value)));
      if (text != null) el.textContent = text;
      return el;
    }
    function chart(title, description, points, markers, xLabel, yLabel, formatY) {
      const figure = node('figure', null, 'sr-chart'); figure.append(node('figcaption', title));
      const plot = svgNode('svg', { viewBox: '0 0 720 280', role: 'img', 'aria-label': title });
      plot.append(svgNode('title', {}, title), svgNode('desc', {}, description));
      const all = points.concat(markers);
      const xmin = Math.min(...all.map(p => p.x)), xmax = Math.max(...all.map(p => p.x));
      let ymin = Math.min(...all.map(p => p.y)), ymax = Math.max(...all.map(p => p.y));
      if (ymin === ymax) { ymin -= Math.max(Math.abs(ymin) * 0.05, 1); ymax += Math.max(Math.abs(ymax) * 0.05, 1); }
      const x = value => 84 + (value - xmin) / (xmax - xmin || 1) * 608;
      const y = value => 224 - (value - ymin) / (ymax - ymin) * 188;
      [0, 0.5, 1].forEach(ratio => {
        const value = ymin + ratio * (ymax - ymin), at = y(value);
        plot.append(svgNode('line', { x1: 84, x2: 692, y1: at, y2: at, class: 'sr-grid' }), svgNode('text', { x: 76, y: at + 4, 'text-anchor': 'end' }, formatY(value)));
      });
      plot.append(svgNode('text', { x: 84, y: 258 }, xLabel(xmin)), svgNode('text', { x: 692, y: 258, 'text-anchor': 'end' }, xLabel(xmax)),
        svgNode('text', { x: 84, y: 18 }, yLabel), svgNode('polyline', { points: points.map(p => `${x(p.x)},${y(p.y)}`).join(' '), class: 'sr-line' }));
      const tooltip = node('p', '점에 초점을 두거나 마우스를 올리면 시각과 값을 확인할 수 있습니다.', 'sr-tooltip'); tooltip.setAttribute('aria-live', 'polite');
      // Markers are keyboard controls; textual evidence is also accessible beneath every chart.
      points.concat(markers).forEach(point => {
        const attrs = { cx: x(point.x), cy: y(point.y), r: point.kind ? 6 : 4, tabindex: 0,
          'aria-label': point.text, class: `sr-point ${point.kind ? 'sr-' + point.kind : ''}` };
        if (point.tradeNumber > 0) { attrs['data-trade-number'] = point.tradeNumber; attrs.role = 'button'; }
        const dot = svgNode('circle', attrs); dot.append(svgNode('title', {}, point.text));
        ['focus', 'mouseenter'].forEach(event => dot.addEventListener(event, () => { tooltip.textContent = point.text; }));
        if (point.tradeNumber > 0) {
          dot.addEventListener('click', () => showTrade(point.tradeNumber));
          dot.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); showTrade(point.tradeNumber); } });
        }
        plot.append(dot);
      });
      figure.append(plot, node('p', description, 'sw-muted'), tooltip); charts.append(figure);
    }
    function renderAnalysis(data) {
      charts.replaceChildren(node('h4', '실행 스냅샷 차트'));
      charts.append(node('p', '청산 거래 수익률의 단순 합산입니다. 계좌의 복리 수익률이나 일별 평가액 곡선이 아닙니다. 비용·자금은 반영하지 않으며 샤프 비율은 비연율화입니다.', 'sw-caution'));
      if (data.prices?.length) {
        const points = data.prices.map(p => ({ x: p.timestamp, y: Number(p.close), text: `${new Date(p.timestamp).toLocaleDateString('ko-KR', { timeZone: 'Asia/Seoul' })} · 종가 ${number(p.close)}` }));
        const markers = (data.trades || []).flatMap(({ number: num, trade }) => ['entry', 'exit'].map(kind => ({
          x: trade[kind].executionBarTimestamp, y: Number(trade[kind].price), kind, tradeNumber: num,
          text: `거래 #${num} ${kind === 'entry' ? '진입' : '청산'} · ${date(trade[kind].executionTimestamp)} · 체결가 ${number(trade[kind].price)}`
        })));
        chart('종가와 체결점', '저장된 일봉 날짜에 맞춘 종가와 체결점입니다. 초록 점은 진입, 주황 점은 청산이며 정확한 체결 시각은 점의 설명에서 확인합니다. 거래 근거로 이동할 수 있고 미청산 포지션은 제외합니다.', points, markers,
          value => new Date(value).toLocaleDateString('ko-KR', { timeZone: 'Asia/Seoul' }), '가격 · 가로축 일봉 날짜 (한국)', number);
      } else charts.append(node('p', '차트에 표시할 데이터가 없습니다.', 'sw-muted'));
      if (data.curve?.length) {
        ['sumReturnRate', 'drawdown'].forEach(key => {
          const drawdown = key === 'drawdown';
          const title = drawdown ? '거래 수익률 낙폭' : '거래 수익률 누적 단순 합계';
          const formatter = value => number(value * 100) + (drawdown ? '%p' : '%');
          chart(title, drawdown ? '청산 거래 수익률 단순 합계의 이전 고점 대비 낙폭을 %p로 표시합니다. 일별 계좌 최대 낙폭이 아닙니다.' : '0번 시작점부터 청산 거래의 수익률을 차례로 더한 값입니다. 계좌 수익률이 아닙니다.',
            data.curve.map(p => ({ x: p.tradeNumber, y: Number(p[key]), tradeNumber: p.tradeNumber,
              text: `거래 #${p.tradeNumber} · ${date(p.timestamp)} · ${title} ${formatter(Number(p[key]))}` })), [], value => `거래 #${value}`, '가로축 청산 거래 번호', formatter);
        });
      }
      aggregates.replaceChildren();
      [['months', '한국 시간 청산월별 거래 집계'], ['exitReasons', '청산 사유별 거래 집계']].forEach(([key, title]) => {
        const section = node('section'); section.append(node('h4', title));
        if (key === 'months') section.append(node('p', '청산월에 속한 완료 거래 수익률 합계입니다. 일별 계좌 성과가 아닙니다.', 'sw-muted'));
        if (!data[key]?.length) section.append(node('p', '집계할 청산 거래가 없습니다.', 'sw-muted'));
        (data[key] || []).forEach(group => {
          const row = node('article', null, 'sr-group'); row.append(node('h5', key === 'months' ? group.key : reason(group.key)));
          pairs(row, [['청산 거래 수', group.closedTrades], ['승률', rate(group.winRate)], ['수익률 단순 합계', rate(group.sumReturnRate)]]);
          references(row, group.tradeNumbers); section.append(row);
        });
        aggregates.append(section);
      });
    }
    function renderExplanation(data) {
      explanationBody.replaceChildren();
      generate.hidden = data.status !== 'NOT_GENERATED'; generate.disabled = false; generate.textContent = 'AI 설명 생성';
      explanationStatus.className = 'sw-muted';
      if (data.status === 'NOT_APPLICABLE') explanationStatus.textContent = '청산된 거래가 없어 이 실행에는 AI 설명을 생성할 수 없습니다.';
      else if (data.status === 'READY') {
        explanationStatus.textContent = '저장된 AI 설명 · AI가 선택한 실행 근거를 서버에서 검증한 문장입니다.';
        (data.highlights || []).forEach(highlight => {
          const row = node('article', null, 'sr-highlight'); row.append(node('p', highlight.text));
          references(row, highlight.tradeNumbers); explanationBody.append(row);
        });
        const metadata = node('details'); metadata.append(node('summary', '설명 생성 정보'));
        pairs(metadata, [['생성 시각 (한국)', date(data.generatedAt)], ['모델', data.model], ['데이터 SHA-256', data.dataSha256]]);
        explanationBody.append(metadata);
      } else explanationStatus.textContent = '아직 생성된 설명이 없습니다. 버튼을 누르면 Codex로 생성하여 이 실행에 저장합니다.';
    }
    let generating = false;
    async function loadExplanation(method = 'GET') {
      if (generating) return;
      generating = true; generate.disabled = true;
      if (method === 'POST') { generate.textContent = '설명 생성 중…'; explanationStatus.textContent = 'Codex가 설명을 생성하는 중입니다. 다른 실행을 조회할 수 있습니다.'; }
      try { const data = await request('explanation', method); if (current()) renderExplanation(data); }
      catch (error) {
        if (!current() || error.name === 'AbortError') return;
        explanationStatus.textContent = error.message; explanationStatus.className = 'sw-error';
        generate.hidden = false; generate.textContent = 'AI 설명 다시 시도';
      } finally { generating = false; if (current()) generate.disabled = false; }
    }
    generate.addEventListener('click', () => loadExplanation('POST'));
    async function loadAnalysis() {
      try { const data = await request('analysis'); if (current()) renderAnalysis(data); }
      catch (error) {
        if (!current() || error.name === 'AbortError') return;
        charts.replaceChildren(node('h4', '실행 스냅샷 차트'), node('p', error.message, 'sw-error'));
        const retry = node('button', '분석 다시 조회'); retry.type = 'button';
        retry.addEventListener('click', () => { retry.disabled = true; loadAnalysis(); }); charts.append(retry);
      }
    }
    loadAnalysis(); loadExplanation();
  }
  window.StrategyResults = { mount };
})();
