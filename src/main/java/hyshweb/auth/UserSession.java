package hyshweb.auth;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

public class UserSession implements Serializable {
    public static final String SESSION_KEY = "sysUser";

    private final String username;
    private final String name;
    private final String userType;
    private final String sealCode;

    public UserSession(String username, String name, String userType, String sealCode) {
        this.username = username;
        this.name = name;
        this.userType = userType;
        this.sealCode = sealCode;
    }

    public static UserSession current(HttpServletRequest request) {
        Object value = request.getSession(false) == null ? null : request.getSession(false).getAttribute(SESSION_KEY);
        return value instanceof UserSession ? (UserSession) value : null;
    }

    public boolean isAdmin() {
        return "超级管理员".equals(userType) || "管理员".equals(userType);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("username", username);
        map.put("name", name);
        map.put("userType", userType);
        map.put("sealCode", sealCode);
        map.put("menus", isAdmin()
                ? List.of("inbound", "outbound", "masterdata", "users")
                : List.of("inbound", "outbound"));
        return map;
    }

    public String getUsername() {
        return username;
    }

    public String getName() {
        return name;
    }

    public String getUserType() {
        return userType;
    }

    public String getSealCode() {
        return sealCode;
    }
}
