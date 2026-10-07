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
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.eclipse.aether.DefaultRepositorySystemSession;

import static ee.omnifish.arquillian.container.quarkus.bootstrap.ApplicationWorkspaceReader.GROUP_ID;
import static ee.omnifish.arquillian.container.quarkus.bootstrap.ApplicationWorkspaceReader.LIBRARY_GROUP_ID;
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
     * @param libraries libraries of the application (e.g. the jars from WEB-INF/lib)
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

        List<Dependency> forcedDependencies = toDependencies(dependencies, version);
        Properties allBuildProperties = new Properties();
        allBuildProperties.putAll(buildProperties);
        addLibraries(libraries, forcedDependencies, allBuildProperties);

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
                .setForcedDependencies(forcedDependencies)
                .setIsolateDeployment(true)
                .setBuildSystemProperties(allBuildProperties)
                .setMode(PROD);

        try (CuratedApplication curatedApplication = builder.build().bootstrap()) {
            AugmentResult result = curatedApplication.createAugmentor().createProductionApplication();
            if (result.getNativeResult() != null) {
                return result.getNativeResult();
            }

            return result.getJar().getPath();
        }
    }

    /**
     * Makes the libraries dependencies of the application, so they are packaged with it, and indexed, so annotations in
     * them are found, the way a Servlet container scans the jars in WEB-INF/lib.
     *
     * <p>
     * Libraries with only Jakarta (or Java) API classes are left out: Quarkus provides those APIs, and a Servlet
     * container doesn't load these packages from a web application either.
     */
    private static void addLibraries(List<Path> libraries, List<Dependency> dependencies, Properties buildProperties) throws IOException {
        int index = 0;
        for (Path library : libraries) {
            if (containsOnlyPlatformClasses(library)) {
                continue;
            }

            String artifactId = WORKSPACE_READER.addLibrary(library);
            dependencies.add(new ArtifactDependency(LIBRARY_GROUP_ID, artifactId, null, TYPE_JAR, VERSION));

            String indexDependency = "quarkus.index-dependency.arquillian-library-" + index++;
            buildProperties.setProperty(indexDependency + ".group-id", LIBRARY_GROUP_ID);
            buildProperties.setProperty(indexDependency + ".artifact-id", artifactId);
        }
    }

    /**
     * @return true if the jar contains classes, all of them in jakarta.* or java.* packages
     */
    private static boolean containsOnlyPlatformClasses(Path jar) throws IOException {
        boolean hasClasses = false;
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class") || name.endsWith("module-info.class") || name.startsWith("META-INF/")) {
                    continue;
                }

                if (!name.startsWith("jakarta/") && !name.startsWith("java/")) {
                    return false;
                }
                hasClasses = true;
            }
        }

        return hasClasses;
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
