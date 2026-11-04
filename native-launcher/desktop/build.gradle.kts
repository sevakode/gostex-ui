import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin { jvmToolchain(21) }
val releaseVersion = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
tasks.processResources {
    from(rootProject.file("version.properties")) { rename { "gostex-build.properties" } }
}

dependencies {
    implementation(project(":shared-core"))
    implementation(project(":release-runtime"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.compose.ui:ui-test:1.12.2")
}

tasks.test {
    systemProperty("apple.awt.UIElement", "true")
}

compose.desktop {
    application {
        mainClass = "dev.gostex.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Msi)
            packageName = "GOSTEX"
            packageVersion = releaseVersion.getProperty("name")
            modules("java.desktop", "java.logging", "java.naming")
        }
    }
}
