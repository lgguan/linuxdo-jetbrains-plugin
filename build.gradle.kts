import java.security.MessageDigest

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.lgguan.linuxdo"
version = "1.0.2"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.commonmark:commonmark:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.30.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.30.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.30.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

// OkHttp/Okio also request stdlib transitively; use the IDE's copy throughout.
configurations.named("implementation") {
    listOf("kotlin-stdlib", "kotlin-stdlib-common", "kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8").forEach {
        exclude(group = "org.jetbrains.kotlin", module = it)
    }
}

// Configure Gradle IntelliJ Plugin
intellij {
    version.set("2024.1.4")
    type.set("IC") // Target IDE Platform: IntelliJ IDEA Community Edition
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks {
    val verifyReaderAssets by registering {
        val vendor = layout.projectDirectory.dir("src/main/resources/web/vendor")
        inputs.dir(vendor)
        doLast {
            val manifest = groovy.json.JsonSlurper().parse(vendor.file("manifest.json").asFile) as List<*>
            manifest.forEach { value ->
                val item = value as Map<*, *>
                listOf("file" to "sha256", "license" to "license_sha256").forEach { (fileKey, hashKey) ->
                    val resource = vendor.file(item[fileKey] as String).asFile
                    val digest = MessageDigest.getInstance("SHA-256").digest(resource.readBytes())
                        .joinToString("") { "%02x".format(it) }
                    check(digest == item[hashKey]) { "Reader asset checksum mismatch: ${resource.name}" }
                }
            }
        }
    }
    processResources { dependsOn(verifyReaderAssets) }
    val verifyBoostAssets by registering {
        val metadata = layout.projectDirectory.dir("src/main/resources/boost")
        inputs.dir(metadata)
        doLast {
            val manifest = groovy.json.JsonSlurper().parse(metadata.file("manifest.json").asFile) as List<*>
            manifest.forEach { value ->
                val item = value as Map<*, *>
                val resource = metadata.file(item["file"] as String).asFile
                val digest = MessageDigest.getInstance("SHA-256").digest(resource.readBytes())
                    .joinToString("") { "%02x".format(it) }
                check(digest == item["sha256"]) { "Boost metadata checksum mismatch: ${resource.name}" }
            }
        }
    }
    processResources { dependsOn(verifyBoostAssets) }
    runPluginVerifier {
        // Real binary/API verification, separate from verifyPlugin's ZIP/descriptor checks.
        val localIde = providers.gradleProperty("verifierIdePath")
        verifierVersion.set("1.410")
        if (localIde.isPresent) runtimeDir.set(file(localIde.get()).resolve("jbr").absolutePath)
        offline.set(providers.gradleProperty("verifierOffline").map(String::toBoolean).getOrElse(false))
        if (localIde.isPresent) localPaths.set(listOf(file(localIde.get())))
        else ideVersions.set(providers.gradleProperty("verifierIdeVersions")
            .map { it.split(',') }.getOrElse(listOf("IU-2026.2.3")))
        failureLevel.set(listOf(org.jetbrains.intellij.tasks.RunPluginVerifierTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            org.jetbrains.intellij.tasks.RunPluginVerifierTask.FailureLevel.INVALID_PLUGIN,
            org.jetbrains.intellij.tasks.RunPluginVerifierTask.FailureLevel.MISSING_DEPENDENCIES))
    }
    withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        includeEmptyDirs = false
    }
    jar {
        from(listOf("LICENSE", "THIRD-PARTY-NOTICES.md")) { into("META-INF") }
    }
    // The compile-time 241 SDK does not contain the modern bundled JCEF module.
    // Do not launch that SDK to index this plugin's settings during packaging.
    buildSearchableOptions { enabled = false }
    jarSearchableOptions { enabled = false }
    buildPlugin {
        // Disabled indexing must not bundle an empty JAR left by an older build.
        exclude("**/searchableOptions-*.jar")
    }
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    patchPluginXml {
        // Remote JCEF with chrome_policy_id and a private Thrift transport is required.
        sinceBuild.set("262")
        untilBuild.set("262.*")
    }

    test {
        useJUnitPlatform()
        // GUI clipboard checks are opt-in; ordinary CI does not require or overwrite a desktop clipboard.
        systemProperty("java.awt.headless", !providers.gradleProperty("desktopTests").map(String::toBoolean).getOrElse(false))
    }
}
