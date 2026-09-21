# 통합 전략 구현 계약과 진행 기록

2026-09-16 사용자 구현 승인. 명세: [기획안](strategy-composition-proposal.md).

## 공통 계약

- `StrategyDefinition`에 nullable `composition: CompositionDefinition` 추가, schemaVersion 4. 기존 6/7인자 생성자와 버전 1~3 유지.
- `CompositionDefinition(sources, entry, exit, selection, selectionSourceId, rankExit, allocation, riskConfirmed, exitConfirmed)`.
- `Source(id, title, prompt, definition, questions, unsupported, included, resolution)`는 생성 시 원본 스냅샷. definition은 버전 1~3만. 제외·질문 해결·원문 조건 변경은 resolution에 이유를 기록.
- `RuleNode(id, sourceId, condition, operator, children)`는 leaf(condition만) 또는 group(children만). 그룹 깊이 최대 3, 진입/청산 합계 말단 30. sourceId는 포함한 원문 ID; 사용자 공통 그룹은 null 허용.
- selection은 기존 `RelativeStrength`; selectionSourceId로 원문 연결. 없으면 특정 종목 실행. 있으면 전체 비교 universe 필수이며 symbol 지정 시 그 종목만 거래.
- `Allocation(maxPositions, rebalance)`; `Rebalance { ENTRY_ONLY, WEEKLY_EQUAL }`. 수량/현금/비용 모델은 기존 포트폴리오 의미 유지.
- 공통 Risk는 최상위 risk. riskConfirmed/exitConfirmed는 공통 적용을 명시 확인. rankExit는 selection 있을 때 필수 Boolean.
- batch 응답 기존 items/questions 유지, optional `total: Item` 추가. 단일 초안에는 total 없음. `/api/strategy-assistant/compose` POST `{items:[{title,prompt,draft}],name}` -> 기존 Draft 형태. 추가 Codex 호출 없음.
- `CompositeRequest(version,startDate,endDate,executionMode,initialCapital,commissionRate,taxRate,slippageRate,symbol,universe)`.
- `CompositeEngine.run(strategy,request,candles)` -> `CompositeResult(account:PortfolioResult,evaluations:List<...>)`.
- `CompositeResult.Evaluation(date,symbol,phase,ready,matched,reason,nodes)`; `NodeEvidence(id,sourceId,ready,matched,evidence:List<StrategyEvaluator.Evidence>)`. ENTRY/EXIT/RISK 등 판정과 미진입 사유 저장.
- POST/GET `/api/strategies/{id}/composite-backtests`; GET/DELETE `/api/composite/runs/{id}`. 기존 portfolio 이력처럼 immutable Snapshot(execution,data,strategy,result,error) 저장.

## 작업 분담 / 검증

1. Root: 모델, 검증, 토탈/선택 조합 API, 저장 및 이력 연결, 통합 테스트.
2. Engine: CompositeRequest/Result/Engine, nested evaluator, 기존 계산 재사용, 엔진 테스트.
3. UI: workbench 토탈/체크 조합/중첩 편집/공통 위험 확인/버전4 실행·이력 및 브라우저 테스트.
4. Review: 변경 전체 계약·회귀 점검, Java/Node/실제 HTTP 흐름.

인터페이스 점검: 1→2 모델 필드 위 계약 공유, 1→3 batch/compose 및 실행 응답 공유, 2→3 account/evaluations 공유. 소스 소유권은 각 작업별로 분리한다. 엔진 작업만 기존 PortfolioEngine 계산 추출 가능; Root는 portfolio 소스 미수정. UI는 JS/CSS/브라우저 테스트 소유.

Ruling: 현재 사용 중인 codex 브랜치에서 구현한다. 기존 사용자 커밋과 작업 공간을 유지하며 새 브랜치 이동/커밋/푸시/운영 서버 재시작은 수행하지 않는다.
Ruling: 원문별 해석을 보존하고 정적 합성만 수행하므로 버전4용 모델 호출이나 출력 스키마는 추가하지 않는다.

상태: 2026-09-16 구현·검증 완료. 사용 방법과 API 예시는 [통합 전략 사용 방법](strategy-composition.md) 참고.

## 완료된 변경

- `strategy/CompositionDefinition`, `CompositionValidator`, `CompositionEvaluator`: 형식 4, 독립 원문 스냅샷, 중첩 조건·출처·변경 사유 검증, 기존 지표 재사용.
- `strategy/assistant/StrategyComposer`, `StrategyBatchService`, `StrategyAssistantController`: 자동 토탈, 선택 조합, 로컬 정적 조합 API. 추가 모델 호출 없음.
- `composite/CompositeRequest`, `CompositeEngine`, `CompositeResult`: 종목 지정/상대강도 비교, 주간 후보와 일별 진입, 단일 계좌·수량·비용·위험 상태·판정 근거.
- `composite/CompositeController`, `CompositeService`, `CompositeRunEntity`, `CompositeRunRepository`: 저장 버전 실행, immutable 이력, 자료 해시, 삭제 연계.
- `strategy/StrategyDefinition`, `StrategyValidator`, `StrategyApiErrors`, `backtest/UserStrategyBacktestRequest`: 버전 및 API 경계 연결, 기존 실행 경로에서 통합 전략 오실행 차단.
- `portfolio/PortfolioSelection`, `PortfolioCalculations`, `PortfolioEngine`: 선정·정수 수량·비용 공식 재사용. 기존 버전 3 테스트 통과.
- `static/js/strategy-workbench.js`: 개별+토탈 목록, 선택 조합, 원문·질문·변경 사유, 중첩 편집, 공통 정책 확인, 실행·이력·한글 판정 근거.
- 테스트: `CompositionTests`, `CompositeEngineTests`, `CompositeLifecycleTests`, `CompositeApiTests`, `CompositeWebWorkflowTests`, 기존 validator/batch 회귀 테스트, `scripts/tests/strategy-composition.*.cjs`.

## 검증 결과

- RED: 신규 모델·엔진 부재로 테스트 컴파일 실패 확인. 이후 실행 API 부재로 HTTP 테스트 2개 실패 확인 후 구현.
- `$env:RUN_WEB_SMOKE='true'; .\gradlew.bat test bootJar --console=plain`: **169개 발견, 163개 통과, 6개 선택적 실제 Codex 테스트 제외, 실패 0**, 실행 JAR 빌드 성공.
- Node/Playwright 상태·브라우저 회귀: **20개 통과**. 마지막 포함 여부 변경 사유 초기화 이후 통합 브라우저 2개 추가 재확인 통과.
- 임시 Spring Boot HTTP/H2 서버에서 6개+토탈 표시 → 정상 개별 저장 → 두 전략 조합 → 명시적 정책 확인 → 저장 → 종목 실행 → 새로고침 후 이력 조회 통과.
- 여섯 전략 전체 토탈의 누락·충돌을 명시 보완한 형식 4를 실제 HTTP로 저장·실행·조회하고, 6개 출처와 결과 스냅샷 일치 확인.
- 원본 전략과 캔들을 수정/삭제해도 저장된 이력 JSON 유지, 전략 삭제 시 통합 이력 cascade 확인.
- AND/OR와 중첩 필터, OR 일부 이력 부족, 동일 종목 중복 신호, 손절·추적·지표 청산 중복, 다음 시가 수량의 미래 종가 배제, 주중 신규 진입, 부분 조정 후 고점·ATR 유지와 평균 매입가 변경 확인.
- `git diff --check` 통과. 실제 화면 캡처 확인 및 390px 화면 가로 넘침 테스트 통과.

모델 응답은 이 통합 검증에서 고정 fixture를 사용했다. 실제 Codex 연결·해석을 다시 호출하지 않았으며, 조합·검증·저장·실행·조회에 모델 호출이 없음을 검증했다. 에이전트가 중간에 크레딧 부족으로 종료하여 Root가 남은 연결·수정·검토·최종 테스트를 완료했다. 별도 독립 에이전트 최종 리뷰는 수행하지 못했다.

Ruling: 상대강도 없는 조합은 신규 진입 때 배분만 제공한다. 주간 재조정 날짜를 추정하지 않는다. 상대강도만 남아 진입 조건이 없는 경우는 기존 버전 3을 사용한다. 버전 4의 단계식 트레일링은 지원하지 않고 비율 추적손절을 사용한다. 기존 버전 의미는 그대로 유지한다.

사용자 실행 서버·DB를 재시작/수정하거나 커밋·푸시하지 않았다. 새 화면과 API 적용에는 사용자 서버 재시작·브라우저 강력 새로고침이 필요하다.

## 2026-09-21 화면 감사 후 승인된 수정

수정: 실제 계산 기간·조건별 이력 부족 안내, 검증 결과 위치와 초점 이동, 단일 조건 그룹 안내, 원문의 줄바꿈 표시. Java 엔진과 저장 계약은 변경하지 않았다.

- 수정 전: 추가 회귀 6건 실패(화면 4건, 표시용 계산 2건), 기존 조합 4건 통과.
- 수정 후: `node --test scripts/tests/*.test.cjs scripts/tests/*.browser.cjs` 26건 모두 통과.
- `RUN_WEB_SMOKE=true`로 `gradlew.bat test bootJar --offline --console=plain` 성공. 별도 테스트 DB의 실제 HTTP 화면·저장·실행·이력 흐름을 포함한다. 기존 엔진의 손절·추적손절·중복 청산 방지 테스트도 실행했다.
- `git diff --check` 통과. Java 테스트의 선택적 실제 Codex 호출은 실행하지 않았다.
- 사용자 localhost:8090의 열린 탭 재확인은 브라우저 도구 연결이 두 차례 종료되어 수행하지 못했다. 사용자 서버 재시작이나 기존 탭 새로고침은 하지 않았다. 기존 초안 손실을 피하려면 필요한 초안을 저장한 뒤 새 화면을 열어야 한다.
- 데이터 부족을 안내하는 수정이며, 과거 일봉을 자동 수집하거나 기존 실행 결과를 다시 계산하는 기능은 추가하지 않았다.

Java 보고서: 발견 169 / 통과 163 / 건너뜀 6 / 실패 0 / 오류 0.
