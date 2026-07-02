plugins {
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

    testImplementation(kotlin("test"))
}
