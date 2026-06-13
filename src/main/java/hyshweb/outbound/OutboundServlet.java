package hyshweb.outbound;

import com.alibaba.fastjson.JSONObject;
import hyshweb.auth.UserSession;
import hyshweb.common.Json;
import hyshweb.common.Params;
import hyshweb.common.Servlets;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@WebServlet("/api/outbound/*")
public class OutboundServlet extends Servlets {
    private final OutboundService service = new OutboundService();

    @Override
    protected void route(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String path = path(request);
        UserSession user = UserSession.current(request);
        if ("/orders".equals(path)) {
            if ("GET".equalsIgnoreCase(request.getMethod())) {
                Json.ok(response, service.list(request));
            } else if ("POST".equalsIgnoreCase(request.getMethod())) {
                Json.ok(response, service.save(Json.body(request), user));
//        /
//            } else if ("PATCH".equalsIgnoreCase(request.getMethod())) {
//                service. (Json.body(request), user);
//                Json.ok(response, true);
//            } else if ("DELETE".equalsIgnoreCase(request.getMethod())) {
//                service.deleteDetail(Params.str(request, "detailUuid"), user);
//                Json.ok(response, true);
//            }
//         /   
            }else {
                Json.fail(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "方法不支持");
            }
            return;
        }
        if ("/inventory".equals(path) && "GET".equalsIgnoreCase(request.getMethod())) {
            Json.ok(response, service.stock(request));
            return;
        }
        if ("/details".equals(path)) {
            if ("PATCH".equalsIgnoreCase(request.getMethod())) {
                service.updateDetail(Json.body(request), user);
                Json.ok(response, true);
            } else if ("DELETE".equalsIgnoreCase(request.getMethod())) {
                service.deleteDetail(Params.str(request, "detailUuid"), user);
                Json.ok(response, true);
            } else {
                Json.fail(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "方法不支持");
            }
            return;
        }
        if ("/costs".equals(path) && "DELETE".equalsIgnoreCase(request.getMethod())) {
            service.deleteCost(Params.str(request, "costUuid"), user);
            Json.ok(response, true);
            return;
        }
        if ("/costs".equals(path) && "PATCH".equalsIgnoreCase(request.getMethod())) {
            service.updateCost(Params.str(request, "costUuid"), Json.body(request), user);
            Json.ok(response, true);
            return;
        }
        if ("/receivables".equals(path) && "POST".equalsIgnoreCase(request.getMethod())) {
            service.saveReceivable(Json.body(request), user);
            Json.ok(response, true);
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
                } else {
                    Json.fail(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "方法不支持");
                }
                return;
            }
            if (parts.length == 2 && "submit".equals(parts[1]) && "POST".equalsIgnoreCase(request.getMethod())) {
                service.submit(orderNo, user);
                Json.ok(response, true);
                return;
            }
            if (parts.length == 2 && "cancel".equals(parts[1]) && "POST".equalsIgnoreCase(request.getMethod())) {
                service.cancel(orderNo, user);
                Json.ok(response, true);
                return;
            }
            if (parts.length == 2 && "details".equals(parts[1])) {
                if ("GET".equalsIgnoreCase(request.getMethod())) {
                    Json.ok(response, service.details(orderNo));
                } else if ("POST".equalsIgnoreCase(request.getMethod())) {
                    JSONObject body = Json.body(request);
                    service.addDetails(orderNo, body.getJSONArray("items"), user);
                    Json.ok(response, true);
                } else {
                    Json.fail(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "方法不支持");
                }
                return;
            }
            if (parts.length == 2 && "costs".equals(parts[1])) {
                if ("GET".equalsIgnoreCase(request.getMethod())) {
                    Json.ok(response, service.costs(orderNo));
                } else if ("POST".equalsIgnoreCase(request.getMethod())) {
                    Json.ok(response, service.addCost(orderNo, Json.body(request), user));
                } else {
                    Json.fail(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "方法不支持");
                }
                return;
            }
            if (parts.length == 2 && "logs".equals(parts[1]) && "GET".equalsIgnoreCase(request.getMethod())) {
                Json.ok(response, service.logs(orderNo));
                return;
            }
            if (parts.length == 2 && "receivables".equals(parts[1]) && "GET".equalsIgnoreCase(request.getMethod())) {
                Json.ok(response, service.receivables(orderNo));
                return;
            }
            if (parts.length == 2 && "export-details".equals(parts[1])) {
                response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                response.setHeader("Content-Disposition", "attachment; filename=\"outbound-details.xlsx\"");
                response.getOutputStream().write(service.exportDetails(orderNo));
                return;
            }
            if (parts.length == 2 && "export-accounts".equals(parts[1])) {
                response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                response.setHeader("Content-Disposition", "attachment; filename=\"outbound-accounts.xlsx\"");
                response.getOutputStream().write(service.exportAccounts(orderNo));
                return;
            }
            if (parts.length == 2 && "nodes".equals(parts[1]) && "POST".equalsIgnoreCase(request.getMethod())) {
                service.sendNode(orderNo, Json.body(request), user);
                Json.ok(response, true);
                return;
            }
        }
        Json.fail(response, HttpServletResponse.SC_NOT_FOUND, "NOT_FOUND", "接口不存在");
    }
}
