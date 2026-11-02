-- 이미 중복된 행이 있으면 인덱스 생성이 실패하므로, 작업마다 가장 먼저 만들어진 문서만 남긴다
delete from documents d
 using documents k
 where d.task_id = k.task_id
   and (d.created_at, d.id) > (k.created_at, k.id);

create unique index uq_documents_task_id on documents (task_id);
