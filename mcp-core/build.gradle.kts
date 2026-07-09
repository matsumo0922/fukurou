plugins {
    `java-library`
    id("matsumo.primitive.kotlin.jvm")
    id("matsumo.primitive.detekt")
}

dependencies {
    implementation(platform(libs.kotlin.bom))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    api(libs.mcp.kotlin.server)

    testImplementation(kotlin("test"))
}
