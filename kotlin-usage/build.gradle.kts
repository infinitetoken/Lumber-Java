import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Not published: Kotlin code using the library as a Kotlin app would, run with the rest of the tests, so the API keeps
// reading well from Kotlin
plugins {
    kotlin("jvm")
}

java {
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    testImplementation(project(":lumber"))
    testImplementation(testFixtures(project(":lumber")))
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val testJava = providers.gradleProperty("testJava")
tasks.test {
    useJUnitPlatform()
    if (testJava.isPresent) {
        javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(testJava.get()) }
    }
}
