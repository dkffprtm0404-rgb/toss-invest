# toss-invest-backend

Spring Boot 기반 자동매매 백엔드 프로젝트입니다.
백테스트 엔진, 페이퍼 트레이딩 시스템, 1분봉 스캘핑 시뮬레이션을 포함합니다.

## 기술 스택

- Java 17
- Spring Boot
- H2 Database
- FinanceDataReader (KOSPI/KOSDAQ 시세 데이터 수집)

## 프로젝트 구조

```
src/main/java/.../
├── backtest/   # 백테스트 엔진 (그리드 서치, 지표 계산 등)
├── paper/      # 페이퍼 트레이딩 (스케줄러, 배치 처리)
```

## ⚠️ 실행 전 필수 설정 (API 키)

이 저장소/압축파일에는 **보안을 위해 실제 API 키를 포함하지 않았습니다.**
실행하려면 아래 위치에 본인의 키를 직접 채워 넣어야 합니다.

### 1. `src/main/resources/application.yml` (또는 `application-secret.yml`)

아래와 같이 **플레이스홀더(`YOUR_API_KEY_HERE` 등)로 표시된 항목**을 본인의 실제 값으로 교체하세요:

```yaml
# TODO: 아래 항목에 실제 값을 입력하세요
your-broker:
  api-key: YOUR_API_KEY_HERE
  api-secret: YOUR_API_SECRET_HERE
  account-no: YOUR_ACCOUNT_NUMBER_HERE
```

> ※ 실제 프로퍼티 이름(`your-broker.api-key` 등)은 예시입니다.
> 본인의 `application.yml`을 열어 **API 키/시크릿/계좌번호 등 민감한 값이 하드코딩된 모든 위치**를 찾아 위와 같이 플레이스홀더로 바꿔주세요.
> (검색 팁: 파일 내에서 `key`, `secret`, `token`, `password` 등의 키워드로 검색하면 빠르게 찾을 수 있습니다.)

### 2. 환경변수 사용을 권장

가능하면 `application.yml`에는 아래처럼 환경변수 참조만 남기고, 실제 값은 로컬 환경변수나 `.env` 파일로 분리하는 것을 권장합니다:

```yaml
your-broker:
  api-key: ${BROKER_API_KEY}
  api-secret: ${BROKER_API_SECRET}
```

## 실행 방법

1. 위 API 키 설정 완료
2. `./gradlew bootRun` (또는 IDE에서 `Application` 클래스 실행)
3. 서버는 기본적으로 `8090` 포트에서 실행됩니다.

## 참고사항

- H2 인메모리 DB를 사용하므로 별도 DB 설치가 필요하지 않습니다.
- 페이퍼 트레이딩은 매일 15:40 KST에 스케줄러가 자동 실행됩니다.
