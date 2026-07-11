plugins {
    id("matsumo.primitive.kotlin.jvm")
    id("matsumo.primitive.detekt")
    alias(libs.plugins.ktor)
    application
}

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

dependencies {
    implementation(platform(libs.kotlin.bom))

    implementation(project(":trading"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.ktor.server.routing.openapi)
    implementation(libs.ktor.server.swagger)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(kotlin("test"))
    testImplementation(libs.testcontainers.postgresql)
}
