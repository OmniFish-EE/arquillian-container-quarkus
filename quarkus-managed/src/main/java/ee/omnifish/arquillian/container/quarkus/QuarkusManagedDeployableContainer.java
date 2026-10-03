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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.exporter.ExplodedExporter;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

/**
 * Arquillian container that builds each deployment into a production Quarkus application (fast-jar or native
 * executable) and runs it in its own process.
 *
 * <p>
 * Unlike {@code quarkus-arquillian}, the server doesn't share the test JVM: no shared classes, static state, system
 * properties or JUL LogManager, and the test class path is not visible to the application.
 */
public class QuarkusManagedDeployableContainer implements DeployableContainer<QuarkusManagedContainerConfiguration> {

    private static final System.Logger LOG = System.getLogger(QuarkusManagedDeployableContainer.class.getName());

    /** How often to try another free port when the application can't use the one picked for it. */
    private static final int FREE_PORT_ATTEMPTS = 3;

    private QuarkusManagedContainerConfiguration configuration;

    private Process process;
    private Path workDirectory;
    private int port;

    @Override
    public Class<QuarkusManagedContainerConfiguration> getConfigurationClass() {
        return QuarkusManagedContainerConfiguration.class;
    }

    @Override
    public void setup(QuarkusManagedContainerConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public void start() throws LifecycleException {
        // Nothing to start: there's one application process per deployment
    }

    @Override
    public void stop() throws LifecycleException {
        stopProcess();
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        // Tests run as client (@Deployment(testable = false)); no in-container protocol support
        return new ProtocolDescription("Local");
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        String name = archiveBaseName(archive);
        boolean isWar = archive instanceof WebArchive;

        try {
            workDirectory = Files.createTempDirectory("arquillian-quarkus-" + name + "-");

            Path exploded = workDirectory.resolve("exploded");
            archive.as(ExplodedExporter.class).exportExplodedInto(exploded.toFile());

            Path application = workDirectory.resolve("application");
            List<Path> libraries = new ArrayList<>();
            if (isWar) {
                toQuarkusLayout(exploded, application, libraries);
            } else {
                application = exploded;
            }

            Properties buildProperties = configuration.getBuildPropertyMap();
            String contextRoot = "/";
            if (isWar && configuration.isContextRootFromArchiveName()) {
                contextRoot = "/" + name;
                buildProperties.putIfAbsent("quarkus.servlet.context-path", contextRoot);
            }
            if (configuration.isNativeImage()) {
                buildProperties.putIfAbsent("quarkus.native.enabled", "true");
                addSystemPropertiesToNativeBuild(buildProperties);
            }

            Path runner = build(name, application, libraries, buildProperties);

            startProcess(runner);

            ProtocolMetaData metaData = new ProtocolMetaData();
            HTTPContext httpContext = new HTTPContext("localhost", port);
            httpContext.add(new Servlet("default", contextRoot));
            metaData.addContext(httpContext);

            return metaData;
        } catch (DeploymentException e) {
            cleanUp();
            throw e;
        } catch (Exception e) {
            cleanUp();
            throw new DeploymentException("Could not deploy " + archive.getName() + " to Quarkus", e);
        }
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        cleanUp();
    }

    @Override
    public void deploy(Descriptor descriptor) throws DeploymentException {
        throw new UnsupportedOperationException("Deploying descriptors is not supported");
    }

    @Override
    public void undeploy(Descriptor descriptor) throws DeploymentException {
        throw new UnsupportedOperationException("Undeploying descriptors is not supported");
    }

    /**
     * Quarkus doesn't run WARs, so rearrange the web archive into an application root Quarkus reads:
     * <ul>
     * <li>{@code WEB-INF/classes} becomes the application root;</li>
     * <li>{@code WEB-INF/web.xml}, {@code WEB-INF/web-fragment.xml} and {@code WEB-INF/beans.xml} move to
     * {@code META-INF/}, where Quarkus looks for them;</li>
     * <li>the web root (everything outside WEB-INF and META-INF) moves to {@code META-INF/resources};</li>
     * <li>{@code WEB-INF/lib} jars become additional application archives;</li>
     * <li>everything else in {@code WEB-INF} and {@code META-INF} is kept.</li>
     * </ul>
     */
    private static void toQuarkusLayout(Path war, Path application, List<Path> libraries) throws IOException {
        Path webInf = war.resolve("WEB-INF");

        Path classes = webInf.resolve("classes");
        if (Files.isDirectory(classes)) {
            Files.move(classes, application);
        } else {
            Files.createDirectories(application);
        }
        Path metaInf = Files.createDirectories(application.resolve("META-INF"));

        Path lib = webInf.resolve("lib");
        if (Files.isDirectory(lib)) {
            try (Stream<Path> jars = Files.list(lib)) {
                jars.filter(jar -> jar.getFileName().toString().endsWith(".jar")).forEach(libraries::add);
            }
        }

        for (String descriptor : List.of("web.xml", "web-fragment.xml", "beans.xml")) {
            Path file = webInf.resolve(descriptor);
            if (Files.exists(file)) {
                Files.move(file, metaInf.resolve(descriptor), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        try (Stream<Path> entries = Files.list(war)) {
            for (Path entry : entries.toList()) {
                String fileName = entry.getFileName().toString();
                if (fileName.equals("WEB-INF")) {
                    copyTree(entry, application.resolve("WEB-INF"), lib);
                } else if (fileName.equals("META-INF")) {
                    copyTree(entry, metaInf, null);
                } else {
                    copyTree(entry, metaInf.resolve("resources").resolve(fileName), null);
                }
            }
        }
    }

    private static void copyTree(Path source, Path target, Path skip) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                if (skip != null && path.startsWith(skip)) {
                    continue;
                }
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else if (!Files.exists(destination)) {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    /**
     * Quarkus deploys servlets during static initialization, which for a native executable happens while the image is
     * built. Servlet container initializers and context listeners then run in the native-image builder, so give them
     * the same system properties as the application has at runtime.
     */
    private void addSystemPropertiesToNativeBuild(Properties buildProperties) {
        List<String> arguments = new ArrayList<>();
        String existing = buildProperties.getProperty("quarkus.native.additional-build-args");
        if (existing != null && !existing.isBlank()) {
            arguments.add(existing);
        }
        configuration.getSystemPropertyMap()
                .forEach((key, value) -> arguments.add(("-D" + key + "=" + value).replace(",", "\\,")));
        if (!arguments.isEmpty()) {
            buildProperties.setProperty("quarkus.native.additional-build-args", String.join(",", arguments));
        }
    }

    /**
     * Builds a production (fast-jar or native) application with the Quarkus bootstrap, which runs in its own class loader so that
     * it doesn't share libraries with the test class path; see {@link IsolatedQuarkusBootstrap}.
     */
    private Path build(String name, Path application, List<Path> libraries, Properties buildProperties) throws Exception {
        LOG.log(INFO, "Building Quarkus application {0} with {1}", name, configuration.getDependencyList());

        return IsolatedQuarkusBootstrap.build(
                name,
                application,
                workDirectory,
                libraries,
                configuration.getDependencyList(),
                configuration.getQuarkusVersion(),
                buildProperties);
    }

    /**
     * Starts the application on the configured port or, when that is 0, on a free port. Another process may take a free
     * port before the application binds it; the application then exits, and is started again on another free port.
     */
    private void startProcess(Path runner) throws Exception {
        int configuredPort = configuration.getHttpPort();
        if (configuredPort > 0) {
            if (!isPortFree(configuredPort)) {
                throw new DeploymentException("Port " + configuredPort + " is already in use");
            }
            startProcess(runner, configuredPort);
            return;
        }

        for (int attempt = 1; ; attempt++) {
            try {
                startProcess(runner, freePort());
                return;
            } catch (ApplicationExitedException e) {
                if (attempt == FREE_PORT_ATTEMPTS) {
                    throw e;
                }
                LOG.log(WARNING, "Quarkus application exited during startup, retrying on another port");
            }
        }
    }

    /**
     * Starts the application: a fast-jar with the JVM of the tests, a native executable directly. A native executable
     * takes the same {@code -D} and memory options.
     */
    private void startProcess(Path runner, int port) throws Exception {
        this.port = port;

        boolean nativeExecutable = !runner.getFileName().toString().endsWith(".jar");

        List<String> command = new ArrayList<>();
        command.add(nativeExecutable ? runner.toString() : Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(configuration.getJavaVmArgumentList());
        command.add("-Dquarkus.http.port=" + port);
        configuration.getSystemPropertyMap()
                .forEach((key, value) -> command.add("-D" + key + "=" + value));

        if (nativeExecutable) {
            if (configuration.isUseJdkLogManager()) {
                // Quarkus builds JBoss LogManager into the native image; it can't be replaced when starting it
                LOG.log(WARNING, "useJdkLogManager is not supported for native executables, ignoring it");
            }
        } else if (configuration.isUseJdkLogManager()) {
            command.add("-cp");
            command.add(runner + File.pathSeparator + launcherLocation());
            command.add(JdkLogManagerLauncher.class.getName());
        } else {
            command.add("-jar");
            command.add(runner.toString());
        }

        LOG.log(INFO, "Starting Quarkus application: {0}", String.join(" ", command));

        process = new ProcessBuilder(command)
                .directory(workDirectory.toFile())
                .redirectErrorStream(true)
                .start();

        forwardOutput(process);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(configuration.getStartupTimeoutInSeconds());
        while (!isAcceptingConnections(port)) {
            if (!process.isAlive()) {
                int exitValue = process.exitValue();
                process = null;
                throw new ApplicationExitedException(exitValue);
            }
            if (System.nanoTime() > deadline) {
                throw new DeploymentException("Quarkus application did not start within "
                        + configuration.getStartupTimeoutInSeconds() + " seconds");
            }
            Thread.sleep(100);
        }
    }

    /**
     * Copies the application's output to this JVM's {@code System.out}. Inheriting the process' I/O would make it write
     * to the native stdout directly, which bypasses e.g. Surefire's capturing and corrupts its channel to a forked test
     * JVM.
     */
    private static void forwardOutput(Process process) {
        Thread forwarder = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            } catch (IOException e) {
                // The process ended or its stream was closed
            }
        }, "quarkus-application-output");
        forwarder.setDaemon(true);
        forwarder.start();
    }

    /** The jar or directory this library's classes are in, which holds the launcher class. */
    private static String launcherLocation() throws Exception {
        return Path.of(JdkLogManagerLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
    }

    private void stopProcess() {
        if (process == null) {
            return;
        }

        process.destroy();
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                LOG.log(WARNING, "Quarkus application did not stop in time, killing it");
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        process = null;
    }

    private void cleanUp() {
        stopProcess();

        if (workDirectory != null && !configuration.isKeepWorkDirectory()) {
            try (Stream<Path> paths = Files.walk(workDirectory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        workDirectory = null;
    }

    /** A port that is free now, chosen by the operating system. */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static boolean isPortFree(int port) {
        // Binds to all addresses, so a server listening only on IPv6 (where "localhost" may resolve to) is found too
        try (ServerSocket socket = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isAcceptingConnections(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 200);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String archiveBaseName(Archive<?> archive) {
        String name = archive.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** The application process ended before it accepted connections. */
    private static final class ApplicationExitedException extends DeploymentException {

        private static final long serialVersionUID = 1L;

        ApplicationExitedException(int exitValue) {
            super("Quarkus application exited during startup with code " + exitValue);
        }
    }
}
