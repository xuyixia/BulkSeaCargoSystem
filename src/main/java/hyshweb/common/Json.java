package hyshweb.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

public final class Json {
    private Json() {
    }

    public static void write(HttpServletResponse response, Object value) throws IOException {
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSON.toJSONString(value));
    }

    public static void ok(HttpServletResponse response, Object data) throws IOException {
        write(response, ApiResponse.ok(data));
    }

    public static void fail(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        write(response, ApiResponse.fail(code, message));
    }

    public static JSONObject body(HttpServletRequest request) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        if (sb.length() == 0) {
            return new JSONObject();
        }
        return JSON.parseObject(sb.toString());
    }
}
