package hyshweb.masterdata;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import com.alibaba.fastjson.JSONObject;

import hyshweb.auth.UserSession;
import hyshweb.common.Db;
import hyshweb.common.Page;
import hyshweb.common.Params;

public class MasterDataService {
    public Page customers(HttpServletRequest request) throws Exception {
        int page = Params.page(request);
        int pageSize = Params.pageSize(request);
        String code = Params.str(request, "code");
        String cnName = Params.str(request, "cnName");
        String enName = Params.str(request, "enName");
        String type = Params.str(request, "type");
        List<Object> params = new ArrayList<>();
        String where = customerWhere(params, code, cnName, enName, type);
        try (Connection conn = Db.getConnection()) {
            int total = count(conn, "customer_info", where, params);
            List<Object> queryParams = new ArrayList<>(params);
            queryParams.add(pageSize);
            queryParams.add((page - 1) * pageSize);
            List<Map<String, Object>> rows = Db.query(conn,
                    "SELECT id, customer_code, customer_en_name, customer_cn_name, customer_type, status, creator, create_time, superior_code " +
                            "FROM customer_info WHERE 1=1 " + where + " ORDER BY create_time DESC LIMIT ? OFFSET ?",
                    queryParams.toArray());
            return new Page(rows, page, pageSize, total, null, null);
        }
    }

    public void saveCustomer(JSONObject body, UserSession user) throws Exception {
        String code = required(body, "customerCode", "代码不能为空");
        String enName = Params.str(body, "customerEnName");
        String cnName = required(body, "customerCnName", "中文名不能为空");
        String type = required(body, "customerType", "类型不能为空");
        String superiorCode = Params.str(body, "superiorCode");
        try (Connection conn = Db.getConnection()) {
            Map<String, Object> exists = Db.queryOne(conn, "SELECT id FROM customer_info WHERE customer_code=?", code);
            if (exists == null) {
                Db.update(conn,
                        "INSERT INTO customer_info (customer_code, customer_en_name, customer_cn_name, customer_type, status, creator, create_time, superior_code) " +
                                "VALUES (?, ?, ?, ?, '启用', ?, NOW(), ?)",
                        code, enName, cnName, type, user.getName(), superiorCode);
            } else {
                Db.update(conn,
                        "UPDATE customer_info SET customer_en_name=?, customer_cn_name=?, customer_type=?, superior_code=? WHERE customer_code=?",
                        enName, cnName, type, superiorCode, code);
            }
        }
    }

    public void disableCustomer(String code) throws Exception {
        setCustomerStatus(code, "禁用");
    }

    public void setCustomerStatus(String code, String status) throws Exception {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("客户代码不能为空");
        }
        try (Connection conn = Db.getConnection()) {
            Db.update(conn, "UPDATE customer_info SET status=? WHERE customer_code=?", status, code);
        }
    }

    public List<Map<String, Object>> dictTypes() throws Exception {
        try (Connection conn = Db.getConnection()) {
            return Db.query(conn,
                    "SELECT DISTINCT dict_type FROM sys_dict ORDER BY dict_type");
        }
    }

    public Page dicts(HttpServletRequest request) throws Exception {
        String type = Params.str(request, "type");
        int page = Params.page(request);
        int pageSize = Params.pageSize(request);
        try (Connection conn = Db.getConnection()) {
            int total = count(conn, "sys_dict", "AND dict_type=?", List.of(type));
            List<Object> queryParams = new ArrayList<>();
            queryParams.add(type);
            queryParams.add(pageSize);
            queryParams.add((page - 1) * pageSize);
            List<Map<String, Object>> rows = Db.query(conn,
                    "SELECT dict_type, dict_code, dict_name, sort_order, status, remark FROM sys_dict WHERE dict_type=? ORDER BY sort_order LIMIT ? OFFSET ?",
                    queryParams.toArray());
            return new Page(rows, page, pageSize, total, null, null);
        }
    }

    public List<Map<String, Object>> enabledDicts(String type) throws Exception {
        try (Connection conn = Db.getConnection()) {
            return Db.query(conn,
                    "SELECT dict_type, dict_code, dict_name, sort_order, status, remark FROM sys_dict WHERE dict_type=? AND status='1' ORDER BY sort_order",
                    type);
        }
    }

    public void saveDict(JSONObject body) throws Exception {
        String type = required(body, "dictType", "字典类型不能为空");
        String code = required(body, "dictCode", "字典代码不能为空");
        String name = required(body, "dictName", "字典名称不能为空");
        int sortOrder = body.getInteger("sortOrder") == null ? 0 : body.getInteger("sortOrder");
        String status = Params.str(body, "status").isEmpty() ? "1" : Params.str(body, "status");
        String remark = Params.str(body, "remark");
        try (Connection conn = Db.getConnection()) {
            Map<String, Object> exists = Db.queryOne(conn, "SELECT dict_code FROM sys_dict WHERE dict_type=? AND dict_code=?", type, code);
            if (exists == null) {
                Db.update(conn,
                        "INSERT INTO sys_dict (dict_type, dict_code, dict_name, sort_order, status, remark) VALUES (?, ?, ?, ?, ?, ?)",
                        type, code, name, sortOrder, status, remark);
            } else {
                Db.update(conn,
                        "UPDATE sys_dict SET dict_name=?, sort_order=?, status=?, remark=? WHERE dict_type=? AND dict_code=?",
                        name, sortOrder, status, remark, type, code);
            }
        }
    }

    public void deleteDict(String type, String code) throws Exception {
        if (type.isBlank() || code.isBlank()) {
            throw new IllegalArgumentException("字典类型和代码不能为空");
        }
        try (Connection conn = Db.getConnection()) {
            Db.update(conn, "DELETE FROM sys_dict WHERE dict_type=? AND dict_code=?", type, code);
        }
    }

    public void setDictStatus(String type, String code, String status) throws Exception {
        if (type.isBlank() || code.isBlank()) {
            throw new IllegalArgumentException("字典类型和代码不能为空");
        }
        try (Connection conn = Db.getConnection()) {
            Db.update(conn, "UPDATE sys_dict SET status=? WHERE dict_type=? AND dict_code=?", status, type, code);
        }
    }

    public Page users(HttpServletRequest request) throws Exception {
        int page = Params.page(request);
        int pageSize = Params.pageSize(request);
        String username = Params.str(request, "username");
        String name = Params.str(request, "name");
        String userType = Params.str(request, "userType");
        String status = Params.str(request, "status");
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" AND usertype != '超级管理员' ");
        if (!username.isEmpty()) {
            where.append(" AND username LIKE ? ");
            params.add("%" + username + "%");
        }
        if (!name.isEmpty()) {
            where.append(" AND name LIKE ? ");
            params.add("%" + name + "%");
        }
        if (!userType.isEmpty() && !"全部".equals(userType)) {
            where.append(" AND usertype=? ");
            params.add(userType);
        }
        if (!status.isEmpty() && !"全部".equals(status)) {
            where.append(" AND status=? ");
            params.add(status);
        }
        try (Connection conn = Db.getConnection()) {
            int total = count(conn, "sys_user", where.toString(), params);
            List<Object> queryParams = new ArrayList<>(params);
            queryParams.add(pageSize);
            queryParams.add((page - 1) * pageSize);
            List<Map<String, Object>> rows = Db.query(conn,
                    "SELECT id, username, name, tel, usertype, status, creator, create_time, seal_code " +
                            "FROM sys_user WHERE 1=1 " + where + " ORDER BY create_time DESC LIMIT ? OFFSET ?",
                    queryParams.toArray());
            return new Page(rows, page, pageSize, total, null, null);
        }
    }

    public void saveUser(JSONObject body, UserSession user) throws Exception {
        String username = required(body, "username", "账号不能为空");
        String name = required(body, "name", "姓名不能为空");
        String password = Params.str(body, "password");
        String tel = Params.str(body, "tel");
        String userType = Params.str(body, "userType").isEmpty() ? "员工" : Params.str(body, "userType");
        String status = Params.str(body, "status").isEmpty() ? "启用" : Params.str(body, "status");
        String sealCode = Params.str(body, "sealCode");
        try (Connection conn = Db.getConnection()) {
            Map<String, Object> exists = Db.queryOne(conn, "SELECT username FROM sys_user WHERE username=?", username);
            if (exists == null) {
                if (password.isEmpty()) {
                    throw new IllegalArgumentException("新增账号密码不能为空");
                }
                Db.update(conn,
                        "INSERT INTO sys_user (username, password, name, tel, usertype, status, creator, create_time, seal_code) VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?)",
                        username, password, name, tel, userType, status, user.getName(), sealCode);
            } else if (password.isEmpty()) {
                Db.update(conn,
                        "UPDATE sys_user SET name=?, tel=?, usertype=?, status=?, seal_code=? WHERE username=?",
                        name, tel, userType, status, sealCode, username);
            } else {
                Db.update(conn,
                        "UPDATE sys_user SET password=?, name=?, tel=?, usertype=?, status=?, seal_code=? WHERE username=?",
                        password, name, tel, userType, status, sealCode, username);
            }
        }
    }

    public void setUserStatus(String username, String status) throws Exception {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("账号不能为空");
        }
        try (Connection conn = Db.getConnection()) {
            Db.update(conn, "UPDATE sys_user SET status=? WHERE username=? AND usertype != '超级管理员'", status, username);
        }
    }

    private String customerWhere(List<Object> params, String code, String cnName, String enName, String type) {
        StringBuilder where = new StringBuilder();
        if (!code.isEmpty()) {
            where.append(" AND customer_code LIKE ? ");
            params.add("%" + code + "%");
        }
        if (!cnName.isEmpty()) {
            where.append(" AND customer_cn_name LIKE ? ");
            params.add("%" + cnName + "%");
        }
        if (!enName.isEmpty()) {
            where.append(" AND customer_en_name LIKE ? ");
            params.add("%" + enName + "%");
        }
        if (!type.isEmpty() && !"全部".equals(type)) {
            where.append(" AND customer_type=? ");
            params.add(type);
        }
        return where.toString();
    }

    private int count(Connection conn, String table, String where, List<Object> params) throws Exception {
        Map<String, Object> row = Db.queryOne(conn, "SELECT COUNT(*) AS total FROM " + table + " WHERE 1=1 " + where, params.toArray());
        return ((Number) row.get("total")).intValue();
    }

    private String required(JSONObject body, String key, String message) {
        String value = Params.str(body, key);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
