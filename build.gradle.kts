plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.3.21"
}

group = "com.samoyed"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	implementation("com.anthropic:anthropic-java:2.60.0")
	// 비밀번호 해시(bcrypt)만 쓴다. Spring Security 전체는 넣지 않는다
	implementation("org.springframework.security:spring-security-crypto")
}

// AuthSetup(배포 준비 CLI)에도 main이 있어 앱 진입점을 정해 둔다
springBoot {
	mainClass.set("com.samoyed.ai_manager_server.AiManagerServerApplicationKt")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
		// List<@Valid T>처럼 타입 인자에 붙인 어노테이션을 바이트코드에 남긴다. 없으면 목록 원소 검사가 조용히 빠진다
		freeCompilerArgs.add("-Xemit-jvm-type-annotations")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// 배포 준비용. 비밀번호는 표준 입력으로 받아 셸 기록에 남지 않게 한다
// 사용: ./gradlew -q hashPassword --console=plain   (입력 후 Enter)
tasks.register<JavaExec>("hashPassword") {
	group = "auth"
	description = "Prints a bcrypt hash for APP_PASSWORD_HASH"
	classpath = sourceSets["main"].runtimeClasspath
	mainClass.set("com.samoyed.ai_manager_server.auth.AuthSetupKt")
	args("hash-password")
	standardInput = System.`in`
}

// 사용: ./gradlew -q totpSecret --console=plain
tasks.register<JavaExec>("totpSecret") {
	group = "auth"
	description = "Prints a new APP_TOTP_SECRET and its otpauth:// URI for an authenticator app"
	classpath = sourceSets["main"].runtimeClasspath
	mainClass.set("com.samoyed.ai_manager_server.auth.AuthSetupKt")
	args("totp-secret")
}
