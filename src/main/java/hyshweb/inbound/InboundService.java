package hyshweb.inbound;

import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import hyshweb.auth.UserSession;
import hyshweb.common.Db;
import hyshweb.common.Page;
import hyshweb.common.Params;

public class InboundService {
    public Page list(HttpServletRequest request) throws Exception {
        int page = Params.page(request);
        int pageSize = Params.pageSize(request);
        List<Object> params = new ArrayList<>();
        String where = where(request, params);
        try (Connection conn = Db.getConnection()) {
            int total = count(conn, where, params);
            List<Object> queryParams = new ArrayList<>(params);
            queryParams.add(pageSize);
            queryParams.add((page - 1) * pageSize);
            List<Map<String, Object>> rows = Db.query(conn,
                    "SELECT o.in_order_uuid, o.in_order_no, o.status, o.track_no, o.in_date, o.customer, o.sales, o.send_type, o.send_no, o.creator, " +
                            "(SELECT COALESCE(SUM(d.volume),0) FROM in_order_detail d WHERE d.in_order_no=o.in_order_no) AS total_volume, " +
                            "(SELECT COALESCE(SUM(d.weight),0) FROM in_order_detail d WHERE d.in_order_no=o.in_order_no) AS total_weight, " +
                            "(SELECT COALESCE(SUM(d.package_qty),0) FROM in_order_detail d WHERE d.in_order_no=o.in_order_no) AS total_package_qty, " +
                            "(SELECT COALESCE(SUM(d.stock_package_qty),0) FROM in_order_detail d WHERE d.in_order_no=o.in_order_no) AS total_stock_package_qty, " +
                            "(SELECT COUNT(*) FROM in_order_detail d WHERE d.in_order_no=o.in_order_no) AS detail_count, " +
                            "(SELECT COUNT(*) FROM in_order_attachment a JOIN in_order_detail d ON a.in_order_detail_uuid=d.in_order_detail_uuid WHERE d.in_order_no=o.in_order_no) AS attach_count, " +
                            "(SELECT l.operate_desc FROM in_order_log l WHERE l.in_order_uuid=o.in_order_uuid AND l.operate_type='send' ORDER BY l.operate_time DESC, l.create_time DESC LIMIT 1) AS logistics_node " +
                            "FROM in_order o WHERE 1=1 " + where + " ORDER BY o.create_time DESC LIMIT ? OFFSET ?",
                    queryParams.toArray());
            return new Page(rows, page, pageSize, total, summaryFromRows(rows), summary(conn, where, params));
        }
    }

    public Map<String, Object> get(String orderNo) throws Exception {
        try (Connection conn = Db.getConnection()) {
            Map<String, Object> order = Db.queryOne(conn, "SELECT * FROM in_order WHERE in_order_no=?", orderNo);
            if (order != null) {
                order.put("details", details(conn, orderNo));
                order.put("logs", logs(conn, String.valueOf(order.get("inOrderUuid"))));
            }
            return order;
        }
    }

    public String save(JSONObject body, UserSession user) throws Exception {
        return Db.tx(conn -> {
            String orderNo = Params.str(body, "inOrderNo");
            boolean creating = orderNo.isEmpty();
            if (creating) {
                orderNo = nextNo(conn, "in_order", "in_order_no", "I");
            }
            Map<String, Object> existing = creating ? null : requireOrder(conn, orderNo);
            String uuid = creating ? UUID.randomUUID().toString() : value(existing.get("inOrderUuid"));
            if (!creating) {
                ensureNoOutboundOccupied(conn, uuid, "已有出库占用，不能编辑入库单：");
            }
            if (creating) {
                Db.update(conn,
                        "INSERT INTO in_order (in_order_uuid, in_order_no, track_no, customer, sales, in_date, status, creator, create_time, operator, operate_time, send_type, send_no) " +
                                "VALUES (?, ?, ?, ?, ?, ?, '草稿', ?, NOW(), ?, NOW(), ?, ?)",
                        uuid, orderNo, Params.str(body, "trackNo"), Params.str(body, "customer"), Params.str(body, "sales"),
                        Params.str(body, "inDate"), user.getName(), user.getName(), Params.str(body, "sendType"), Params.str(body, "sendNo"));
            } else {
                Db.update(conn,
                        "UPDATE in_order SET track_no=?, customer=?, sales=?, in_date=?, status='草稿', operator=?, operate_time=NOW(), send_type=?, send_no=? WHERE in_order_no=?",
                        Params.str(body, "trackNo"), Params.str(body, "customer"), Params.str(body, "sales"), Params.str(body, "inDate"),
                        user.getName(), Params.str(body, "sendType"), Params.str(body, "sendNo"), orderNo);
            }
            JSONArray details = body.getJSONArray("details");
            if (details != null) {
                Db.update(conn, "DELETE FROM in_order_attachment WHERE in_order_detail_uuid IN (SELECT in_order_detail_uuid FROM in_order_detail WHERE in_order_no=? AND status='草稿')", orderNo);
                Db.update(conn, "DELETE FROM in_order_detail WHERE in_order_no=? AND status='草稿'", orderNo);
                for (int i = 0; i < details.size(); i++) {
                    JSONObject d = details.getJSONObject(i);
                    saveDetail(conn, uuid, orderNo, d, user);
                }
            }
            return orderNo;
        });
    }

    public void submit(String orderNo, UserSession user) throws Exception {
        Db.tx(conn -> {
            Map<String, Object> order = requireOrder(conn, orderNo);
            if (!"草稿".equals(value(order.get("status")))) {
                throw new IllegalArgumentException("仅草稿入库单可提交");
            }
            int details = ((Number) Db.queryOne(conn, "SELECT COUNT(*) AS total FROM in_order_detail WHERE in_order_no=?", orderNo).get("total")).intValue();
            if (details == 0) {
                throw new IllegalArgumentException("至少需要一条明细才能提交");
            }
            Db.update(conn, "UPDATE in_order SET status='有效', operator=?, operate_time=NOW() WHERE in_order_no=?", user.getName(), orderNo);
            log(conn, value(order.get("inOrderUuid")), null, "send", "入库", user.getName(), "");
            return null;
        });
    }

    public void cancel(String orderNo, UserSession user) throws Exception {
        Db.tx(conn -> {
            Map<String, Object> order = requireOrder(conn, orderNo);
            if (!"有效".equals(value(order.get("status")))) {
                throw new IllegalArgumentException("仅有效入库单可取消提交");
            }
            String uuid = value(order.get("inOrderUuid"));
            Map<String, Object> occupied = Db.queryOne(conn,
                    "SELECT product_name FROM in_order_detail WHERE in_order_uuid=? AND (stock_package_qty < package_qty OR stock_qty < qty) LIMIT 1",
                    uuid);
            if (occupied != null) {
                throw new IllegalArgumentException("已有出库占用，不能取消提交：" + value(occupied.get("productName")));
            }
            Db.update(conn, "UPDATE in_order SET status='草稿', operator=?, operate_time=NOW() WHERE in_order_no=?", user.getName(), orderNo);
            log(conn, uuid, null, "", "取消提交", user.getName(), "状态改为草稿");
            return null;
        });
    }

    public void delete(String orderNo) throws Exception {
        Db.tx(conn -> {
            Map<String, Object> order = requireOrder(conn, orderNo);
            if (!"草稿".equals(value(order.get("status")))) {
                throw new IllegalArgumentException("仅草稿入库单可删除");
            }
            ensureNoOutboundOccupied(conn, value(order.get("inOrderUuid")), "已有出库占用，不能删除入库单：");
            Db.update(conn, "DELETE FROM in_order_attachment WHERE in_order_detail_uuid IN (SELECT in_order_detail_uuid FROM in_order_detail WHERE in_order_no=?)", orderNo);
            Db.update(conn, "DELETE FROM in_order_log WHERE in_order_uuid=?", order.get("inOrderUuid"));
            Db.update(conn, "DELETE FROM in_order_detail WHERE in_order_no=?", orderNo);
            Db.update(conn, "DELETE FROM in_order WHERE in_order_no=?", orderNo);
            return null;
        });
    }

    public void deleteDetail(String detailUuid, UserSession user) throws Exception {
        Db.tx(conn -> {
            Map<String, Object> detail = Db.queryOne(conn, "SELECT * FROM in_order_detail WHERE in_order_detail_uuid=?", detailUuid);
            if (detail == null) {
                throw new IllegalArgumentException("明细不存在");
            }
            if (!"草稿".equals(value(detail.get("status")))) {
                throw new IllegalArgumentException("仅草稿明细可删除");
            }
            if (number(detail.get("stockPackageQty")).doubleValue() < number(detail.get("packageQty")).doubleValue()
                    || number(detail.get("stockQty")).doubleValue() < number(detail.get("qty")).doubleValue()) {
                throw new IllegalArgumentException("已有出库占用，不能删除明细");
            }
            Db.update(conn, "DELETE FROM in_order_attachment WHERE in_order_detail_uuid=?", detailUuid);
            Db.update(conn, "DELETE FROM in_order_detail WHERE in_order_detail_uuid=?", detailUuid);
            log(conn, value(detail.get("inOrderUuid")), detailUuid, "", "删除明细", user.getName(), "删除入库明细");
            return null;
        });
    }

    public void finishDelivery(String orderNo, JSONObject body, UserSession user) throws Exception {
        Db.tx(conn -> {
            Map<String, Object> order = requireOrder(conn, orderNo);
            if (!"有效".equals(value(order.get("status")))) {
                throw new IllegalArgumentException("仅有效入库单可完成派送");
            }
            String sendType = required(body, "sendType", "派送类型不能为空");
            String sendNo = Params.str(body, "sendNo");
            if ("派送".equals(sendType) && sendNo.isEmpty()) {
                throw new IllegalArgumentException("派送单号不能为空");
            }
            if ("自提".equals(sendType)) {
                sendNo = "";
            }
            Db.update(conn, "UPDATE in_order SET send_type=?, send_no=?, operator=?, operate_time=NOW() WHERE in_order_no=?", sendType, sendNo, user.getName(), orderNo);
            log(conn, value(order.get("inOrderUuid")), null, "send", "完成派送", user.getName(), sendType + (sendNo.isEmpty() ? "" : "：" + sendNo));
            return null;
        });
    }

    public void finishCollection(JSONObject body, UserSession user) throws Exception {
        Db.tx(conn -> {
            String detailUuid = required(body, "detailUuid", "明细不能为空");
            Map<String, Object> detail = Db.queryOne(conn, "SELECT * FROM in_order_detail WHERE in_order_detail_uuid=?", detailUuid);
            if (detail == null) {
                throw new IllegalArgumentException("明细不存在");
            }
            Db.update(conn,
                    "UPDATE in_order_detail SET rece_price=?, rece_currency=?, rece_amount=? WHERE in_order_detail_uuid=?",
                    body.getDouble("recePrice"), Params.str(body, "receCurrency"), body.getDouble("receAmount"), detailUuid);
            log(conn, value(detail.get("inOrderUuid")), detailUuid, "send", "完成收款", user.getName(), "录入实收金额");
            return null;
        });
    }

    public void adjustInventory(JSONObject body, UserSession user) throws Exception {
        String detailUuid = Params.str(body, "detailUuid");
        Db.tx(conn -> {
            Map<String, Object> before = Db.queryOne(conn, "SELECT * FROM in_order_detail WHERE in_order_detail_uuid=?", detailUuid);
            if (before == null) {
                throw new IllegalArgumentException("明细不存在");
            }
            String remark = inventoryRemark(before, body);
            Db.update(conn,
                    "UPDATE in_order_detail SET package_qty=?, stock_package_qty=?, qty=?, stock_qty=?, weight=?, volume=? WHERE in_order_detail_uuid=?",
                    body.getDouble("packageQty"), body.getDouble("stockPackageQty"), body.getDouble("qty"), body.getDouble("stockQty"),
                    body.getDouble("weight"), body.getDouble("volume"), detailUuid);
            log(conn, value(before.get("inOrderUuid")), detailUuid, "", "修改库存", user.getName(), remark);
            return null;
        });
    }

    public List<Map<String, Object>> detailList(String orderNo) throws Exception {
        try (Connection conn = Db.getConnection()) {
            return details(conn, orderNo);
        }
    }

    public List<Map<String, Object>> logList(String orderUuid) throws Exception {
        try (Connection conn = Db.getConnection()) {
            return logs(conn, orderUuid);
        }
    }

    public List<Map<String, Object>> logListByOrderNo(String orderNo) throws Exception {
        try (Connection conn = Db.getConnection()) {
            Map<String, Object> order = requireOrder(conn, orderNo);
            return logs(conn, value(order.get("inOrderUuid")));
        }
    }

    public List<Map<String, Object>> relatedOutbounds(String orderNo) throws Exception {
        try (Connection conn = Db.getConnection()) {
            requireOrder(conn, orderNo);
            return Db.query(conn,
                    "SELECT oo.out_order_no, oo.so_no, oo.status, oo.loading_date, oo.container_no, oo.wljd, ood.track_no, ood.product_name, ood.out_package_qty, ood.out_qty " +
                            "FROM out_order_detail ood JOIN out_order oo ON oo.out_order_uuid=ood.out_order_uuid " +
                            "JOIN in_order_detail iod ON iod.in_order_detail_uuid=ood.in_order_detail_uuid " +
                            "WHERE iod.in_order_no=? ORDER BY oo.create_time DESC, ood.create_time DESC",
                    orderNo);
        }
    }

    public List<Map<String, Object>> logisticsNodes(String orderNo) throws Exception {
        try (Connection conn = Db.getConnection()) {
            Map<String, Object> order = requireOrder(conn, orderNo);
            String orderUuid = value(order.get("inOrderUuid"));
            return Db.query(conn,
                    "SELECT operate_desc, operate_time, MAX(atd) AS atd, MAX(eta) AS eta, MAX(ata) AS ata FROM (" +
                            "SELECT operate_desc, operate_time, atd, eta, ata FROM in_order_log WHERE in_order_uuid=? AND operate_type='send' " +
                            "UNION ALL " +
                            "SELECT ool.operate_desc, ool.operate_time, oo.atd_time AS atd, oo.eta_time AS eta, oo.ata_time AS ata " +
                            "FROM out_order_log ool JOIN out_order_detail ood ON ood.out_order_uuid=ool.out_order_uuid JOIN in_order_detail iod ON iod.in_order_detail_uuid=ood.in_order_detail_uuid JOIN out_order oo ON oo.out_order_uuid=ool.out_order_uuid " +
                            "WHERE iod.in_order_uuid=? AND ool.operate_type='send') t GROUP BY operate_desc, operate_time ORDER BY operate_time DESC",
                    orderUuid, orderUuid);
        }
    }

    public byte[] template() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("入库导入模板");
            Row header = sheet.createRow(0);
            String[] columns = {"跟踪单号", "品名", "英文品名", "唛头", "件数", "包装", "重量", "体积", "数量", "应付单价", "应付币制", "应付单位", "应付金额", "应收单价", "应收币制", "应收单位", "应收金额"};
            for (int i = 0; i < columns.length; i++) {
                header.createCell(i).setCellValue(columns[i]);
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private void saveDetail(Connection conn, String orderUuid, String orderNo, JSONObject d, UserSession user) throws Exception {
        required(d, "productName", "品名不能为空");
        required(d, "productEnName", "英文品名不能为空");
        required(d, "marks", "唛头不能为空");
        required(d, "packageType", "包装种类不能为空");
        double packageQty = positive(d, "packageQty", "件数必须大于0");
        positive(d, "weight", "重量必须大于0");
        positive(d, "volume", "体积必须大于0");
        double qty = d.getDouble("qty") == null ? 0 : d.getDouble("qty");
        double costAmount = amount(d.getDouble("costPrice"), Params.str(d, "yfUnit"), d, "costAmount");
        double incomeAmount = amount(d.getDouble("incomePrice"), Params.str(d, "ysUnit"), d, "incomeAmount");
        Db.update(conn,
                "INSERT INTO in_order_detail (in_order_detail_uuid, in_order_uuid, in_order_no, status, track_no, control_word, warehouse_code, creator, create_time, operator, operate_time, product_name, product_en_name, package_qty, package_type, weight, volume, qty, stock_package_qty, stock_qty, marks, cost_price, cost_currency, yf_unit, cost_amount, income_price, income_currency, ys_unit, income_amount, rece_price, rece_currency, rece_amount) " +
                        "VALUES (?, ?, ?, '草稿', ?, '0000000000', '', ?, NOW(), ?, NOW(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), orderUuid, orderNo, Params.str(d, "trackNo"), user.getName(), user.getName(),
                Params.str(d, "productName"), Params.str(d, "productEnName"), packageQty, Params.str(d, "packageType"),
                d.getDouble("weight"), d.getDouble("volume"), qty, packageQty, qty, Params.str(d, "marks"),
                d.getDouble("costPrice"), Params.str(d, "costCurrency"), Params.str(d, "yfUnit"), costAmount,
                d.getDouble("incomePrice"), Params.str(d, "incomeCurrency"), Params.str(d, "ysUnit"), incomeAmount,
                d.getDouble("recePrice"), Params.str(d, "receCurrency"), d.getDouble("receAmount"));
    }

    private List<Map<String, Object>> details(Connection conn, String orderNo) throws Exception {
        return Db.query(conn, "SELECT * FROM in_order_detail WHERE in_order_no=? ORDER BY create_time", orderNo);
    }

    private List<Map<String, Object>> logs(Connection conn, String orderUuid) throws Exception {
        return Db.query(conn, "SELECT * FROM in_order_log WHERE in_order_uuid=? ORDER BY operate_time DESC, create_time DESC", orderUuid);
    }

    private Map<String, Object> summary(Connection conn, String where, List<Object> params) throws Exception {
        return Db.queryOne(conn,
                "SELECT COALESCE(SUM(d.volume),0) AS total_volume, COALESCE(SUM(d.weight),0) AS total_weight, COALESCE(SUM(d.package_qty),0) AS total_package_qty, COALESCE(SUM(d.stock_package_qty),0) AS total_stock_package_qty " +
                        "FROM in_order o LEFT JOIN in_order_detail d ON o.in_order_no=d.in_order_no WHERE 1=1 " + where,
                params.toArray());
    }

    private Map<String, Object> summaryFromRows(List<Map<String, Object>> rows) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalVolume", sum(rows, "totalVolume"));
        summary.put("totalWeight", sum(rows, "totalWeight"));
        summary.put("totalPackageQty", sum(rows, "totalPackageQty"));
        summary.put("totalStockPackageQty", sum(rows, "totalStockPackageQty"));
        return summary;
    }

    private String where(HttpServletRequest request, List<Object> params) {
        StringBuilder where = new StringBuilder();
        String orderNo = Params.str(request, "orderNo");
        String trackNo = Params.str(request, "trackNo");
        addLike(where, params, "o.in_order_no", orderNo);
        addLike(where, params, "o.track_no", trackNo);
        if (orderNo.isEmpty() && trackNo.isEmpty()) {
            addEq(where, params, "o.customer", Params.str(request, "customer"));
            addEq(where, params, "o.sales", Params.str(request, "sales"));
            addEq(where, params, "o.status", Params.str(request, "status"));
            addLike(where, params, "o.send_no", Params.str(request, "sendNo"));
            String logisticsNode = Params.str(request, "logisticsNode");
            if (!logisticsNode.isEmpty() && !"全部".equals(logisticsNode)) {
                where.append(" AND (SELECT l.operate_desc FROM in_order_log l WHERE l.in_order_uuid=o.in_order_uuid AND l.operate_type='send' ORDER BY l.operate_time DESC, l.create_time DESC LIMIT 1) LIKE ? ");
                params.add("%" + logisticsNode + "%");
            }
            addCollectionStatus(where, params, Params.str(request, "collectionStatus"));
            String start = Params.str(request, "startDate");
            String end = Params.str(request, "endDate");
            if (start.isEmpty() && end.isEmpty()) {
                start = LocalDate.now().minusDays(7).toString();
                end = LocalDate.now().toString();
            }
            if (!start.isEmpty() && !end.isEmpty()) {
                where.append(" AND o.in_date BETWEEN ? AND ? ");
                params.add(start);
                params.add(end);
            }
        }
        return where.toString();
    }

    private void addCollectionStatus(StringBuilder where, List<Object> params, String status) {
        if (status.isEmpty() || "全部".equals(status)) {
            return;
        }
        String paid = "EXISTS (SELECT 1 FROM in_order_attachment a WHERE a.in_order_detail_uuid=d.in_order_detail_uuid)";
        if ("已收款".equals(status)) {
            where.append(" AND EXISTS (SELECT 1 FROM in_order_detail d WHERE d.in_order_no=o.in_order_no) ");
            where.append(" AND NOT EXISTS (SELECT 1 FROM in_order_detail d WHERE d.in_order_no=o.in_order_no AND NOT ").append(paid).append(") ");
            return;
        }
        if ("未收款".equals(status)) {
            where.append(" AND NOT EXISTS (SELECT 1 FROM in_order_detail d WHERE d.in_order_no=o.in_order_no AND ").append(paid).append(") ");
            return;
        }
        if ("部分收款".equals(status)) {
            where.append(" AND EXISTS (SELECT 1 FROM in_order_detail d WHERE d.in_order_no=o.in_order_no AND ").append(paid).append(") ");
            where.append(" AND EXISTS (SELECT 1 FROM in_order_detail d WHERE d.in_order_no=o.in_order_no AND NOT ").append(paid).append(") ");
        }
    }

    private void addLike(StringBuilder where, List<Object> params, String column, String value) {
        if (!value.isEmpty()) {
            where.append(" AND ").append(column).append(" LIKE ? ");
            params.add("%" + value + "%");
        }
    }

    private void addEq(StringBuilder where, List<Object> params, String column, String value) {
        if (!value.isEmpty() && !"全部".equals(value)) {
            where.append(" AND ").append(column).append("=? ");
            params.add(value);
        }
    }

    private int count(Connection conn, String where, List<Object> params) throws Exception {
        return ((Number) Db.queryOne(conn, "SELECT COUNT(*) AS total FROM in_order o WHERE 1=1 " + where, params.toArray()).get("total")).intValue();
    }

    private Map<String, Object> requireOrder(Connection conn, String orderNo) throws Exception {
        Map<String, Object> order = Db.queryOne(conn, "SELECT * FROM in_order WHERE in_order_no=?", orderNo);
        if (order == null) {
            throw new IllegalArgumentException("入库单不存在");
        }
        return order;
    }

    private String inventoryRemark(Map<String, Object> before, JSONObject body) {
        List<String> changes = new ArrayList<>();
        addChange(changes, "packageQty", before.get("packageQty"), body.getDouble("packageQty"));
        addChange(changes, "stockPackageQty", before.get("stockPackageQty"), body.getDouble("stockPackageQty"));
        addChange(changes, "qty", before.get("qty"), body.getDouble("qty"));
        addChange(changes, "stockQty", before.get("stockQty"), body.getDouble("stockQty"));
        addChange(changes, "weight", before.get("weight"), body.getDouble("weight"));
        addChange(changes, "volume", before.get("volume"), body.getDouble("volume"));
        return changes.isEmpty() ? "库存字段无变化" : String.join("; ", changes);
    }

    private void addChange(List<String> changes, String field, Object oldValue, Double newValue) {
        double oldNumber = number(oldValue).doubleValue();
        double nextNumber = newValue == null ? 0 : newValue;
        if (Double.compare(oldNumber, nextNumber) != 0) {
            changes.add(field + ": " + oldNumber + " -> " + nextNumber);
        }
    }

    private void ensureNoOutboundOccupied(Connection conn, String orderUuid, String messagePrefix) throws Exception {
        Map<String, Object> occupied = Db.queryOne(conn,
                "SELECT product_name FROM in_order_detail WHERE in_order_uuid=? AND (stock_package_qty < package_qty OR stock_qty < qty) LIMIT 1",
                orderUuid);
        if (occupied != null) {
            throw new IllegalArgumentException(messagePrefix + value(occupied.get("productName")));
        }
    }

    private String nextNo(Connection conn, String table, String column, String prefix) throws Exception {
        String day = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"));
        String like = prefix + day + "%";
        Map<String, Object> row = Db.queryOne(conn, "SELECT " + column + " AS order_no FROM " + table + " WHERE " + column + " LIKE ? ORDER BY " + column + " DESC LIMIT 1 FOR UPDATE", like);
        String lastNo = row == null ? "" : value(row.get("orderNo"));
        int serial = lastNo.length() < 3 ? 1 : Integer.parseInt(lastNo.substring(lastNo.length() - 3)) + 1;
        return prefix + day + String.format("%03d", serial);
    }

    private void log(Connection conn, String orderUuid, String detailUuid, String type, String desc, String contact, String remark) throws Exception {
        Db.update(conn,
                "INSERT INTO in_order_log (in_order_log_uuid, in_order_uuid, in_order_detail_uuid, operate_type, operate_desc, operate_time, operate_contact, remark, control_word, warehouse_code, creator, create_time) " +
                        "VALUES (?, ?, ?, ?, ?, NOW(), ?, ?, '0000000000', '', ?, NOW())",
                UUID.randomUUID().toString(), orderUuid, detailUuid, type, desc, contact, remark, contact);
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String required(JSONObject body, String key, String message) {
        String value = Params.str(body, key);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private Number number(Object value) {
        return value instanceof Number ? (Number) value : 0;
    }

    private double positive(JSONObject body, String key, String message) {
        Double value = body.getDouble(key);
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private double amount(Double price, String unit, JSONObject body, String manualKey) {
        if (price == null || unit == null || unit.isBlank() || "无".equals(unit)) {
            Double manual = body.getDouble(manualKey);
            return manual == null ? 0 : manual;
        }
        double base = switch (unit) {
            case "按重量" -> body.getDouble("weight") == null ? 0 : body.getDouble("weight");
            case "按体积" -> body.getDouble("volume") == null ? 0 : body.getDouble("volume");
            case "按数量" -> body.getDouble("qty") == null ? 0 : body.getDouble("qty");
            default -> 0;
        };
        return Math.round(price * base * 10.0) / 10.0;
    }

    private double sum(List<Map<String, Object>> rows, String key) {
        return rows.stream().mapToDouble(row -> number(row.get(key)).doubleValue()).sum();
    }
}
