-- 답변을 만들면서 서버가 실제로 실행한 작업(도구 호출, 웹 검색). 한 줄에 하나.
-- 다음 요청 때 모델에게 근거로 보여 줘, 실제로 한 일을 지어냈다고 오해하지 않게 한다
alter table messages add column actions text;
