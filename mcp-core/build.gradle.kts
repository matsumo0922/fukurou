plugins {
    `java-library`
    `java-test-fixtures`
    id("matsumo.primitive.kotlin.jvm")
    id("matsumo.primitive.detekt")
}

dependencies {
    implementation(platform(libs.kotlin.bom))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    api(libs.mcp.kotlin.server)

    testFixturesImplementation(platform(libs.kotlin.bom))
    testFixturesImplementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}
