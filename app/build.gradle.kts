plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tutorapp.fluenttutor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tutorapp.fluenttutor"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            abiFilters.addAll(setOf("arm64-v8a"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            pickFirsts += "lib/**/libonnxruntime.so"
            pickFirsts += "lib/**/libc++_shared.so"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // ONNX Runtime：sherpa-onnx (Qwen3-ASR) 在執行期需要它的 libonnxruntime.so，版本須與 AAR 對齊 (見下方 alignSherpaAar)
    implementation(libs.onnxruntime.android)

    // 離線 ASR：sherpa-onnx AAR (放在 app/libs/sherpa-onnx-1.13.8.aar；*.aar 不進版控)
    implementation(mapOf("name" to "sherpa-onnx-1.13.8", "ext" to "aar"))

    // 回覆生成：llama.cpp 載入 Qwen3.5 GGUF (與 Verbead 相同)
    implementation("org.codeshipping:llama-kotlin-android:0.1.7") {
        exclude(group = "androidx.core", module = "core")
        exclude(group = "androidx.core", module = "core-ktx")
    }

    // JSON
    implementation(libs.gson)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.ui.tooling)
}

// 將 sherpa-onnx AAR 內 .so 的 ELF symbol version 需求對齊到 VERS_1.28.0，才能與 ONNX Runtime 1.28.0 連結。
// 與 Verbead 的 alignSherpaAar 相同。
tasks.register("alignSherpaAar") {
    doLast {
        val aarFile = file("libs/sherpa-onnx-1.13.8.aar")
        if (!aarFile.exists()) return@doLast
        val oldVers = listOf("VERS_1.24.3", "VERS_1.28.2")
        fun isTarget(name: String) = name.contains("sherpa-onnx-c-api") || name.contains("sherpa-onnx-jni")

        var needsPatch = false
        java.util.zip.ZipFile(aarFile).use { zf ->
            for (e in zf.entries()) {
                if (e.name.endsWith(".so") && isTarget(e.name)) {
                    val s = String(zf.getInputStream(e).readBytes(), Charsets.ISO_8859_1)
                    if (oldVers.any { s.contains(it) }) { needsPatch = true; break }
                }
            }
        }
        if (!needsPatch) return@doLast

        logger.lifecycle("alignSherpaAar: aligning symbol version to VERS_1.28.0...")
        val tempOut = file("libs/temp_${aarFile.name}")
        java.util.zip.ZipFile(aarFile).use { zf ->
            java.util.zip.ZipOutputStream(tempOut.outputStream().buffered()).use { zos ->
                for (e in zf.entries()) {
                    val data = zf.getInputStream(e).readBytes()
                    if (isTarget(e.name)) {
                        val repl = "VERS_1.28.0".toByteArray()
                        for (old in oldVers) {
                            val t = old.toByteArray()
                            var i = 0
                            while (i <= data.size - t.size) {
                                var m = true
                                for (j in t.indices) if (data[i + j] != t[j]) { m = false; break }
                                if (m) { System.arraycopy(repl, 0, data, i, repl.size); i += t.size } else i++
                            }
                        }
                        // ELF vna_hash 修正 (VERS_1.28.2/1.24.3 -> VERS_1.28.0 的 hash)
                        for (i in 0..data.size - 4) {
                            val b0 = data[i].toInt() and 0xff; val b1 = data[i + 1].toInt() and 0xff
                            val b2 = data[i + 2].toInt() and 0xff; val b3 = data[i + 3].toInt() and 0xff
                            if (b0 == 0x83 && b1 == 0xf2 && b2 == 0x7b && b3 == 0x02) { data[i] = 0x80.toByte(); data[i + 1] = 0xfe.toByte() }
                            else if (b0 == 0x82 && b1 == 0xfe && b2 == 0x7b && b3 == 0x02) { data[i] = 0x80.toByte() }
                        }
                    }
                    val out = java.util.zip.ZipEntry(e.name).apply { time = e.time }
                    zos.putNextEntry(out); zos.write(data); zos.closeEntry()
                }
            }
        }
        java.nio.file.Files.move(tempOut.toPath(), aarFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        logger.lifecycle("alignSherpaAar: done.")
    }
}
tasks.matching { it.name.startsWith("pre") && it.name.endsWith("Build") }.configureEach {
    dependsOn("alignSherpaAar")
}
