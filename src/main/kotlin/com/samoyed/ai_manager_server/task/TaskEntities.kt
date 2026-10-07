package com.samoyed.ai_manager_server.task

import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.UUID

enum class TaskStatus { PENDING, RUNNING, DONE, FAILED }

@Entity
@Table(name = "tasks")
class Task(
    @Column(nullable = false, length = 50)
    val type: String,

    @Column(nullable = false, columnDefinition = "text")
    val instruction: String,

    @Column(columnDefinition = "text")
    val context: String? = null,
) {
    @Id
    val id: UUID = UUID.randomUUID()

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: TaskStatus = TaskStatus.PENDING

    @Column(nullable = false)
    var attempts: Int = 0

    @Column(columnDefinition = "text")
    var error: String? = null

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "started_at")
    var startedAt: OffsetDateTime? = null

    @Column(name = "finished_at")
    var finishedAt: OffsetDateTime? = null
}

@Entity
@Table(name = "documents")
class Document(
    @Column(name = "task_id")
    val taskId: UUID?,

    @Column(nullable = false, length = 300)
    var title: String,

    @Column(nullable = false, columnDefinition = "text")
    var content: String,
) {
    @Id
    val id: UUID = UUID.randomUUID()

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
}