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
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.jboss.arquillian.container.spi.ConfigurationException;
import org.jboss.arquillian.container.spi.client.container.ContainerConfiguration;

/**
 * Configuration of the {@link QuarkusManagedDeployableContainer}.
 *
 * <p>
 * Each property can be set in {@code arquillian.xml}, or as a system property prefixed with
 * {@value #SYSTEM_PROPERTY_PREFIX} (e.g. {@code arquillian.quarkus.dependencies}), which is used as the default.
 */
public class QuarkusManagedContainerConfiguration implements ContainerConfiguration {

    static final String SYSTEM_PROPERTY_PREFIX = "arquillian.quarkus.";

    /**
     * Extensions and libraries the application is built with, as {@code groupId:artifactId[:version]}, separated by
     * commas or whitespace. The version may be left out for artifacts managed by the Quarkus BOM.
     */
    private String dependencies = property("dependencies", "io.quarkus:quarkus-undertow");

    /** Version of the Quarkus BOM that manages dependency versions; defaults to the Quarkus bootstrap version. */
    private String quarkusVersion = property("quarkusVersion", null);

    /**
     * The HTTP port of the application, or 0 (the default) for a free port per deployment, which lets test runs on
     * the same machine run in parallel.
     */
    private int httpPort = Integer.parseInt(property("httpPort", "0"));

    /** Seconds to wait for the application to accept HTTP connections. */
    private int startupTimeoutInSeconds = Integer.parseInt(property("startupTimeoutInSeconds", "60"));

    /** Extra arguments for the application JVM, separated by whitespace. */
    private String javaVmArguments = property("javaVmArguments", "");

    /** System properties for the application JVM, one {@code key=value} per line. */
    private String systemProperties = property("systemProperties", "");

    /** Quarkus build time configuration, one {@code key=value} per line. */
    private String buildProperties = property("buildProperties", "");

    /**
     * Deploy a web archive at {@code /<archive name without .war>}, as Jakarta EE servers do, instead of at
     * {@code /}.
     */
    private boolean contextRootFromArchiveName = Boolean.parseBoolean(property("contextRootFromArchiveName", "true"));

    /**
     * Start the application with the JDK's own LogManager instead of JBoss LogManager, for code that relies on JDK
     * LogManager behavior (e.g. {@code LogManager.getLogger(name)} returning null for unknown loggers).
     */
    private boolean useJdkLogManager = Boolean.parseBoolean(property("useJdkLogManager", "false"));

    /**
     * Build each deployment into a native executable instead of a fast-jar, and run that. Needs GraalVM or Mandrel:
     * {@code GRAALVM_HOME}, or {@code quarkus.native.graalvm-home} in the build properties. Also see the Quarkus
     * {@code quarkus.native.*} build properties, e.g. for a container build.
     * <p>
     * The system properties are given to the native image build as well: Quarkus deploys servlets while the image is
     * built, so servlet container initializers and context listeners run there.
     */
    private boolean nativeImage = Boolean.parseBoolean(property("nativeImage", "false"));

    /** Keep the build and work directory of each deployment after undeploying, for troubleshooting. */
    private boolean keepWorkDirectory = Boolean.parseBoolean(property("keepWorkDirectory", "false"));

    @Override
    public void validate() throws ConfigurationException {
        if (getDependencyList().isEmpty()) {
            throw new ConfigurationException("At least one dependency is required, e.g. io.quarkus:quarkus-undertow");
        }
        if (httpPort < 0) {
            throw new ConfigurationException("httpPort must be 0 (a free port) or positive, but was " + httpPort);
        }
    }

    List<String> getDependencyList() {
        List<String> list = new ArrayList<>();
        for (String dependency : dependencies.split("[,\\s]+")) {
            if (!dependency.isBlank()) {
                list.add(dependency.trim());
            }
        }
        return list;
    }

    List<String> getJavaVmArgumentList() {
        List<String> list = new ArrayList<>();
        for (String argument : javaVmArguments.split("\\s+")) {
            if (!argument.isBlank()) {
                list.add(argument);
            }
        }
        return list;
    }

    Properties getSystemPropertyMap() {
        return toProperties(systemProperties);
    }

    Properties getBuildPropertyMap() {
        return toProperties(buildProperties);
    }

    private static Properties toProperties(String lines) {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(lines));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties;
    }

    private static String property(String name, String defaultValue) {
        return System.getProperty(SYSTEM_PROPERTY_PREFIX + name, defaultValue);
    }

    public String getDependencies() {
        return dependencies;
    }

    public void setDependencies(String dependencies) {
        this.dependencies = dependencies;
    }

    public String getQuarkusVersion() {
        return quarkusVersion;
    }

    public void setQuarkusVersion(String quarkusVersion) {
        this.quarkusVersion = quarkusVersion;
    }

    public int getHttpPort() {
        return httpPort;
    }

    public void setHttpPort(int httpPort) {
        this.httpPort = httpPort;
    }

    public int getStartupTimeoutInSeconds() {
        return startupTimeoutInSeconds;
    }

    public void setStartupTimeoutInSeconds(int startupTimeoutInSeconds) {
        this.startupTimeoutInSeconds = startupTimeoutInSeconds;
    }

    public String getJavaVmArguments() {
        return javaVmArguments;
    }

    public void setJavaVmArguments(String javaVmArguments) {
        this.javaVmArguments = javaVmArguments;
    }

    public String getSystemProperties() {
        return systemProperties;
    }

    public void setSystemProperties(String systemProperties) {
        this.systemProperties = systemProperties;
    }

    public String getBuildProperties() {
        return buildProperties;
    }

    public void setBuildProperties(String buildProperties) {
        this.buildProperties = buildProperties;
    }

    public boolean isContextRootFromArchiveName() {
        return contextRootFromArchiveName;
    }

    public void setContextRootFromArchiveName(boolean contextRootFromArchiveName) {
        this.contextRootFromArchiveName = contextRootFromArchiveName;
    }

    public boolean isUseJdkLogManager() {
        return useJdkLogManager;
    }

    public void setUseJdkLogManager(boolean useJdkLogManager) {
        this.useJdkLogManager = useJdkLogManager;
    }

    public boolean isNativeImage() {
        return nativeImage;
    }

    public void setNativeImage(boolean nativeImage) {
        this.nativeImage = nativeImage;
    }

    public boolean isKeepWorkDirectory() {
        return keepWorkDirectory;
    }

    public void setKeepWorkDirectory(boolean keepWorkDirectory) {
        this.keepWorkDirectory = keepWorkDirectory;
    }
}
