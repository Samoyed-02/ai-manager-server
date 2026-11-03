package com.samoyed.ai_manager_server.memory

import com.samoyed.ai_manager_server.config.InvalidRequestResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID

data class MemoryDto(
    val id: UUID,
    val category: MemoryCategory,
    val content: String,
    val sourceConversationId: UUID?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

data class MemoryRequest(
    val category: MemoryCategory,

    @field:NotBlank(message = "비어 있을 수 없습니다")
    @field:Size(max = MAX_MEMORY_CONTENT, message = "최대 {max}자까지 입력할 수 있습니다")
    val content: String,
)

/** AI가 나에 대해 무엇을 기억하는지 보고 고친다 */
@RestController
@RequestMapping("/memories")
class MemoryController(private val memories: MemoryRepository) {

    @GetMapping
    fun list(@RequestParam(required = false) category: MemoryCategory?): List<MemoryDto> =
        (if (category == null) memories.findAll().sortedByDescending { it.updatedAt }
        else memories.findByCategoryOrderByUpdatedAtDesc(category))
            .map { it.toDto() }

    @PostMapping
    fun create(@Valid @RequestBody req: MemoryRequest): ResponseEntity<Any> {
        rejectSensitive(req.content)?.let { return it }
        val saved = memories.save(Memory(req.category, req.content.trim()))
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toDto())
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @Valid @RequestBody req: MemoryRequest): ResponseEntity<Any> {
        val memory = memories.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        rejectSensitive(req.content)?.let { return it }
        memory.category = req.category
        memory.content = req.content.trim()
        memory.updatedAt = OffsetDateTime.now()
        return ResponseEntity.ok(memories.save(memory).toDto())
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) {
        if (!memories.existsById(id)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        memories.deleteById(id)
    }

    /** 직접 입력한 기억도 모든 맥락에 들어가므로 민감 정보는 받지 않는다 */
    private fun rejectSensitive(content: String): ResponseEntity<Any>? =
        if (SensitiveInfoFilter.isSensitive(content)) {
            ResponseEntity.badRequest().body(
                InvalidRequestResponse(fields = mapOf("content" to "비밀번호·카드번호 같은 민감 정보는 기억으로 저장할 수 없습니다"))
            )
        } else null

    private fun Memory.toDto() = MemoryDto(id, category, content, sourceConversationId, createdAt, updatedAt)
}
