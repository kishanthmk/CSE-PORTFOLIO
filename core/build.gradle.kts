import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // optional: -Pstatement=/path/to/broker-statement.pdf checks a real statement end to end
    (project.findProperty("statement") as String?)?.let { systemProperty("statement.pdf", it) }
    testLogging { events("passed", "failed", "skipped") }
}
