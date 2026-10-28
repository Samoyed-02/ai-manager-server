package com.samoyed.ai_manager_server

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AiManagerServerApplicationTests {

	@Test
	fun contextLoads() {
	}

}
