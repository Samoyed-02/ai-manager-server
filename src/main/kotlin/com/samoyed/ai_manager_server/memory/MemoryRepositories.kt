package com.samoyed.ai_manager_server.memory

import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface MemoryRepository : JpaRepository<Memory, UUID> {
    fun findAllByOrderByUpdatedAtDesc(limit: Limit): List<Memory>
    fun findByCategoryOrderByUpdatedAtDesc(category: MemoryCategory): List<Memory>
}
