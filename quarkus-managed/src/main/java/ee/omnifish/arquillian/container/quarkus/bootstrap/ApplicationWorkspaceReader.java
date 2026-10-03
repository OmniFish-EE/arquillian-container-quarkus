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
import java.util.List;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.repository.WorkspaceRepository;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Resolves the application artifact being built, which exists in no repository, to its directory and a generated
 * minimal POM.
 *
 * <p>
 * Without it, the resolver looks for the application's POM in the remote repositories for every deployment: a request
 * that never finds anything, and that fails the build when there is no network.
 */
final class ApplicationWorkspaceReader implements WorkspaceReader {

    static final String GROUP_ID = "ee.omnifish.arquillian";
    static final String VERSION = "1.0";

    private final WorkspaceRepository repository = new WorkspaceRepository("arquillian-quarkus");

    private String artifactId;
    private Path application;
    private Path pom;

    /**
     * Sets the application currently being built.
     *
     * @param artifactId the artifactId of the application
     * @param application the application root
     * @param workDirectory where to write the generated POM
     */
    synchronized void setApplication(String artifactId, Path application, Path workDirectory) throws IOException {
        Path pomFile = workDirectory.resolve(artifactId + "-" + VERSION + ".pom");

        Files.writeString(pomFile, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                </project>
                """.formatted(GROUP_ID, artifactId, VERSION), UTF_8);

        this.artifactId = artifactId;
        this.application = application;
        this.pom = pomFile;
    }

    @Override
    public WorkspaceRepository getRepository() {
        return repository;
    }

    @Override
    public synchronized File findArtifact(Artifact artifact) {
        if (!isApplication(artifact)) {
            return null;
        }

        return "pom".equals(artifact.getExtension()) ? pom.toFile() : application.toFile();
    }

    @Override
    public synchronized List<String> findVersions(Artifact artifact) {
        return isApplication(artifact) ? List.of(VERSION) : List.of();
    }

    private boolean isApplication(Artifact artifact) {
        return
            artifactId != null &&
            GROUP_ID.equals(artifact.getGroupId()) &&
            artifactId.equals(artifact.getArtifactId()) &&
            VERSION.equals(artifact.getVersion());
    }
}
