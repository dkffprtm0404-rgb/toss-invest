const test = require('node:test');
const assert = require('node:assert/strict');
const {DraftState, DraftCollection} = require('../../src/main/resources/static/js/strategy-workbench.js');
const workbench = require('../../src/main/resources/static/js/strategy-workbench.js');
const item = n => ({title:`개별 ${n}`,prompt:`원문 ${n}`,draft:{strategy:{schemaVersion:2,name:`개별 ${n}`},ready:true}});

test('six individual drafts and total remain independent; a single item has no duplicate total', () => {
  const collection = new DraftCollection();
  const items = Array.from({length:6}, (_,i)=>item(i+1));
  const total = {title:'전체 토탈 · 6개',prompt:'전체',draft:{strategy:{schemaVersion:4,name:'전체 토탈',composition:{sources:items.map((x,i)=>({id:`s${i}`,definition:x.draft.strategy}))}},ready:false}};
  collection.accept({items,total});
  assert.equal(collection.items.length,7);
  assert.equal(collection.items[6].kind,'total');
  collection.select(0).state.draft.name='수정';
  assert.equal(collection.select(6).state.draft.composition.sources[0].definition.name,'개별 1');
  collection.accept({items:[item(1)]});
  assert.equal(collection.items.length,1);
});

test('composition edits invalidate common confirmations and validation does not silently restore them', () => {
  const state = new DraftState();
  state.accept({strategy:{schemaVersion:4,composition:{riskConfirmed:true,exitConfirmed:true}},ready:true});
  state.changed();
  assert.equal(state.draft.composition.riskConfirmed,false);
  assert.equal(state.draft.composition.exitConfirmed,false);
  state.validate({ready:false,issues:[{path:'composition.riskConfirmed',message:'확인 필요'}]});
  assert.equal(state.canConfirm(),false);
  state.draft.composition.riskConfirmed=true; state.draft.composition.exitConfirmed=true;
  state.changed(false);
  assert.equal(state.draft.composition.riskConfirmed,true);
  state.validate({ready:true,issues:[]});
  assert.equal(state.canConfirm(),true);
});

test('coverage reports actual account dates and unready leaves even when their OR parent is ready', () => {
  const snapshot = {execution:{startDate:'2025-01-02',endDate:'2025-12-30'},strategy:{strategy:{composition:{
    entry:{id:'root',children:[{id:'fast',condition:{}},{id:'slow',sourceId:'s2',condition:{}}]}
  }}},result:{account:{equity:[{date:'2025-11-21'},{date:'2025-12-30'}]},evaluations:[
    {date:'2025-11-21',symbol:'005930',phase:'ENTRY',ready:true,nodes:[{id:'root',ready:true},{id:'fast',ready:true},{id:'slow',ready:false}]},
    {date:'2025-12-30',symbol:'005930',phase:'ENTRY',ready:true,nodes:[{id:'root',ready:true},{id:'fast',ready:true},{id:'slow',ready:true}]}
  ]}};
  const original = structuredClone(snapshot);
  const coverage = workbench.executionCoverage(snapshot);
  assert.deepEqual(coverage, {firstDate:'2025-11-21',lastDate:'2025-12-30',days:2,rangeDiffers:true,
    unready:[{id:'slow',sourceId:'s2',symbol:'005930',phase:'ENTRY',days:1,evaluatedDays:2,firstDate:'2025-11-21',lastDate:'2025-11-21'}]});
  assert.deepEqual(snapshot, original, 'history summaries must not mutate saved facts');
});

test('coverage distinguishes no data from a fully ready run and counts each leaf date once', () => {
  const execution={startDate:'2025-07-01',endDate:'2025-07-02'};
  assert.deepEqual(workbench.executionCoverage({execution,result:null}),{firstDate:null,lastDate:null,days:0,rangeDiffers:false,unready:[]});
  const snapshot={execution,strategy:{strategy:{composition:{entry:{id:'one',condition:{}}}}},result:{account:{equity:[{date:'2025-07-01'},{date:'2025-07-02'}]},evaluations:[
    {date:'2025-07-01',symbol:'A',phase:'ENTRY',nodes:[{id:'one',ready:true}]}
  ]}};
  assert.deepEqual(workbench.executionCoverage(snapshot),{firstDate:'2025-07-01',lastDate:'2025-07-02',days:2,rangeDiffers:false,unready:[]});
  snapshot.result.evaluations[0].nodes[0].ready=false;
  snapshot.result.evaluations.push(structuredClone(snapshot.result.evaluations[0]));
  assert.equal(workbench.executionCoverage(snapshot).unready[0].days,1);
  assert.equal(workbench.executionCoverage(snapshot).unready[0].evaluatedDays,1);
});
