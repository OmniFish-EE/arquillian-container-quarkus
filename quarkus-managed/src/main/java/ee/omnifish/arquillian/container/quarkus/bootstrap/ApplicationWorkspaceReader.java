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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.repository.WorkspaceRepository;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Resolves the artifacts of the application being built, which exist in no repository: the application itself (to its
 * directory) and the libraries it contains (the jars from WEB-INF/lib), each with a generated minimal POM.
 *
 * <p>
 * Without it, the resolver looks for the application's POM in the remote repositories for every deployment: a request
 * that never finds anything, and that fails the build when there is no network. The libraries need coordinates to
 * become dependencies of the application, as only dependencies are packaged.
 */
final class ApplicationWorkspaceReader implements WorkspaceReader {

    static final String GROUP_ID = "ee.omnifish.arquillian";
    static final String LIBRARY_GROUP_ID = GROUP_ID + ".lib";
    static final String VERSION = "1.0";

    private final WorkspaceRepository repository = new WorkspaceRepository("arquillian-quarkus");

    /** The file and POM of each artifact, by groupId:artifactId */
    private final Map<String, Path[]> artifacts = new HashMap<>();

    private Path workDirectory;

    /**
     * Sets the application currently being built, which replaces the previous one and its libraries.
     *
     * @param artifactId the artifactId of the application
     * @param application the application root
     * @param workDirectory where to write the generated POMs
     */
    synchronized void setApplication(String artifactId, Path application, Path workDirectory) throws IOException {
        this.workDirectory = workDirectory;
        artifacts.clear();
        add(GROUP_ID, artifactId, application);
    }

    /**
     * Adds a library of the current application.
     *
     * @param library the library jar
     * @return the artifactId of the library, in group {@value #LIBRARY_GROUP_ID}
     */
    synchronized String addLibrary(Path library) throws IOException {
        String baseName = library.getFileName().toString().replaceFirst("\\.jar$", "").replaceAll("[^A-Za-z0-9._-]", "_");

        String artifactId = baseName;
        for (int i = 2; artifacts.containsKey(LIBRARY_GROUP_ID + ":" + artifactId); i++) {
            artifactId = baseName + "-" + i;
        }

        add(LIBRARY_GROUP_ID, artifactId, library);

        return artifactId;
    }

    private void add(String groupId, String artifactId, Path file) throws IOException {
        Path pomFile = workDirectory.resolve(groupId + "." + artifactId + "-" + VERSION + ".pom");

        Files.writeString(pomFile, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                </project>
                """.formatted(groupId, artifactId, VERSION), UTF_8);

        artifacts.put(groupId + ":" + artifactId, new Path[] { file, pomFile });
    }

    @Override
    public WorkspaceRepository getRepository() {
        return repository;
    }

    @Override
    public synchronized File findArtifact(Artifact artifact) {
        Path[] filePom = find(artifact);
        if (filePom == null) {
            return null;
        }

        return "pom".equals(artifact.getExtension()) ? filePom[1].toFile() : filePom[0].toFile();
    }

    @Override
    public synchronized List<String> findVersions(Artifact artifact) {
        return find(artifact) != null ? List.of(VERSION) : List.of();
    }

    private Path[] find(Artifact artifact) {
        if (!VERSION.equals(artifact.getVersion())) {
            return null;
        }

        return artifacts.get(artifact.getGroupId() + ":" + artifact.getArtifactId());
    }
}
