package com.samoyed.ai_manager_server.review

import com.anthropic.core.jsonMapper
import org.springframework.data.domain.Limit
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class ReviewSummaryDto(val id: UUID, val periodStart: LocalDate, val periodEnd: LocalDate, val createdAt: OffsetDateTime)
data class ReviewDto(
    val id: UUID,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val stats: Map<String, Any?>,
    val content: String,
    val createdAt: OffsetDateTime,
)

@RestController
@RequestMapping("/reviews")
class ReviewController(private val reviews: ReviewRepository) {

    @GetMapping
    fun list(): List<ReviewSummaryDto> =
        reviews.findAllByOrderByCreatedAtDesc(Limit.of(20)).map { ReviewSummaryDto(it.id, it.periodStart, it.periodEnd, it.createdAt) }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): ReviewDto {
        val review = reviews.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        @Suppress("UNCHECKED_CAST")
        val stats = jsonMapper().readValue(review.stats, Map::class.java) as Map<String, Any?>
        return ReviewDto(review.id, review.periodStart, review.periodEnd, stats, review.content, review.createdAt)
    }
}
