package hyshweb.tracking;

import hyshweb.auth.UserSession;
import hyshweb.common.Json;
import hyshweb.common.Params;
import hyshweb.common.Servlets;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@WebServlet("/api/tracking/*")
public class TrackingServlet extends Servlets {
    private final TrackingService service = new TrackingService();

    @Override
    protected void route(HttpServletRequest request, HttpServletResponse response) throws Exception {
        UserSession user = UserSession.current(request);
        String path = path(request);
        if ("/sales".equals(path)) {
            Json.ok(response, service.sales(user));
            return;
        }
        if ("/orders".equals(path)) {
            Json.ok(response, service.orders(request, user));
            return;
        }
        if (path.startsWith("/orders/")) {
            Json.ok(response, service.detail(path.substring("/orders/".length()), user));
            return;
        }
        Json.fail(response, HttpServletResponse.SC_NOT_FOUND, "NOT_FOUND", "接口不存在");
    }
}
