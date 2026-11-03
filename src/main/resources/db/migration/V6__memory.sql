-- 통화 기록도 대화로 저장하고, 대화마다 요약과 기억 추출 상태를 둔다
alter table conversations
    add column channel             varchar(10) not null default 'CHAT' check (channel in ('CHAT', 'VOICE')),
    add column summary             text,
    -- 마지막으로 기억을 추출한 시점의 updated_at. 이후 새 메시지가 오면 updated_at이 더 커져 다시 대상이 된다
    add column memory_processed_at timestamptz,
    -- 기억 추출 중인 워커의 표시. 실패하거나 서버가 죽으면 일정 시간 뒤 다른 워커가 다시 가져간다
    add column memory_claimed_at   timestamptz;

create index idx_conversations_memory_pending on conversations (updated_at)
    where memory_processed_at is null or memory_processed_at < updated_at;

-- 대화에서 뽑은, 사용자에 대해 오래 유효한 정보
create table memories (
    id                     uuid primary key default gen_random_uuid(),
    category               varchar(20)  not null
        check (category in ('FACT', 'PREFERENCE', 'ROUTINE', 'GOAL', 'PROGRESS', 'OTHER')),
    content                varchar(500) not null,
    source_conversation_id uuid references conversations (id) on delete set null,
    created_at             timestamptz  not null default now(),
    updated_at             timestamptz  not null default now()
);
create index idx_memories_updated on memories (updated_at desc);
