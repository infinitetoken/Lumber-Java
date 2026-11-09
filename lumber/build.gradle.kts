plugins {
    `java-library`
    `java-test-fixtures`
    id("com.vanniktech.maven.publish")
}

description = "Java and Kotlin client for Lumber: end-to-end encrypted cloud logging"

// Java 11 is the oldest with java.net.http, which sends the logs, so the library needs nothing beyond the JDK
tasks.withType<JavaCompile>().configureEach {
    options.release = 11
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-options", "-Werror"))
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "com.infinitetoken.lumber")
    }
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:all,-missing", "-quiet")
        links("https://docs.oracle.com/en/java/javase/11/docs/api/")
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The test fixtures (a stand-in Lumber API) are for this repository's tests, never published
// (after evaluation, once the publish plugin has added the sources variants)
afterEvaluate {
    val javaComponent = components["java"] as AdhocComponentWithVariants
    for (name in listOf("testFixturesApiElements", "testFixturesRuntimeElements", "testFixturesSourcesElements")) {
        configurations.findByName(name)?.let { javaComponent.withVariantsFromConfiguration(it) { skip() } }
    }
}

// ./gradlew test runs on the JDK running Gradle; -PtestJava=11 runs the tests on Java 11 instead, as CI does for each
// supported version
val testJava = providers.gradleProperty("testJava")
tasks.test {
    useJUnitPlatform()
    systemProperty("lumber.version", version.toString())
    if (testJava.isPresent) {
        javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(testJava.get()) }
    }
}

mavenPublishing {
    pom {
        name = "Lumber"
        description = project.description
    }
}
