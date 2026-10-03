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

import java.util.logging.LogManager;

/**
 * Starts a Quarkus fast-jar application with the JDK's own {@link LogManager}.
 *
 * <p>
 * Quarkus' entry point sets {@code java.util.logging.manager} to JBoss LogManager, but the JDK reads that property only
 * when the LogManager is first used. Using it here first keeps the JDK LogManager. Quarkus then logs that the
 * LogManager was accessed too early, and runs with the JDK one.
 *
 * <p>
 * Runs in the application JVM, with only {@code quarkus-run.jar} and this class on the class path. It must not use
 * anything else from this library.
 */
public final class JdkLogManagerLauncher {

    private static final String QUARKUS_ENTRY_POINT = "io.quarkus.bootstrap.runner.QuarkusEntryPoint";

    private JdkLogManagerLauncher() {
    }

    public static void main(String[] args) throws Throwable {
        LogManager.getLogManager();

        Class.forName(QUARKUS_ENTRY_POINT)
                .getMethod("main", String[].class)
                .invoke(null, (Object) args);
    }
}
