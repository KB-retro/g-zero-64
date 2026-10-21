plugins { id("com.android.application") }

val repository = rootProject.projectDir

val buildCommit = providers.exec {
    workingDir(repository)
    commandLine("git", "rev-parse", "HEAD")
}.standardOutput.asText.map { it.trim() }

// CMake writes the game's runtime files and the extractor here; Gradle packages them.
val nativeAssets = layout.buildDirectory.dir("generated/gdx-assets")
val nativeJniLibs = layout.buildDirectory.dir("generated/gdx-jnilibs")

// SDL's Java half must match the SDL2 the game links (android/SDL, same release).
val prepareSdlJava by tasks.registering {
    val source = file("../SDL/android-project/app/src/main/java")
    val output = layout.buildDirectory.dir("generated/sdl-java")
    inputs.dir(source)
    outputs.dir(output)
    doLast {
        output.get().asFile.deleteRecursively()
        source.walkTopDown().filter { it.isFile && it.extension == "java" }.forEach { original ->
            val destination = output.get().asFile.resolve(original.relativeTo(source))
            destination.parentFile.mkdirs()
            var text = original.readText()
            if (original.name == "HIDDeviceManager.java") {
                // Android 14+ requires an explicit export flag on runtime receivers.
                val call = "mContext.registerReceiver(mUsbBroadcast, filter);"
                check(text.contains(call)) { "SDL USB receiver changed: review the Android patch" }
                text = text.replace(call, """
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        mContext.registerReceiver(mUsbBroadcast, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
                    } else {
                        mContext.registerReceiver(mUsbBroadcast, filter);
                    }
                """.trimIndent())
                text = text.replace("private void initializeUSB()", "@android.annotation.SuppressLint(\"UnspecifiedRegisterReceiverFlag\")\n    private void initializeUSB()")
            }
            destination.writeText(text)
        }
    }
}

android {
    namespace = "io.github.kbretro.gzero64"
    compileSdk = 37
    ndkVersion = "30.0.16248370"
    buildFeatures { buildConfig = true }
    defaultConfig {
        applicationId = "io.github.kbretro.gzero64"
        minSdk = 30
        targetSdk = 37
        // CI builds count up so every APK installs as an update (0.1.<run>).
        val runNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull()
        versionCode = runNumber ?: 1
        versionName = "0.1.${runNumber ?: 0}"
        buildConfigField("String", "BUILD_COMMIT", "\"${buildCommit.get().take(12)}\"")
        buildConfigField("String", "BUILD_COMMIT_FULL", "\"${buildCommit.get()}\"")
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    // Release: optimized, and without the desktop developer gates.
                    "-DCMAKE_BUILD_TYPE=Release",
                    // SDLActivity loads libSDL2.so before libmain.so.
                    "-DSDL_SHARED=ON",
                    "-DSDL_STATIC=OFF",
                    "-DFETCHCONTENT_SOURCE_DIR_SDL2=${file("../SDL").absolutePath}",
                    "-DGDX_ANDROID_ASSETS_DIR=${nativeAssets.get().asFile.absolutePath}",
                    "-DGDX_ANDROID_JNILIBS_DIR=${nativeJniLibs.get().asFile.absolutePath}")
                targets += listOf("G-Diffuser", "SDL2")
            }
        }
    }
    // A fixed key (GZ64_KEYSTORE, a .jks path, plus GZ64_KEYSTORE_PASSWORD) keeps every build
    // installable over the previous one; without it the debug key signs the APK.
    val stableKeystore = providers.environmentVariable("GZ64_KEYSTORE").orNull
    signingConfigs {
        if (stableKeystore != null) {
            create("stable") {
                storeFile = file(stableKeystore)
                storePassword = providers.environmentVariable("GZ64_KEYSTORE_PASSWORD").get()
                keyAlias = "gzero64"
                keyPassword = providers.environmentVariable("GZ64_KEYSTORE_PASSWORD").get()
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("stable") ?: signingConfigs.getByName("debug")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("../../CMakeLists.txt")
            version = "4.1.2"
        }
    }
    // The extractor runs as a child process, so native libraries must be real files on disk.
    packaging { jniLibs { useLegacyPackaging = true } }
    sourceSets["main"].java.directories.add(layout.buildDirectory.dir("generated/sdl-java").get().asFile.path)
    sourceSets["main"].assets.directories.add(nativeAssets.get().asFile.path)
    sourceSets["main"].jniLibs.directories.add(nativeJniLibs.get().asFile.path)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.named("preBuild") { dependsOn(prepareSdlJava) }

// The native build produces packaged assets and the extractor, so merging waits for it.
tasks.configureEach {
    val merge = Regex("merge(\\w+?)(Assets|JniLibFolders|NativeLibs)").matchEntire(name)
    if (merge != null) {
        val variant = merge.groupValues[1]
        dependsOn(tasks.matching { it.name == "externalNativeBuild$variant" || it.name.startsWith("buildCMake$variant") })
    }
}
