package ee.omnifish.arquillian.container.quarkus.it.multipledeployments;

import java.io.IOException;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Responds with the ID of the application's process. */
@WebServlet("/pid")
public class ProcessIdServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("text/plain");
        response.getWriter().print(ProcessHandle.current().pid());
    }
}
