-- 최근 문서 N개 조회(채팅·작업마다 실행되는 컨텍스트, GET /documents)가 전체 정렬을 하지 않도록
create index idx_documents_created_at on documents (created_at desc);
