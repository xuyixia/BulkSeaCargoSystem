package hyshweb.masterdata;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.alibaba.fastjson.JSONObject;

import hyshweb.auth.UserSession;
import hyshweb.common.Json;
import hyshweb.common.Params;
import hyshweb.common.Servlets;

@WebServlet("/api/master/*")
public class MasterDataServlet extends Servlets {
    private final MasterDataService service = new MasterDataService();

    @Override
    protected void route(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String path = path(request);
        UserSession user = UserSession.current(request);
        if (path.startsWith("/customers")) {
            if ("GET".equalsIgnoreCase(request.getMethod())) {
                Json.ok(response, service.customers(request));
            } else if ("POST".equalsIgnoreCase(request.getMethod())) {
                service.saveCustomer(Json.body(request), user);
                Json.ok(response, true);
            } else if ("DELETE".equalsIgnoreCase(request.getMethod())) {
                service.disableCustomer(Params.str(request, "code"));
                Json.ok(response, true);
            } else if ("PATCH".equalsIgnoreCase(request.getMethod())) {
                JSONObject body = Json.body(request);
                service.setCustomerStatus(Params.str(body, "code"), Params.str(body, "status"));
                Json.ok(response, true);
            } else {
                Json.fail(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "方法不支持");
            }
            return;
        }
        if (path.startsWith("/dict-types")) {
            if ("GET".equalsIgnoreCase(request.getMethod())) {
                Json.ok(response, service.dictTypes());
            } else {
                Json.fail(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "方法不支持");
            }
            return;
        }
        if (path.startsWith("/dicts/enabled")) {
            if ("GET".equalsIgnoreCase(request.getMethod())) {
                Json.ok(response, service.enabledDicts(Params.str(request, "type")));
            } else {
                Json.fail(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "方法不支持");
            }
            return;
        }
        if (path.startsWith("/dicts")) {
            if ("GET".equalsIgnoreCase(request.getMethod())) {
                Json.ok(response, service.dicts(request));
            } else if ("POST".equalsIgnoreCase(request.getMethod())) {
                service.saveDict(Json.body(request));
                Json.ok(response, true);
            } else if ("PATCH".equalsIgnoreCase(request.getMethod())) {
                JSONObject body = Json.body(request);
                service.setDictStatus(Params.str(body, "dictType"), Params.str(body, "dictCode"), Params.str(body, "status"));
                Json.ok(response, true);
            } else if ("DELETE".equalsIgnoreCase(request.getMethod())) {
                service.deleteDict(Params.str(request, "type"), Params.str(request, "code"));
                Json.ok(response, true);
            } else {
                Json.fail(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "方法不支持");
            }
            return;
        }
        if (path.startsWith("/users")) {
            if ("GET".equalsIgnoreCase(request.getMethod())) {
                Json.ok(response, service.users(request));
            } else if ("POST".equalsIgnoreCase(request.getMethod())) {
                service.saveUser(Json.body(request), user);
                Json.ok(response, true);
            } else if ("PATCH".equalsIgnoreCase(request.getMethod())) {
                JSONObject body = Json.body(request);
                service.setUserStatus(Params.str(body, "username"), Params.str(body, "status"));
                Json.ok(response, true);
            } else {
                Json.fail(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "方法不支持");
            }
            return;
        }
        Json.fail(response, HttpServletResponse.SC_NOT_FOUND, "NOT_FOUND", "接口不存在");
    }
}
