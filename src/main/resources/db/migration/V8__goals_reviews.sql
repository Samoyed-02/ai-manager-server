-- 사용자가 등록한 목표. 활동 기록을 연결해 주간 진행률을 계산한다
create table goals (
    id                    uuid primary key default gen_random_uuid(),
    title                 varchar(100)  not null,
    description           varchar(1000),
    category              varchar(20)   not null check (category in ('STUDY', 'EXERCISE', 'WORK', 'OTHER')),
    weekly_target_minutes int check (weekly_target_minutes between 1 and 10080),
    due_date              date,
    status                varchar(20)   not null default 'ACTIVE' check (status in ('ACTIVE', 'DONE', 'DROPPED')),
    created_at            timestamptz   not null default now(),
    updated_at            timestamptz   not null default now()
);

alter table activity_logs add column goal_id uuid references goals (id) on delete set null;
create index idx_activity_logs_goal on activity_logs (goal_id, occurred_on);

-- 주간 회고. 숫자(stats)는 서버가 계산하고 본문은 Claude가 쓴다
create table reviews (
    id           uuid primary key default gen_random_uuid(),
    period_start date        not null,
    period_end   date        not null,
    stats        text        not null,
    content      text        not null,
    routine_id   uuid references routines (id) on delete set null,
    created_at   timestamptz not null default now()
);
create index idx_reviews_created on reviews (created_at desc);

alter table routines drop constraint routines_kind_check;
alter table routines add constraint routines_kind_check check (kind in ('REMINDER', 'BRIEFING', 'REVIEW'));

-- 기본 주간 회고: 매주 일요일 21:00 (한국 시간). 말로 시각을 바꾸거나 끌 수 있다
insert into routines (title, kind, message, days, time_of_day, next_run_at)
select '주간 회고', 'REVIEW', '지난 한 주를 돌아보고 다음 주에 집중할 것을 제안해 줘', 'SUN', '21:00',
       case when this_week > now() then this_week else this_week + interval '7 days' end
  from (select (date_trunc('week', now() at time zone 'Asia/Seoul') + interval '6 days 21 hours') at time zone 'Asia/Seoul' as this_week) w;
