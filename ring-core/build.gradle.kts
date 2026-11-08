plugins {
    kotlin("jvm")
    // FakeFamily / FakeRingDriver for tests in this module, :ring-ycbt and :app (testFixtures(project(":ring-core"))).
    `java-test-fixtures`
}

kotlin { jvmToolchain(17) }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
