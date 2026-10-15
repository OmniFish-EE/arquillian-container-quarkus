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
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
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

    /** Arquillian's HTTPS context, which Arquillian versions before 1.11 don't have */
    private static final String HTTPS_CONTEXT_CLASS = "org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPSContext";

    /** The password of the generated key store; it only protects a self-signed test certificate */
    private static final String KEY_STORE_PASSWORD = "arquillian";

    /**
     * The applications that are deployed, by archive: Arquillian undeploys the archive instance it deployed. A test
     * class can have several deployments, which run at the same time.
     */
    private final Map<Archive<?>, Application> applications = Collections.synchronizedMap(new IdentityHashMap<>());

    /** Stops the applications that are still running when the test JVM exits without undeploying them. */
    private Thread shutdownHook;

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
        RuntimeException failure = null;
        for (Map.Entry<Archive<?>, Application> deployed : deployedApplications()) {
            try {
                // Removed only once stopped, so that the shutdown hook still kills it if the JVM exits meanwhile
                cleanUp(deployed.getValue());
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            } finally {
                applications.remove(deployed.getKey());
            }
        }

        removeShutdownHook();

        if (failure != null) {
            throw new LifecycleException("Could not clean up all Quarkus applications", failure);
        }
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
        Application running = new Application(archive.getName());

        try {
            running.workDirectory = Files.createTempDirectory("arquillian-quarkus-" + name + "-");

            Path exploded = running.workDirectory.resolve("exploded");
            archive.as(ExplodedExporter.class).exportExplodedInto(exploded.toFile());

            Path application = running.workDirectory.resolve("application");
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

            Path runner = build(name, application, running.workDirectory, libraries, buildProperties);

            if (configuration.getHttpsPort() >= 0) {
                createKeyStore(running.workDirectory.resolve("https.p12"));
            }

            // Known before its process starts, so that the shutdown hook kills it if the JVM exits during startup
            addShutdownHook();
            applications.put(archive, running);
            startProcess(running, runner);

            ProtocolMetaData metaData = new ProtocolMetaData();
            HTTPContext httpContext = new HTTPContext("localhost", running.port);
            httpContext.add(new Servlet("default", contextRoot));
            metaData.addContext(httpContext);

            if (running.httpsPort > 0) {
                HTTPContext httpsContext = createHttpsContext("localhost", running.httpsPort);
                if (httpsContext != null) {
                    httpsContext.add(new Servlet("default", contextRoot));
                    metaData.addContext(httpsContext);
                }
            }

            return metaData;
        } catch (DeploymentException e) {
            cleanUp(archive, running);
            throw e;
        } catch (Exception e) {
            cleanUp(archive, running);
            throw new DeploymentException("Could not deploy " + archive.getName() + " to Quarkus", e);
        }
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        Archive<?> key = archive;
        Application running = applications.get(archive);

        if (running == null) {
            // Not the instance that was deployed: fall back to the archive's name
            for (Map.Entry<Archive<?>, Application> deployed : deployedApplications()) {
                if (deployed.getValue().name.equals(archive.getName())) {
                    key = deployed.getKey();
                    running = deployed.getValue();
                    break;
                }
            }
        }

        if (running != null) {
            // Removed only once stopped, so that the shutdown hook still kills it if the JVM exits meanwhile
            cleanUp(key, running);
        }
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
     * <li>{@code WEB-INF/lib} jars become libraries of the application (ShrinkWrap's exploded export turns a library
     * added as archive into a directory, which is zipped into a jar again), and stay available as resources under
     * {@code WEB-INF/lib};</li>
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
                for (Path jar : jars.filter(jar -> jar.getFileName().toString().endsWith(".jar")).toList()) {
                    Path library = Files.isDirectory(jar) ? toJar(jar, war.resolveSibling("libraries")) : jar;
                    libraries.add(library);

                    // Also a web resource, as in a WAR: e.g. a JSP 1.1 taglib URI can name the jar with the TLD
                    Path libraryResource = application.resolve("WEB-INF/lib").resolve(jar.getFileName().toString());
                    Files.createDirectories(libraryResource.getParent());
                    Files.copy(library, libraryResource, StandardCopyOption.REPLACE_EXISTING);
                }
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

    /**
     * Zips a library that was exported as directory into a jar with the same name.
     */
    private static Path toJar(Path directory, Path targetDirectory) throws IOException {
        Path jar = Files.createDirectories(targetDirectory).resolve(directory.getFileName().toString());
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jarOut = new JarOutputStream(out);
             Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted().toList()) {
                if (path.equals(directory)) {
                    continue;
                }
                String name = directory.relativize(path).toString().replace(File.separatorChar, '/');
                if (Files.isDirectory(path)) {
                    jarOut.putNextEntry(new JarEntry(name + "/"));
                } else {
                    jarOut.putNextEntry(new JarEntry(name));
                    Files.copy(path, jarOut);
                }
                jarOut.closeEntry();
            }
        }
        return jar;
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
    private Path build(String name, Path application, Path workDirectory, List<Path> libraries, Properties buildProperties) throws Exception {
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
    private void startProcess(Application running, Path runner) throws Exception {
        int configuredPort = configuration.getHttpPort();
        int configuredHttpsPort = configuration.getHttpsPort();
        for (int fixedPort : new int[] { configuredPort, configuredHttpsPort }) {
            if (fixedPort > 0 && !isPortFree(fixedPort)) {
                throw new DeploymentException("Port " + fixedPort + " is already in use");
            }
        }

        if (configuredPort > 0 && configuredHttpsPort != 0) {
            startProcess(running, runner, configuredPort, configuredHttpsPort);
            return;
        }

        for (int attempt = 1; ; attempt++) {
            try {
                int httpPort = configuredPort > 0 ? configuredPort : freePort(configuredHttpsPort);
                startProcess(running, runner, httpPort, configuredHttpsPort == 0 ? freePort(httpPort) : configuredHttpsPort);
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
     *
     * @param httpsPort the HTTPS port, or -1 for no HTTPS
     */
    private void startProcess(Application running, Path runner, int port, int httpsPort) throws Exception {
        running.port = port;
        running.httpsPort = httpsPort;

        boolean nativeExecutable = !runner.getFileName().toString().endsWith(".jar");

        List<String> command = new ArrayList<>();
        command.add(nativeExecutable ? runner.toString() : Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(configuration.getJavaVmArgumentList());
        command.add("-Dquarkus.http.port=" + port);
        if (httpsPort > 0) {
            command.add("-Dquarkus.http.ssl-port=" + httpsPort);
            command.add("-Dquarkus.http.ssl.certificate.key-store-file=" + running.workDirectory.resolve("https.p12"));
            command.add("-Dquarkus.http.ssl.certificate.key-store-password=" + KEY_STORE_PASSWORD);
        }
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

        Process process = new ProcessBuilder(command)
                .directory(running.workDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        running.process = process;

        forwardOutput(process);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(configuration.getStartupTimeoutInSeconds());
        while (!isAcceptingConnections(port) || httpsPort > 0 && !isAcceptingConnections(httpsPort)) {
            if (!process.isAlive()) {
                int exitValue = process.exitValue();
                running.process = null;
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

    private static void stopProcess(Application running) {
        Process process = running.process;
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
        running.process = null;
    }

    private void cleanUp(Archive<?> archive, Application running) {
        try {
            cleanUp(running);
        } finally {
            applications.remove(archive);
        }
    }

    private void cleanUp(Application running) {
        stopProcess(running);

        if (running.workDirectory != null && !configuration.isKeepWorkDirectory()) {
            try (Stream<Path> paths = Files.walk(running.workDirectory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        running.workDirectory = null;
    }

    private synchronized void addShutdownHook() {
        if (shutdownHook == null) {
            shutdownHook = new Thread(this::killApplications, "arquillian-quarkus-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
    }

    private synchronized void removeShutdownHook() {
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException e) {
                // The JVM is already exiting, and runs the hook
            }
            shutdownHook = null;
        }
    }

    /**
     * Kills the applications that are still running, and any processes they started, when the test JVM exits without
     * undeploying them. Doesn't wait for them to stop; the JVM is exiting.
     */
    private void killApplications() {
        for (Map.Entry<Archive<?>, Application> deployed : deployedApplications()) {
            Process process = deployed.getValue().process;
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    /** A copy of the deployed applications, to iterate without holding their lock while stopping them. */
    private List<Map.Entry<Archive<?>, Application>> deployedApplications() {
        synchronized (applications) {
            return applications.entrySet().stream()
                    .<Map.Entry<Archive<?>, Application>>map(deployed -> Map.entry(deployed.getKey(), deployed.getValue()))
                    .toList();
        }
    }

    /** A deployed application: its process, ports and work directory. */
    private static final class Application {

        /** The name of the deployed archive */
        final String name;

        Path workDirectory;

        /** Also read by the shutdown hook */
        volatile Process process;

        int port;

        /** The HTTPS port of the application, or -1 for none */
        int httpsPort = -1;

        Application(String name) {
            this.name = name;
        }
    }

    /** A port that is free now, chosen by the operating system, other than the given one. */
    private static int freePort(int otherPort) throws IOException {
        while (true) {
            try (ServerSocket socket = new ServerSocket(0)) {
                if (socket.getLocalPort() != otherPort) {
                    return socket.getLocalPort();
                }
            }
        }
    }

    /**
     * Creates a key store with a self-signed certificate for {@code localhost}, with the JDK's keytool.
     */
    private static void createKeyStore(Path keyStore) throws Exception {
        List<String> command = List.of(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-noprompt",
                "-alias", "localhost",
                "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "3650",
                "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1,ip:::1",
                "-storetype", "PKCS12",
                "-keystore", keyStore.toString(),
                "-storepass", KEY_STORE_PASSWORD,
                "-keypass", KEY_STORE_PASSWORD);

        Process keytool = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(keytool.getInputStream().readAllBytes(), Charset.defaultCharset());
        if (keytool.waitFor() != 0) {
            throw new DeploymentException("Could not create a key store for HTTPS: " + output);
        }
    }

    /**
     * @return Arquillian's HTTPS context, which URLs injected with {@code @ArquillianResource @Secured} are taken from;
     *         null if this version of Arquillian doesn't have it, which then takes the port of the annotation
     */
    private static HTTPContext createHttpsContext(String host, int port) throws ReflectiveOperationException {
        Class<?> httpsContextClass;
        try {
            httpsContextClass = Class.forName(HTTPS_CONTEXT_CLASS, true, HTTPContext.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            LOG.log(INFO, "This version of Arquillian has no HTTPS context; URLs injected with @Secured get the port"
                    + " of the annotation instead of the HTTPS port {0}", String.valueOf(port));
            return null;
        }

        return (HTTPContext) httpsContextClass.getConstructor(String.class, int.class).newInstance(host, port);
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
