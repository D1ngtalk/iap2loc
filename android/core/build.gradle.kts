plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure JVM module: the iAP2 accessory, the engine and the web console. No Android APIs here,
// so the protocol is unit-tested on the build machine and the Android app only adds a transport.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.bouncycastle.pkix)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The console page is shared with the desktop build: Web/index.html is the single source.
tasks.processResources {
    from(rootDir.resolve("../Web")) {
        include("index.html")
        into("web")
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
