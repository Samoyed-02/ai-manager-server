package com.samoyed.ai_manager_server.routine

import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface RoutineRepository : JpaRepository<Routine, UUID> {
    fun findAllByOrderByTimeOfDayAsc(): List<Routine>
    fun findByEnabledTrueOrderByTimeOfDayAsc(): List<Routine>
}

interface NotificationRepository : JpaRepository<Notification, UUID> {
    fun findAllByOrderByCreatedAtDesc(limit: Limit): List<Notification>
    fun findByReadAtIsNullOrderByCreatedAtDesc(limit: Limit): List<Notification>
    fun countByReadAtIsNull(): Long
}
