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
    testFixturesApi(libs.kotlinx.serialization.json)
    testFixturesApi(libs.mcp.kotlin.client)

    testImplementation(kotlin("test"))
}
