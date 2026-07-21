plugins {
    `java-test-fixtures`
    id("matsumo.primitive.kotlin.jvm")
    id("matsumo.primitive.detekt")
}

dependencies {
    implementation(platform(libs.kotlin.bom))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)

    testFixturesImplementation(platform(libs.kotlin.bom))

    testImplementation(kotlin("test"))
    testImplementation(libs.testcontainers.postgresql)

    constraints {
        testImplementation(libs.commons.compress) {
            because(
                "Testcontainers pulls commons-compress 1.24.0; " +
                    "1.26.0+ fixes CVE-2024-25710/CVE-2024-26308.",
            )
        }
    }
}

tasks.named<Test>("test") {
    exclude("**/TradingAdmissionHealthIsolationRegressionSuite*.class")
}

tasks.register<Test>("admissionHealthIsolationRegressionTest") {
    group = "verification"
    description = "Runs admission-dependent trading tests after unhealthy predecessors in one JUnit 4 worker."
    dependsOn(tasks.named("testClasses"))
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/TradingAdmissionHealthIsolationRegressionSuite.class")
    systemProperty("fukurou.test.admission-health-isolation-regression", "true")
    maxParallelForks = 1
    forkEvery = 0
}

tasks.register<JavaExec>("runOneShotLlm") {
    val mcpJarPath = rootProject.layout.projectDirectory
        .file("mcp/build/libs/fukurou-mcp-all.jar")
        .asFile
        .absolutePath

    group = "application"
    description = "Runs the manual one-shot Proposer -> Falsifier -> paper-entry LLM runner."
    dependsOn(":mcp:buildFatJar", tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("me.matsumo.fukurou.trading.runner.OneShotRunnerMainKt")
    workingDir = rootProject.projectDir
    environment("FUKUROU_REPOSITORY_ROOT", rootProject.projectDir.absolutePath)
    environment("FUKUROU_LLM_WORKING_DIRECTORY", rootProject.projectDir.absolutePath)
    environment("FUKUROU_MCP_JAR_PATH", mcpJarPath)
}

tasks.register<JavaExec>("runTtlReplay") {
    group = "application"
    description = "Runs the read-only TTL shortening sensitivity replay over recorded execution data."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("me.matsumo.fukurou.trading.replay.TtlReplayMainKt")
    workingDir = rootProject.projectDir
}
