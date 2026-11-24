plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    api(libs.bundles.commonmark)
    testImplementation(libs.junit)
    testImplementation(libs.sqlite.jdbc)
    testFixturesImplementation(libs.kotlinx.serialization.json)
    testFixturesImplementation(libs.sqlite.jdbc)
}

tasks.withType<Test>().configureEach {
    systemProperty("fixtures.dir", rootProject.file("fixtures").absolutePath)
    maxParallelForks = 3
    timeout.set(java.time.Duration.ofMinutes(10))
}
