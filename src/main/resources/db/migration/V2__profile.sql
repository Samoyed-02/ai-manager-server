create table profile (
                         id         int primary key default 1 check (id = 1),
                         full_md    text not null default '',
                         brief_md   text not null default '',
                         updated_at timestamptz not null default now()
);

insert into profile (id) values (1);