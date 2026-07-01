plugins {
    `kotlin-dsl`
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21

    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

kotlin {
    sourceSets.all {
        languageSettings {
            languageVersion = "2.0"
        }
    }
}

dependencies {
    compileOnly(gradleKotlinDsl())
    implementation(libs.kotlin.gradlePlugin)
    implementation("org.jetbrains.kotlin:kotlin-serialization:${libs.versions.kotlin.get()}")
    implementation(libs.detekt.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("KotlinJvmPlugin") {
            id = "matsumo.primitive.kotlin.jvm"
            implementationClass = "primitive.KotlinJvmPlugin"
        }
        register("DetektPlugin") {
            id = "matsumo.primitive.detekt"
            implementationClass = "primitive.DetektPlugin"
        }
    }
}
