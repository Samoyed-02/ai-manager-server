-- 기기별 로그인 세션. 토큰 원문은 저장하지 않고 SHA-256 해시만 둔다
create table auth_sessions (
    id           uuid primary key default gen_random_uuid(),
    token_hash   char(64)     not null unique,
    device_name  varchar(100) not null,
    created_at   timestamptz  not null default now(),
    last_used_at timestamptz  not null default now(),
    -- 최대 유효 기간(만든 뒤 90일). 30일 동안 안 쓰면 last_used_at으로도 만료된다
    expires_at   timestamptz  not null,
    revoked_at   timestamptz
);

-- 로그인 시도. 실패가 몰리면 잠시 막는다
create table login_attempts (
    id         bigserial primary key,
    ip         varchar(64) not null,
    success    boolean     not null,
    created_at timestamptz not null default now()
);
create index idx_login_attempts_ip on login_attempts (ip, created_at desc);
create index idx_login_attempts_created on login_attempts (created_at desc);

-- 한 번 쓴 TOTP 코드(30초 단위 counter). 같은 코드를 다시 쓰지 못하게 한다
create table totp_used_codes (
    counter bigint primary key,
    used_at timestamptz not null default now()
);
