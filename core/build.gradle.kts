import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "de.mirranet.ondura.core"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            // Written by the Rust tasks below.
            jniLibs.srcDir(layout.buildDirectory.dir("rustJniLibs"))
            java.srcDir(layout.buildDirectory.dir("generated/uniffi/kotlin"))
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // JNA as .aar: carries libjnidispatch.so for every Android ABI.
    api(libs.jna) { artifact { type = "aar" } }
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
}

// ---------------------------------------------------------------- Rust core
//
// The Rust workspace in /rust builds `ondura-mobile` (the bridge over the
// Ondura-Core crates) with cargo-ndk for each ABI, then uniffi-bindgen
// generates the Kotlin bindings from the built library.
// Requirements: rustup targets for the ABIs and `cargo install cargo-ndk`.

val rustDir: File = rootProject.file("rust")
val jniOut = layout.buildDirectory.dir("rustJniLibs")
val bindingsOut = layout.buildDirectory.dir("generated/uniffi/kotlin")
val abis: List<String> = (findProperty("ondura.abis") as String? ?: "arm64-v8a")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
val rustTriples = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7-linux-androideabi",
    "x86_64" to "x86_64-linux-android",
    "x86" to "i686-linux-android",
)
val ndkDir = provider { android.ndkDirectory.absolutePath }

val rustSources = fileTree(rustDir) {
    include("Cargo.toml", "Cargo.lock", "*/Cargo.toml", "*/uniffi.toml", "*/src/**")
}

val cargoBuild = tasks.register<Exec>("cargoBuild") {
    group = "rust"
    description = "Builds the Rust core for ${abis.joinToString()}"
    inputs.files(rustSources)
    inputs.property("abis", abis)
    inputs.property("graphClientId", System.getenv("ONDURA_GRAPH_CLIENT_ID") ?: "")
    outputs.dir(jniOut)
    workingDir = rustDir
    doFirst {
        environment("ANDROID_NDK_HOME", ndkDir.get())
        environment("ANDROID_NDK_ROOT", ndkDir.get())
    }
    val args = mutableListOf("cargo", "ndk", "--platform", "26")
    abis.forEach { args += listOf("-t", it) }
    args += listOf("-o", jniOut.get().asFile.absolutePath, "build", "--release", "-p", "ondura-mobile")
    commandLine(args)
}

val uniffiBindings = tasks.register<Exec>("uniffiBindings") {
    group = "rust"
    description = "Generates the Kotlin bindings of the Rust core"
    dependsOn(cargoBuild)
    val triple = rustTriples[abis.first()] ?: error("unknown ABI ${abis.first()}")
    val library = File(rustDir, "target/$triple/release/libondura_mobile.so")
    inputs.files(rustSources)
    inputs.property("abis", abis)
    outputs.dir(bindingsOut)
    workingDir = rustDir
    doFirst { delete(bindingsOut) }
    commandLine(
        "cargo", "run", "-q", "-p", "uniffi-bindgen", "--",
        "generate", "--library", library.absolutePath,
        "--language", "kotlin", "--no-format",
        "--out-dir", bindingsOut.get().asFile.absolutePath,
    )
}

tasks.named("preBuild") { dependsOn(uniffiBindings) }
