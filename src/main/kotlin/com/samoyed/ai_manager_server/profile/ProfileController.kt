package com.samoyed.ai_manager_server.profile

import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.*
import java.time.OffsetDateTime

data class ProfileDto(val fullMd: String, val briefMd: String, val updatedAt: OffsetDateTime)
/** fullMd는 모든 채팅·작업의 시스템 프롬프트에 들어가므로 길이를 제한한다 */
const val MAX_PROFILE_FULL = 20_000
const val MAX_PROFILE_BRIEF = 2_000

data class UpdateProfileRequest(
    @field:Size(max = MAX_PROFILE_FULL, message = "최대 {max}자까지 입력할 수 있습니다")
    val fullMd: String,

    @field:Size(max = MAX_PROFILE_BRIEF, message = "최대 {max}자까지 입력할 수 있습니다")
    val briefMd: String,
)

@RestController
@RequestMapping("/profile")
class ProfileController(private val profiles: ProfileRepository) {

    @GetMapping
    fun get(): ProfileDto = profiles.findById(1).orElseThrow().toDto()

    @PutMapping
    fun update(@Valid @RequestBody req: UpdateProfileRequest): ProfileDto {
        val p = profiles.findById(1).orElseThrow()
        p.fullMd = req.fullMd
        p.briefMd = req.briefMd
        p.updatedAt = OffsetDateTime.now()
        return profiles.save(p).toDto()
    }

    private fun Profile.toDto() = ProfileDto(fullMd, briefMd, updatedAt)
}
