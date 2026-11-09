plugins {
    `java-library`
    id("com.vanniktech.maven.publish")
}

description = "Logback appender for Lumber: end-to-end encrypted cloud logging for SLF4J"

tasks.withType<JavaCompile>().configureEach {
    options.release = 11
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-options", "-Werror"))
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "com.infinitetoken.lumber.logback")
    }
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:all,-missing", "-quiet")
        links("https://docs.oracle.com/en/java/javase/11/docs/api/")
    }
}

dependencies {
    api(project(":lumber"))
    // Logback 1.5 and later run on Java 11, as this does
    api("ch.qos.logback:logback-classic:1.6.6")
    api("org.slf4j:slf4j-api:2.0.20")

    testImplementation(testFixtures(project(":lumber")))
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

mavenPublishing {
    pom {
        name = "Lumber for Logback"
        description = project.description
    }
}
