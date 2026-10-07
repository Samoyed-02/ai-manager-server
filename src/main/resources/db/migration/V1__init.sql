create table conversations (
                               id         uuid primary key default gen_random_uuid(),
                               title      varchar(200),
                               created_at timestamptz not null default now(),
                               updated_at timestamptz not null default now()
);

create table messages (
                          id              bigserial primary key,
                          conversation_id uuid not null references conversations (id) on delete cascade,
                          role            varchar(20) not null check (role in ('user', 'assistant')),
                          content         text not null,
                          created_at      timestamptz not null default now()
);
create index idx_messages_conversation on messages (conversation_id, id);

create table tasks (
                       id          uuid primary key default gen_random_uuid(),
                       type        varchar(50) not null,
                       instruction text not null,
                       context     text,
                       status      varchar(20) not null default 'PENDING'
                           check (status in ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
                       attempts    int not null default 0,
                       error       text,
                       created_at  timestamptz not null default now(),
                       started_at  timestamptz,
                       finished_at timestamptz
);
create index idx_tasks_pending on tasks (created_at) where status = 'PENDING';

create table documents (
                           id         uuid primary key default gen_random_uuid(),
                           task_id    uuid references tasks (id) on delete set null,
                           title      varchar(300) not null,
                           content    text not null,
                           created_at timestamptz not null default now(),
                           updated_at timestamptz not null default now()
);