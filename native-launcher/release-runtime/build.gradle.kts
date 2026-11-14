plugins { id("org.jetbrains.kotlin.jvm") }
kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
dependencies {
    testImplementation(kotlin("test"))
    // Host checks execute Android JSON journal code on the JVM after this test task.
    // Resolve the runtime explicitly rather than relying on a developer's cache.
    testRuntimeOnly("org.json:json:20180813")
}
