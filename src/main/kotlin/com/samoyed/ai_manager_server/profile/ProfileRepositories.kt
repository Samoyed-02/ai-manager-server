package com.samoyed.ai_manager_server.profile

import org.springframework.data.jpa.repository.JpaRepository

interface ProfileRepository : JpaRepository<Profile, Int>
