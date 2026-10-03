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
package ee.omnifish.arquillian.container.quarkus;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Runs the Quarkus bootstrap in its own class loader.
 *
 * <p>
 * The Quarkus bootstrap and its dependencies (Maven Resolver, Maven model, Plexus, ...) are embedded in this
 * container's jar as nested jars, instead of being dependencies of it. They are loaded in a class loader whose parent is
 * the platform class loader, so they neither pollute the test class path nor get replaced by other versions of the same
 * libraries on it (Maven's version mediation would otherwise pick e.g. the Maven Resolver of ShrinkWrap Resolver).
 * Quarkus' own tooling (Maven and Gradle plugins, JBang) hosts the bootstrap the same way.
 *
 * <p>
 * The builder in the isolated class loader is called reflectively, with JDK types only.
 */
final class IsolatedQuarkusBootstrap {

    /** Where the build puts the bootstrap jars, in the container jar (or classes directory). */
    static final String BOOTSTRAP_LIBRARIES = "META-INF/quarkus-bootstrap/";

    private static final String BUILDER = "ee.omnifish.arquillian.container.quarkus.bootstrap.QuarkusApplicationBuilder";

    private static ClassLoader classLoader;

    private IsolatedQuarkusBootstrap() {
    }

    /**
     * Builds a production Quarkus application with the isolated bootstrap.
     *
     * @return the path of the runnable {@code quarkus-run.jar}, or of the native executable
     */
    static Path build(String name, Path application, Path workDirectory, List<Path> libraries, List<String> dependencies,
            String quarkusVersion, Properties buildProperties) throws Exception {
        ClassLoader bootstrapClassLoader = classLoader();
        Method build = bootstrapClassLoader.loadClass(BUILDER).getMethod("build",
                String.class, Path.class, Path.class, List.class, List.class, String.class, Properties.class);

        Thread thread = Thread.currentThread();
        ClassLoader originalClassLoader = thread.getContextClassLoader();
        thread.setContextClassLoader(bootstrapClassLoader);
        try {
            return (Path) build.invoke(null, name, application, workDirectory, libraries, dependencies, quarkusVersion,
                    buildProperties);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception exception) {
                throw exception;
            }
            throw e;
        } finally {
            thread.setContextClassLoader(originalClassLoader);
        }
    }

    private static synchronized ClassLoader classLoader() throws Exception {
        if (classLoader == null) {
            classLoader = createClassLoader();
        }
        return classLoader;
    }

    private static ClassLoader createClassLoader() throws Exception {
        Path location = Path.of(IsolatedQuarkusBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI());

        List<URL> urls = new ArrayList<>();

        // This jar (or classes directory) itself, for the builder class
        urls.add(location.toUri().toURL());

        if (Files.isDirectory(location)) {
            // Running from target/classes: the jars are there as plain files
            try (Stream<Path> jars = Files.list(location.resolve(BOOTSTRAP_LIBRARIES))) {
                for (Path jar : jars.filter(IsolatedQuarkusBootstrap::isJar).sorted().toList()) {
                    urls.add(jar.toUri().toURL());
                }
            }
        } else {
            urls.addAll(extractNestedJars(location));
        }

        if (urls.size() == 1) {
            throw new IllegalStateException("No Quarkus bootstrap libraries found under " + BOOTSTRAP_LIBRARIES + " in " + location);
        }

        return new URLClassLoader("arquillian-quarkus-bootstrap", urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader());
    }

    /** URLClassLoader can't load nested jars, so extract them to a temporary directory for the life of the JVM. */
    private static List<URL> extractNestedJars(Path containerJar) throws IOException {
        Path directory = Files.createTempDirectory("arquillian-quarkus-bootstrap-");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteRecursively(directory)));

        List<URL> urls = new ArrayList<>();
        try (JarFile jarFile = new JarFile(containerJar.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (entry.isDirectory() || !entryName.startsWith(BOOTSTRAP_LIBRARIES) || !entryName.endsWith(".jar")) {
                    continue;
                }

                Path target = directory.resolve(entryName.substring(BOOTSTRAP_LIBRARIES.length()));
                try (InputStream in = jarFile.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                urls.add(target.toUri().toURL());
            }
        }
        return urls;
    }

    private static boolean isJar(Path path) {
        return path.getFileName().toString().endsWith(".jar");
    }

    private static void deleteRecursively(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            // Best effort, in a shutdown hook
        }
    }
}
