/*
 * Maze Game
 * Copyright (c) 2025 Sascha Strauß
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    application
    alias(libs.plugins.shadow.jar)
    `maven-publish`
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}

dependencies {
    implementation(platform(project(":")))

    // --- Ktor ----------------------------------------------------------
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.content.neg)
    implementation(libs.ktor.serialization.jackson)
    implementation(libs.ktor.server.thymeleaf)

    // --- JWT -------------------------------------------------
    implementation(libs.jwt)

    // --- Jackson (YAML & Kotlin-Module) --------------------------------
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.module.kotlin)

    implementation(projects.mazegameCommon)
    implementation(projects.mazegameClientKtor) // Used for client wrapper

    // --- Tests (optional) ---------------------------------------------
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}

application {
    mainClass.set("de.dreamcube.mazegame.server.ApplicationKt")
}

tasks.withType<ShadowJar> {
    archiveBaseName.set(project.name)
    mergeServiceFiles()
}

tasks.test { useJUnitPlatform() }

// Deliberately separate from test/check: this experiment creates thousands of TCP clients.
val soakTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
kotlin.target.compilations.getByName(soakTest.name).associateWith(kotlin.target.compilations.getByName("main"))
configurations[soakTest.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[soakTest.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

tasks.register<JavaExec>("trapeaterSoak") {
    group = "verification"
    description = "Measures turn latency as disconnected trapeater retry loops accumulate."
    classpath = soakTest.runtimeClasspath
    mainClass.set("de.dreamcube.mazegame.server.soak.TrapeaterSoakKt")
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    workingDir = projectDir
    maxHeapSize = "512m"
    // Matches the recorded experiment; this is not a physical CPU limit.
    jvmArgs("-XX:ActiveProcessorCount=2")
    systemProperty("logback.configurationFile", file("src/soakTest/resources/logback-soak.xml").absolutePath)
}

tasks.register<JavaExec>("naturalTrapeaterSoak") {
    group = "verification"
    description = "Measures natural auto-trapeater lifecycles with bait generation and active dummy bots."
    classpath = soakTest.runtimeClasspath
    mainClass.set("de.dreamcube.mazegame.server.soak.NaturalTrapeaterSoakKt")
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    workingDir = projectDir
    maxHeapSize = "512m"
    jvmArgs("-XX:ActiveProcessorCount=2")
    systemProperty("logback.configurationFile", file("src/soakTest/resources/logback-soak.xml").absolutePath)
}
