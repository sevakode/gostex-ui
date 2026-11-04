plugins { id("org.jetbrains.kotlin.multiplatform") }

kotlin {
    jvm()
    jvmToolchain(21)
    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
