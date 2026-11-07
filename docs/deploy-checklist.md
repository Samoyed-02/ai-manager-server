# 배포 체크리스트

운영에 처음 배포하거나 다시 배포할 때 위에서부터 확인한다. 2026-11-03에 실제 이미지와 임시 Postgres 17로 이 절차를 따라 해 확인했다.

## 1. 환경변수

### 필수

| 변수 | 내용 | 빠지면 |
|---|---|---|
| `SPRING_DATASOURCE_URL` | 예: `jdbc:postgresql://<host>:5432/<db>` | 기동 실패. 개발에서는 `spring-boot-docker-compose`가 넣어 주지만 이 모듈은 jar에 들어가지 않는다 |
| `SPRING_DATASOURCE_USERNAME` | DB 사용자 | 기동 실패 |
| `SPRING_DATASOURCE_PASSWORD` | DB 비밀번호 | 기동 실패 |
| `APP_PASSWORD_HASH` | 로그인 비밀번호의 bcrypt 해시. `./gradlew -q hashPassword --console=plain`으로 만든다(비밀번호는 표준 입력, 12자 이상). 원래 비밀번호는 어디에도 두지 않는다 | 없거나 bcrypt 형식이 아니면 기동 실패 |
| `APP_TOTP_SECRET` | 인증 앱(Google Authenticator 등) 비밀값(base32). `./gradlew -q totpSecret --console=plain`이 값과 `otpauth://` URI를 출력한다. URI를 QR로 만들어 인증 앱에 등록한다 | 없거나 80비트 미만이면 기동 실패 |
| `ANTHROPIC_API_KEY` | Claude API 키 | 없으면 기동 실패. 값이 틀리면 기동은 되고 채팅·작업이 "서버 설정 문제로 처리할 수 없습니다"로 실패한다 |
| `ALLOWED_ORIGINS` | 프론트엔드 주소. 여러 개면 쉼표로 구분 (예: `https://app.example.com`) | 기본값 `http://localhost:3000`이 쓰여 브라우저 요청이 CORS로 막힌다 |

### 선택

| 변수 | 기본값 | 내용 |
|---|---|---|
| `PORT` | `8080` | 플랫폼이 포트를 정해 주면 그대로 쓴다 |
| `APP_PASSCODE` | 없음 | **전환 기간용.** 설정돼 있을 때만 예전 `X-App-Passcode` 헤더를 받는다(쓰일 때 1분에 한 번 경고 로그). 클라이언트가 `/auth/login`으로 바뀌면 **지운다** |
| `APP_TRUST_FORWARDED_FOR` | `false` | 앞단 프록시가 `X-Forwarded-For`를 덮어쓰는 환경에서만 `true`. 로그인 시도 제한과 감사 로그가 실제 IP를 쓰고, `X-Forwarded-Proto: https`면 HSTS를 붙인다. 프록시 없이 `true`로 두면 IP를 위조해 시도 제한을 피할 수 있다 |
| `JAVA_TOOL_OPTIONS` | 없음 | `-XX:MaxRAMPercentage=75` 권장. 지정하지 않으면 JVM이 컨테이너 메모리의 25%만 힙으로 쓴다 |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | 없음 (텍스트 로그) | `ecs`로 두면 로그가 JSON 한 줄씩 나온다. Claude 사용량 로그(`claude.usage`)의 토큰 수 등이 별도 필드가 되어 로그 서비스에서 바로 집계할 수 있다 |

## 2. DB

- **버전:** Postgres 18 (Railway 운영 18.6). 다른 버전이면 `compose.yaml`과 `TestcontainersConfiguration`도 같은 메이저 버전으로 바꾼다.
- **백업:** 첫 배포 전에 백업한다. Flyway가 기동할 때 남은 마이그레이션(현재 V1~V10)을 자동으로 적용한다.
- **V3 중복 문서 확인:** V3는 작업마다 가장 먼저 만들어진 문서만 남기고 나머지를 **삭제**한다. 기존 DB에 배포하기 전에 지워질 문서가 있는지 본다.
  ```sql
  select task_id, count(*) from documents group by task_id having count(*) > 1;
  ```
  결과가 있으면 지워질 문서를 먼저 확인하거나 따로 보관한다.

## 3. Claude Console

- **웹 검색·페이지 읽기:** 채팅이 `web_search`·`web_fetch` 도구를 쓴다. 조직 설정([Capabilities](https://platform.claude.com/settings/capabilities))에서 기본으로 켜져 있지만, 관리자가 꺼 두었으면 모든 채팅이 400으로 실패한다(사용자에게는 "요청을 처리할 수 없습니다"). 첫 배포 전에 켜져 있는지 확인한다.
- **기억 추출 비용:** 대화가 끝날 때마다(통화는 저장 즉시, 채팅은 30분 동안 조용하면) Claude를 한 번 더 호출해 기억과 요약을 뽑는다(`claude.usage` 로그의 `kind=memory`). **V6 배포 직후에는 기존 대화가 모두 대상이 되어** 1분에 최대 5개씩 처리된다. 대화가 많으면 그만큼 비용이 나가니, 원하지 않으면 배포 후 `update conversations set memory_processed_at = updated_at;`로 기존 대화를 건너뛴다.
- **주간 회고 비용:** V8이 기본 주간 회고 루틴(매주 일요일 21:00)을 하나 만든다. 주 1회 Claude 호출이다(`kind=review`). 원하지 않으면 말로 끄거나 `PUT /routines/{id}`로 `enabled=false`.
- **루틴 브리핑 비용:** `BRIEFING` 루틴은 실행할 때마다 Claude를 한 번 호출한다(`kind=routine`). 매일 1개면 하루 1회다. 루틴은 최대 30개까지 만들 수 있다.
- **비용:** 웹 검색은 토큰 비용과 별도로 1,000회당 $10다. 질문 하나에 최대 6회로 제한했고(질문당 최대 $0.06), `claude.usage` 로그의 `web_search_requests`로 사용량을 본다. 페이지 읽기는 추가 요금이 없고 읽은 내용만큼 입력 토큰이 늘어난다(페이지당 최대 2만 토큰).

## 4. 인프라

- **SSE:** `POST /chat`은 SSE로 최대 3분 동안 응답을 보낸다. 앞단 리버스 프록시에서 응답 버퍼링을 끄고(nginx라면 `proxy_buffering off`), 유휴 타임아웃을 3분 이상으로 둔다.
- **헬스체크:** `GET /actuator/health`. 인증 없이 열려 있고 `{"status":"UP"}`만 돌려준다. DB에 연결하지 못하면 503이다. 다른 actuator 경로는 열지 않았다(`management.yml`).
- **인스턴스 수:** 여러 개로 띄워도 된다. 작업 워커가 `FOR UPDATE SKIP LOCKED`로 작업을 가져가므로 같은 작업을 두 인스턴스가 동시에 처리하지 않는다.
- **재시작:** 작업 처리 중 서버가 종료되면 그 작업은 `RUNNING`으로 남았다가 10분 뒤 stale 복구로 다시 처리된다.
- **실행 사용자:** 컨테이너는 `app`(uid 10001)으로 실행된다.

## 5. 배포 후 확인

```sh
# 헬스체크 → {"status":"UP"} 200
curl -s -w " %{http_code}\n" https://<server>/actuator/health
# 인증 없이 → 401
curl -s -o /dev/null -w "%{http_code}\n" https://<server>/conversations
# 로그인 (인증 앱의 지금 코드) → {"token": ...}
curl -s -H 'Content-Type: application/json' \
  -d '{"password":"<비밀번호>","totp":"<6자리>","deviceName":"배포 확인"}' https://<server>/auth/login
# 토큰과 함께 → 200, 응답 헤더에 Cache-Control: no-store
curl -s -D - -o /dev/null -H "Authorization: Bearer <token>" https://<server>/conversations
# 확인용 세션은 끝나면 로그아웃 → 204
curl -s -o /dev/null -w "%{http_code}\n" -X POST -H "Authorization: Bearer <token>" https://<server>/auth/logout
# 프론트엔드 origin의 preflight → 200
curl -s -o /dev/null -w "%{http_code}\n" -X OPTIONS \
  -H "Origin: <프론트엔드 주소>" -H "Access-Control-Request-Method: GET" https://<server>/conversations
```

```sql
-- 마이그레이션이 V10까지 성공했는지
select version, success from flyway_schema_history order by installed_rank;
```

- 프론트엔드에서 채팅을 한 번 보내 응답이 끝까지 스트리밍되는지 본다.
- 최신 정보가 필요한 질문(예: 오늘 날씨)을 보내 답변 끝에 `**출처**`나 `**검색한 페이지**` 목록이 붙는지, 로그에 `web_search_requests`가 찍히는지 본다.
- 같은 대화에서 5분 안에 한 번 더 보내 `claude.usage` 로그의 `cache_read_tokens`가 0보다 큰지 본다.
- 작업을 하나 만들어 `DONE`이 되고 문서가 생기는지 본다.

## 6. Railway

저장소의 `railway.json`이 빌드(Dockerfile)와 배포 설정(헬스체크 `/actuator/health`, 대기 120초, 실패 시 재시작)을 정한다. 이 값은 Railway 화면의 설정보다 우선한다. 헬스체크에 실패한 새 배포는 트래픽을 받지 않고 이전 배포가 계속 돈다.

**Variables**

Railway Postgres가 주는 주소는 `postgresql://…` 형식이라 Spring에 그대로 쓸 수 없다. 참조 변수로 JDBC 주소를 만든다(`Postgres`는 DB 서비스 이름).

```
SPRING_DATASOURCE_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}
SPRING_DATASOURCE_USERNAME=${{Postgres.PGUSER}}
SPRING_DATASOURCE_PASSWORD=${{Postgres.PGPASSWORD}}
```

DB 버전은 `select version();`으로 확인한다. 17이 아니면 2절을 따른다.

**API 키 교체**

1. Claude Console에서 새 키를 만든다.
2. Railway Variables의 `ANTHROPIC_API_KEY`를 새 키로 바꾸고 재배포한다.
3. 채팅이 동작하는지 확인한다.
4. 이전 키를 Console에서 폐기한다.

**`APP_TRUST_FORWARDED_FOR` 정하기**

기본값 `false`면 모든 요청이 프록시 IP로 보여 로그인 시도 제한이 한 묶음으로 집계된다. 배포 후 Railway가 이 헤더를 어떻게 다루는지 실제로 확인하고 정한다.

1. `APP_TRUST_FORWARDED_FOR=true`로 재배포한다.
2. 위조한 헤더로 로그인 실패를 한 번 보낸다.
   ```sh
   curl -s -o /dev/null -w "%{http_code}\n" -H 'X-Forwarded-For: 203.0.113.9' -H 'Content-Type: application/json' \
     -d '{"password":"wrong-password-000","totp":"000000","deviceName":"확인"}' https://<server>/auth/login
   ```
3. 로그의 로그인 실패 기록에 찍힌 IP를 본다.
   - `203.0.113.9`면 위조가 통한다. **`false`로 되돌린다.**
   - 내 실제 IP면 Railway가 헤더를 덮어쓰는 것이다. `true`로 둔다.
