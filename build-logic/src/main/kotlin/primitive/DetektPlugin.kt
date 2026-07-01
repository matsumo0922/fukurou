package primitive

import me.matsumo.fukurou.configureDetekt
import me.matsumo.fukurou.library
import me.matsumo.fukurou.libs
import me.matsumo.fukurou.plugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class DetektPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply(libs.plugin("detekt").pluginId)

            configureDetekt()

            dependencies {
                "detektPlugins"(libs.library("detekt-formatting"))
            }
        }
    }
}
