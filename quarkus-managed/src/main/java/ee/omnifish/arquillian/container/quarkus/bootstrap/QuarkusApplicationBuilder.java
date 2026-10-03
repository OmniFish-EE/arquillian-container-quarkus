/*
 * Copyright (c) 2026 OmniFish. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */
package ee.omnifish.arquillian.container.quarkus.bootstrap;

import io.quarkus.bootstrap.app.AdditionalDependency;
import io.quarkus.bootstrap.app.AugmentResult;
import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.maven.dependency.ArtifactDependency;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.maven.dependency.GACTV;
import io.quarkus.maven.dependency.ResolvedArtifactDependency;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.eclipse.aether.DefaultRepositorySystemSession;

import static ee.omnifish.arquillian.container.quarkus.bootstrap.ApplicationWorkspaceReader.GROUP_ID;
import static ee.omnifish.arquillian.container.quarkus.bootstrap.ApplicationWorkspaceReader.VERSION;
import static io.quarkus.bootstrap.app.QuarkusBootstrap.Mode.PROD;
import static io.quarkus.maven.dependency.ArtifactCoords.TYPE_JAR;

/**
 * Builds a production (fast-jar) Quarkus application, the way Quarkus' JBang integration does: the application classes
 * as application artifact, and the configured extensions as its dependencies, versions managed by the Quarkus BOM.
 *
 * <p>
 * Runs in an isolated class loader that holds the Quarkus bootstrap and its dependencies, so none of those are on the
 * test class path. It is called reflectively and must therefore only take and return JDK types; nothing outside this
 * package may reference it directly.
 */
public final class QuarkusApplicationBuilder {

    private static final ApplicationWorkspaceReader WORKSPACE_READER = new ApplicationWorkspaceReader();

    private static MavenArtifactResolver mavenArtifactResolver;

    private QuarkusApplicationBuilder() {
    }

    /**
     * Builds the application.
     *
     * @param name the base name of the application
     * @param application the application root (classes and resources)
     * @param workDirectory the directory to build in
     * @param libraries additional application archives (e.g. the jars from WEB-INF/lib)
     * @param dependencies extensions and libraries as {@code groupId:artifactId[:version]}
     * @param quarkusVersion the version of the Quarkus BOM, or null for the version of this bootstrap
     * @param buildProperties Quarkus build time configuration
     * @return the path of the runnable {@code quarkus-run.jar}, or of the native executable when
     *         {@code quarkus.native.enabled} is set
     * @throws Exception when the application could not be built
     */
    public static synchronized Path build(String name, Path application, Path workDirectory, List<Path> libraries,
            List<String> dependencies, String quarkusVersion, Properties buildProperties) throws Exception {

        String version = quarkusVersion != null ? quarkusVersion : bootstrapQuarkusVersion();
        WORKSPACE_READER.setApplication(name, application, workDirectory);

        QuarkusBootstrap.Builder builder = QuarkusBootstrap.builder()
                .setBaseClassLoader(QuarkusApplicationBuilder.class.getClassLoader())
                .setMavenArtifactResolver(mavenArtifactResolver())
                .setProjectRoot(workDirectory)
                .setTargetDirectory(workDirectory.resolve("target"))
                .setBaseName(name)
                .setLocalProjectDiscovery(false)
                .setAppArtifact(new ResolvedArtifactDependency(
                        GROUP_ID,
                        name,
                        null,
                        TYPE_JAR,
                        VERSION,
                        application))
                .setManagingProject(new GACTV("io.quarkus", "quarkus-bom", "", "pom", version))
                .setForcedDependencies(toDependencies(dependencies, version))
                .setIsolateDeployment(true)
                .setBuildSystemProperties(buildProperties)
                .setMode(PROD);

        for (Path library : libraries) {
            builder.addAdditionalApplicationArchive(new AdditionalDependency(library, false, false));
        }

        try (CuratedApplication curatedApplication = builder.build().bootstrap()) {
            AugmentResult result = curatedApplication.createAugmentor().createProductionApplication();
            if (result.getNativeResult() != null) {
                return result.getNativeResult();
            }

            return result.getJar().getPath();
        }
    }

    private static List<Dependency> toDependencies(List<String> coordinatesList, String quarkusVersion) {
        List<Dependency> dependencies = new ArrayList<>();

        for (String coordinates : coordinatesList) {
            String[] parts = coordinates.split(":");
            String version;
            if (parts.length == 3) {
                version = parts[2];
            } else if (parts.length == 2 && parts[0].startsWith("io.quarkus")) {
                version = quarkusVersion;
            } else {
                throw new IllegalArgumentException(
                        "Expected groupId:artifactId:version (version optional for io.quarkus), but got " + coordinates);
            }
            dependencies.add(new ArtifactDependency(parts[0], parts[1], null, TYPE_JAR, version));
        }

        return dependencies;
    }

    private static MavenArtifactResolver mavenArtifactResolver() throws Exception {
        if (mavenArtifactResolver == null) {
            BootstrapMavenContext mavenContext =
                new BootstrapMavenContext(BootstrapMavenContext.config()
                        .setWorkspaceDiscovery(false));

            DefaultRepositorySystemSession session =
                new DefaultRepositorySystemSession(
                    mavenContext.getRepositorySystemSession());

            session.setWorkspaceReader(WORKSPACE_READER);

            mavenArtifactResolver = MavenArtifactResolver.builder()
                    .setRepositorySystem(mavenContext.getRepositorySystem())
                    .setRepositorySystemSession(session)
                    .setRemoteRepositoryManager(mavenContext.getRemoteRepositoryManager())
                    .setRemoteRepositories(mavenContext.getRemoteRepositories())
                    .build();
        }

        return mavenArtifactResolver;
    }

    /** The version of the Quarkus bootstrap this builder runs with. */
    private static String bootstrapQuarkusVersion() throws IOException {
        try (InputStream versionFile = QuarkusBootstrap.class.getClassLoader().getResourceAsStream("quarkus-version.txt")) {
            if (versionFile == null) {
                throw new IllegalStateException("Can't determine the Quarkus version; set quarkusVersion");
            }

            return new String(versionFile.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }
}
