package com.samoyed.ai_manager_server.task

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
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
        tasks.save(Task(req.type, req.instruction, req.context)).toDto()

    @GetMapping("/tasks")
    fun list(): List<TaskDto> = tasks.findTop20ByOrderByCreatedAtDesc().map { it.toDto() }

    @GetMapping("/tasks/{id}")
    fun get(@PathVariable id: UUID): TaskDto =
        tasks.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }.toDto()

    @GetMapping("/documents")
    fun documents(): List<DocumentSummaryDto> =
        documents.findTop50ByOrderByCreatedAtDesc()
            .map { DocumentSummaryDto(it.id, it.taskId, it.title, it.createdAt) }

    @GetMapping("/documents/{id}")
    fun document(@PathVariable id: UUID): DocumentDto =
        documents.findById(id)
            .map { DocumentDto(it.id, it.taskId, it.title, it.content, it.createdAt) }
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }

    private fun Task.toDto() = TaskDto(
        id, type, instruction, status, error, createdAt, finishedAt,
        documentId = if (status == TaskStatus.DONE) documents.findByTaskId(id)?.id else null,
    )
}