package ee.omnifish.arquillian.container.quarkus.it.multipledeployments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * Checks that the processes of both deployments of {@link MultipleDeploymentsTest} were stopped when they were
 * undeployed. Before the container tracked each deployment, the first deployment's process kept running.
 */
@Order(2)
class ProcessesStoppedTest {

    @Test
    void bothProcessesStopped() throws Exception {
        List<String> processIds = Files.readAllLines(MultipleDeploymentsTest.PROCESS_IDS);
        assertEquals(2, processIds.size(), "process IDs: " + processIds);

        for (String processId : processIds) {
            assertFalse(ProcessHandle.of(Long.parseLong(processId)).map(ProcessHandle::isAlive).orElse(false),
                    "process " + processId + " is still running");
        }
    }
}
