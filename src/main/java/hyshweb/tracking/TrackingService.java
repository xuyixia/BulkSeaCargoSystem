package hyshweb.tracking;

import hyshweb.auth.UserSession;
import hyshweb.common.Db;
import hyshweb.common.Page;
import hyshweb.common.Params;

import javax.servlet.http.HttpServletRequest;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TrackingService {
    public List<Map<String, Object>> sales(UserSession user) throws Exception {
        if (user.getSealCode() == null || user.getSealCode().isBlank()) {
            return List.of();
        }
        try (Connection conn = Db.getConnection()) {
            return Db.query(conn,
                    "SELECT customer_code, customer_en_name, customer_cn_name FROM customer_info " +
                            "WHERE status='启用' AND customer_type='Sale' AND (customer_code=? OR superior_code=?) ORDER BY customer_code",
                    user.getSealCode(), user.getSealCode());
        }
    }

    public Page orders(HttpServletRequest request, UserSession user) throws Exception {
        int page = Params.page(request);
        int pageSize = Params.pageSize(request);
        String trackNo = Params.str(request, "trackNo");
        String inOrderNo = Params.str(request, "inOrderNo");
        String saleCode = Params.str(request, "saleCode");
        LocalDate end = Params.date(Params.str(request, "endDate"));
        LocalDate start = Params.date(Params.str(request, "startDate"));
        if (start == null || end == null) {
            throw new IllegalArgumentException("日期范围不能为空");
        }
        if (ChronoUnit.DAYS.between(start, end) > 30) {
            throw new IllegalArgumentException("查询日期跨度不能超过30天");
        }
        if (saleCode.isEmpty()) {
            throw new IllegalArgumentException("销售不能为空");
        }
        ensureSaleAllowed(user, saleCode);

        List<Object> params = new ArrayList<>();
        String where = buildWhere(params, trackNo, inOrderNo, saleCode, start, end);
        try (Connection conn = Db.getConnection()) {
            int total = count(conn, where, params);
            List<Object> queryParams = new ArrayList<>(params);
            queryParams.add(pageSize);
            queryParams.add((page - 1) * pageSize);
            List<Map<String, Object>> rows = Db.query(conn,
                    "SELECT io.track_no, io.in_order_no, io.in_date, io.customer, io.sales, " +
                            "(SELECT GROUP_CONCAT(DISTINCT iod.product_name SEPARATOR '/') FROM in_order_detail iod WHERE iod.in_order_no=io.in_order_no) AS product_names, " +
                            "(SELECT COALESCE(SUM(iod.package_qty),0) FROM in_order_detail iod WHERE iod.in_order_no=io.in_order_no) AS package_qty, " +
                            "(SELECT COALESCE(SUM(iod.weight),0) FROM in_order_detail iod WHERE iod.in_order_no=io.in_order_no) AS weight, " +
                            "(SELECT COALESCE(SUM(iod.volume),0) FROM in_order_detail iod WHERE iod.in_order_no=io.in_order_no) AS volume, " +
                            "io.send_type, io.send_no " +
                            "FROM in_order io WHERE 1=1 " + where + " ORDER BY io.create_time DESC LIMIT ? OFFSET ?",
                    queryParams.toArray());
            return new Page(rows, page, pageSize, total, null, null);
        }
    }

    public Map<String, Object> detail(String trackNo, UserSession user) throws Exception {
        try (Connection conn = Db.getConnection()) {
            Map<String, Object> order = Db.queryOne(conn,
                    "SELECT in_order_uuid, in_order_no, track_no, in_date, customer, sales, send_type, send_no FROM in_order WHERE track_no=?",
                    trackNo);
            if (order == null) {
                return null;
            }
            String saleCode = saleCodeForName(conn, String.valueOf(order.get("sales")));
            ensureSaleAllowed(user, saleCode);
            Map<String, Object> result = new LinkedHashMap<>(order);
            result.put("details", Db.query(conn,
                    "SELECT product_name, product_en_name, marks, package_qty, package_type, weight, volume FROM in_order_detail WHERE in_order_no=?",
                    order.get("inOrderNo")));
            result.put("nodes", Db.query(conn,
                    "SELECT operate_desc, operate_time, atd, eta, ata FROM in_order_log WHERE in_order_uuid=? AND operate_type='send' " +
                            "UNION ALL " +
                            "SELECT DISTINCT ool.operate_desc, ool.operate_time, oo.atd_time AS atd, oo.eta_time AS eta, oo.ata_time AS ata " +
                            "FROM out_order_log ool JOIN out_order_detail ood ON ood.out_order_uuid=ool.out_order_uuid JOIN in_order_detail iod ON iod.in_order_detail_uuid=ood.in_order_detail_uuid JOIN out_order oo ON oo.out_order_uuid=ool.out_order_uuid " +
                            "WHERE iod.in_order_uuid=? AND ool.operate_type='send' ORDER BY operate_time",
                    order.get("inOrderUuid"), order.get("inOrderUuid")));
            return result;
        }
    }

    private void ensureSaleAllowed(UserSession user, String saleCode) throws Exception {
        if (saleCode == null || saleCode.isBlank()) {
            throw new IllegalArgumentException("销售不能为空");
        }
        boolean allowed = sales(user).stream().anyMatch(row -> saleCode.equals(String.valueOf(row.get("customerCode"))));
        if (!allowed) {
            throw new IllegalArgumentException("无权查询该销售数据");
        }
    }

    private String saleCodeForName(Connection conn, String salesName) throws Exception {
        Map<String, Object> row = Db.queryOne(conn,
                "SELECT customer_code FROM customer_info WHERE customer_type='Sale' AND status='启用' AND customer_cn_name=? LIMIT 1",
                salesName);
        return row == null ? "" : String.valueOf(row.get("customerCode"));
    }

    private String buildWhere(List<Object> params, String trackNo, String inOrderNo, String saleCode, LocalDate start, LocalDate end) {
        StringBuilder where = new StringBuilder();
        if (!trackNo.isEmpty()) {
            where.append(" AND io.track_no LIKE ? ");
            params.add("%" + trackNo + "%");
        }
        if (!inOrderNo.isEmpty()) {
            where.append(" AND io.in_order_no LIKE ? ");
            params.add("%" + inOrderNo + "%");
        }
        where.append(" AND io.sales=(SELECT customer_cn_name FROM customer_info WHERE customer_code=? LIMIT 1) ");
        params.add(saleCode);
        where.append(" AND io.in_date BETWEEN ? AND ? ");
        params.add(start.toString());
        params.add(end.toString());
        return where.toString();
    }

    private int count(Connection conn, String where, List<Object> params) throws Exception {
        Map<String, Object> row = Db.queryOne(conn, "SELECT COUNT(*) AS total FROM in_order io WHERE 1=1 " + where, params.toArray());
        return ((Number) row.get("total")).intValue();
    }
}
