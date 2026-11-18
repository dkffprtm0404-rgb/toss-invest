# 프로젝트 문서 목차

[프로젝트 README](../README.md)에서 프로젝트 소개와 실행 방법을 확인하고, 아래에서 기획·명세·사용 안내·검토 기록을 찾아본다.

문서 정리일: **2026-11-17**. 기존 `.docs/`, `build/`, `.superpowers/`와 루트 `HELP.md`에 있던 문서를 용도별로 모았다. 이 정리는 기능 구현이나 과거 미완료 검증의 완료를 뜻하지 않는다. 각 문서의 작성일·판정·미검증 범위를 따른다.

## 폴더 구성

```text
docs/
├── README.md   # 전체 목차와 관리 기준
├── plans/      # 기획·설계·실행 계획
├── specs/      # 기능 명세·API 계약
├── guides/     # 실행·사용·운영 확인 안내
├── reports/    # 검증·검토·완료·진행 기록
├── examples/   # 요청 JSON 예제
└── assets/     # 문서 이미지
```

## 기획·설계·실행 계획

- [프로젝트 전체 기획과 단계별 진행](plans/project-plan.md)
- [사용자 전략 백테스트 구현 계획](plans/user-strategy-implementation.md)
- [통합 전략 기획안](plans/strategy-composition-proposal.md)
- [전략별 모의매매 설계](plans/strategy-paper-trading-design.md)
- [전략별 모의매매 구현 계획](plans/strategy-paper-trading-plan.md)
- [3년 일봉 보관·지표·브리핑 구현 계획 — 2026-11-05](plans/2026-11-05-data-indicators-briefing-plan.md)
- [국내 지수·환율 수집과 브리핑 복구 계획 — 2026-11-11](plans/2026-11-11-domestic-quotes-recovery-plan.md)
- [데이터 준비·선택·오류 안내 후속 카드 — 2026-11-11](plans/2026-11-11-follow-up-cards-b5ea6f8d.md)
- [미완료 기능 마무리 실행 계획 — 2026-11-12](plans/2026-11-12-completion-plan.md)
- [대시보드 브리핑 접기·계좌 숨김 계획과 기록 — 2026-11-16](plans/dashboard-visibility-plan.md)
- [대시보드 발표용 공백 처리 계획과 기록 — 2026-11-16](plans/dashboard-presentation-plan.md)

## 기능 명세·API 계약

- [사용자 전략 모델과 실행 정책](specs/strategy-spec.md)
- [전략 버전·실행 결과 저장](specs/strategy-persistence.md)
- [여러 전략 입력](specs/strategy-batch-input.md)
- [이동평균·돌파·52주 조건 확장](specs/strategy-expansion.md)
- [MACD·볼린저밴드 조건](specs/strategy-indicators.md)
- [상대강도 포트폴리오](specs/strategy-portfolio.md)
- [개별 전략·통합 전략](specs/strategy-composition.md)
- [결과 차트·AI 설명](specs/backtest-results-explanation.md)
- [저장 전략 비교](specs/strategy-comparison.md)
- [저장 전략 모의매매](specs/strategy-paper-trading.md)
- [기존 점수 기반 매매 규칙](specs/trading-rules.md)
- [백테스트 실행 전 데이터 준비 상태](specs/data-readiness.md)
- [장전 시장 요약·일봉 보관](specs/market-briefing.md)

## 실행·사용·운영 안내

- [Codex 전략 입력과 웹 연결](guides/codex-strategy-web.md)
- [사용자 전략 백테스트 API 사용법](guides/user-strategy-backtest.md)
- [통합 검증과 재현 가능한 시연](guides/integration-demo.md)
- [장전 브리핑 운영 확인 준비서 — 2026-11-12](guides/2026-11-12-briefing-operations.md) — 운영 확인 명령은 작성 당시 미실행
- [Spring·Gradle 공식 참고 문서](guides/spring-reference.md) — 기존 `HELP.md`

## 검증·검토·완료·진행 기록

- [기존 엔진 검증](reports/engine-validation.md)
- [통합 전략 구현 계약과 진행 기록](reports/strategy-composition-implementation.md)
- [통합 전략 SDD 진행 원장](reports/strategy-composition-progress.md)
- [기능·오류·사용 편의성 점검 — 2026-11-10](reports/product-audit-2026-11-10.md)
- [자료 조사와 후속 확인 사항 — 2026-11-10](reports/research-standby-2026-11-10.md)
- [데이터 준비 상태 최종 취합](reports/data-readiness-final-report.md)
- [데이터 준비 상태 화면 구현 인계](reports/data-readiness-frontend-review.md)
- [데이터 준비 상태 QA 정적 검토](reports/data-readiness-qa-review.md)
- [데이터 준비 상태 코드 검토](reports/data-readiness-code-review.md)
- [장전 브리핑 변경 정적 검토](reports/market-briefing-latest-review.md)
- [마무리 취합 보고 — 2026-11-12](reports/2026-11-12-completion-report.md)
- [데이터 준비·후속 화면 개선 QA — 2026-11-12](reports/2026-11-12-completion-qa.md) — 실행 차단·검증 미완료 기록

## 예제·이미지

- [골든크로스·5% 손절 백테스트 요청 JSON](examples/golden-cross-backtest.json)
- [프로젝트 기획 원문 그래프](assets/proposal-source-image.png)

## 관리 기준

- 루트 `README.md`는 저장소 진입점으로 유지한다. 프로젝트 Markdown은 용도에 맞는 `docs/` 하위 폴더에 작성하고 이 목차에 등록한다.
- 계획·설계는 `plans/`, 기능 계약은 `specs/`, 실행 안내는 `guides/`, 검토·인계·검증 결과는 `reports/`에 저장한다. 날짜가 있는 문서는 기존 `YYYY-MM-DD-주제.md` 형식을 따른다.
- `build/`에는 테스트 결과·로그·캡처·빌드 산출물을 둔다. 보존할 계획서와 수동 작성 보고서는 Git 제외·빌드 정리로 사라지지 않도록 `docs/`에 둔다.
- 문서 링크는 해당 Markdown 파일 기준 상대 경로를 사용한다. 코드·명령 예제는 별도 안내가 없으면 저장소 루트에서 실행하며, 요청 JSON 경로는 `docs/examples/`를 사용한다.
- 파일을 이동하거나 추가할 때 루트 README·이 목차·문서 간 링크·소스 주석의 경로를 함께 확인한다. 이미지와 요청 예제도 관련 링크에 맞춰 관리한다.
- 이전 `build/`·`.superpowers/` 문서에는 이동 안내를 추가했다. 그 안의 과거 명령·판정·이전 경로는 당시 기록으로 보존하며, 현재 문서 위치는 이 목차를 따른다. 과거 테스트 수치와 미검증 사항을 현재 검증 결과로 해석하지 않는다.
