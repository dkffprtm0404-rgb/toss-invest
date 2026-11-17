const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const file = path.resolve(__dirname, '../../src/main/resources/static/js/strategy-workbench.js');
const workbench = fs.existsSync(file) ? require(file) : {};

test('draft selection retains independent edits and questions while clearing confirmation', () => {
  assert.equal(typeof workbench.DraftCollection, 'function');
  const collection = new workbench.DraftCollection();
  const batch = {items:[
    {title:'A', prompt:'A 원문', draft:{strategy:{name:'A',risk:{stopLoss:{rate:-0.05}}},ready:true,questions:[],unsupported:[]}},
    {title:'B', prompt:'B 원문', draft:{strategy:{name:'B'},ready:false,questions:['손절률?'],unsupported:[]}}
  ]};
  collection.accept(batch);
  const a = collection.select(0); a.state.draft.name = 'A 수정'; a.state.confirmed = true;
  const b = collection.select(1); b.clarifications = '-9%';
  b.state.accept({strategy:{name:'B',risk:{stopLoss:{rate:-0.09}}},ready:true,questions:[],unsupported:[]});
  const restored = collection.select(0);
  assert.equal(restored.state.draft.name, 'A 수정');
  assert.equal(restored.state.draft.risk.stopLoss.rate, -0.05);
  assert.equal(restored.state.confirmed, false);
  assert.equal(collection.select(1).clarifications, '-9%');
  assert.equal(batch.items[0].draft.strategy.name, 'A', 'editing a draft must not mutate the response snapshot');
  assert.throws(() => collection.select(99));
});

test('editing extended rules upgrades only the draft contract and invalidates confirmation', () => {
  const old = { schemaVersion: 1, name: '이전 전략', entry: { conditions: [{ type: 'VOLUME' }] } };
  const state = new workbench.DraftState();
  state.accept({ strategy: structuredClone(old), ready: true });
  state.draft.name = '이름 수정'; state.changed();
  assert.equal(state.draft.schemaVersion, 1);
  state.draft.entry.conditions.push({ type: 'RANGE_BREAKOUT' }); state.confirmed = true; state.changed();
  assert.equal(state.draft.schemaVersion, 2);
  assert.equal(state.confirmed, false);
  assert.equal(old.schemaVersion, 1);
  for (const key of ['atrStop', 'trailingStop']) {
    state.accept({ strategy: { ...structuredClone(old), risk: { [key]: {} } }, ready: false });
    state.changed(); assert.equal(state.draft.schemaVersion, 2);
  }
});

test('trend and range ratio conditions upgrade a legacy draft without filling missing values', () => {
  for (const type of ['MA_TREND', 'RANGE_RATIO']) {
    const state = new workbench.DraftState();
    state.accept({ strategy: { schemaVersion: 1, entry: { conditions: [{ type }] } }, ready: false });
    state.changed();
    assert.equal(state.draft.schemaVersion, 2);
    assert.deepEqual(state.draft.entry.conditions[0], { type });
    assert.equal(state.canSave(), false);
  }
});

test('MACD and Bollinger edits upgrade only the draft and keep explicit missing parameters', () => {
  for (const type of ['MACD', 'BOLLINGER']) {
    const original = { schemaVersion: 1, entry: { conditions: [{ type }] } };
    const state = new workbench.DraftState();
    state.accept({ strategy: structuredClone(original), ready: false });
    state.changed();
    assert.equal(state.draft.schemaVersion, 2);
    assert.deepEqual(state.draft.entry.conditions[0], { type });
    assert.equal(state.canSave(), false);
    assert.equal(original.schemaVersion, 1);
  }
});

test('saved combined prompts over 6000 characters reopen as two inputs without changing their text', () => {
  assert.equal(typeof workbench.splitSavedPrompt, 'function');
  const delimiter = '\n\n[보완 입력]\n';
  const prompt = '원'.repeat(6000);
  const clarifications = '보'.repeat(6000);
  const original = prompt + delimiter + clarifications;
  const result = workbench.splitSavedPrompt(original);
  assert.deepEqual(result, { prompt, clarifications });
  assert.equal(result.prompt + delimiter + result.clarifications, original);
});

test('saved prompt splitting uses the last delimiter and preserves unsplittable originals', () => {
  assert.equal(typeof workbench.splitSavedPrompt, 'function');
  const delimiter = '\n\n[보완 입력]\n';
  const prompt = '첫 원문' + delimiter + '이전 보완';
  assert.deepEqual(workbench.splitSavedPrompt(prompt + delimiter + '마지막 보완'), { prompt, clarifications: '마지막 보완' });
  const tooLong = '원'.repeat(6001) + delimiter + '보완';
  assert.deepEqual(workbench.splitSavedPrompt(tooLong), { prompt: tooLong, clarifications: '' });
  assert.deepEqual(workbench.splitSavedPrompt('일반 원문'), { prompt: '일반 원문', clarifications: '' });
  const literalDelimiter = '원문에 적은 구분자' + delimiter + '  ';
  assert.deepEqual(workbench.splitSavedPrompt(literalDelimiter), { prompt: literalDelimiter, clarifications: '' });
  assert.deepEqual(workbench.splitSavedPrompt(null), { prompt: '', clarifications: '' });
});

test('percent inputs retain missing values and convert signed fractional percentages', () => {
  assert.equal(typeof workbench.percentToRate, 'function');
  assert.equal(workbench.percentToRate(''), null);
  assert.equal(workbench.percentToRate('-5'), -0.05);
  assert.equal(workbench.percentToRate('0'), 0);
  assert.equal(workbench.percentToRate('0.29'), 0.0029);
  assert.equal(workbench.rateToPercent(-0.0575), '-5.75');
  assert.equal(workbench.rateToPercent(null), '');
});

test('editing or unresolved interpretation questions prevent confirmed saving', () => {
  assert.equal(typeof workbench.DraftState, 'function');
  const state = new workbench.DraftState();
  state.accept({ strategy: { name: 'example' }, ready: true, issues: [], questions: [], unsupported: [] });
  assert.equal(state.canSave(), false);
  state.confirmed = true;
  assert.equal(state.canSave(), true);
  state.promptDirty = true;
  state.validate({ ready: true, issues: [] });
  state.confirmed = true;
  assert.equal(state.canSave(), false, 'validating an old draft cannot confirm a changed natural-language prompt');
  state.promptDirty = false;
  state.changed();
  assert.equal(state.canSave(), false);
  assert.equal(state.confirmed, false);
  state.accept({ strategy: {}, ready: false, issues: [], questions: ['어느 기간?'], unsupported: [] });
  state.validate({ ready: true, issues: [] });
  state.confirmed = true;
  assert.equal(state.canSave(), false);
  state.accept({ strategy: {}, ready: false, issues: [], questions: [], unsupported: ['점수 필터'] });
  state.validate({ ready: true, issues: [] });
  state.confirmed = true;
  assert.equal(state.canSave(), false);
});

test('validation errors and busy state block saving even after confirmation', () => {
  assert.equal(typeof workbench.DraftState, 'function');
  const state = new workbench.DraftState();
  state.accept({ strategy: {}, ready: true, issues: [], questions: [], unsupported: [] });
  state.confirmed = true;
  state.busy = true;
  assert.equal(state.canSave(), false);
  state.busy = false;
  state.validate({ ready: false, issues: [{ path: 'entry', message: '누락' }] });
  assert.equal(state.canSave(), false);
});

test('readiness permits only reviewed READY or PARTIAL results for the current execution input', () => {
  for (const status of ['READY', 'PARTIAL', 'NO_DATA', 'INSUFFICIENT_DATA', 'INVALID_DATA']) {
    const state = new workbench.DataReadinessState();
    const token = state.begin('input-A');
    assert.equal(state.canRun('input-A'), false);
    state.resolve(token, 'input-A', { status });
    assert.equal(state.canRun('input-A'), false, 'the user must review the returned status');
    state.confirmed = true;
    assert.equal(state.canRun('input-A'), ['READY', 'PARTIAL'].includes(status));
    assert.equal(state.canRun('input-B'), false);
    state.invalidate();
    assert.equal(state.canRun('input-A'), false);
    assert.equal(state.data, null);
    assert.equal(state.confirmed, false);
  }
});

test('readiness ignores late success and failure, including an A to B to A input change', () => {
  const state = new workbench.DataReadinessState();
  const old = state.begin('A');
  state.invalidate();
  state.begin('B');
  state.invalidate();
  const current = state.begin('A');
  assert.equal(state.resolve(old, 'A', { status: 'READY' }), false);
  assert.equal(state.reject(old, 'A', '늦은 실패'), false);
  assert.equal(state.phase, 'loading');
  assert.equal(state.resolve(current, 'A', { status: 'PARTIAL' }), true);
  state.confirmed = true;
  assert.equal(state.canRun('A'), true);
  const retry = state.begin('A');
  assert.equal(state.canRun('A'), false, 'refresh clears the earlier permission immediately');
  state.reject(retry, 'A', '조회 실패');
  assert.equal(state.phase, 'error');
  assert.equal(state.data, null);
  assert.equal(state.canRun('A'), false);
});

test('readiness applies only to saved single-symbol schema 1 and 2 strategies', () => {
  for (const schemaVersion of [1, 2]) assert.equal(workbench.supportsReadiness({ strategy: { schemaVersion } }), true);
  for (const schemaVersion of [3, 4, null]) assert.equal(workbench.supportsReadiness({ strategy: { schemaVersion } }), false);
  assert.equal(workbench.supportsReadiness(null), false);
  assert.equal(workbench.supportsReadiness({ strategy: { schemaVersion: 2, composition: {} } }), false);
  assert.equal(workbench.supportsReadiness({ strategy: { schemaVersion: 1, portfolio: {} } }), false);
});

function portfolioPreviewInput(changes = {}, universeChanges = {}) {
  return {
    startDate:'2026-03-02', endDate:'2026-03-03', executionMode:'NEXT_DAY_OPEN',
    initialCapital:'1000000', commissionRate:'0', taxRate:'0', slippageRate:'0', symbol:'',
    universe:JSON.stringify({source:'합성 구조 검사 자료 · 기준일 2026-03-03',
      tradingDates:['2026-02-27','2026-03-02','2026-03-03','2026-03-11'],
      members:[{symbol:'005930',market:'KOSPI',from:'2020-01-01',to:null}], ...universeChanges}),
    ...changes
  };
}

test('Korean validation advice identifies fields and the actual period and loss boundaries', () => {
  const period = workbench.validationIssueText({path:'entry.conditions[0].shortPeriod',code:'INVALID',message:'Period must be between 1 and 500 bars.'}, {});
  assert.match(period, /단기 기간/); assert.match(period, /entry\.conditions\[0\]\.shortPeriod/); assert.match(period, /1~500/);
  assert.doesNotMatch(period, /Period must/);
  const loss = workbench.validationIssueText({path:'risk.stopLoss.rate',code:'INVALID',message:'invalid'}, {});
  assert.match(loss, /손절/); assert.match(loss, /-100% 초과, 0% 미만/); assert.match(loss, /-0\.05/);
  const rsi = {entry:{conditions:[{type:'RSI'}]}};
  assert.match(workbench.validationIssueText({path:'entry.conditions[0].threshold',code:'REQUIRED'}, rsi), /0~100/);
});

test('MACD signal comparison advice permits LINE and does not offer incompatible values', () => {
  const issue = {path:'entry.conditions[0].value',code:'REQUIRED'};
  const signal = workbench.validationIssueText(issue, {entry:{conditions:[{type:'MACD',reference:'SIGNAL'}]}});
  assert.match(signal, /LINE/); assert.doesNotMatch(signal, /HISTOGRAM|시그널선\(SIGNAL\)/);
  const threshold = workbench.validationIssueText(issue, {entry:{conditions:[{type:'MACD',reference:'THRESHOLD'}]}});
  assert.match(threshold, /LINE/); assert.match(threshold, /SIGNAL/); assert.match(threshold, /HISTOGRAM/);
});

test('valid portfolio preview preserves the supplied source, symbols and JSON without filling defaults', () => {
  const input = portfolioPreviewInput(), original = input.universe;
  const preview = workbench.portfolioInputPreview(input);
  assert.deepEqual(preview.issues, []);
  assert.deepEqual(preview.summary, {source:'합성 구조 검사 자료 · 기준일 2026-03-03',dates:4,symbols:1});
  assert.equal(input.universe, original); assert.equal(input.commissionRate, '0'); assert.equal(input.symbol, '');
});

test('portfolio preview points to missing source and malformed JSON instead of treating them as prepared data', () => {
  assert.ok(workbench.portfolioInputPreview(portfolioPreviewInput({}, {source:''})).issues.some(i => i.path === 'universe.source'));
  for (const universe of ['{"source":', '[]', 'null']) {
    const preview = workbench.portfolioInputPreview(portfolioPreviewInput({universe}));
    assert.ok(preview.issues.some(i => i.path === 'universe')); assert.equal(preview.summary, null);
  }
});

test('portfolio calendar preview rejects duplicates, reversed dates and an end plus seven boundary', () => {
  for (const tradingDates of [
    ['2026-02-27','2026-03-02','2026-03-02','2026-03-11'],
    ['2026-03-02','2026-02-27','2026-03-11'],
    ['2026-02-30','2026-03-11'],
    ['2026-02-27','2026-03-02','2026-03-10'],
    ['2026-03-02','2026-03-11']
  ]) {
    assert.ok(workbench.portfolioInputPreview(portfolioPreviewInput({}, {tradingDates})).issues.some(i => i.path.startsWith('universe.tradingDates')));
  }
  assert.deepEqual(workbench.portfolioInputPreview(portfolioPreviewInput()).issues, []);
});

test('portfolio member preview rejects numeric symbols, aggregate markets and inclusive overlapping intervals', () => {
  const valid = {symbol:'005930',market:'KOSPI',from:'2020-01-01',to:null};
  for (const [member,field] of [[{...valid,symbol:5930},'symbol'],[{...valid,market:'KOSPI_KOSDAQ'},'market'],
    [{...valid,from:'2026-02-30'},'from'],[{...valid,to:'2019-12-31'},'to']]) {
    assert.ok(workbench.portfolioInputPreview(portfolioPreviewInput({}, {members:[member]})).issues.some(i => i.path === `universe.members[0].${field}`));
  }
  const members = [{...valid,to:'2026-03-01'},{...valid,from:'2026-03-01'}];
  assert.ok(workbench.portfolioInputPreview(portfolioPreviewInput({}, {members})).issues.some(i => i.path === 'universe.members[1].from'));
  members[1].from = '2026-03-02';
  assert.deepEqual(workbench.portfolioInputPreview(portfolioPreviewInput({}, {members})).issues, []);
});

test('composition preview checks explicit symbol and cost inputs even without a universe', () => {
  const options = {needsUniverse:false,symbolRequired:true};
  const missing = workbench.portfolioInputPreview(portfolioPreviewInput({universe:'',commissionRate:'',taxRate:'-0.1',slippageRate:'25.1'}), options);
  for (const field of ['symbol','commissionRate','taxRate','slippageRate']) assert.ok(missing.issues.some(i => i.field === field));
  const valid = workbench.portfolioInputPreview(portfolioPreviewInput({symbol:'005930',universe:''}), options);
  assert.deepEqual(valid.issues, []); assert.equal(valid.summary, null);
});

test('portfolio execution date preview follows the ten year leap-day boundary', () => {
  const options = {needsUniverse:false};
  assert.deepEqual(workbench.portfolioInputPreview(portfolioPreviewInput({startDate:'2024-02-29',endDate:'2034-02-28'}), options).issues, []);
  for (const dates of [{startDate:'2024-02-29',endDate:'2034-03-01'}, {startDate:'2026-02-30',endDate:'2026-03-03'},
    {startDate:'2026-03-03',endDate:'2026-03-02'}]) {
    assert.ok(workbench.portfolioInputPreview(portfolioPreviewInput(dates), options).issues.some(i => ['startDate','endDate'].includes(i.field)));
  }
});
