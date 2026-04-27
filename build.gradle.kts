plugins {
    java
    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "ai.intellistream"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

val commonmarkVersion = "0.22.0"
val testcontainersVersion = "1.20.4"
val luceneVersion = "10.4.0"

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:$testcontainersVersion")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.security:spring-security-messaging")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    runtimeOnly("org.postgresql:postgresql")
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    implementation("org.commonmark:commonmark:$commonmarkVersion")
    implementation("org.commonmark:commonmark-ext-gfm-tables:$commonmarkVersion")
    implementation("org.commonmark:commonmark-ext-autolink:$commonmarkVersion")

    implementation("org.jsoup:jsoup:1.18.1")

    implementation("org.apache.lucene:lucene-core:$luceneVersion")
    implementation("org.apache.lucene:lucene-analysis-common:$luceneVersion")
    implementation("org.apache.lucene:lucene-queryparser:$luceneVersion")

    // Streaming multipart parser (used for attachment uploads; bypasses Spring's MultipartResolver
    // for the upload endpoint so we never buffer the file).
    implementation("org.apache.commons:commons-fileupload2-jakarta-servlet6:2.0.0-M2")

    // Apache Tika for MIME sniffing on attachment uploads. tika-core only — the full
    // distribution drags in PDFBox, POI, and a half-dozen other parsers we don't need
    // (the goal is "what type are these bytes really?", not "extract text from PDF").
    // Replaces URLConnection.guessContentTypeFromStream which only knew ~10 magic-byte
    // families and mis-typed common formats (HEIC, AVIF, modern Office files, polyglot
    // PNG-with-HTML payloads).
    implementation("org.apache.tika:tika-core:2.9.2")

    // Optional Vault / OpenBao secret backend. The processor talks to Vault's HTTP KV-v2
    // endpoint directly with Spring's RestClient — no spring-vault-core / spring-cloud-vault
    // dependency, both of which currently target Spring Framework 6 and don't link cleanly
    // against Spring 7 (Boot 4 brings in Spring 7's pruned RestTemplate constructors). The
    // surface we use is small enough that a 60-line implementation is cheaper than chasing
    // the upstream library's release schedule.

    // Lombok generates entity boilerplate (getters/setters/no-arg ctors). Compile-only +
    // annotation processor so no Lombok bytecode lands on the runtime classpath.
    compileOnly("org.projectlombok:lombok:1.18.38")
    annotationProcessor("org.projectlombok:lombok:1.18.38")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:vault")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testCompileOnly("org.projectlombok:lombok:1.18.38")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.38")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Auto-activate the dev profile when running via Gradle so plain `./gradlew bootRun`
// picks up the maintainer's local LAN config (server.address, Keycloak issuer-uri,
// allowed-origins). Tests and `java -jar build/libs/...` are unaffected — the dev
// profile only activates here.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    args("--spring.profiles.active=dev")
}
