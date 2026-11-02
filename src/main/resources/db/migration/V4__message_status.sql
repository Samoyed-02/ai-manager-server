-- assistant 응답이 정상 종료됐는지 기록한다. 기존 메시지는 모두 정상으로 본다
alter table messages
    add column status varchar(20) not null default 'COMPLETE'
        check (status in ('COMPLETE', 'TRUNCATED', 'REFUSED', 'INTERRUPTED'));
