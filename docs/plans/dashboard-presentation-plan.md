# Dashboard presentation privacy — 2026-11-16

> 문서 정리(2026-11-17): `build/dashboard-presentation-plan.md`에서 이동했다. 아래 명령·검증 결과·상태와 이전 경로는 작성 당시 기록이다. 현재 문서 위치는 [전체 목차](../README.md)를 따른다.

Goal: blank personal account and holdings values for a presentation while retaining headings, labels, table columns and usable simulation panels.

Design: add an explicit toggle beside the existing account visibility control. In presentation mode, replace account values and holdings rows with fixed empty presentation markup, close holding quotes, suppress account/holdings reads, and guard every pending account/holdings response and error before rendering. Restore fresh data only after the user explicitly disables presentation mode. Persist this choice independently from collapse/hide preferences. Use existing HTML/CSS/JavaScript only; preserve all pre-existing edits.

Approaches: render blank values rather than transparent text or blur so selection/copy and accessibility do not expose rendered data. Keep the prior collapse control as a separate display preference. Do not change backend authorization, stored account data, market data, strategy workflows or simulation results.

- [x] Add 5 browser regressions covering blanks, restoration, pending account/holdings responses, initial saved preference, storage errors and mobile/keyboard use; confirm RED. All five failed before implementation.
- [x] Implement the toggle, blank rendering, asynchronous guards, storage and scoped styles; update dashboard asset versions.
- [x] Verify relevant browser regressions, the standalone suite, mobile captures, static review and served static assets. Final standalone result: 85 tests, 82 passed, 3 failed in workbench-only strategy fixtures.

Changed files: `src/main/resources/static/index.html`, `src/main/resources/static/js/dashboard.js`, `src/main/resources/static/css/dashboard.css`, `scripts/tests/dashboard-paper.browser.cjs`.

Known prior standalone-suite failures: two independent `strategy-composition.browser.cjs` scenarios (expected -8 versus -9, and an expected universe validation message). These fixtures load only unchanged workbench assets, not the dashboard assets.

## Verification progress

- All five new browser regressions passed after implementation (5/5, 0 failures).
- `node --check src/main/resources/static/js/dashboard.js` passed. Normal repository `git diff --check` passed with only an existing LF-to-CRLF conversion notice for the test file. An earlier check with `core.autocrlf=false` incorrectly treated the repository's CRLF line endings as whitespace; no source formatting changes were made in response.
- Inspected the 375px presentation screenshot `build/dashboard-presentation-mobile.png`: headings, account labels and table headers remain, with blank account values, totals and holdings. New tests also assert no horizontal overflow and no personal values or clickable holding attributes in the rendered account region.
- Updated only the existing generated static copies: `build/resources/main/static/index.html`, `build/resources/main/static/js/dashboard.js`, `build/resources/main/static/css/dashboard.css`. Before synchronization localhost:8090 served old assets; afterward GETs confirmed the new toggle, cache version, JavaScript initialization and blank-row style. No account API requests or server restart were needed for this check.
- Complete standalone suite finished: 85 tests, 82 passed, 3 failed, exit 1; log: `build/dashboard-presentation-node.log`. All five new presentation regressions passed in both focused and complete runs.
- In addition to the two prior composition failures, `strategy-readiness.browser.cjs` test `input and saved-version changes invalidate the result and its review` failed at line 119 (expected the review checkbox to be invisible). Actual fixture inspection confirms it loads only `strategy-workbench.js` and `strategy-workbench.css`, independently of this task's assets. No changes were made to this test or its workbench sources.
- Final scoped `git diff --check` and JavaScript syntax check each returned exit 0. The sandbox execution account intermittently failed with Windows error 1909; the approved read/check rerun obtained the final counts and exit codes.
- Independent review of the supplied exact source excerpts found no bugs. The reviewer could not inspect files directly because of the same sandbox account error; runtime, screenshot and final-file verification were performed by the coordinator.

## User operation

Refresh the page (Ctrl+F5 if an older page is cached), open `계좌·모의매매`, and click `발표용 공백 처리` beside the account refresh controls. If the cards were previously collapsed, first enable presentation mode and then click `계좌·보유종목 보기` to show the blank cards. The mode persists through reload; `실제 데이터 표시` explicitly disables it and fetches fresh values.

Only account information and real holdings are blanked. The existing simulation panels remain available. No backend stored data, authorization, Java source, or server process was modified; Java compilation and external account integrations were not tested by this frontend-only task.
