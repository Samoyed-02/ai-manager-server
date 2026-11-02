package com.samoyed.ai_manager_server.task

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.data.domain.Limit
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID

data class CreateTaskRequest(
    @field:NotBlank val instruction: String,
    val type: String = "document",
    val context: String? = null,
)

data class TaskDto(
    val id: UUID,
    val type: String,
    val instruction: String,
    val status: TaskStatus,
    val error: String?,
    val createdAt: OffsetDateTime,
    val finishedAt: OffsetDateTime?,
    val documentId: UUID?,
)

data class DocumentSummaryDto(val id: UUID, val taskId: UUID?, val title: String, val createdAt: OffsetDateTime)
data class DocumentDto(val id: UUID, val taskId: UUID?, val title: String, val content: String, val createdAt: OffsetDateTime)

@RestController
class TaskController(
    private val tasks: TaskRepository,
    private val documents: DocumentRepository,
) {
    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun create(@Valid @RequestBody req: CreateTaskRequest): TaskDto =
        tasks.save(Task(req.type, req.instruction, req.context)).toDto(documentId = null)

    @GetMapping("/tasks")
    fun list(): List<TaskDto> {
        val recent = tasks.findTop20ByOrderByCreatedAtDesc()
        // DONE 작업의 문서 ID를 한 번에 조회한다 (task_id는 unique)
        val doneIds = recent.filter { it.status == TaskStatus.DONE }.map { it.id }
        val documentIds = if (doneIds.isEmpty()) emptyMap()
        else documents.findByTaskIdIn(doneIds).associate { it.taskId to it.id }
        return recent.map { it.toDto(documentIds[it.id]) }
    }

    @GetMapping("/tasks/{id}")
    fun get(@PathVariable id: UUID): TaskDto {
        val task = tasks.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        val documentId = if (task.status == TaskStatus.DONE) documents.findRefByTaskId(id)?.id else null
        return task.toDto(documentId)
    }

    @GetMapping("/documents")
    fun documents(): List<DocumentSummaryDto> =
        documents.findAllByOrderByCreatedAtDesc(Limit.of(50))
            .map { DocumentSummaryDto(it.id, it.taskId, it.title, it.createdAt) }

    @GetMapping("/documents/{id}")
    fun document(@PathVariable id: UUID): DocumentDto =
        documents.findById(id)
            .map { DocumentDto(it.id, it.taskId, it.title, it.content, it.createdAt) }
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }

    private fun Task.toDto(documentId: UUID?) = TaskDto(
        id, type, instruction, status, error, createdAt, finishedAt, documentId,
    )
}