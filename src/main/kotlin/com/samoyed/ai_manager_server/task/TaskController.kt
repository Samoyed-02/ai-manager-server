package com.samoyed.ai_manager_server.task

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.data.domain.Limit
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID

const val MAX_TASK_INSTRUCTION = 10_000
const val MAX_TASK_CONTEXT = 50_000

data class CreateTaskRequest(
    @field:NotBlank(message = "비어 있을 수 없습니다")
    @field:Size(max = MAX_TASK_INSTRUCTION, message = "최대 {max}자까지 입력할 수 있습니다")
    val instruction: String,

    /** 지금 처리할 수 있는 작업 종류는 document뿐이다 */
    @field:Pattern(regexp = "document", message = "지원하지 않는 작업 종류입니다")
    val type: String = "document",

    @field:Size(max = MAX_TASK_CONTEXT, message = "최대 {max}자까지 입력할 수 있습니다")
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

    /** 작업의 문서는 남는다 (task_id만 null이 된다) */
    @DeleteMapping("/tasks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteTask(@PathVariable id: UUID) {
        if (tasks.deleteReturningCount(id) == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }

    /** 작업은 DONE으로 남고 documentId만 null이 된다 */
    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteDocument(@PathVariable id: UUID) {
        if (documents.deleteReturningCount(id) == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }

    private fun Task.toDto(documentId: UUID?) = TaskDto(
        id, type, instruction, status, error, createdAt, finishedAt, documentId,
    )
}