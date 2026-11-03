package com.samoyed.ai_manager_server.task

import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

interface TaskRepository : JpaRepository<Task, UUID> {
    fun findTop20ByOrderByCreatedAtDesc(): List<Task>

    fun countByStatusAndFinishedAtBetween(status: TaskStatus, from: java.time.OffsetDateTime, to: java.time.OffsetDateTime): Long

    /**
     * 작업의 문서는 DB의 on delete set null로 남는다.
     * 처리 중인 작업이면 워커가 조건부 update로 결과를 버리므로 취소처럼 동작한다
     */
    @Transactional
    @Modifying
    @Query("delete from Task t where t.id = :id")
    fun deleteReturningCount(id: UUID): Int
}

/** 문서 목록용 projection. 본문(content)은 읽지 않는다 */
interface DocumentSummary {
    val id: UUID
    val taskId: UUID?
    val title: String
    val createdAt: OffsetDateTime
}

/** 작업 → 문서 연결용 projection */
interface DocumentRef {
    val id: UUID
    val taskId: UUID?
}

interface DocumentRepository : JpaRepository<Document, UUID> {
    fun findAllByOrderByCreatedAtDesc(limit: Limit): List<DocumentSummary>
    fun findByTaskIdIn(taskIds: Collection<UUID>): List<DocumentRef>
    fun findRefByTaskId(taskId: UUID): DocumentRef?

    @Transactional
    @Modifying
    @Query("delete from Document d where d.id = :id")
    fun deleteReturningCount(id: UUID): Int
}
