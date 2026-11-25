import java.time.Duration
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val appMajor = 1
val appMinor = 2
val appBugfix = 3
require(appMajor in 1..99 && appMinor in 1..9 && appBugfix in 1..9) {
    "Version out of range: major 1-99, minor 1-9, bugfix 1-9"
}

val releaseSigning = Properties().apply {
    val file = rootProject.file("key.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.ftapps.remft"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ftapps.remft"
        minSdk = 24
        targetSdk = 37
        versionCode = appMajor * 100 + appMinor * 10 + appBugfix
        versionName = "$appMajor.$appMinor.$appBugfix"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appAuthRedirectScheme"] = "msauth"
        manifestPlaceholders["appLabel"] = "RemFT"
        val packageName = "com.ftapps.remft"
        buildConfigField("String", "SOUNDS_AUTHORITY", "\"$packageName.sounds\"")
        buildConfigField("String", "FILES_AUTHORITY", "\"$packageName.files\"")
        manifestPlaceholders["soundsAuthority"] = "$packageName.sounds"
        manifestPlaceholders["filesAuthority"] = "$packageName.files"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keys/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseSigning.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("fixtures.dir", rootProject.file("fixtures").absolutePath)
                it.maxHeapSize = "2g"
            }
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = false
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/NOTICE.md", "META-INF/LICENSE.md")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    val store = releaseSigning.getProperty("storeFile")?.let { rootProject.file(it) }
    doFirst {
        if (store == null || !store.exists()) {
            throw GradleException("Release signing key missing: key.properties or its keystore was not found.")
        }
    }
}

tasks.withType<Test>().configureEach {
    maxParallelForks = 2
    timeout.set(Duration.ofMinutes(15))
}

val copyReleaseToBuildsFt = tasks.register("copyReleaseToBuildsFt") {
    val outDir = rootProject.file("builds-ft")
    val apkDir = layout.buildDirectory.dir("outputs/apk/release")
    val apkName = "remft-$appMajor.$appMinor.$appBugfix-arm64.apk"
    doLast {
        outDir.mkdirs()
        val apk = apkDir.get().asFile
            .listFiles { file -> file.extension == "apk" }?.firstOrNull() ?: return@doLast
        apk.copyTo(outDir.resolve(apkName), overwrite = true)
        outDir.listFiles { file -> file.extension == "apk" }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(3)
            ?.forEach { it.delete() }
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(copyReleaseToBuildsFt)
}

dependencies {
    implementation(project(":domain"))
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.hilt.viewmodel.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.haze)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.commonmark)
    implementation(libs.appauth)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.androidx.exifinterface)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":domain")))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}
