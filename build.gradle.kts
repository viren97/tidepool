plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
}

allprojects {
    group = "dev.tidepool"
    version = "0.1.0-SNAPSHOT"
}

// Test split shared by every module:
//   ./gradlew test             -> unit tests only (no Docker needed); part of `build`
//   ./gradlew integrationTest  -> tests tagged @Tag("integration") (Testcontainers, needs Docker)
subprojects {
    plugins.withType<JavaPlugin> {
        val testSourceSet = the<SourceSetContainer>()["test"]

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }

        tasks.named<Test>("test") {
            useJUnitPlatform { excludeTags("integration") }
        }

        tasks.register<Test>("integrationTest") {
            description = "Runs tests tagged 'integration' (need Docker)."
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            testClassesDirs = testSourceSet.output.classesDirs
            classpath = testSourceSet.runtimeClasspath
            useJUnitPlatform { includeTags("integration") }
            // A module may have no integration tests yet; that is not a failure.
            failOnNoDiscoveredTests = false
            shouldRunAfter(tasks.named("test"))
        }
    }
}
