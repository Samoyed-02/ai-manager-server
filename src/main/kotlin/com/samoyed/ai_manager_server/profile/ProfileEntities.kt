package com.samoyed.ai_manager_server.profile

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(name = "profile")
class Profile(
    @Column(name = "full_md", nullable = false, columnDefinition = "text")
    var fullMd: String = "",

    @Column(name = "brief_md", nullable = false, columnDefinition = "text")
    var briefMd: String = "",
) {
    @Id
    val id: Int = 1

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
}
