package com.samoyed.ai_manager_server.review

import jakarta.persistence.*
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "reviews")
class Review(
    @Column(name = "period_start", nullable = false)
    val periodStart: LocalDate,

    @Column(name = "period_end", nullable = false)
    val periodEnd: LocalDate,

    /** 서버가 계산한 통계(JSON) */
    @Column(nullable = false, columnDefinition = "text")
    val stats: String,

    @Column(nullable = false, columnDefinition = "text")
    val content: String,

    @Column(name = "routine_id")
    val routineId: UUID? = null,
) {
    @Id
    val id: UUID = UUID.randomUUID()

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()
}

interface ReviewRepository : JpaRepository<Review, UUID> {
    fun findAllByOrderByCreatedAtDesc(limit: Limit): List<Review>
}
