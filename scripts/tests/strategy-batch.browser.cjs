const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const source = fs.readFileSync(path.resolve(__dirname, '../../src/main/resources/static/js/strategy-workbench.js'), 'utf8');
const styles = fs.readFileSync(path.resolve(__dirname, '../../src/main/resources/static/css/strategy-workbench.css'), 'utf8');
const strategy = (name, rate) => ({schemaVersion:2,name,originalPrompt:name+' 원문',entry:{conditions:[{type:'MA_CROSS',averageType:'SMA',shortPeriod:5,longPeriod:20,direction:'UP'}]},risk:{stopLoss:{rate}}});

test('batch drafts keep edits, clarify and save independently, and survive a failed replacement', async () => {
  const browser = await chromium.launch({headless:true,channel:process.env.SW_BROWSER_CHANNEL || 'msedge'});
  try {
    const page = await browser.newPage(); const errors = []; page.on('pageerror', e => errors.push(e.message));
    const requests = []; let saved = null; let failBatch = false; let failHistory = false;
    await page.route('**/*', async route => {
      const req = route.request(); const url = new URL(req.url());
      if (url.pathname === '/') return route.fulfill({contentType:'text/html',body:'<html lang="ko"><div id="strategyWorkbench"></div></html>'});
      const body = req.postData() ? JSON.parse(req.postData()) : null;
      requests.push({path:url.pathname,method:req.method(),body});
      let data = [];
      if (url.pathname.endsWith('/status')) data = {ready:true};
      else if (url.pathname.endsWith('/interpret-batch')) {
        if (failBatch) return route.fulfill({status:502,contentType:'application/json',body:JSON.stringify({code:'CODEX_FAILED',message:'해석 실패'})});
        data = {items:[
          {title:'A',prompt:'A 원문',draft:{strategy:strategy('A',-0.05),ready:true,issues:[],questions:[],unsupported:[]}},
          {title:'B',prompt:'B 원문',draft:{strategy:strategy('B',null),ready:false,issues:[],questions:['B 손절률은?'],unsupported:[]}}
        ],questions:[]};
      } else if (url.pathname.endsWith('/interpret')) data = {strategy:strategy('B',-0.09),ready:true,issues:[],questions:[],unsupported:[]};
      else if (url.pathname.endsWith('/validate')) data = {strategy:body,ready:true,issues:[]};
      else if (url.pathname === '/api/strategies' && req.method() === 'POST') {
        saved = {id:9,version:1,strategy:body}; data = saved;
      } else if (url.pathname === '/api/strategies') data = saved ? [saved] : [];
      else if (url.pathname.endsWith('/versions')) {
        if (failHistory) return route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'HISTORY_FAILED',message:'이력 조회 실패'})});
        data = saved ? [saved] : [];
      }
      else if (url.pathname === '/api/strategies/9') data = saved;
      return route.fulfill({contentType:'application/json',body:JSON.stringify(data)});
    });
    await page.goto('http://batch.test/'); await page.addStyleTag({content:'*{box-sizing:border-box}' + styles}); await page.addScriptTag({content:source});
    await page.getByLabel('전략 원문',{exact:true}).fill('A 원문\nB 원문');
    await page.getByRole('button',{name:'Codex로 조건 해석',exact:true}).click();
    await page.getByRole('button',{name:/초안 2 · B/}).waitFor();
    await page.getByLabel('전략 이름',{exact:true}).fill('A 수정');
    await page.getByLabel('손절 수익률 (%) · 음수, 예: -5',{exact:true}).fill('-6');
    await page.getByRole('button',{name:/초안 2 · B/}).click();
    await page.getByText('보완 질문: B 손절률은?',{exact:true}).waitFor();
    await page.getByLabel('보완 입력 · 질문의 답이나 미지원 조건 수정',{exact:true}).fill('-9%');
    await page.getByRole('button',{name:/초안 1 · A 수정/}).click();
    assert.equal(await page.getByLabel('손절 수익률 (%) · 음수, 예: -5',{exact:true}).inputValue(),'-6');
    assert.equal(await page.getByText('보완 질문: B 손절률은?',{exact:true}).count(),0);
    await page.getByRole('button',{name:/초안 2 · B/}).click();
    assert.equal(await page.getByLabel('보완 입력 · 질문의 답이나 미지원 조건 수정',{exact:true}).inputValue(),'-9%');
    await page.getByRole('button',{name:'선택 전략 다시 해석',exact:true}).click();
    const confirmation = page.getByLabel('원문·조건·위험 관리 설정을 검토했으며 이 내용으로 저장합니다.',{exact:true});
    await confirmation.check(); await page.getByRole('button',{name:'새 전략으로 저장',exact:true}).click();
    await page.getByText('실행 대상: 전략 #9',{exact:false}).waitFor();
    assert.deepEqual(requests.find(r => r.path.endsWith('/interpret')).body,{prompt:'B 원문',clarifications:'-9%'});
    assert.equal(saved.strategy.risk.stopLoss.rate,-0.09);
    await page.getByRole('button',{name:/초안 1 · A 수정/}).click();
    assert.equal(await page.getByRole('button',{name:'선택 전략의 새 버전 저장',exact:true}).isDisabled(),true);
    assert.equal(await page.getByRole('button',{name:'선택 버전 백테스트 실행',exact:true}).isDisabled(),true);
    assert.equal(await page.getByLabel('손절 수익률 (%) · 음수, 예: -5',{exact:true}).inputValue(),'-6');
    failBatch = true;
    await page.getByRole('button',{name:'Codex로 조건 해석',exact:true}).click();
    await page.getByText('CODEX_FAILED · 해석 실패',{exact:true}).waitFor();
    assert.equal(await page.getByRole('button',{name:/초안 2 · B.*저장됨/}).count(),1);
    assert.equal(await page.getByLabel('전략 이름',{exact:true}).inputValue(),'A 수정');
    failHistory = true;
    await page.getByRole('button',{name:/초안 2 · B/}).click();
    await page.getByText('HISTORY_FAILED · 이력 조회 실패',{exact:true}).waitFor();
    assert.equal(await page.getByLabel('전략 이름',{exact:true}).inputValue(),'B', 'history failure must not leave A editor attached to B state');
    await page.getByLabel('손절 수익률 (%) · 음수, 예: -5',{exact:true}).fill('-10');
    await page.getByRole('button',{name:/초안 1 · A 수정/}).click();
    assert.equal(await page.getByLabel('손절 수익률 (%) · 음수, 예: -5',{exact:true}).inputValue(),'-6');
    failHistory = false;
    assert.equal(requests.filter(r => r.path === '/api/strategies' && r.method === 'POST').length,1);
    await page.screenshot({path:path.resolve(__dirname,'../../build/strategy-batch-desktop.png'),fullPage:true});
    await page.setViewportSize({width:390,height:844});
    assert.ok(await page.locator('#strategyWorkbench').evaluate(el => el.scrollWidth <= el.clientWidth));
    await page.screenshot({path:path.resolve(__dirname,'../../build/strategy-batch-mobile.png'),fullPage:true});
    assert.deepEqual(errors,[]);
  } finally { await browser.close(); }
});
