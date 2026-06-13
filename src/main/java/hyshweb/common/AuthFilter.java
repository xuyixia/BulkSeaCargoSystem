package hyshweb.common;

import hyshweb.auth.UserSession;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@WebFilter("/api/*")
public class AuthFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;
        String path = req.getServletPath() + (req.getPathInfo() == null ? "" : req.getPathInfo());
        if ("/api/auth/login".equals(path)) {
            chain.doFilter(request, response);
            return;
        }
        UserSession user = UserSession.current(req);
        if (user == null) {
            Json.fail(resp, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "请先登录");
            return;
        }
        if (path.startsWith("/api/master/") && !user.isAdmin()) {
            Json.fail(resp, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "无权限访问资料管理");
            return;
        }
        chain.doFilter(request, response);
    }
}
