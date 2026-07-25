/*
 * Copyright 2026 Olav Gjerde
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

package ai.intellistream.chat.service;

import ai.intellistream.chat.web.dto.AboutDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.SpringVersion;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.function.Supplier;

/**
 * Builds the About payload.
 *
 * <p>Versions are read from the running classes rather than from a hand-maintained list, because a
 * hand-maintained list is wrong the moment someone bumps a dependency and forgets this file. Most
 * come from the jar manifest via {@code Package.getImplementationVersion()}; a few libraries expose
 * a dedicated accessor, which is preferred where it exists because shaded or repackaged jars can
 * lose the manifest attribute.
 *
 * <p>Every lookup is individually guarded. A missing manifest entry yields {@code null} for that
 * row and a missing class is skipped entirely, so swapping a dependency out can never turn the
 * About dialog into a 500.
 */
@Service
public class AboutService {

    private static final Logger log = LoggerFactory.getLogger(AboutService.class);

    /**
     * Constants rather than configuration: the licence of this software is a fact about the source
     * you are running, not a per-deployment setting. A fork that relicenses changes it here, at
     * the same time as it changes LICENSE and the file headers. Bundled third-party components
     * keep their own terms, listed in THIRD-PARTY-NOTICES.md.
     */
    private static final String LICENSE = "Apache License 2.0";
    private static final String LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0";
    private static final String COPYRIGHT = "Copyright 2026 Olav Gjerde";

    private final ObjectProvider<BuildProperties> buildProperties;
    private final DataSource dataSource;
    private final String appName;

    public AboutService(ObjectProvider<BuildProperties> buildProperties,
                        DataSource dataSource,
                        @org.springframework.beans.factory.annotation.Value("${ichat.branding.title:IntelliStream Chat}")
                        String appName) {
        this.buildProperties = buildProperties;
        this.dataSource = dataSource;
        this.appName = appName;
    }

    /**
     * @param includeDetail whether to attach server and component detail (admins only —
     *                      see {@link AboutDto}).
     */
    public AboutDto about(boolean includeDetail) {
        var build = buildProperties.getIfAvailable();
        // Fall back to the jar manifest, then to "dev": running from an exploded classpath (bootRun,
        // tests) there is no build-info and no manifest, and reporting "unknown" there is noise.
        String version = build != null ? build.getVersion() : manifestVersion();
        Instant buildTime = build != null ? build.getTime() : null;

        if (!includeDetail) {
            return new AboutDto(appName, version, buildTime, LICENSE, LICENSE_URL, COPYRIGHT, null, null);
        }
        return new AboutDto(appName, version, buildTime, LICENSE, LICENSE_URL, COPYRIGHT,
                serverInfo(), components());
    }

    private String manifestVersion() {
        var v = AboutService.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }

    private AboutDto.ServerInfo serverInfo() {
        var runtime = ManagementFactory.getRuntimeMXBean();
        return new AboutDto.ServerInfo(
                System.getProperty("java.version"),
                System.getProperty("java.vendor"),
                System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"),
                System.getProperty("os.name") + " " + System.getProperty("os.version"),
                System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() / (1024 * 1024),
                runtime.getUptime() / 1000,
                ZoneId.systemDefault().getId() + " (" + TimeZone.getDefault().getDisplayName() + ")");
    }

    private List<AboutDto.Component> components() {
        var out = new ArrayList<AboutDto.Component>();
        add(out, "Spring Boot", SpringBootVersion::getVersion);
        add(out, "Spring Framework", SpringVersion::getVersion);
        add(out, "Spring Security", () -> org.springframework.security.core.SpringSecurityCoreVersion.getVersion());
        add(out, "Tomcat", () -> org.apache.catalina.util.ServerInfo.getServerNumber());
        add(out, "Hibernate ORM", () -> org.hibernate.Version.getVersionString());
        add(out, "PostgreSQL", this::databaseVersion);
        add(out, "PostgreSQL JDBC", () -> pkgVersion("org.postgresql.Driver", "org.postgresql", "postgresql"));
        add(out, "Flyway", () -> pkgVersion("org.flywaydb.core.Flyway", "org.flywaydb", "flyway-core"));
        add(out, "Apache Lucene", () -> org.apache.lucene.util.Version.LATEST.toString());
        add(out, "CommonMark", () -> pkgVersion("org.commonmark.parser.Parser", "org.commonmark", "commonmark"));
        add(out, "jsoup", () -> pkgVersion("org.jsoup.nodes.Document", "org.jsoup", "jsoup"));
        add(out, "Apache Tika", () -> pkgVersion("org.apache.tika.Tika", "org.apache.tika", "tika-core"));
        add(out, "HikariCP", () -> pkgVersion("com.zaxxer.hikari.HikariDataSource", "com.zaxxer", "HikariCP"));
        // Jackson moved from com.fasterxml.jackson to tools.jackson at 3.x, which Boot 4 pulls in.
        // Resolved by name rather than by import so this keeps working across that split.
        add(out, "Jackson", () -> pkgVersion("tools.jackson.databind.ObjectMapper", "tools.jackson.core", "jackson-databind"));
        return out;
    }

    /**
     * Runs one lookup. A {@code Throwable} catch is deliberate rather than lazy: these are
     * reflective and classloader-sensitive calls, and {@code NoClassDefFoundError} — the failure a
     * removed dependency actually produces — is an Error, not an Exception.
     */
    private void add(List<AboutDto.Component> out, String name, Supplier<String> lookup) {
        try {
            out.add(new AboutDto.Component(name, lookup.get()));
        } catch (Throwable t) {
            log.debug("About: no version for {} ({})", name, t.toString());
        }
    }

    /**
     * Version of the jar that owns {@code className}.
     *
     * <p>Tries the manifest first, then Maven's {@code pom.properties}. The fallback matters:
     * {@code Package.getImplementationVersion()} returns null whenever the producing jar ships no
     * {@code Implementation-Version} attribute, which several common libraries do not, and that
     * turned four rows of this table into "unknown". Every Maven-built artifact embeds
     * {@code META-INF/maven/<group>/<artifact>/pom.properties}, so it is the more reliable source.
     */
    private String pkgVersion(String className, String groupId, String artifactId) {
        Class<?> type;
        try {
            type = Class.forName(className);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
        var fromManifest = type.getPackage().getImplementationVersion();
        if (fromManifest != null) return fromManifest;

        var resource = "META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties";
        try (var in = type.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) return null;
            var props = new java.util.Properties();
            props.load(in);
            return props.getProperty("version");
        } catch (java.io.IOException e) {
            log.debug("About: could not read {}", resource, e);
            return null;
        }
    }

    private String databaseVersion() {
        try (var conn = dataSource.getConnection()) {
            var md = conn.getMetaData();
            return md.getDatabaseProductVersion();
        } catch (SQLException e) {
            log.debug("About: could not read the database version", e);
            return null;
        }
    }
}
