# AI Manager Server

채팅과 음성 통화를 오가도 같은 기억과 도구를 쓰는 개인 AI 비서의 서버입니다.
웹 앱은 [ai-manager](https://github.com/Samoyed-02/ai-manager) (Next.js, Gemini Live)에 있습니다.

## 주요 기능

- **채팅:** Claude API로 답하고 SSE로 스트리밍합니다. 웹 검색과 출처, 대화 안에서 쓰는 도구(활동 기록, 목표, 루틴, 기억, 문서 작업)를 지원합니다.
- **기억과 맥락:** 대화와 통화에서 기억을 뽑아 저장하고, 새 대화에서도 기억·목표·이번 주 활동을 맥락으로 넘깁니다. 비밀번호 같은 민감 정보는 저장하지 않습니다.
- **음성 통화 연동:** 통화 기록(`POST /voice/calls`)을 받아 기억으로 정리하고, 음성 모델이 부른 도구를 대신 실행합니다(`GET /tools`, `POST /tools/{name}`).
- **루틴:** 브리핑, 주간 회고를 정해진 시간에 실행해 알림으로 남깁니다.
- **인증:** bcrypt 비밀번호 + TOTP 2단계 인증, 기기별 세션(Bearer), IP별 로그인 시도 제한

## 해결한 문제

- **프롬프트 캐시가 깨지던 문제:** 매 턴 바뀌는 맥락이 시스템 프롬프트에 있어 캐시가 쓰이지 않았습니다. 맥락을 대화 기록 뒤로 옮기고 고정 지시를 캐시해 두 번째 턴부터 도구 사용 턴 비용이 약 80% 줄었습니다($0.051~0.058 → $0.009~0.012).
- **모델이 자기 작업을 부정하던 문제:** 실행한 작업을 저장해 다음 턴에 서버 기록으로 보여 줘, 등록한 일을 "하지 않았다"고 정정하지 않게 했습니다.

자세한 경위는 [`docs/devlog`](docs/devlog), 실제 API 점검 결과는 [`docs/live-check`](docs/live-check)에 있습니다.

## 기술 스택

Kotlin, Spring Boot 4, JPA, Flyway, PostgreSQL, Claude API (Java SDK), Testcontainers, GitHub Actions, Docker, Railway

## 실행

```bash
./gradlew test      # Testcontainers 통합 테스트 (Docker 필요)
./gradlew bootRun   # compose.yaml의 Postgres를 자동으로 띄웁니다 (Docker 필요)
```

`bootRun`에는 `APP_PASSWORD_HASH`, `APP_TOTP_SECRET`, `ANTHROPIC_API_KEY`가 필요합니다. 만드는 방법과 배포 절차는 [배포 체크리스트](docs/deploy-checklist.md)에 있습니다.
