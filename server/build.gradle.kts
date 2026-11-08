plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("dev.avijit.orbit.server.MainKt")
    applicationName = "orbit-server"
}

val ktor = "3.0.3"

dependencies {
    implementation(project(":sync-protocol"))
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-server-auth:$ktor")
    implementation("io.ktor:ktor-server-sessions:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    implementation("io.ktor:ktor-server-html-builder:$ktor")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("at.favre.lib:bcrypt:0.10.2")
    implementation("org.slf4j:slf4j-simple:2.0.16")
    implementation("com.google.zxing:core:3.5.3")
    implementation("io.ktor:ktor-client-core:$ktor")
    implementation("io.ktor:ktor-client-cio:$ktor")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-server-test-host:$ktor")
    testImplementation("io.ktor:ktor-client-mock:$ktor")
}

tasks.test {
    // Tests never touch the real ~/.orbit-server.
    environment("ORBIT_SERVER_HOME", layout.buildDirectory.dir("tmp/test-home").get().asFile.absolutePath)
}
