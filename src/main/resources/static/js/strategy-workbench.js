(function () {
  'use strict';

  const assumptionLabels = {
    DAILY_LONG_ONLY_SINGLE_POSITION: '일봉 기준으로 매수 후 매도하는 전략이며, 한 번에 하나의 포지션만 보유합니다.',
    NO_CAPITAL_QUANTITY_OR_COST_MODEL: '투자 자금, 매매 수량, 수수료·세금·슬리피지는 계산에 반영하지 않습니다.',
    CLOSED_TRADE_SUM_NOT_COMPOUND_ACCOUNT_RETURN: '수익률 합계는 청산된 거래의 수익률을 단순히 더한 값이며, 계좌의 복리 수익률이 아닙니다.',
    TRADE_DRAWDOWN_NOT_DAILY_EQUITY_DRAWDOWN: '최대 낙폭은 거래별 수익률 기준이며, 일별 계좌 평가액 기준이 아닙니다.',
    TRADE_SHARPE_NOT_ANNUALIZED_ZERO_RISK_FREE: '샤프 비율은 거래별 수익률로 계산하며, 연율화하지 않고 무위험 수익률은 0으로 가정합니다.',
    OPEN_POSITION_EXCLUDED_FROM_CLOSED_METRICS: '아직 청산하지 않은 포지션은 청산 거래 통계에서 제외합니다.',
    HOLDING_BARS_NOT_CALENDAR_DAYS: '보유 기간은 달력상의 날짜 수가 아닌 데이터의 봉 수로 계산합니다.',
    AVAILABLE_HISTORY_USED_FOR_INDICATOR_WARMUP: '지표의 초기값 계산에는 확보된 과거 데이터를 사용합니다.',
    KST_DAILY_TIMESTAMPS_ASSUMED_OPEN_09_00_CLOSE_15_30: '일봉의 시가 시각은 한국 시간 09:00, 종가 시각은 15:30으로 가정합니다.',
    MISSING_MARKET_DAYS_NOT_INFERRED: '데이터가 없는 날짜의 거래일 여부는 추정하지 않습니다.',
    SIMPLE_RSI_FLAT_WINDOW_IS_100: '단순 RSI 계산 구간에서 가격 변화가 전혀 없으면 RSI를 100으로 처리합니다.',
    ZERO_VOLUME_BAR_CANNOT_FILL: '거래량이 0인 봉에서는 매매를 체결하지 않습니다.',
    SAME_CLOSE_FILL_IS_A_SIMULATION_ASSUMPTION: '신호가 발생한 당일 종가에 체결되는 것은 시뮬레이션상의 가정입니다.',
    NEXT_AVAILABLE_BAR_OPEN_WITHIN_REQUESTED_PERIOD: '요청한 기간 안에서 다음으로 존재하는 봉의 시가에 체결합니다.'
  };

  // Move the decimal point as text so 0.29% does not become 0.0029000000000000002.
  function shiftDecimal(value, places) {
    if (value == null || String(value).trim() === '') return '';
    const match = String(value).trim().match(/^([+-]?)(\d*)(?:\.(\d*))?(?:e([+-]?\d+))?$/i);
    if (!match || !(match[2] || match[3])) return '';
    const digits = (match[2] || '') + (match[3] || '');
    const point = (match[2] || '').length + Number(match[4] || 0) + places;
    if (Math.abs(point) > 400) return '';
    const expanded = point <= 0 ? '0.' + '0'.repeat(-point) + digits
      : point >= digits.length ? digits + '0'.repeat(point - digits.length)
        : digits.slice(0, point) + '.' + digits.slice(point);
    const parts = expanded.split('.');
    const whole = parts[0].replace(/^0+(?=\d)/, '') || '0';
    const fraction = (parts[1] || '').replace(/0+$/, '');
    return (match[1] === '-' ? '-' : '') + whole + (fraction ? '.' + fraction : '');
  }
  function percentToRate(value) {
    const shifted = shiftDecimal(value, -2);
    return shifted === '' || !Number.isFinite(Number(shifted)) ? null : Number(shifted);
  }
  function rateToPercent(value) { return shiftDecimal(value, 2); }
  function splitSavedPrompt(original) {
    const raw = original || '';
    const delimiter = '\n\n[보완 입력]\n';
    const index = raw.lastIndexOf(delimiter);
    if (index >= 0) {
      const prompt = raw.slice(0, index);
      const clarifications = raw.slice(index + delimiter.length);
      // Blank clarifications are omitted by the server; keep literal trailing delimiters in the original.
      if (prompt.trim() && clarifications.trim() && prompt.length <= 6000 && clarifications.length <= 6000) return { prompt, clarifications };
    }
    return { prompt: raw, clarifications: '' };
  }
  class DraftState {
    constructor() { this.draft = null; this.issues = []; this.questions = []; this.unsupported = []; this.busy = false; this.changed(); }
    changed() { this.validated = false; this.confirmed = false; }
    accept(result) {
      this.draft = result.strategy;
      this.questions = result.questions || [];
      this.unsupported = result.unsupported || [];
      this.promptDirty = false;
      this.validate(result);
    }
    validate(result) { this.issues = result.issues || []; this.validated = result.ready === true; this.confirmed = false; }
    canConfirm() { return !!this.draft && this.validated && !this.promptDirty && !this.issues.length && !this.questions.length && !this.unsupported.length; }
    canSave() { return this.canConfirm() && this.confirmed && !this.busy; }
  }
  if (typeof module !== 'undefined' && module.exports) module.exports = { percentToRate, rateToPercent, splitSavedPrompt, DraftState };
  if (typeof document === 'undefined') return;
  const root = document.getElementById('strategyWorkbench');
  if (!root) return;

  const state = new DraftState();
  let selected = null;
  let displayedRunId = null;
  let strategyPage = 0, versionPage = 0, runPage = 0;
  let assistantReady = false;
  let fieldId = 0;
  const node = (tag, text, cls) => {
    const item = document.createElement(tag);
    if (text != null) item.textContent = text;
    if (cls) item.className = cls;
    return item;
  };
  const button = (text, action, cls) => {
    const item = node('button', text, cls);
    item.type = 'button'; item.addEventListener('click', action); return item;
  };
  const status = node('p', '연결 상태 확인 중…', 'sw-muted'); status.setAttribute('role', 'status');
  const notice = node('p', '', 'sw-notice'); notice.setAttribute('role', 'status'); notice.setAttribute('aria-live', 'polite');
  const controls = node('fieldset', null, 'sw-controls');
  controls.append(node('legend', '전략 작성과 백테스트', 'sw-sr-only'));
  root.append(node('span', 'STRATEGY WORKBENCH', 'sw-eyebrow'), node('h2', '내 말로 만드는 투자 전략'),
    node('p', '자연어 입력 → 조건 확인·저장 → 백테스트 → 실행 이력', 'sw-intro'), status, notice, controls);
  function block(title) { const el = node('section', null, 'sw-block'); el.append(node('h3', title)); controls.append(el); return el; }
  function field(parent, labelText, value, onInput, options) {
    const wrap = node('label', null, 'sw-field');
    wrap.append(node('span', labelText));
    const input = node(options?.choices ? 'select' : options?.textarea ? 'textarea' : 'input');
    input.id = 'sw-field-' + (++fieldId);
    input.setAttribute('aria-label', labelText);
    if (options?.choices) {
      const empty = node('option', options.emptyLabel || '선택 필요'); empty.value = ''; input.append(empty);
      options.choices.forEach(([key, text]) => { const opt = node('option', text); opt.value = key; input.append(opt); });
    } else if (!options?.textarea) { input.type = options?.type || 'text'; if (input.type === 'number') input.step = options?.step || 'any'; }
    if (options?.maxLength) input.maxLength = options.maxLength;
    if (options?.placeholder) input.placeholder = options.placeholder;
    input.value = value == null ? '' : value;
    input.addEventListener(options?.choices ? 'change' : 'input', () => onInput(input.value));
    wrap.append(input); parent.append(wrap); return input;
  }
  function notify(message, error = false) { notice.textContent = message; notice.classList.toggle('sw-error', error); }
  async function api(url, method = 'GET', data) {
    const response = await fetch(url, { method, headers: method === 'GET' ? {} : { 'Content-Type': 'application/json', 'X-Strategy-Local': '1' },
      ...(data === undefined ? {} : { body: JSON.stringify(data) }) });
    if (response.status === 204) return null;
    let result;
    try { result = await response.json(); } catch { throw new Error('서버 응답을 읽지 못했습니다. 연결 상태를 확인해 주세요.'); }
    if (!response.ok) {
      const details = (result.issues || []).map(x => `${x.path}: ${x.message}`).join(' / ');
      throw new Error([result.code, result.message || '요청에 실패했습니다.', details].filter(Boolean).join(' · '));
    }
    return result;
  }
  async function operation(message, action) {
    if (state.busy) return;
    state.busy = true; controls.disabled = true; root.setAttribute('aria-busy', 'true'); notify(message); sync();
    try { await action(); }
    catch (error) { notify(error.message || '요청 중 오류가 발생했습니다.', true); }
    finally { state.busy = false; controls.disabled = false; root.setAttribute('aria-busy', 'false'); sync(); }
  }
  const write = block('1. 전략을 설명해 주세요');
  const prompt = field(write, '전략 원문', '', () => { state.promptDirty = true; changed(); }, { textarea: true, maxLength: 6000,
    placeholder: '사용할 이동평균 종류와 기간, 매수·매도 조건, 손절·익절 기준 등을 적어 주세요.' });
  const clarification = field(write, '보완 입력 · 질문의 답이나 미지원 조건 수정', '', () => { state.promptDirty = true; changed(); },
    { textarea: true, maxLength: 6000, placeholder: '보완 내용을 입력한 뒤 다시 해석하세요. 누락값은 자동으로 채우지 않습니다.' });
  const interpret = button('Codex로 조건 해석', () => operation('Codex가 조건을 정리하고 있습니다. 최대 2분 정도 걸릴 수 있습니다…', async () => {
    if (!prompt.value.trim()) throw new Error('전략 원문을 입력해 주세요.');
    state.changed(); sync();
    const result = await api('/api/strategy-assistant/interpret', 'POST', { prompt: prompt.value, clarifications: clarification.value || null });
    state.accept(result); renderEditor(); renderIssues(); notify(result.ready ? '조건을 검토하고 확인 체크 후 저장하세요.' : '누락값·보완 질문·미지원 조건을 확인해 주세요.');
  }), 'sw-primary');
  write.append(interpret, button('연결 상태 다시 확인', () => operation('연결 확인 중…', checkStatus)));
  write.append(node('p', '개인 PC의 Codex ChatGPT 구독 로그인을 사용합니다. API 과금으로 전환하지 않습니다.', 'sw-muted'));
  const edit = block('2. 조건을 확인하고 저장하세요');
  const issues = node('div', null, 'sw-issues'); issues.setAttribute('aria-live', 'polite');
  const editor = node('div');
  const validate = button('입력 조건 검증', () => operation('조건 검증 중…', async () => {
    state.changed(); sync();
    state.validate(await api('/api/strategy-assistant/validate', 'POST', state.draft));
    renderIssues(); notify(state.canConfirm() ? '검증 완료. 조건과 원문을 확인하고 체크하세요.' : '보완 사항을 해결한 뒤 다시 확인해 주세요.');
  }));
  const confirmLabel = node('label', null, 'sw-check');
  const confirm = node('input'); confirm.type = 'checkbox'; confirm.addEventListener('change', () => { state.confirmed = confirm.checked; sync(); });
  confirmLabel.append(confirm, node('span', '원문·조건·위험 관리 설정을 검토했으며 이 내용으로 저장합니다.'));
  const saveNew = button('새 전략으로 저장', () => save(false), 'sw-primary');
  const saveVersion = button('선택 전략의 새 버전 저장', () => save(true));
  edit.append(issues, editor, validate, confirmLabel, saveNew, saveVersion);
  function changed() { state.changed(); sync(); renderIssues(); }
  function sync() {
    interpret.disabled = !assistantReady || !prompt.value.trim() || prompt.value.length > 6000 || clarification.value.length > 6000 || state.busy;
    validate.disabled = !state.draft || state.busy;
    confirm.checked = state.confirmed;
    confirm.disabled = !state.canConfirm() || state.busy;
    saveNew.disabled = !state.canSave(); saveVersion.disabled = !state.canSave() || !selected;
    runButton.disabled = !selected || state.busy;
  }
  function renderIssues() {
    issues.replaceChildren();
    const messages = [
      ...state.issues.map(x => `${x.path} · ${x.message}`),
      ...state.questions.map(x => `보완 질문: ${x}`),
      ...state.unsupported.map(x => `미지원: ${x}`)
    ];
    if (state.promptDirty) messages.unshift('원문 또는 보완 입력이 변경되었습니다. Codex로 다시 해석해 주세요.');
    if (prompt.value.length > 6000 || clarification.value.length > 6000)
      messages.unshift('저장 원문을 입력 제한에 맞게 나누지 못했습니다. 원문은 보존됩니다. 다시 해석하려면 원문과 보완 입력을 각각 6,000자 이하로 정리해 주세요.');
    if (messages.length) {
      const list = node('ul'); messages.forEach(message => list.append(node('li', message))); issues.append(list);
    } else if (state.draft) issues.append(node('p', state.canConfirm() ? '검증 완료 · 명시적 확인 후 저장할 수 있습니다.' : '변경한 조건을 검증해 주세요.'));
  }
  const operators = [['AND', '모든 조건 충족 (AND)'], ['OR', '하나 이상 충족 (OR)']];
  const comparisons = [['GTE', '이상 (≥)'], ['LTE', '이하 (≤)'], ['CROSS_ABOVE', '위로 돌파'], ['CROSS_BELOW', '아래로 돌파']];
  const types = [['MA_CROSS', '이동평균 교차'], ['RSI', 'RSI'], ['VOLUME', '거래량']];
  function number(value) { return value.trim() === '' || !Number.isFinite(Number(value)) ? null : Number(value); }
  function editField(parent, label, obj, key, choices, percent = false) {
    field(parent, label, percent ? rateToPercent(obj[key]) : obj[key], value => {
      obj[key] = choices ? value || null : percent ? percentToRate(value) : number(value); changed();
    }, choices ? { choices } : { type: 'number' });
  }
  function renderGroup(key, title) {
    const section = node('div', null, 'sw-rule-group'); section.append(node('h4', title)); editor.append(section);
    const group = state.draft[key];
    if (!group) { section.append(node('p', key === 'entry' ? '진입 조건이 누락되었습니다.' : '별도 청산 조건 없음'), button('조건 그룹 추가', () => {
      state.draft[key] = { operator: null, conditions: [] }; changed(); renderEditor();
    })); return; }
    editField(section, '조건 결합', group, 'operator', operators);
    (group.conditions || []).forEach((condition, index) => {
      const row = node('div', null, 'sw-condition'); section.append(row);
      field(row, `조건 ${index + 1} 종류`, condition?.type, value => {
        group.conditions[index] = value ? { type: value } : null; changed(); renderEditor();
      }, { choices: types });
      if (condition?.type === 'MA_CROSS') {
        editField(row, '이동평균 종류', condition, 'averageType', [['SMA', '단순 이동평균 (SMA)'], ['EMA', '지수 이동평균 (EMA)']]);
        editField(row, '단기 기간 (봉)', condition, 'shortPeriod'); editField(row, '장기 기간 (봉)', condition, 'longPeriod');
        editField(row, '교차 방향', condition, 'direction', [['UP', '상향 교차'], ['DOWN', '하향 교차']]);
      } else if (condition?.type === 'RSI') {
        editField(row, 'RSI 계산 방식', condition, 'method', [['SIMPLE', '단순 평균 (SIMPLE)']]);
        editField(row, '기간 (봉)', condition, 'period'); editField(row, 'RSI 기준 (0~100)', condition, 'threshold');
        editField(row, '비교 방식', condition, 'comparison', comparisons);
      } else if (condition?.type === 'VOLUME') {
        editField(row, '평균 거래량 기간 (봉)', condition, 'period'); editField(row, '거래량 배수', condition, 'multiplier');
        editField(row, '비교 방식', condition, 'comparison', comparisons.slice(0, 2));
      }
      row.append(button(`조건 ${index + 1} 삭제`, () => { group.conditions.splice(index, 1); changed(); renderEditor(); }));
    });
    const add = button('조건 추가', () => { if (!group.conditions) group.conditions = []; group.conditions.push(null); changed(); renderEditor(); });
    add.disabled = (group.conditions || []).length >= 20; section.append(add);
    if (key === 'exit') section.append(button('청산 조건 그룹 제거', () => { state.draft.exit = null; changed(); renderEditor(); }));
  }
  function renderEditor() {
    editor.replaceChildren();
    if (!state.draft) { editor.append(node('p', '전략을 해석하거나 저장된 전략을 선택하면 편집할 수 있습니다.', 'sw-muted')); return; }
    const draft = state.draft;
    field(editor, '전략 이름', draft.name, value => { draft.name = value; changed(); }, { maxLength: 200 });
    editor.append(node('p', '빈칸은 미입력 값으로 유지합니다. 조건을 추가한 뒤 종류와 필수 항목을 직접 선택하세요.', 'sw-muted'));
    renderGroup('entry', '진입 조건'); renderGroup('exit', '청산 조건');
    const risk = node('div', null, 'sw-rule-group'); risk.append(node('h4', '위험 관리')); editor.append(risk);
    const risks = [['stopLoss', '손절 사용', 'rate', '손절 수익률 (%) · 음수, 예: -5', true],
      ['takeProfit', '익절 사용', 'rate', '익절 수익률 (%) · 양수, 예: 10', true],
      ['timeExit', '시간 청산 사용', 'days', '보유 봉 수 기준 · 이 수를 초과하면 청산', false]];
    risks.forEach(([key, title, prop, label, percent]) => {
      const line = node('div', null, 'sw-risk-row'); const toggle = node('input'); toggle.type = 'checkbox'; toggle.checked = draft.risk?.[key] != null;
      const labelEl = node('label', null, 'sw-check'); labelEl.append(toggle, node('span', title)); line.append(labelEl);
      toggle.addEventListener('change', () => { if (!draft.risk) draft.risk = {}; draft.risk[key] = toggle.checked ? { [prop]: null } : null; changed(); renderEditor(); });
      if (draft.risk?.[key]) editField(line, label, draft.risk[key], prop, null, percent);
      risk.append(line);
    });
    field(risk, '트레일링 정책 · 명시적으로 선택할 때만 적용', draft.risk?.trailing, value => {
      if (!draft.risk) draft.risk = {}; draft.risk.trailing = value || null; changed();
    }, { choices: [['LEGACY_STEP_3_PERCENT', '기존 3% 단계 트레일링 (LEGACY_STEP_3_PERCENT)']], emptyLabel: '사용 안 함' });
    const original = node('details'); original.append(node('summary', '저장될 원문 확인'), node('pre', draft.originalPrompt || '(원문 없음)', 'sw-prose')); editor.append(original);
  }
  async function save(update) {
    if (!state.canSave()) return;
    await operation('전략 저장 중…', async () => {
      const saved = update ? await api(`/api/strategies/${selected.id}`, 'PUT', { expectedVersion: selected.version, strategy: state.draft })
        : await api('/api/strategies', 'POST', state.draft);
      state.changed();
      await loadStrategies(false); await selectSaved(saved);
      notify(`저장 완료 · ${saved.strategy.name || '이름 없음'} / 버전 ${saved.version}. 아래에서 실행 조건을 선택하세요.`);
    });
  }
  const library = block('3. 저장된 전략과 버전');
  const strategyList = node('div', null, 'sw-list');
  const strategyMore = button('전략 20개 더 보기', () => operation('전략 불러오는 중…', async () => { await loadStrategies(true); notify('전략 목록을 불러왔습니다.'); }));
  const selection = node('p', '선택한 전략 없음', 'sw-selection');
  const versions = node('div', null, 'sw-list');
  const versionMore = button('버전 20개 더 보기', () => operation('버전 불러오는 중…', async () => { await loadVersions(true); notify('버전 목록을 불러왔습니다.'); })); versionMore.hidden = true;
  library.append(button('전략 목록 새로고침', () => operation('전략 불러오는 중…', async () => { await loadStrategies(false); notify('전략 목록을 새로고침했습니다.'); })), strategyList, strategyMore, selection, versions, versionMore);
  async function loadStrategies(more) {
    const page = more ? strategyPage + 1 : 0;
    const entries = await api(`/api/strategies?page=${page}&size=20`);
    if (!more) strategyList.replaceChildren();
    if (!entries.length && !more) strategyList.append(node('p', '저장된 전략이 없습니다. 위에서 첫 전략을 작성해 주세요.', 'sw-muted'));
    entries.forEach(saved => {
      const row = node('div', null, 'sw-list-row');
      row.append(button(`${saved.strategy.name || '이름 없음'} · #${saved.id} · 최신 v${saved.version}`,
        () => operation('전략과 이력 불러오는 중…', async () => { await selectSaved(await api(`/api/strategies/${saved.id}`)); notify('저장된 전략을 불러왔습니다. 수정한 내용은 검증 후 새 버전으로 저장하세요.'); })),
        button(`전략 #${saved.id} 삭제`, () => {
          if (state.busy || !window.confirm(`“${saved.strategy.name || '이름 없음'}” 전략 #${saved.id}의 모든 버전과 실행 이력을 삭제합니다. 복구할 수 없습니다. 삭제하시겠습니까?`)) return;
          operation('전략 삭제 중…', async () => {
            await api(`/api/strategies/${saved.id}`, 'DELETE');
            if (selected?.id === saved.id) {
              selected = null; displayedRunId = null;
              state.accept({ strategy: null, ready: false });
              prompt.value = ''; clarification.value = '';
              selection.textContent = '선택한 전략 없음';
              runTarget.textContent = '저장된 전략과 버전을 먼저 선택하세요.';
              versions.replaceChildren(); versionMore.hidden = true;
              runList.replaceChildren(); runMore.hidden = true; resultArea.replaceChildren();
              renderEditor(); renderIssues();
            }
            await loadStrategies(false);
            notify(`전략 #${saved.id}의 모든 버전과 실행 이력을 삭제했습니다.`);
          });
        }, 'sw-danger'));
      strategyList.append(row);
    });
    strategyPage = page; strategyMore.hidden = entries.length < 20;
  }
  async function selectSaved(saved) {
    selected = saved; displayedRunId = null; state.accept({ strategy: structuredClone(saved.strategy), ready: false });
    const restored = splitSavedPrompt(saved.strategy.originalPrompt);
    prompt.value = restored.prompt; clarification.value = restored.clarifications;
    selection.textContent = `선택: ${saved.strategy.name || '이름 없음'} · 전략 #${saved.id} / 버전 ${saved.version}`;
    runTarget.textContent = `실행 대상: 전략 #${saved.id}, 저장된 버전 ${saved.version} (편집 중인 초안은 실행되지 않습니다.)`;
    renderEditor(); renderIssues(); resultArea.replaceChildren(node('p', '실행하거나 아래 이력을 선택하면 결과가 표시됩니다.', 'sw-muted'));
    await loadVersions(false); await loadRuns(false);
  }
  async function loadVersions(more) {
    if (!selected) return;
    const page = more ? versionPage + 1 : 0;
    const entries = await api(`/api/strategies/${selected.id}/versions?page=${page}&size=20`);
    if (!more) versions.replaceChildren();
    entries.forEach(saved => versions.append(button(`v${saved.version} · ${dateTime(saved.versionCreatedAt)}`, () => operation('버전 불러오는 중…', async () => {
      await selectSaved(await api(`/api/strategies/${saved.id}/versions/${saved.version}`)); notify(`버전 ${saved.version}을 선택했습니다. 과거 버전으로 덮어쓰기는 최신 버전 충돌 검사로 보호됩니다.`);
    }))));
    versionPage = page; versionMore.hidden = entries.length < 20;
  }
  const run = block('4. 백테스트 실행');
  const runTarget = node('p', '저장된 전략과 버전을 먼저 선택하세요.', 'sw-selection');
  const runFields = node('div', null, 'sw-condition');
  const symbol = field(runFields, '종목 코드', '', () => {}, { placeholder: '예: 005930', maxLength: 32 });
  const start = field(runFields, '시작일', '', () => {}, { type: 'date' });
  const end = field(runFields, '종료일', '', () => {}, { type: 'date' });
  const mode = field(runFields, '체결 방식', '', () => {}, { choices: [['SAME_DAY_CLOSE', '신호 당일 종가'], ['NEXT_DAY_OPEN', '신호 다음 거래일 시가']] });
  const runButton = button('선택 버전 백테스트 실행', () => operation('저장된 버전으로 백테스트 실행 중…', async () => {
    if (!selected) throw new Error('저장된 전략을 선택하세요.');
    if (!/^[A-Za-z0-9]{1,32}$/.test(symbol.value.trim()) || !start.value || !end.value || !mode.value)
      throw new Error('종목 코드(영문·숫자), 시작일, 종료일, 체결 방식을 모두 입력하세요.');
    if (start.value > end.value) throw new Error('종료일은 시작일보다 빠를 수 없습니다.');
    const result = await api(`/api/strategies/${selected.id}/backtests`, 'POST', { version: selected.version, symbol: symbol.value.trim(), startDate: start.value, endDate: end.value, executionMode: mode.value });
    renderResult(result); await loadRuns(false); notify(`실행 #${result.id} 결과가 저장되었습니다. 상태: ${statusLabel(result.status)}`);
  }), 'sw-primary');
  run.append(runTarget, runFields, node('p', '저장한 조건과 로컬 일봉 데이터로 계산하며 AI 토큰을 사용하지 않습니다. 필요한 이력 데이터가 없으면 거래가 없거나 실행이 제한될 수 있습니다.', 'sw-muted'), runButton);
  const results = block('5. 결과와 실행 이력');
  results.append(node('p', '아래 수치는 청산된 거래 단위 통계입니다. 초기 자금·포지션 크기·수수료·세금·슬리피지를 적용하지 않아 계좌 수익률과 다릅니다.', 'sw-caution'));
  const resultArea = node('div');
  const runList = node('div', null, 'sw-list');
  const runMore = button('실행 이력 20개 더 보기', () => operation('이력 불러오는 중…', async () => { await loadRuns(true); notify('실행 이력을 불러왔습니다.'); })); runMore.hidden = true;
  results.append(resultArea, node('h4', '선택 전략의 전체 버전 실행 이력'), runList, runMore);
  async function loadRuns(more) {
    if (!selected) return;
    const page = more ? runPage + 1 : 0;
    const entries = await api(`/api/strategies/${selected.id}/backtests?page=${page}&size=20`);
    if (!more) runList.replaceChildren();
    if (!entries.length && !more) runList.append(node('p', '실행 이력이 없습니다.', 'sw-muted'));
    entries.forEach(item => {
      const row = node('div', null, 'sw-list-row');
      row.append(button(`#${item.id} · v${item.version} · ${statusLabel(item.status)} · ${dateTime(item.createdAt)}`,
        () => operation('저장된 실행 결과 불러오는 중…', async () => { renderResult(await api(`/api/backtest/runs/${item.id}`)); notify('당시 저장된 전략·데이터·결과 스냅샷을 표시합니다.'); })),
        button(`실행 #${item.id} 삭제`, () => {
          if (state.busy || !window.confirm(`실행 #${item.id}의 결과와 당시 데이터·조건 스냅샷을 삭제합니다. 저장된 전략은 유지됩니다. 복구할 수 없습니다. 삭제하시겠습니까?`)) return;
          operation('실행 이력 삭제 중…', async () => {
            await api(`/api/backtest/runs/${item.id}`, 'DELETE');
            if (displayedRunId === item.id) {
              displayedRunId = null;
              resultArea.replaceChildren(node('p', '조회 중이던 실행 이력을 삭제했습니다.', 'sw-muted'));
            }
            await loadRuns(false); notify(`실행 #${item.id} 이력을 삭제했습니다.`);
          });
        }, 'sw-danger'));
      runList.append(row);
    });
    runPage = page; runMore.hidden = entries.length < 20;
  }
  function dateTime(value) { return value == null ? '—' : new Date(value).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }); }
  function pct(value) { return value == null ? '—' : `${Number(rateToPercent(value)).toLocaleString('ko-KR', { maximumFractionDigits: 4 })}%`; }
  function statusLabel(value) { return ({ COMPLETED: '완료', NO_DATA: '데이터 없음', INSUFFICIENT_DATA: '준비 데이터 부족', NO_TRADES: '거래 없음', FAILED: '실패' })[value] || value || '상태 없음'; }
  function reasonLabel(value) {
    return ({ ENTRY_CONDITIONS: '진입 조건 충족', EXIT_CONDITIONS: '청산 조건 충족', STOP_LOSS: '손절', TAKE_PROFIT: '익절', TIME_EXIT: '보유 봉 수 초과', TRAILING_STOP: '트레일링 청산', NO_NEXT_BAR: '다음 봉 없음', BUY: '매수', SELL: '매도' })[value] || value || '—';
  }
  function ruleSummary(condition) {
    if (!condition) return '미입력 조건';
    const comparison = comparisons.find(([key]) => key === condition.comparison)?.[1] || '비교 미입력';
    if (condition.type === 'MA_CROSS') return `${condition.averageType || '종류 미입력'} ${condition.shortPeriod ?? '?'} / ${condition.longPeriod ?? '?'}봉 ${condition.direction === 'UP' ? '상향 교차' : condition.direction === 'DOWN' ? '하향 교차' : '방향 미입력'}`;
    if (condition.type === 'RSI') return `RSI ${condition.method || '?'} ${condition.period ?? '?'}봉 · ${condition.threshold ?? '?'} ${comparison}`;
    if (condition.type === 'VOLUME') return `거래량 ${condition.period ?? '?'}봉 평균 × ${condition.multiplier ?? '?'} ${comparison}`;
    return condition.type || '종류 미입력';
  }
  function snapshotRules(parent, strategy) {
    parent.append(node('h4', '실행 당시 전략 조건'));
    ['entry', 'exit'].forEach(key => {
      const group = strategy[key];
      parent.append(node('p', `${key === 'entry' ? '진입' : '청산'} · ${!group ? '없음' : group.operator === 'AND' ? '모두 충족' : group.operator === 'OR' ? '하나 이상 충족' : '단일 조건'}`));
      if (group) { const list = node('ul'); (group.conditions || []).forEach(c => list.append(node('li', ruleSummary(c)))); parent.append(list); }
    });
    const risk = strategy.risk;
    pairs(parent, [['손절', risk?.stopLoss ? pct(risk.stopLoss.rate) : '없음'], ['익절', risk?.takeProfit ? pct(risk.takeProfit.rate) : '없음'],
      ['시간 청산', risk?.timeExit ? `${risk.timeExit.days}봉 초과` : '없음'], ['트레일링', risk?.trailing === 'LEGACY_STEP_3_PERCENT' ? '기존 3% 단계 정책' : '없음']]);
    parent.append(node('pre', strategy.originalPrompt || '(원문 없음)', 'sw-prose'));
  }
  function pairs(parent, entries) {
    const dl = node('dl', null, 'sw-metrics'); entries.forEach(([label, value]) => { const div = node('div'); div.append(node('dt', label), node('dd', value == null ? '—' : String(value))); dl.append(div); }); parent.append(dl);
  }
  function details(parent, title, value) { const el = node('details'); el.append(node('summary', title), node('pre', JSON.stringify(value, null, 2), 'sw-prose')); parent.append(el); }
  function renderResult(item) {
    displayedRunId = item.id;
    resultArea.replaceChildren(); const snapshot = item.snapshot; const result = snapshot.result;
    resultArea.append(node('h4', `실행 #${item.id} · ${statusLabel(item.status)}`));
    pairs(resultArea, [['전략 / 버전', `${snapshot.strategy.strategy.name || '이름 없음'} / v${snapshot.strategy.version}`], ['종목', snapshot.execution.symbol],
      ['요청 기간', `${snapshot.execution.startDate} ~ ${snapshot.execution.endDate}`], ['체결 방식', snapshot.execution.executionMode === 'NEXT_DAY_OPEN' ? '다음 거래일 시가' : '당일 종가'], ['실행 시각 (한국)', dateTime(item.createdAt)]]);
    if (snapshot.error) resultArea.append(node('p', `${snapshot.error.code}: ${snapshot.error.message}`, 'sw-error'));
    if (result) {
      const m = result.metrics;
      if (m) pairs(resultArea, [['청산 거래 수', m.closedTrades], ['거래 승률', pct(m.winRate)], ['거래 평균 수익률', pct(m.averageTradeReturnRate)],
        ['거래 수익률 단순 합계', pct(m.sumTradeReturnRate)], ['거래 수익률 최대 낙폭', pct(m.tradeReturnMaxDrawdown)],
        ['거래 샤프 비율 (비연율화)', m.tradeSharpeRatio], ['평균 보유 봉 수', m.averageHoldingBars]]);
      pairs(resultArea, [['실제 데이터 기간', result.actualStartDate ? `${result.actualStartDate} ~ ${result.actualEndDate}` : '없음'],
        ['캔들 수', result.candleCount], ['준비 봉 수 / 필요 봉 수', `${result.warmupBars} / ${result.requiredWarmupBars}`]]);
      if (!(result.trades || []).length) resultArea.append(node('p', '청산된 거래가 없습니다. 미청산 포지션과 대기 주문을 별도로 확인하세요.', 'sw-muted'));
      else {
        const scroller = node('div', null, 'sw-table-scroll'); const table = node('table');
        const caption = node('caption', '청산 거래 내역 · 시각은 한국 시간'); table.append(caption);
        const header = node('tr'); ['진입 체결', '청산 체결', '진입가', '청산가', '보유 봉', '수익률', '진입 / 청산 사유'].forEach(label => { const th = node('th', label); th.scope = 'col'; header.append(th); });
        const thead = node('thead'); thead.append(header); table.append(thead); const tbody = node('tbody');
        result.trades.forEach(trade => { const row = node('tr'); [dateTime(trade.entry.executionTimestamp), dateTime(trade.exit.executionTimestamp), trade.entry.price, trade.exit.price, trade.holdingBars, pct(trade.returnRate), `${reasonLabel(trade.entry.reason)} / ${reasonLabel(trade.exit.reason)}`].forEach(value => row.append(node('td', value))); tbody.append(row); });
        table.append(tbody); scroller.append(table); resultArea.append(scroller);
        details(resultArea, '거래별 신호 시각과 조건 근거', result.trades);
      }
      if (result.openPosition) {
        const p = result.openPosition;
        resultArea.append(node('h4', '미청산 포지션 (청산 통계에서 제외)'));
        pairs(resultArea, [['진입 시각', dateTime(p.entry.executionTimestamp)], ['진입가', p.entry.price], ['평가가', p.valuationPrice], ['미실현 수익률', pct(p.unrealizedReturnRate)], ['보유 봉 수', p.holdingBars]]);
        details(resultArea, '미청산 진입 근거', p);
      }
      if (result.pendingOrder) { resultArea.append(node('p', `대기 주문: ${reasonLabel(result.pendingOrder.action)} · ${reasonLabel(result.pendingOrder.status)} · ${reasonLabel(result.pendingOrder.reason)}`)); details(resultArea, '대기 주문 근거', result.pendingOrder); }
      if (result.assumptions?.length) {
        resultArea.append(node('h4', '백테스트 계산 기준과 체결 가정'));
        const list = node('ul');
        result.assumptions.forEach(code => list.append(node('li', Object.hasOwn(assumptionLabels, code) ? assumptionLabels[code] : code)));
        resultArea.append(list);
      }
    }
    // Histories display the immutable snapshot; no re-run and no current-candle replacement.
    const snapshotDetails = node('details'); snapshotDetails.append(node('summary', '실행 당시 전략·데이터·비용 스냅샷'));
    snapshotRules(snapshotDetails, snapshot.strategy.strategy);
    pairs(snapshotDetails, [['엔진', snapshot.engineVersion], ['데이터 출처', snapshot.data.source], ['주기 / 시간대', `${snapshot.data.interval} / ${snapshot.data.timezone}`],
      ['저장 캔들 수', snapshot.data.candles.length], ['데이터 SHA-256', snapshot.data.sha256], ['비용 모델', snapshot.costs.model], ['자금 모델', snapshot.costs.capitalModel]]);
    details(snapshotDetails, '당시 전략 원문과 조건', snapshot.strategy); details(snapshotDetails, '당시 실행·비용 설정', { execution: snapshot.execution, costs: snapshot.costs });
    resultArea.append(snapshotDetails);
  }
  async function checkStatus() {
    assistantReady = false;
    try {
      const current = await api('/api/strategy-assistant/status'); assistantReady = current.ready === true;
      status.textContent = `${current.ready ? '연결 준비 완료' : '연결 확인 필요'} · ${current.message || current.code || ''}${current.model ? ' · ' + current.model : ''}`;
      notify(current.ready ? '전략을 입력하거나 저장된 전략을 선택하세요.' : current.message || 'Codex 로그인 상태를 확인해 주세요.', !current.ready);
    } catch (error) { status.textContent = 'Codex 연결 상태를 확인할 수 없습니다.'; throw error; }
  }
  renderEditor();
  operation('작업 화면 준비 중…', async () => {
    // Keep saved-strategy access available even if Codex is not installed or logged in.
    try { await checkStatus(); } catch (error) { notify(error.message, true); }
    try { await loadStrategies(false); } catch (error) { strategyList.replaceChildren(node('p', error.message, 'sw-error')); }
  });
})();
