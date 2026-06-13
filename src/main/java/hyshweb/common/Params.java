package hyshweb.common;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import javax.servlet.http.HttpServletRequest;

import com.alibaba.fastjson.JSONObject;

public final class Params {
    private Params() {
    }

    public static String str(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        return value == null ? "" : value.trim();
    }

    public static String str(JSONObject body, String name) {
        String value = body.getString(name);
        return value == null ? "" : value.trim();
    }

    public static int intParam(HttpServletRequest request, String name, int defaultValue) {
        try {
            String value = str(request, name);
            return value.isEmpty() ? defaultValue : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static int intParam(JSONObject body, String name, int defaultValue) {
        Integer value = body.getInteger(name);
        return value == null ? defaultValue : value;
    }

    public static int page(HttpServletRequest request) {
        return Math.max(1, intParam(request, "page", intParam(request, "currentPage", 1)));
    }

    public static int pageSize(HttpServletRequest request) {
        return Math.min(100, Math.max(1, intParam(request, "pageSize", 20)));
    }

    public static LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
