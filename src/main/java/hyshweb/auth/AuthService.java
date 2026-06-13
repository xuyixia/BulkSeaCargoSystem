package hyshweb.auth;

import hyshweb.common.Db;
import hyshweb.common.Passwords;

import java.sql.Connection;
import java.util.Map;

public class AuthService {
    public UserSession login(String username, String password) throws Exception {
        try (Connection conn = Db.getConnection()) {
            Map<String, Object> row = Db.queryOne(conn,
                    "SELECT username, password, name, usertype, seal_code FROM sys_user WHERE username=? AND status='启用'",
                    username);
            if (row == null) {
                return null;
            }
            String stored = value(row.get("password"));
            if (!Passwords.verify(password, stored)) {
                return null;
            }
            return new UserSession(
                    value(row.get("username")),
                    value(row.get("name")),
                    value(row.get("usertype")),
                    value(row.get("sealCode"))
            );
        }
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
