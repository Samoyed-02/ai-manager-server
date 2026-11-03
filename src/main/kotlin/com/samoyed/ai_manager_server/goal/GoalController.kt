package com.samoyed.ai_manager_server.goal

import com.samoyed.ai_manager_server.activity.ActivityCategory
import com.samoyed.ai_manager_server.config.InvalidRequestResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.util.UUID

data class GoalDto(
    val id: UUID,
    val title: String,
    val description: String?,
    val category: ActivityCategory,
    val status: GoalStatus,
    val weeklyTargetMinutes: Int?,
    val dueDate: LocalDate?,
    val thisWeekMinutes: Long,
    val daysLeft: Long?,
    val progress: String,
)

data class GoalRequest(
    val title: String? = null,
    val description: String? = null,
    val category: ActivityCategory? = null,
    val weeklyTargetMinutes: Int? = null,
    val dueDate: LocalDate? = null,
    val status: GoalStatus? = null,
)

/** 화면에서 목표를 보고 고친다. 규칙은 말로 등록할 때(도구)와 같다 */
@RestController
@RequestMapping("/goals")
class GoalController(private val goals: GoalRepository, private val service: GoalService) {

    @GetMapping
    fun list(@RequestParam(required = false) status: GoalStatus?): List<GoalDto> =
        (if (status == null) goals.findAllByOrderByCreatedAtDesc() else goals.findByStatusOrderByCreatedAtAsc(status)).map { it.toDto() }

    @PostMapping
    fun create(@RequestBody req: GoalRequest): ResponseEntity<Any> = handle(HttpStatus.CREATED) {
        if (req.title == null || req.category == null) throw GoalValidationException("title과 category는 필수입니다")
        service.create(req.title, req.category, req.description, req.weeklyTargetMinutes, req.dueDate)
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody req: GoalRequest): ResponseEntity<Any> {
        if (!goals.existsById(id)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return handle(HttpStatus.OK) {
            service.update(id, GoalChange(req.title, req.description, req.category, req.weeklyTargetMinutes, req.dueDate, req.status))
        }
    }

    private fun handle(status: HttpStatus, action: () -> Goal): ResponseEntity<Any> =
        try {
            ResponseEntity.status(status).body(action().toDto())
        } catch (e: GoalValidationException) {
            ResponseEntity.badRequest().body(InvalidRequestResponse(fields = mapOf("goal" to e.message!!)))
        }

    private fun Goal.toDto(): GoalDto {
        val p = service.progress(this)
        return GoalDto(id, title, description, category, status, weeklyTargetMinutes, dueDate, p.weekMinutes, p.daysLeft, p.line())
    }
}
