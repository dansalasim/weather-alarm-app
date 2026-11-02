// Pure Kotlin: the forecast engine, PAGASA rules, source parsers. No Android code here.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    // Only the JSON parser is used (no serialization plugin needed).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

// Plain-Kotlin checks (no JUnit). `./gradlew :core:checks` fails the build if any check fails.
val checkMains = listOf("CoreChecksKt", "PagasaChecksKt", "SourceChecksKt", "FeedChecksKt", "SuspensionChecksKt")
val checkTasks = checkMains.map { main ->
    tasks.register<JavaExec>("run$main") {
        group = "verification"
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("ph.weatherapp.core.$main")
    }
}
tasks.register("checks") {
    group = "verification"
    dependsOn(checkTasks)
}
