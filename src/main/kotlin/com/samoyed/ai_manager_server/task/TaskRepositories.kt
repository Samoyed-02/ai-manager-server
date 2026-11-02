package com.samoyed.ai_manager_server.task

import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime
import java.util.UUID

interface TaskRepository : JpaRepository<Task, UUID> {
    fun findTop20ByOrderByCreatedAtDesc(): List<Task>
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
}
