package com.samoyed.ai_manager_server.task

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TaskRepository : JpaRepository<Task, UUID> {
    fun findTop20ByOrderByCreatedAtDesc(): List<Task>
}

interface DocumentRepository : JpaRepository<Document, UUID> {
    fun findTop50ByOrderByCreatedAtDesc(): List<Document>
    fun findByTaskId(taskId: UUID): Document?
}