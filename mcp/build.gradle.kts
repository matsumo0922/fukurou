import org.gradle.jvm.tasks.Jar

plugins {
    id("matsumo.primitive.kotlin.jvm")
    id("matsumo.primitive.detekt")
    application
}

application {
    mainClass.set("me.matsumo.fukurou.mcp.FukurouMcpServerKt")
}

dependencies {
    implementation(platform(libs.kotlin.bom))

    implementation(project(":trading"))
    implementation(project(":mcp-core"))
    implementation(project(":mcp-gmo-coin"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mcp.kotlin.server)

    testImplementation(libs.mcp.kotlin.client)
    testImplementation(kotlin("test"))
}

val mcpFatJar = tasks.register<Jar>("buildFatJar") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Builds a fixed-name executable fat jar for MCP stdio registration."
    archiveFileName.set("fukurou-mcp-all.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes("Main-Class" to application.mainClass.get())
    }

    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath
            .get()
            .filter { runtimeFile -> runtimeFile.name.endsWith(".jar") }
            .map { runtimeJar -> zipTree(runtimeJar) }
    })
}

tasks.named("build") {
    dependsOn(mcpFatJar)
}

tasks.register<JavaExec>("smokeStdio") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Starts the MCP fat jar over stdio and calls get_ticker plus the reject-only dummy trade tool."
    dependsOn(mcpFatJar, tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.matsumo.fukurou.mcp.testing.McpSmokeClientKt")
    args(layout.buildDirectory.file("libs/fukurou-mcp-all.jar").get().asFile.absolutePath)
}

tasks.register<JavaExec>("timeoutStdio") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Starts the MCP fat jar and verifies a caller-side timeout exits before any trade side effect."
    dependsOn(mcpFatJar, tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.matsumo.fukurou.mcp.testing.McpTimeoutClientKt")
    args(layout.buildDirectory.file("libs/fukurou-mcp-all.jar").get().asFile.absolutePath)
}
