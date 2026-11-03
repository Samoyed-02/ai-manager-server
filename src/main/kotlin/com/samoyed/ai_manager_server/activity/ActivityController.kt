package com.samoyed.ai_manager_server.activity

import com.samoyed.ai_manager_server.routine.RoutineSchedule
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.util.UUID

data class ActivityDto(val id: UUID, val category: ActivityCategory, val description: String, val minutes: Int?, val date: LocalDate)

@RestController
@RequestMapping("/activities")
class ActivityController(private val activities: ActivityLogRepository) {

    /** 기본은 최근 7일 (한국 날짜) */
    @GetMapping
    fun list(@RequestParam(required = false) from: LocalDate?, @RequestParam(required = false) to: LocalDate?): List<ActivityDto> {
        val end = to ?: LocalDate.now(RoutineSchedule.ZONE)
        val start = from ?: end.minusDays(6)
        if (start.isAfter(end) || start.plusDays(366).isBefore(end)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "기간은 1~366일이어야 합니다")
        return activities.findByOccurredOnBetweenOrderByOccurredOnDescCreatedAtDesc(start, end)
            .map { ActivityDto(it.id, it.category, it.description, it.minutes, it.occurredOn) }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) {
        if (!activities.existsById(id)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        activities.deleteById(id)
    }
}
