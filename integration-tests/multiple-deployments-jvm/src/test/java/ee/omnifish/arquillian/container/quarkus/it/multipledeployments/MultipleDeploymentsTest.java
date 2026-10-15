package ee.omnifish.arquillian.container.quarkus.it.multipledeployments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Two deployments in one test class run at the same time, each in its own process. {@link ProcessesStoppedTest} checks
 * afterwards that both processes were stopped when they were undeployed.
 */
@ExtendWith(ArquillianExtension.class)
@Order(1)
class MultipleDeploymentsTest {

    /** Where the IDs of the applications' processes are written, for {@link ProcessesStoppedTest} */
    static final Path PROCESS_IDS = Path.of("target", "multiple-deployments.pids");

    @ArquillianResource
    @OperateOnDeployment("first")
    private URL first;

    @ArquillianResource
    @OperateOnDeployment("second")
    private URL second;

    @Deployment(name = "first", testable = false)
    static WebArchive first() {
        return ShrinkWrap.create(WebArchive.class, "first.war").addClass(ProcessIdServlet.class);
    }

    @Deployment(name = "second", testable = false)
    static WebArchive second() {
        return ShrinkWrap.create(WebArchive.class, "second.war").addClass(ProcessIdServlet.class);
    }

    @Test
    void bothRunInTheirOwnProcess() throws Exception {
        long firstProcess = processId(first);
        long secondProcess = processId(second);

        assertNotEquals(firstProcess, secondProcess);
        assertTrue(ProcessHandle.of(firstProcess).map(ProcessHandle::isAlive).orElse(false));
        assertTrue(ProcessHandle.of(secondProcess).map(ProcessHandle::isAlive).orElse(false));

        Files.writeString(PROCESS_IDS, firstProcess + "\n" + secondProcess + "\n");
    }

    private static long processId(URL base) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(base.toURI().resolve("pid")).build(),
                BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        return Long.parseLong(response.body().trim());
    }
}
