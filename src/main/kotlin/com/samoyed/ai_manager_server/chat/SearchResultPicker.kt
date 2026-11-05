package com.samoyed.ai_manager_server.chat

import java.net.URI

/** 웹 검색 인용이나 읽은 페이지. 사용자에게 보여줄 때 출처를 함께 표시해야 한다 */
internal data class Source(val url: String, val title: String?)

/** 제목과 답변이 이만큼 겹쳐야 답변에 쓰인 페이지로 본다 */
private const val MIN_SHARED_BIGRAMS = 2
private const val MIN_TITLE_OVERLAP = 0.2
/** 하나도 통과하지 못했을 때 대신 보여 줄 수 */
private const val FALLBACK_SOURCES = 3
/** 경로가 이 길이 이하인 한 단계 경로(/tech, /news)는 섹션 첫 화면으로 본다 */
private const val MAX_SECTION_PATH = 12

/** 이 쿼리가 있으면 사이트 안 검색 결과 페이지로 본다 */
private val SEARCH_QUERY_KEYS = setOf("q", "query", "keyword")

private val WORD = Regex("[0-9a-z가-힣]+")

/**
 * 인용 없이 답한 경우 검색 결과 중 답변에 쓰였을 페이지를 고른다.
 * 제목을 두 글자 단위로 쪼개 답변과 겹치는 정도로 점수를 매기고, 점수 순으로 최대 limit개를 돌려준다.
 * 첫 화면과 검색 결과 페이지는 특정 기사가 아니라서 먼저 뺀다.
 * 하나도 통과하지 못해도 검색 결과는 출처와 함께 보여야 하므로, 남은 결과의 앞쪽을 대신 돌려준다
 */
internal fun pickSearchResults(answer: String, results: Collection<Source>, limit: Int): List<Source> {
    val answerBigrams = bigrams(answer)
    val candidates = results.filterNot { isListingPage(it.url) }.ifEmpty { results.toList() }
    val picked = candidates
        .mapNotNull { source ->
            val title = bigrams(source.title.orEmpty())
            val shared = title.count { it in answerBigrams }
            val overlap = if (title.isEmpty()) 0.0 else shared.toDouble() / title.size
            if (shared >= MIN_SHARED_BIGRAMS && overlap >= MIN_TITLE_OVERLAP) source to overlap else null
        }
        .sortedByDescending { it.second }
        .map { it.first }
        .take(limit)
    if (picked.isNotEmpty()) return picked
    return candidates.take(FALLBACK_SOURCES)
}

/** AI, IT처럼 두 글자 영어 단어는 거의 모든 답변에 나와서 겹쳐도 근거가 되지 않는다 */
private fun bigrams(text: String): Set<String> =
    WORD.findAll(text.lowercase())
        .map { it.value }
        .filter { word -> word.length > 2 || word.any { it in '가'..'힣' } }
        .flatMap { it.windowed(2) }
        .toSet()

/** 사이트나 섹션의 첫 화면, 사이트 안 검색 결과. 특정 기사가 아니라서 답변의 근거로 보여 주기 어렵다 */
private fun isListingPage(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val path = uri.path.orEmpty().trim('/')
    val section = path.isEmpty() || ('/' !in path && path.length <= MAX_SECTION_PATH && path.none { it.isDigit() })
    val search = path.split('/').any { it.equals("search", ignoreCase = true) } ||
        uri.rawQuery.orEmpty().split('&').any { it.substringBefore('=').lowercase() in SEARCH_QUERY_KEYS }
    return section || search
}
