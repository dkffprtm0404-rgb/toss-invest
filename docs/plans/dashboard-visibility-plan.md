# Dashboard visibility implementation plan — 2026-11-16

> 문서 정리(2026-11-17): `build/dashboard-visibility-plan.md`에서 이동했다. 아래 명령·검증 결과·상태와 이전 경로는 작성 당시 기록이다. 현재 문서 위치는 [전체 목차](../README.md)를 따른다.

Goal: shrink the market briefing on demand and conceal real account details and holdings.

Design: retain a compact briefing title and an accessible expand/collapse button. Hide its entire body, including generation/refresh actions. Add one account toolbar button to hide both account panels and close an open holding quote. Keep simulation panels usable. Save each choice independently in localStorage, tolerating unavailable storage. These are display preferences, not API access controls.

Approaches: use the existing static HTML/CSS/JavaScript and hidden attributes. Separate per-card controls add unnecessary choices; removing panels would lose easy restoration and risk existing state. Preserve all pre-existing workspace changes. No backend or dependency changes.

- [x] Extend existing browser fixtures with tests for collapse, restoration, pending updates, reload, blocked storage, keyboard operation and 375px layout.
- [x] Run the focused tests and confirm the missing behavior fails. All 5 failed waiting for the missing controls.
- [x] Add controls, scoped styles and preference handling in index.html, dashboard.js/css and market-briefing.js/css. Update affected asset cache versions.
- [x] Run the complete standalone Node/browser suite and review the changes and captured mobile layouts. The 5 new behavior tests pass; the full suite has 78 passes and 2 failures out of 80.

Review focus: hidden grid CSS, quote disclosure, asynchronous updates reopening panels, storage exceptions, independently saved preferences, preserving existing edits.

## Changed source files

- `src/main/resources/static/index.html`
- `src/main/resources/static/js/dashboard.js`
- `src/main/resources/static/js/market-briefing.js`
- `src/main/resources/static/css/dashboard.css`
- `src/main/resources/static/css/market-briefing.css`
- `scripts/tests/dashboard-paper.browser.cjs`
- `scripts/tests/market-briefing.browser.cjs`

## Validation

- RED: all 5 new behavior tests failed at the missing controls before implementation.
- GREEN: all 5 passed after implementation. The complete run also covers automatic briefing refresh and independent persistence of the two preferences.
- Full standalone command: `node --test --test-concurrency=1` with all `scripts/tests/*.(test|browser).cjs` files, using the existing bundled Playwright and headless Edge. Result: 80 tests, 78 passed, 2 failed. Log: `build/dashboard-visibility-node.log`.
- Failures are both in unchanged `scripts/tests/strategy-composition.browser.cjs`: `six plus total, subset composition, nested edits, independent snapshots, confirmations and immutable execution history` (line 64: expected -8, received -9), and `relative-strength composition requires comparison universe even for one symbol, and exclusion keeps source snapshot` (line 96: expected validation message not visible). This fixture loads only `strategy-workbench.js` and its CSS into a synthetic document; none of this task's changed assets are loaded. These failures are outside the visibility changes and were left intact.
- JavaScript syntax checks passed for both modified production scripts; scoped `git diff --check` passed.
- Inspected 375px captures: `build/market-briefing-collapsed-mobile.png` and `build/dashboard-accounts-hidden-mobile.png`. No horizontal page overflow in the new browser scenarios.
- Independent read-only review found no actionable bugs in the scoped additions.
- No Java code, backend/API behavior, dependencies, running server, or deployment changed by this task. Java build and live-server application of the assets were not tested.
