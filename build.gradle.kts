import java.util.jar.JarFile

plugins {
    java
    id("com.gradleup.shadow") version "9.3.1"
}

group = "com.infinitygear"
version = "2.0.0-SNAPSHOT"

val mockitoAgent = configurations.create("mockitoAgent")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.auxilor.io/repository/maven-public/")
    maven("https://repo.nexomc.com/releases")
    maven("https://jitpack.io")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.112-stable")
    compileOnly("com.willfp:EcoEnchants:2026.33")
    compileOnly("com.willfp:eco:2026.33")
    compileOnly("com.willfp:libreforge:2026.33")
    compileOnly("com.willfp:libreforge-loader:2026.33")
    compileOnly("me.clip:placeholderapi:2.12.3")
    compileOnly("com.nexomc:nexo:1.27.0")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")

    implementation("org.xerial:sqlite-jdbc:3.50.3.0")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.20.0")
    mockitoAgent("org.mockito:mockito-core:5.20.0") { isTransitive = false }
    testImplementation("io.papermc.paper:paper-api:26.2.build.112-stable")
    testImplementation("me.clip:placeholderapi:2.12.3")
    testCompileOnly("com.willfp:EcoEnchants:2026.33")
    testCompileOnly("com.willfp:eco:2026.33")
    testCompileOnly("com.willfp:libreforge:2026.33")
    testRuntimeOnly("com.willfp:EcoEnchants:2026.33") { isTransitive = false }
    testRuntimeOnly("com.willfp:eco:2026.33") { isTransitive = false }
    testRuntimeOnly("com.willfp:libreforge:2026.33:shadow") { isTransitive = false }
    testRuntimeOnly("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.mariadb.jdbc:mariadb-java-client:3.5.6")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.compileJava {
    options.encoding = "UTF-8"
    options.release = 25
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
    inputs.property("mariadbIntegrationEnabled", providers.environmentVariable("INFINITYGEAR_TEST_JDBC_URL").map { it.isNotBlank() }.orElse(false))
    jvmArgs("-javaagent:${mockitoAgent.asPath}")
}

tasks.shadowJar {
    archiveBaseName = "InfinityGear"
    archiveClassifier = ""
    mergeServiceFiles()

    // InfinityPickaxes is deployed exclusively to Linux servers. Keep SQLite's
    // glibc and musl binaries, but do not ship native libraries for other OSes.
    exclude("org/sqlite/native/FreeBSD/**")
    exclude("org/sqlite/native/Linux-Android/**")
    exclude("org/sqlite/native/Mac/**")
    exclude("org/sqlite/native/Windows/**")
}

tasks.jar {
    enabled = false
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

// Consumers compile against this artifact; only the provider loads these classes at runtime.
val archivesApiJar = tasks.register<Jar>("archivesApiJar") {
    archiveClassifier = "archives-api-v1"
    from(sourceSets.main.get().output) { include("com/infinitygear/api/v1/**") }
}
tasks.assemble { dependsOn(archivesApiJar) }

val verifyArchivesApiJar = tasks.register("verifyArchivesApiJar") {
    group = "verification"
    description = "Verifies that the published Archives consumer jar contains only the versioned API namespace"
    dependsOn(archivesApiJar)
    doLast {
        val entries = mutableListOf<String>()
        JarFile(archivesApiJar.get().archiveFile.get().asFile).use { jar ->
            val iterator = jar.entries()
            while (iterator.hasMoreElements()) entries += iterator.nextElement().name
        }
        check(entries.all { it.endsWith("/") || it.startsWith("META-INF/") || it.startsWith("com/infinitygear/api/v1/") }) {
            "archives-api-v1 contains a non-versioned API entry: " + entries
                .first { !it.endsWith("/") && !it.startsWith("META-INF/") && !it.startsWith("com/infinitygear/api/v1/") }
        }
        check("com/infinitygear/api/InfinityGearService.class" !in entries) {
            "archives-api-v1 must not publish the in-plugin InfinityGearService compatibility interface"
        }
    }
}
tasks.check { dependsOn(verifyArchivesApiJar) }

tasks.register<JavaExec>("quarantineMigration") {
    group = "verification"
    description = "Explicit read-only preflight/dry-run or approved legacy quarantine import"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.infinitygear.persistence.LegacyQuarantineMigrationCli"
}
