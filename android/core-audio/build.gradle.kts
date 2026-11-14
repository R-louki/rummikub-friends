plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core-model"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
