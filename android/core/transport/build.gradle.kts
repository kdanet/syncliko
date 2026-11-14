plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":core:protocol"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bouncycastle.pkix)
}

tasks.test {
    // Optional interop test against a running Mac app / syncliko-probe: pass the invite URL.
    System.getenv("SYNCLIKO_INVITE")?.let { systemProperty("syncliko.invite", it) }
    System.getenv("SYNCLIKO_HOST")?.let { systemProperty("syncliko.host", it) }
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}
