package hyshweb.attachment;

import hyshweb.auth.UserSession;
import hyshweb.common.Json;
import hyshweb.common.Params;
import hyshweb.common.Servlets;

import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.util.Map;

@MultipartConfig(maxFileSize = 2L * 1024L * 1024L)
@WebServlet("/api/attachments/*")
public class AttachmentServlet extends Servlets {
    private final AttachmentService service = new AttachmentService();

    @Override
    protected void route(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String path = path(request);
        if ("/".equals(path)) {
            if ("GET".equalsIgnoreCase(request.getMethod())) {
                Json.ok(response, service.list(Params.str(request, "ownerType"), Params.str(request, "ownerId")));
                return;
            }
            if ("POST".equalsIgnoreCase(request.getMethod())) {
                Part file = request.getPart("file");
                boolean replace = Boolean.parseBoolean(Params.str(request, "replace"));
                service.upload(Params.str(request, "ownerType"), Params.str(request, "ownerId"), file, UserSession.current(request), replace);
                Json.ok(response, true);
                return;
            }
        }
        if (path.startsWith("/") && path.endsWith("/content")) {
            String uuid = path.substring(1, path.length() - "/content".length());
            Map<String, Object> meta = service.meta(uuid);
            if (meta == null) {
                Json.fail(response, HttpServletResponse.SC_NOT_FOUND, "NOT_FOUND", "附件不存在");
                return;
            }
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition", "inline; filename=\"" + meta.get("attachmentName") + "\"");
            response.getOutputStream().write((byte[]) meta.get("fileContent"));
            return;
        }
        if (path.startsWith("/") && "DELETE".equalsIgnoreCase(request.getMethod())) {
            service.delete(path.substring(1));
            Json.ok(response, true);
            return;
        }
        Json.fail(response, HttpServletResponse.SC_NOT_FOUND, "NOT_FOUND", "接口不存在");
    }
}
