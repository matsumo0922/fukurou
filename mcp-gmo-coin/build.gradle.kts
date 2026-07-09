import org.gradle.jvm.tasks.Jar

plugins {
    id("matsumo.primitive.kotlin.jvm")
    id("matsumo.primitive.detekt")
    application
}

application {
    mainClass.set("me.matsumo.fukurou.mcp.gmo.GmoCoinMcpServerKt")
}

dependencies {
    implementation(platform(libs.kotlin.bom))

    implementation(project(":trading"))
    implementation(project(":mcp-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mcp.kotlin.server)

    testImplementation(kotlin("test"))
}

val gmoCoinMcpFatJar = tasks.register<Jar>("buildFatJar") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Builds a fixed-name executable fat jar for the standalone GMO Coin MCP server."
    archiveFileName.set("gmo-coin-mcp-all.jar")
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
    dependsOn(gmoCoinMcpFatJar)
}
