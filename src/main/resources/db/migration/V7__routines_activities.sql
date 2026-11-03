-- 말로 등록하는 루틴. 정해진 시각(한국 시간)에 실행돼 알림함에 결과를 남긴다
create table routines (
    id          uuid primary key default gen_random_uuid(),
    title       varchar(100)  not null,
    -- REMINDER: message를 그대로 알림으로, BRIEFING: message를 지시로 삼아 Claude가 브리핑 작성
    kind        varchar(20)   not null check (kind in ('REMINDER', 'BRIEFING')),
    message     varchar(2000) not null,
    -- 쉼표로 구분한 요일(MON,WED). 비어 있으면 매일
    days        varchar(30)   not null default '',
    time_of_day time          not null,
    enabled     boolean       not null default true,
    next_run_at timestamptz   not null,
    last_run_at timestamptz,
    -- 실행 중인 워커의 표시. 실패하거나 서버가 죽으면 일정 시간 뒤 다시 가져간다
    claimed_at  timestamptz,
    created_at  timestamptz   not null default now(),
    updated_at  timestamptz   not null default now()
);
create index idx_routines_due on routines (next_run_at) where enabled;

-- 알림함. 아직 푸시가 없어 다음 채팅·통화의 맥락으로 전달된다
create table notifications (
    id         uuid primary key default gen_random_uuid(),
    routine_id uuid references routines (id) on delete set null,
    title      varchar(200) not null,
    body       text         not null,
    created_at timestamptz  not null default now(),
    read_at    timestamptz
);
create index idx_notifications_unread on notifications (created_at desc) where read_at is null;

-- 말로 남기는 활동 기록. 3단계 성장 회고의 재료
create table activity_logs (
    id                     uuid primary key default gen_random_uuid(),
    category               varchar(20)  not null check (category in ('STUDY', 'EXERCISE', 'WORK', 'OTHER')),
    description            varchar(300) not null,
    minutes                int check (minutes between 1 and 1440),
    occurred_on            date         not null,
    source_conversation_id uuid references conversations (id) on delete set null,
    created_at             timestamptz  not null default now()
);
create index idx_activity_logs_date on activity_logs (occurred_on desc);
