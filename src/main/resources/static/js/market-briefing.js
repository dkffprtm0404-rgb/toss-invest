(function () {
  'use strict';
  const root = document.getElementById('marketBriefing');
  if (!root) return;
  const content = document.getElementById('marketBriefingContent');
  const dateLabel = document.getElementById('marketBriefingDate');
  const badge = document.getElementById('marketBriefingBadge');
  const status = document.getElementById('marketBriefingStatus');
  const refresh = document.getElementById('marketBriefingRefresh');
  const generate = document.getElementById('marketBriefingGenerate');
  const labels = { READY: '준비 완료', PARTIAL: '일부 자료 누락', FAILED: '생성 실패', NO_DATA: '자료 없음', CLOSED: '휴장일' };
  let busy = false, generating = false, retry = false;

  function node(tag, text, className) {
    const element = document.createElement(tag);
    if (text != null) element.textContent = String(text);
    if (className) element.className = className;
    return element;
  }
  function kstParts(value) {
    if (value == null || value === '') return null;
    const date = new Date(value);
    if (!Number.isFinite(date.getTime())) return null;
    return Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
      timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', hourCycle: 'h23'
    }).formatToParts(date).filter(part => part.type !== 'literal').map(part => [part.type, part.value]));
  }
  function kstDate(value) {
    const parts = kstParts(value);
    return parts ? `${parts.year}-${parts.month}-${parts.day}` : null;
  }
  function timestamp(value) {
    const parts = kstParts(value);
    return parts ? `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute} (한국시간)` : '시각 확인 불가';
  }
  function number(value) {
    if (value == null || String(value).trim() === '' || !Number.isFinite(Number(value))) return null;
    return Number(value);
  }
  function safeLink(source, text) {
    let url;
    try {
      url = new URL(source.url);
      if (url.protocol !== 'https:' || url.username || url.password) return node('span', text);
    } catch { return node('span', text); }
    const link = node('a', text);
    link.href = url.href;
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    return link;
  }
  function citation(source) {
    const reference = node('span', null, 'mb-citation');
    reference.append(safeLink(source, source.label || source.title || source.id || '출처'),
      node('span', ` · ${source.kind === 'NEWS' ? '기사 작성' : '시세 기준'} ${timestamp(source.observedAt)}`));
    return reference;
  }
  function sync() {
    refresh.disabled = busy;
    generate.disabled = busy;
    generate.textContent = generating ? '생성 중…' : retry ? '오늘 브리핑 다시 시도' : '오늘 브리핑 생성';
    root.setAttribute('aria-busy', String(busy));
  }
  function message(text, failed = false) {
    status.textContent = text;
    status.classList.toggle('mb-error', failed);
  }
  function empty() {
    retry = false;
    dateLabel.textContent = '아직 생성된 브리핑 없음';
    badge.textContent = '저장본 없음';
    badge.removeAttribute('data-status');
    content.replaceChildren(node('p', '저장된 브리핑이 없습니다.', 'mb-empty'));
    message('예약 시각 이후 오늘 브리핑을 생성하거나 저장본을 다시 조회할 수 있습니다.');
  }
  function render(data) {
    if (!data || !/^\d{4}-\d{2}-\d{2}$/.test(data.date) || !Object.hasOwn(labels, data.status))
      throw new Error('브리핑 응답 형식을 확인할 수 없습니다.');
    const today = kstDate(Date.now());
    dateLabel.textContent = data.date === today ? `${data.date} · 오늘` : `${data.date} 저장 브리핑 · 오늘 자료 아님`;
    badge.textContent = labels[data.status];
    badge.dataset.status = data.status;
    retry = data.status === 'FAILED' || data.status === 'NO_DATA';
    content.replaceChildren();
    const meta = node('div', null, 'mb-meta');
    for (const [label, value] of [['자료 기준', data.cutoffAt], ['수집', data.collectedAt], ['생성', data.generatedAt]]) {
      if (value) meta.append(node('span', `${label} ${timestamp(value)}`));
    }
    if (meta.childElementCount) content.append(meta);

    if (data.status === 'CLOSED') {
      content.append(node('p', '휴장일에는 브리핑을 생성하지 않습니다.', 'mb-empty'));
    } else {
      const sources = Array.isArray(data.sources) ? data.sources.filter(source => source && typeof source === 'object') : [];
      const byId = new Map(sources.map(source => [source.id, source]));
      if (sources.length) content.append(node('p', '브리핑 날짜와 시세·기사 기준일은 다를 수 있습니다. 확정 종가와 자료 기준 시각 이전의 뉴스를 사용합니다.', 'mb-empty'));
      const quotes = sources.filter(source => source.kind === 'QUOTE');
      if (quotes.length) {
        const strip = node('dl', null, 'mb-quotes');
        quotes.forEach(source => {
          const quote = node('div', null, 'mb-quote');
          const label = node('dt'); label.append(safeLink(source, source.label || source.title || source.id));
          const value = number(source.value), change = number(source.changeRate);
          const amount = value === null ? '자료 없음' : value.toLocaleString('ko-KR', { maximumFractionDigits: 4 });
          const result = node('dd', amount + (value !== null && source.unit ? ` ${source.unit}` : ''), 'mb-quote-value');
          if (change !== null) {
            const delta = node('span', change.toLocaleString('en-US', {
              style: 'percent', minimumFractionDigits: 2, maximumFractionDigits: 2, signDisplay: 'exceptZero'
            }), 'mb-change');
            delta.dataset.direction = change > 0 ? 'up' : change < 0 ? 'down' : 'flat';
            result.append(delta);
          }
          quote.append(label, result, node('dd', `시세 기준 ${timestamp(source.observedAt)}`, 'mb-observed'));
          strip.append(quote);
        });
        content.append(strip);
      }
      const items = Array.isArray(data.items) ? data.items.filter(item => item && typeof item === 'object') : [];
      if (items.length) {
        const list = node('ol', null, 'mb-items');
        items.forEach(item => {
          const row = node('li');
          row.append(node('h3', item.title || '시장 요약'), node('p', item.summary || '요약 내용이 없습니다.', 'mb-summary'));
          const refs = node('div', null, 'mb-references');
          const ids = Array.isArray(item.sourceIds) ? [...new Set(item.sourceIds)] : [];
          ids.forEach(id => refs.append(byId.has(id) ? citation(byId.get(id)) : node('span', `출처 ID 확인 필요: ${id}`, 'mb-citation')));
          if (!ids.length) refs.append(node('span', '연결된 출처 정보가 없습니다.', 'mb-citation'));
          row.append(refs); list.append(row);
        });
        content.append(list);
      } else {
        content.append(node('p', data.status === 'NO_DATA' ? '수집된 자료가 없어 요약을 제공하지 못했습니다.' : '제공할 AI 요약이 없습니다.', 'mb-empty'));
      }
      if (sources.length) {
        const details = node('details', null, 'mb-sources');
        details.append(node('summary', `전체 자료 출처 ${sources.length}개`));
        const list = node('ul');
        sources.forEach(source => {
          const item = node('li');
          item.append(safeLink(source, source.title || source.label || source.id || '출처'),
            node('span', ` · ${source.kind === 'NEWS' ? '기사 작성' : '시세 기준'} ${timestamp(source.observedAt)}`));
          list.append(item);
        });
        details.append(list); content.append(details);
      }
    }
    const warnings = Array.isArray(data.warnings) ? data.warnings.filter(warning => typeof warning === 'string' && warning.trim()) : [];
    if (warnings.length) {
      const warning = node('aside', null, 'mb-warnings'); warning.append(node('h3', '자료 확인'));
      const list = node('ul'); warnings.forEach(text => list.append(node('li', text)));
      warning.append(list); content.append(warning);
    }
    if (typeof data.error === 'string' && data.error) content.append(node('p', data.error, 'mb-error mb-failure'));
    const messages = {
      READY: '저장된 브리핑을 확인했습니다. 자료의 관측 시각을 함께 확인해 주세요.',
      PARTIAL: '일부 자료가 누락되었습니다. 아래 안내를 확인해 주세요.',
      FAILED: '요약 생성에 실패했습니다. 수집 자료와 오류를 확인하고 다시 시도하세요.',
      NO_DATA: '자료가 부족해 요약을 생성하지 못했습니다.', CLOSED: '거래 일정 확인 완료'
    };
    message(messages[data.status], data.status === 'FAILED');
  }

  async function load(create = false) {
    if (busy) return;
    busy = true; generating = create; sync();
    message(create ? '브리핑 생성 중… 최대 2분 정도 걸릴 수 있습니다.' : '저장된 브리핑을 불러오는 중…');
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 130000);
    try {
      const response = await fetch(`/api/market-briefings/${create ? 'today' : 'latest'}`, {
        method: create ? 'POST' : 'GET', signal: controller.signal,
        headers: create ? { 'X-Strategy-Local': '1' } : {}
      });
      if (!create && response.status === 404) { empty(); return; }
      let data;
      try { data = await response.json(); }
      catch { throw new Error(response.ok ? '브리핑 응답을 읽을 수 없습니다.' : `브리핑 요청에 실패했습니다. (HTTP ${response.status})`); }
      if (!response.ok) {
        const detail = typeof data?.message === 'string' ? data.message : typeof data?.error === 'string' ? data.error : '잠시 후 다시 시도해 주세요.';
        throw new Error(`브리핑 요청에 실패했습니다. (HTTP ${response.status}) · ${detail}`);
      }
      render(data);
    } catch (error) {
      retry = true;
      if (!content.hasChildNodes()) {
        dateLabel.textContent = '저장본 확인 실패';
        badge.textContent = '조회 실패';
        badge.removeAttribute('data-status');
      }
      message(error.name === 'AbortError' ? '브리핑 요청 시간이 초과되었습니다. 저장본을 조회하거나 다시 시도해 주세요.' : error.message || '브리핑을 불러오지 못했습니다.', true);
    } finally {
      clearTimeout(timeout); busy = false; generating = false; sync();
    }
  }
  function refreshWhenVisible() {
    if (document.visibilityState === 'visible' && !busy) load();
  }
  refresh.addEventListener('click', () => load());
  generate.addEventListener('click', () => load(true));
  setInterval(refreshWhenVisible, 60000);
  window.addEventListener('focus', refreshWhenVisible);
  document.addEventListener('visibilitychange', refreshWhenVisible);
  load();
})();
