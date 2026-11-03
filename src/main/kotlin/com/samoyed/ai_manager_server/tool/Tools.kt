package com.samoyed.ai_manager_server.tool

import com.samoyed.ai_manager_server.activity.ActivityCategory
import com.samoyed.ai_manager_server.activity.ActivityLog
import com.samoyed.ai_manager_server.activity.ActivityLogRepository
import com.samoyed.ai_manager_server.activity.MAX_ACTIVITY_DESCRIPTION
import com.samoyed.ai_manager_server.memory.MAX_MEMORY_CONTENT
import com.samoyed.ai_manager_server.memory.Memory
import com.samoyed.ai_manager_server.memory.MemoryCategory
import com.samoyed.ai_manager_server.memory.MemoryRepository
import com.samoyed.ai_manager_server.memory.SensitiveInfoFilter
import com.samoyed.ai_manager_server.routine.MAX_ROUTINE_MESSAGE
import com.samoyed.ai_manager_server.routine.MAX_ROUTINE_TITLE
import com.samoyed.ai_manager_server.routine.Routine
import com.samoyed.ai_manager_server.routine.RoutineChange
import com.samoyed.ai_manager_server.routine.RoutineKind
import com.samoyed.ai_manager_server.routine.RoutineRepository
import com.samoyed.ai_manager_server.routine.RoutineSchedule
import com.samoyed.ai_manager_server.routine.RoutineService
import com.samoyed.ai_manager_server.routine.RoutineValidationException
import com.samoyed.ai_manager_server.routine.scheduleLabel
import com.samoyed.ai_manager_server.task.MAX_TASK_CONTEXT
import com.samoyed.ai_manager_server.task.MAX_TASK_INSTRUCTION
import com.samoyed.ai_manager_server.task.Task
import com.samoyed.ai_manager_server.task.TaskRepository
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.LocalDate

const val CREATE_TASK_TOOL = "create_task"

private val DAY_CODES = DayOfWeek.entries.map { it.name.take(3) }

private fun daysProp() = mapOf(
    "type" to "array",
    "items" to mapOf("type" to "string", "enum" to DAY_CODES),
    "description" to "실행할 요일. 비우거나 생략하면 매일",
)

private fun ToolInput.days(name: String): Set<DayOfWeek>? =
    optionalStringList(name)?.map { code ->
        RoutineSchedule.parseDay(code) ?: throw ToolInputException("$name 값 '$code'은(는) 쓸 수 없어. 가능한 값: ${DAY_CODES.joinToString()}")
    }?.toSet()

private fun json(vararg pairs: Pair<String, Any?>): String =
    pairs.joinToString(",", "{", "}") { (k, v) ->
        "\"$k\":" + when (v) {
            null -> "null"
            is Number, is Boolean -> v.toString()
            else -> "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
    }

private fun Routine.toJson() = json(
    "id" to id, "title" to title, "kind" to kind.name, "schedule" to scheduleLabel(),
    "enabled" to enabled, "message" to message,
)

/** 작성자(TaskWorker)는 대화를 볼 수 없으므로 필요한 내용은 context에 담게 한다 */
@Component
class CreateTaskTool(private val tasks: TaskRepository) : AgentTool {
    override val name = CREATE_TASK_TOOL
    override val description = """
        문서 작성 작업을 백그라운드 작성자에게 맡긴다. 자기소개서, 보고서, 계획서처럼 긴 문서를 써 달라고 사용자가 분명히 요청했을 때만 호출해.
        짧은 답이나 대화 중 설명은 직접 답하고, 사용자가 원하는지 애매하면 호출하지 말고 먼저 물어봐.
        작성자는 이 대화를 볼 수 없으니, 대화에서 나온 요구사항·배경·사실을 context에 빠짐없이 담아.
        작업은 바로 만들어지고 몇 분 안에 문서가 완성된다.
    """.trimIndent()
    override val inputSchema = objectSchema(
        listOf("instruction"),
        "instruction" to stringProp("작성할 문서와 요구사항. 예: 'OO기업 백엔드 신입 지원용 자기소개서 1번 문항 초안'", MAX_TASK_INSTRUCTION),
        "context" to stringProp("작성자가 알아야 할 대화 내용, 배경, 사실. 작성자는 대화를 볼 수 없다", MAX_TASK_CONTEXT),
    )
    override val maxPerReply = 1

    override fun run(input: ToolInput, ctx: ToolContext): ToolResult {
        val task = tasks.save(Task("document", input.string("instruction", MAX_TASK_INSTRUCTION), input.optionalString("context", MAX_TASK_CONTEXT)))
        return ToolResult(true, json("task_id" to task.id, "status" to "PENDING"), "문서 작성 맡김: ${task.instruction}", task)
    }
}

@Component
class SaveMemoryTool(private val memories: MemoryRepository) : AgentTool {
    override val name = "save_memory"
    override val description = """
        사용자가 "기억해", "앞으로 ~해줘"처럼 오래 기억하길 원하는 사실·선호·루틴·목표·진행 상황을 저장한다.
        대화가 끝나면 서버가 자동으로도 기억을 정리하니, 사용자가 명시적으로 부탁했거나 꼭 남겨야 할 때만 호출해.
        비밀번호, 카드·계좌번호, 주민번호, API 키 같은 민감 정보는 저장하지 마(서버도 거절한다).
        내용은 '사용자는 ~'으로 시작하는 한 문장.
    """.trimIndent()
    override val inputSchema = objectSchema(
        listOf("category", "content"),
        "category" to stringProp("FACT 사실, PREFERENCE 선호, ROUTINE 루틴, GOAL 목표, PROGRESS 진행 상황, OTHER 기타", enum = MemoryCategory.entries.map { it.name }),
        "content" to stringProp("기억할 내용 한 문장", MAX_MEMORY_CONTENT),
    )
    override val maxPerReply = 5

    override fun run(input: ToolInput, ctx: ToolContext): ToolResult {
        val category = input.enum<MemoryCategory>("category")
        val content = input.string("content", MAX_MEMORY_CONTENT)
        if (SensitiveInfoFilter.isSensitive(content)) return ToolResult.error("민감 정보로 보여 저장하지 않았어. 사용자에게 민감 정보는 기억하지 않는다고 알려.")
        val memory = memories.save(Memory(category, content, ctx.conversationId))
        return ToolResult(true, json("memory_id" to memory.id), "기억함: $content")
    }
}

@Component
class CreateRoutineTool(private val service: RoutineService) : AgentTool {
    override val name = "create_routine"
    override val description = """
        정해진 시각(한국 시간)에 반복 실행되는 루틴을 등록한다. 결과는 알림함에 쌓이고 다음 대화에서 사용자에게 전한다.
        kind=REMINDER: message를 그대로 알림으로 보낸다. 예: "매일 밤 10시에 오늘 공부한 거 기록하라고 알려줘"
        kind=BRIEFING: message를 지시로 삼아 AI가 사용자의 기억·활동 기록·작업을 보고 브리핑을 작성한다. 예: "매일 아침 8시에 오늘 할 일 정리해줘"
        시각이나 요일이 애매하면 등록하기 전에 물어봐.
    """.trimIndent()
    override val inputSchema = objectSchema(
        listOf("title", "kind", "message", "time"),
        "title" to stringProp("루틴 이름. 예: '아침 브리핑'", MAX_ROUTINE_TITLE),
        "kind" to stringProp("REMINDER 또는 BRIEFING", enum = RoutineKind.entries.map { it.name }),
        "message" to stringProp("REMINDER면 보낼 문구, BRIEFING이면 브리핑에 담을 내용 지시", MAX_ROUTINE_MESSAGE),
        "time" to stringProp("실행 시각 HH:mm (24시간, 한국 시간)", pattern = "^\\d{1,2}:\\d{2}$"),
        "days" to daysProp(),
    )
    override val maxPerReply = 3

    override fun run(input: ToolInput, ctx: ToolContext): ToolResult {
        val time = input.optionalTime("time") ?: throw ToolInputException("time 은(는) 필수야. HH:mm 형식.")
        val routine = try {
            service.create(
                input.string("title", MAX_ROUTINE_TITLE), input.enum<RoutineKind>("kind"),
                input.string("message", MAX_ROUTINE_MESSAGE), time, input.days("days") ?: emptySet(),
            )
        } catch (e: RoutineValidationException) {
            return ToolResult.error(e.message!!)
        }
        return ToolResult(true, routine.toJson(), "루틴 등록: ${routine.title} (${routine.scheduleLabel()})")
    }
}

@Component
class ListRoutinesTool(private val routines: RoutineRepository) : AgentTool {
    override val name = "list_routines"
    override val description = "등록된 루틴 목록을 id와 함께 본다. 루틴을 수정·삭제하기 전에 id를 확인할 때 쓴다."
    override val inputSchema = objectSchema(emptyList())
    override val maxPerReply: Int? = null

    override fun run(input: ToolInput, ctx: ToolContext): ToolResult =
        ToolResult(true, routines.findAllByOrderByTimeOfDayAsc().joinToString(",", "[", "]") { it.toJson() })
}

@Component
class UpdateRoutineTool(private val service: RoutineService) : AgentTool {
    override val name = "update_routine"
    override val description = """
        루틴을 켜거나 끄고, 이름·종류·내용·시각·요일을 바꾼다. 바꿀 항목만 넣는다. id는 list_routines로 먼저 확인해.
    """.trimIndent()
    override val inputSchema = objectSchema(
        listOf("id"),
        "id" to stringProp("루틴 id"),
        "enabled" to mapOf("type" to "boolean", "description" to "false면 끄고 true면 켠다"),
        "title" to stringProp("새 이름", MAX_ROUTINE_TITLE),
        "kind" to stringProp("REMINDER 또는 BRIEFING", enum = RoutineKind.entries.map { it.name }),
        "message" to stringProp("새 문구나 지시", MAX_ROUTINE_MESSAGE),
        "time" to stringProp("새 시각 HH:mm", pattern = "^\\d{1,2}:\\d{2}$"),
        "days" to daysProp(),
    )
    override val maxPerReply = 3

    override fun run(input: ToolInput, ctx: ToolContext): ToolResult {
        val change = RoutineChange(
            title = input.optionalString("title", MAX_ROUTINE_TITLE),
            kind = input.optionalEnum<RoutineKind>("kind"),
            message = input.optionalString("message", MAX_ROUTINE_MESSAGE),
            time = input.optionalTime("time"),
            days = input.days("days"),
            enabled = input.optionalBoolean("enabled"),
        )
        val routine = try {
            service.update(input.uuid("id"), change)
        } catch (e: RoutineValidationException) {
            return ToolResult.error(e.message!!)
        }
        val state = if (routine.enabled) routine.scheduleLabel() else "꺼짐"
        return ToolResult(true, routine.toJson(), "루틴 수정: ${routine.title} ($state)")
    }
}

@Component
class DeleteRoutineTool(private val service: RoutineService) : AgentTool {
    override val name = "delete_routine"
    override val description = """
        루틴을 영구 삭제한다. 되돌릴 수 없으니, 어떤 루틴을 지울지 사용자에게 이름으로 다시 말해 주고 분명히 확인받은 뒤에만 호출해.
        잠시 멈추려는 거면 삭제 대신 update_routine으로 끈다. id는 list_routines로 먼저 확인해.
    """.trimIndent()
    override val inputSchema = objectSchema(listOf("id"), "id" to stringProp("삭제할 루틴 id"))
    override val maxPerReply = 3

    override fun run(input: ToolInput, ctx: ToolContext): ToolResult {
        val routine = try {
            service.delete(input.uuid("id"))
        } catch (e: RoutineValidationException) {
            return ToolResult.error(e.message!!)
        }
        return ToolResult(true, json("deleted" to routine.id), "루틴 삭제: ${routine.title}")
    }
}

@Component
class LogActivityTool(private val activities: ActivityLogRepository) : AgentTool {
    override val name = "log_activity"
    override val description = """
        사용자가 한 일을 기록한다. 예: "오늘 알고리즘 2시간 했어", "어제 헬스 1시간". 성장 기록과 회고에 쓰인다.
        시간을 말하지 않았으면 minutes는 비운다. 날짜를 말하지 않았으면 date는 비운다(오늘로 기록).
    """.trimIndent()
    override val inputSchema = objectSchema(
        listOf("description", "category"),
        "description" to stringProp("한 일. 예: '알고리즘 문제 풀이 (백준 DP 3문제)'", MAX_ACTIVITY_DESCRIPTION),
        "category" to stringProp("STUDY 공부, EXERCISE 운동, WORK 일, OTHER 기타", enum = ActivityCategory.entries.map { it.name }),
        "minutes" to mapOf("type" to "integer", "description" to "걸린 시간(분), 1~1440", "minimum" to 1, "maximum" to 1440),
        "date" to stringProp("한 날짜 YYYY-MM-DD (한국 날짜). 생략하면 오늘"),
    )
    override val maxPerReply = 5

    override fun run(input: ToolInput, ctx: ToolContext): ToolResult {
        val today = LocalDate.now(RoutineSchedule.ZONE)
        val date = input.optionalDate("date") ?: today
        if (date.isAfter(today)) throw ToolInputException("date 는 미래일 수 없어.")
        val log = activities.save(
            ActivityLog(
                category = input.enum<ActivityCategory>("category"),
                description = input.string("description", MAX_ACTIVITY_DESCRIPTION),
                minutes = input.optionalInt("minutes", 1..1440),
                occurredOn = date,
                sourceConversationId = ctx.conversationId,
            )
        )
        val time = log.minutes?.let { " ${it}분" } ?: ""
        return ToolResult(true, json("activity_id" to log.id, "date" to log.occurredOn), "활동 기록: ${log.description}$time")
    }
}
