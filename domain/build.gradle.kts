plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    api(libs.bundles.commonmark)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    systemProperty("fixtures.dir", rootProject.file("fixtures").absolutePath)
}
