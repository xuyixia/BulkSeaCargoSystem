package hyshweb.auth;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.alibaba.fastjson.JSONObject;

import hyshweb.common.Json;
import hyshweb.common.Params;
import hyshweb.common.Servlets;

@WebServlet("/api/auth/*")
public class AuthServlet extends Servlets {
    private final AuthService service = new AuthService();

    @Override
    protected void route(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String path = path(request);
        if ("/login".equals(path) && "POST".equalsIgnoreCase(request.getMethod())) {
            JSONObject body = Json.body(request);
            String username = Params.str(body, "username");
            String password = Params.str(body, "password");
            if (username.isEmpty() || password.isEmpty()) {
                throw new IllegalArgumentException("账号和密码不能为空");
            }
            UserSession user = service.login(username, password);
            if (user == null) {
                Json.fail(response, HttpServletResponse.SC_UNAUTHORIZED, "LOGIN_FAILED", "账号或密码错误");
                return;
            }
            request.getSession(true).setAttribute(UserSession.SESSION_KEY, user);
            Json.ok(response, user.toMap());
            return;
        }
        if ("/logout".equals(path)) {
            if (request.getSession(false) != null) {
                request.getSession(false).invalidate();
            }
            Json.ok(response, true);
            return;
        }
        if ("/me".equals(path)) {
            UserSession user = UserSession.current(request);
            Json.ok(response, user == null ? null : user.toMap());
            return;
        }
        Json.fail(response, HttpServletResponse.SC_NOT_FOUND, "NOT_FOUND", "接口不存在");
    }
}
