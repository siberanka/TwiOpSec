import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    java
    jacoco
}

group = "com.siberanka.twiopsec"
version = "1.3.2"
val pluginVersion = version.toString()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.123-stable")
    compileOnly("net.luckperms:api:5.5")

    testImplementation(platform("org.junit:junit-bom:5.14.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.papermc.paper:paper-api:26.2.build.123-stable")
    testImplementation("net.luckperms:api:5.5")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Javadoc>().configureEach {
    isFailOnError = true
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:all,-missing", true)
}

tasks.processResources {
    filesMatching("paper-plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("file.encoding", "UTF-8")
    val legacyDir = providers.gradleProperty("legacyT2Dir").orNull
    if (legacyDir != null) {
        systemProperty("twiopsec.legacyT2Dir", legacyDir)
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jar {
    archiveFileName.set("TwiOpSec-${project.version}.jar")
    manifest {
        attributes(
            "Implementation-Title" to "TwiOpSec",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "siberanka",
            "Built-Jdk-Spec" to "25"
        )
    }
}

tasks.register("releaseCheck") {
    group = "verification"
    dependsOn(tasks.clean, tasks.check, tasks.jar, tasks.javadoc)
}

tasks.named("check") { mustRunAfter("clean") }
tasks.named("jar") { mustRunAfter("clean") }
tasks.named("javadoc") { mustRunAfter("clean") }
