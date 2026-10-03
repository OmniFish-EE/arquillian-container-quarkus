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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.jboss.arquillian.container.spi.event.container.BeforeDeploy;
import org.jboss.arquillian.core.api.annotation.Observes;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.Node;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;

/**
 * Adds the Faces servlet with the default mappings to web archives that use Faces but don't map it themselves.
 *
 * <p>
 * Jakarta Faces requires an implementation to map the {@code FacesServlet} automatically to {@code /faces/*},
 * {@code *.jsf}, {@code *.faces} and {@code *.xhtml} when the application doesn't. The MyFaces Quarkus extension only
 * maps {@code *.xhtml}, unless {@code web.xml} declares the servlet, in which case it uses those mappings.
 *
 * <p>
 * Observes {@code BeforeDeploy} rather than being an {@code ApplicationArchiveProcessor}, because Arquillian only
 * applies those to testable deployments, while the archives here are often deployed with {@code testable = false}.
 *
 * <p>
 * Enabled with the system property {@value #ENABLED_PROPERTY}.
 */
public class FacesServletMappingArchiveProcessor {

    static final String ENABLED_PROPERTY = QuarkusManagedContainerConfiguration.SYSTEM_PROPERTY_PREFIX
            + "addFacesServletMappings";

    private static final String WEB_XML = "WEB-INF/web.xml";
    private static final String FACES_SERVLET_CLASS = "jakarta.faces.webapp.FacesServlet";

    private static final String FACES_SERVLET = """
                <servlet>
                    <servlet-name>Faces Servlet</servlet-name>
                    <servlet-class>jakarta.faces.webapp.FacesServlet</servlet-class>
                    <load-on-startup>1</load-on-startup>
                </servlet>
                <servlet-mapping>
                    <servlet-name>Faces Servlet</servlet-name>
                    <url-pattern>/faces/*</url-pattern>
                    <url-pattern>*.jsf</url-pattern>
                    <url-pattern>*.faces</url-pattern>
                    <url-pattern>*.xhtml</url-pattern>
                </servlet-mapping>
            """;

    private static final String EMPTY_WEB_XML = """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">
            </web-app>
            """;

    public void process(@Observes BeforeDeploy event) {
        if (!event.getDeployment().isArchiveDeployment()) {
            return;
        }

        Archive<?> archive = event.getDeployment().getArchive();
        if (!Boolean.getBoolean(ENABLED_PROPERTY) || !(archive instanceof WebArchive) || !usesFaces(archive)) {
            return;
        }

        String webXml = archive.contains(WEB_XML) ? read(archive.get(WEB_XML)) : EMPTY_WEB_XML;
        if (webXml.contains(FACES_SERVLET_CLASS)) {
            // Mapped by the application itself
            return;
        }

        int end = webXml.lastIndexOf("</web-app>");
        if (end < 0) {
            return;
        }

        archive.delete(WEB_XML);
        archive.add(new StringAsset(webXml.substring(0, end) + FACES_SERVLET + webXml.substring(end)), WEB_XML);
    }

    private static boolean usesFaces(Archive<?> archive) {
        return archive.contains("WEB-INF/faces-config.xml")
                || archive.getContent(path -> path.get().endsWith(".xhtml")).size() > 0;
    }

    private static String read(Node node) {
        try (InputStream in = node.getAsset().openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
