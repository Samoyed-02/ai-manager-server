package com.samoyed.ai_manager_server.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 실제 API 점검(2026-11-05)에서 받은 검색 결과와 답변으로 확인한다 */
class SearchResultPickerTest {

    @Test
    fun `답변과 제목이 겹치는 페이지만 점수 순으로 고른다`() {
        val answer = "오늘(11/5 목) 서울은 흐리고 비가 가끔 오는 날씨입니다. 아침에 12도 안팎으로 시작했고, 낮 최고는 17도로 예보됐습니다. " +
            "서울 예상 강수량은 6일 새벽까지 5mm 안팎이라 많지 않습니다. 내일부터 북쪽 찬 공기가 내려와 기온이 크게 떨어집니다. " +
            "입동인 7일 아침에는 서울 최저기온이 영하 4도 안팎으로 예보됐고, 올가을 첫 영하권 추위가 예상됩니다."
        val results = listOf(
            Source("https://www.accuweather.com/ko/kr/seoul/226081/weather-forecast/226081", "서울특별시, 서울시, 대한민국 3일 날씨 예보"),
            Source("https://news.sbs.co.kr/news/endPage.do?news_id=N1008831297", "(날씨) 전국 곳곳 비…내일부터 다시 찬 바람"),
            Source("https://en.wikipedia.org/wiki/Seoul_City_Hall", "Seoul City Hall"),
            Source("https://www.topstarnews.net/news/articleView.html?idxno=16268644", "(오늘날씨) 전국 비·남해안 최대 40㎜…돌풍·천둥번개, 내일부터 추위"),
        )

        val picked = pickSearchResults(answer, results, 5)

        assertEquals(listOf("(날씨) 전국 곳곳 비…내일부터 다시 찬 바람", "(오늘날씨) 전국 비·남해안 최대 40㎜…돌풍·천둥번개, 내일부터 추위", "서울특별시, 서울시, 대한민국 3일 날씨 예보"), picked.map { it.title })
    }

    @Test
    fun `포털 첫 화면은 고르지 않고 앞쪽에 없던 기사도 고른다`() {
        val answer = "금융회사 37곳에서 해킹으로 정보 약 47만건이 유출됐습니다. 삼성전자는 3분기 사상 최대 실적을 냈습니다."
        val results = listOf(
            Source("https://news.daum.net/tech", "다음뉴스"),
            Source("https://www.itdaily.kr/", "아이티데일리"),
            Source("https://www.itbiznews.com/", "IT비즈뉴스"),
            Source("https://news.daum.net/it-tech", "다음뉴스"),
            Source("https://www.example.com/news/1", "다른 기사"),
            Source("https://www.blockmedia.co.kr/archives/1161560", "금융사 37곳서 해킹…정보 47만건 유출"),
            Source("https://www.example.com/news/2", "삼성전자 3분기 사상 최대 실적"),
        )

        val picked = pickSearchResults(answer, results, 5)

        assertEquals(listOf("https://www.example.com/news/2", "https://www.blockmedia.co.kr/archives/1161560").sorted(), picked.map { it.url }.sorted())
    }

    @Test
    fun `AI·IT 같은 두 글자 영어 단어만 겹치는 제목은 고르지 않는다`() {
        val answer = "AI 반도체 수요로 삼성전자가 기흥캠퍼스를 재편합니다. 국내 IT 업계 결과 발표가 이어졌습니다."
        val results = listOf(
            Source("https://news.samsung.com/medialibrary/kr/search/AI?page=117", "\"AI\"의 검색결과"),
            Source("https://jeongjailronews.substack.com/p/2026-11", "정자일로 IT 뉴스"),
            Source("https://www.fnnews.com/news/202611042120579216", "AI칩 수요 대응해 기흥캠 재편… 2030년까지 20조 투입 예고"),
        )

        assertEquals(listOf("https://www.fnnews.com/news/202611042120579216"), pickSearchResults(answer, results, 5).map { it.url })
    }

    @Test
    fun `통과한 페이지가 limit보다 많으면 limit개만 고른다`() {
        val results = (1..6).map { Source("https://news.example.com/$it", "서울 날씨 소식 $it") }

        assertEquals(results.take(5), pickSearchResults("서울 날씨 소식입니다.", results, 5))
    }

    @Test
    fun `하나도 겹치지 않으면 첫 화면을 뺀 앞쪽 결과 3개를 대신 고른다`() {
        val results = listOf(
            Source("https://news.daum.net/tech", "다음뉴스"),
            Source("https://a.example.com/articles/1", "기사 A"),
            Source("https://b.example.com/articles/2", "기사 B"),
            Source("https://c.example.com/articles/3", "기사 C"),
            Source("https://d.example.com/articles/4", "기사 D"),
        )

        assertEquals(results.subList(1, 4), pickSearchResults("요약입니다.", results, 5))
    }

    @Test
    fun `첫 화면뿐이면 그래도 앞쪽 결과를 보여 준다`() {
        val results = listOf(Source("https://news.daum.net/tech", "다음뉴스"), Source("https://www.itdaily.kr/", "아이티데일리"))

        assertEquals(results, pickSearchResults("요약입니다.", results, 5))
    }
}
