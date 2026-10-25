// The Navigator engine: the goal graph and the layers that reason about it.
// A plain Java library on purpose — no Android, no network — so every layer
// runs and is tested on the desktop JVM exactly as it runs on the phone.
plugins {
    `java-library`
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

dependencies {
    api(libs.gson)
    testImplementation(libs.junit)
}
