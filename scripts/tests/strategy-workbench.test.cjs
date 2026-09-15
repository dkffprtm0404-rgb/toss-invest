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
