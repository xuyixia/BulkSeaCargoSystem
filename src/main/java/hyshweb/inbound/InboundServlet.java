package hyshweb.inbound;

import hyshweb.auth.UserSession;
import hyshweb.common.Json;
import hyshweb.common.Params;
import hyshweb.common.Servlets;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@WebServlet("/api/inbound/*")
public class InboundServlet extends Servlets {
    private final InboundService service = new InboundService();

    @Override
    protected void route(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String path = path(request);
        if ("/orders".equals(path)) {
            if ("GET".equalsIgnoreCase(request.getMethod())) {
                Json.ok(response, service.list(request));
            } else if ("POST".equalsIgnoreCase(request.getMethod())) {
                Json.ok(response, service.save(Json.body(request), UserSession.current(request)));
            }
            return;
        }
        if (path.startsWith("/orders/")) {
            String rest = path.substring("/orders/".length());
            String[] parts = rest.split("/");
            String orderNo = parts[0];
            if (parts.length == 1) {
                if ("GET".equalsIgnoreCase(request.getMethod())) {
                    Json.ok(response, service.get(orderNo));
                } else if ("DELETE".equalsIgnoreCase(request.getMethod())) {
                    service.delete(orderNo);
                    Json.ok(response, true);
                }
                return;
            }
            if (parts.length == 2 && "submit".equals(parts[1])) {
                service.submit(orderNo, UserSession.current(request));
                Json.ok(response, true);
                return;
            }
            if (parts.length == 2 && "cancel".equals(parts[1])) {
                service.cancel(orderNo, UserSession.current(request));
                Json.ok(response, true);
                return;
            }
            if (parts.length == 2 && "details".equals(parts[1])) {
                Json.ok(response, service.detailList(orderNo));
                return;
            }
            if (parts.length == 2 && "logs".equals(parts[1])) {
                Json.ok(response, service.logListByOrderNo(orderNo));
                return;
            }
            if (parts.length == 2 && "outbounds".equals(parts[1])) {
                Json.ok(response, service.relatedOutbounds(orderNo));
                return;
            }
            if (parts.length == 2 && "nodes".equals(parts[1])) {
                Json.ok(response, service.logisticsNodes(orderNo));
                return;
            }
            if (parts.length == 2 && "delivery".equals(parts[1])) {
                service.finishDelivery(orderNo, Json.body(request), UserSession.current(request));
                Json.ok(response, true);
                return;
            }
        }
        if ("/details".equals(path) && "DELETE".equalsIgnoreCase(request.getMethod())) {
            service.deleteDetail(Params.str(request, "detailUuid"), UserSession.current(request));
            Json.ok(response, true);
            return;
        }
        if ("/inventory/adjust".equals(path) && "POST".equalsIgnoreCase(request.getMethod())) {
            service.adjustInventory(Json.body(request), UserSession.current(request));
            Json.ok(response, true);
            return;
        }
        if ("/collection/finish".equals(path) && "POST".equalsIgnoreCase(request.getMethod())) {
            service.finishCollection(Json.body(request), UserSession.current(request));
            Json.ok(response, true);
            return;
        }
        if ("/template".equals(path)) {
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=\"inbound-template.xlsx\"");
            response.getOutputStream().write(service.template());
            return;
        }
        if ("/import".equals(path)) {
            Json.fail(response, HttpServletResponse.SC_NOT_IMPLEMENTED, "NOT_OPEN", "导入功能暂未开放");
            return;
        }
        Json.fail(response, HttpServletResponse.SC_NOT_FOUND, "NOT_FOUND", "接口不存在");
    }
}
