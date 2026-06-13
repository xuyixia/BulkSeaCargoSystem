package hyshweb.common;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public final class Db {
    private static final HikariDataSource DATA_SOURCE = createDataSource();

    private Db() {
    }

    public interface TxWork<T> {
        T run(Connection conn) throws Exception;
    }

    public static Connection getConnection() throws SQLException {
        return DATA_SOURCE.getConnection();
    }

    public static <T> T tx(TxWork<T> work) throws Exception {
        try (Connection conn = getConnection()) {
            boolean oldAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                T result = work.run(conn);
                conn.commit();
                return result;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(oldAutoCommit);
            }
        }
    }

    public static List<Map<String, Object>> query(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rows(rs);
            }
        }
    }

    public static Map<String, Object> queryOne(Connection conn, String sql, Object... params) throws SQLException {
        List<Map<String, Object>> rows = query(conn, sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static int update(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    public static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    public static List<Map<String, Object>> rows(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int count = meta.getColumnCount();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= count; i++) {
                row.put(toCamel(meta.getColumnLabel(i)), rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }

    private static HikariDataSource createDataSource() {
        Properties props = loadProperties();
        String url = value("HYSH_DB_URL", props.getProperty("db.url"));
        String user = value("HYSH_DB_USER", props.getProperty("db.user"));
        String password = value("HYSH_DB_PASSWORD", props.getProperty("db.password"));
        String driver = value("HYSH_DB_DRIVER", props.getProperty("db.driver", "com.mysql.cj.jdbc.Driver"));
        int poolSize = Integer.parseInt(value("HYSH_DB_POOL_SIZE", props.getProperty("db.poolSize", "10")));

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(required(url, "HYSH_DB_URL or db.url"));
        config.setUsername(required(user, "HYSH_DB_USER or db.user"));
        config.setPassword(password == null ? "" : password);
        config.setMaximumPoolSize(poolSize);
        config.setDriverClassName(driver);
        config.setPoolName("hysh-web");
        return new HikariDataSource(config);
    }

    private static Properties loadProperties() {
        Properties props = new Properties();
        try (InputStream in = Db.class.getClassLoader().getResourceAsStream("db.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取 db.properties 失败", e);
        }
        return props;
    }

    private static String value(String envName, String fallback) {
        String env = System.getenv(envName);
        return env == null || env.isBlank() ? fallback : env;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少数据库配置：" + name);
        }
        return value;
    }

    private static String toCamel(String name) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char ch : name.toCharArray()) {
            if (ch == '_') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(ch));
                upper = false;
            } else {
                sb.append(Character.toLowerCase(ch));
            }
        }
        return sb.toString();
    }
}
