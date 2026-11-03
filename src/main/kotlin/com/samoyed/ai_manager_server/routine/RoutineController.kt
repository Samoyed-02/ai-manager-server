package com.samoyed.ai_manager_server.routine

import com.samoyed.ai_manager_server.config.InvalidRequestResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.data.domain.Limit
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.UUID

data class RoutineDto(
    val id: UUID,
    val title: String,
    val kind: RoutineKind,
    val message: String,
    val days: List<String>,
    val time: String,
    val schedule: String,
    val enabled: Boolean,
    val nextRunAt: OffsetDateTime,
    val lastRunAt: OffsetDateTime?,
)

private const val TIME_PATTERN = "^([01]?\\d|2[0-3]):[0-5]\\d$"

data class RoutineRequest(
    @field:Size(min = 1, max = MAX_ROUTINE_TITLE, message = "1~{max}자여야 합니다")
    val title: String? = null,
    val kind: RoutineKind? = null,
    @field:Size(min = 1, max = MAX_ROUTINE_MESSAGE, message = "1~{max}자여야 합니다")
    val message: String? = null,
    @field:Pattern(regexp = TIME_PATTERN, message = "HH:mm 형식이어야 합니다")
    val time: String? = null,
    val days: List<String>? = null,
    val enabled: Boolean? = null,
)

data class NotificationDto(val id: UUID, val routineId: UUID?, val title: String, val body: String, val createdAt: OffsetDateTime, val readAt: OffsetDateTime?)

/** 화면에서 루틴을 보고 고친다. 규칙은 말로 등록할 때(도구)와 같다 */
@RestController
@RequestMapping("/routines")
class RoutineController(private val routines: RoutineRepository, private val service: RoutineService) {

    @GetMapping
    fun list(): List<RoutineDto> = routines.findAllByOrderByTimeOfDayAsc().map { it.toDto() }

    @PostMapping
    fun create(@Valid @RequestBody req: RoutineRequest): ResponseEntity<Any> = handle(HttpStatus.CREATED) {
        val missing = listOfNotNull("title".takeIf { req.title == null }, "kind".takeIf { req.kind == null },
            "message".takeIf { req.message == null }, "time".takeIf { req.time == null })
        if (missing.isNotEmpty()) throw RoutineValidationException("필수 항목이 없습니다: ${missing.joinToString()}")
        service.create(req.title!!, req.kind!!, req.message!!, LocalTime.parse(req.time!!.padStart(5, '0')), days(req.days) ?: emptySet())
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @Valid @RequestBody req: RoutineRequest): ResponseEntity<Any> {
        if (!routines.existsById(id)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return handle(HttpStatus.OK) {
            service.update(
                id,
                RoutineChange(req.title, req.kind, req.message, req.time?.let { LocalTime.parse(it.padStart(5, '0')) }, days(req.days), req.enabled),
            )
        }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) {
        if (!routines.existsById(id)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        service.delete(id)
    }

    private fun days(codes: List<String>?) = codes?.map {
        RoutineSchedule.parseDay(it) ?: throw RoutineValidationException("요일은 MON~SUN이어야 합니다: $it")
    }?.toSet()

    private fun handle(status: HttpStatus, action: () -> Routine): ResponseEntity<Any> =
        try {
            ResponseEntity.status(status).body(action().toDto())
        } catch (e: RoutineValidationException) {
            ResponseEntity.badRequest().body(InvalidRequestResponse(fields = mapOf("routine" to e.message!!)))
        }
}

fun Routine.toDto() = RoutineDto(
    id, title, kind, message, dayOfWeeks.map { it.name.take(3) }, "%02d:%02d".format(timeOfDay.hour, timeOfDay.minute),
    scheduleLabel(), enabled, nextRunAt, lastRunAt,
)

/** 루틴 결과가 쌓이는 알림함 */
@RestController
@RequestMapping("/notifications")
class NotificationController(private val notifications: NotificationRepository) {

    @GetMapping
    fun list(@RequestParam(defaultValue = "false") unread: Boolean): List<NotificationDto> =
        (if (unread) notifications.findByReadAtIsNullOrderByCreatedAtDesc(Limit.of(50))
        else notifications.findAllByOrderByCreatedAtDesc(Limit.of(50)))
            .map { NotificationDto(it.id, it.routineId, it.title, it.body, it.createdAt, it.readAt) }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun read(@PathVariable id: UUID) {
        val notification = notifications.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        if (notification.readAt == null) {
            notification.readAt = OffsetDateTime.now()
            notifications.save(notification)
        }
    }
}
