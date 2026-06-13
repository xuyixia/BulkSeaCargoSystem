package hyshweb.outbound;

import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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

public class OutboundService {
    private static final List<String> SEND_NODES = List.of("出库", "完成报关", "完成交重", "启运", "到港", "清关启动", "清关完成", "到仓");

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
                    "SELECT o.out_order_uuid, o.out_order_no, o.so_no, o.status, o.loading_date, o.container_no, o.car_plate, o.customs_broker, o.export_port, o.atd_time, o.eta_time, o.ata_time, o.wljd, o.creator, " +
                            "(SELECT COALESCE(SUM(d.out_package_qty),0) FROM out_order_detail d WHERE d.out_order_uuid=o.out_order_uuid) AS total_package_qty, " +
                            "(SELECT COALESCE(SUM(d.out_qty),0) FROM out_order_detail d WHERE d.out_order_uuid=o.out_order_uuid) AS total_qty, " +
                            "(SELECT COALESCE(SUM(d.weight),0) FROM out_order_detail d WHERE d.out_order_uuid=o.out_order_uuid) AS total_weight, " +
                            "(SELECT COALESCE(SUM(d.volume),0) FROM out_order_detail d WHERE d.out_order_uuid=o.out_order_uuid) AS total_volume, " +
                            "(SELECT COALESCE(SUM(c.amount),0) FROM cost_order c WHERE c.out_order_uuid=o.out_order_uuid) AS total_cost, " +
                            "(SELECT COUNT(*) FROM out_order_detail d WHERE d.out_order_uuid=o.out_order_uuid) AS detail_count " +
                            "FROM out_order o LEFT JOIN out_order_detail od ON od.out_order_uuid=o.out_order_uuid WHERE 1=1 " + where +
                            " GROUP BY o.out_order_uuid ORDER BY o.create_time DESC LIMIT ? OFFSET ?",
                    queryParams.toArray());
            return new Page(rows, page, pageSize, total, summaryFromRows(rows), summary(conn, where, params));
        }
    }

    public Map<String, Object> get(String orderNo) throws Exception {
        try (Connection conn = Db.getConnection()) {
            Map<String, Object> order = Db.queryOne(conn, "SELECT * FROM out_order WHERE out_order_no=?", orderNo);
            if (order != null) {
                String uuid = value(order.get("outOrderUuid"));
                order.put("details", details(conn, uuid));
                order.put("costs", costs(conn, uuid));
                order.put("logs", logs(conn, uuid));
                order.put("receivables", receivables(conn, uuid));
            }
            return order;
        }
    }

    public String save(JSONObject body, UserSession user) throws Exception {
        return Db.tx(conn -> {
            String orderNo = Params.str(body, "outOrderNo");
            boolean creating = orderNo.isEmpty();
            String uuid;
            if (creating) {
                orderNo = nextNo(conn);
                uuid = UUID.randomUUID().toString();
                String containerNo = required(body, "containerNo", "柜号不能为空");
                validateContainerNo(containerNo);
                Db.update(conn,
                        "INSERT INTO out_order (out_order_uuid, out_order_no, status, so_no, loading_date, container_no, car_plate, customs_broker, export_port, warehouse_code, creator, create_time, atd_time, eta_time, ata_time) " +
                                "VALUES (?, ?, '草稿', ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NULL, NULL, NULL)",
                        uuid, orderNo, required(body, "soNo", "SO号不能为空"), required(body, "loadingDate", "装柜日期不能为空"),
                        containerNo, Params.str(body, "carPlate"), Params.str(body, "customsBroker"),
                        Params.str(body, "exportPort"), "WH001", user.getName());
            } else {
                Map<String, Object> order = requireOrder(conn, orderNo);
                uuid = value(order.get("outOrderUuid"));
                String containerNo = required(body, "containerNo", "柜号不能为空");
                validateContainerNo(containerNo);
                Db.update(conn,
                        "UPDATE out_order SET so_no=?, loading_date=?, container_no=?, car_plate=?, customs_broker=?, export_port=?, warehouse_code=?, status='草稿' WHERE out_order_no=?",
                        required(body, "soNo", "SO号不能为空"), required(body, "loadingDate", "装柜日期不能为空"),
                        containerNo, Params.str(body, "carPlate"), Params.str(body, "customsBroker"),
                        Params.str(body, "exportPort"), "WH001", orderNo);
            }
            log(conn, uuid, "", "保存", "保存出库单为草稿状态", user.getName());
            return orderNo;
        });
    }

    public void submit(String orderNo, UserSession user) throws Exception {
        Db.tx(conn -> {
            Map<String, Object> order = requireOrder(conn, orderNo);
            int detailCount = number(Db.queryOne(conn, "SELECT COUNT(*) AS total FROM out_order_detail WHERE out_order_uuid=?", order.get("outOrderUuid")).get("total")).intValue();
            if (detailCount == 0) {
                throw new IllegalArgumentException("请先添加出库明细");
            }
            Db.update(conn, "UPDATE out_order SET status='有效' WHERE out_order_uuid=?", order.get("outOrderUuid"));
            log(conn, value(order.get("outOrderUuid")), "", "状态变更", "提交出库单，状态改为有效", user.getName());
            return null;
        });
    }

    public void cancel(String orderNo, UserSession user) throws Exception {
        Db.tx(conn -> {
            Map<String, Object> order = requireOrder(conn, orderNo);
            Db.update(conn, "UPDATE out_order SET status='草稿' WHERE out_order_uuid=?", order.get("outOrderUuid"));
            log(conn, value(order.get("outOrderUuid")), "", "状态变更", "取消提交，状态改为草稿", user.getName());
            return null;
        });
    }

    public void delete(String orderNo) throws Exception {
        Db.tx(conn -> {
            Map<String, Object> order = requireOrder(conn, orderNo);
            String uuid = value(order.get("outOrderUuid"));
            if (!"草稿".equals(value(order.get("status")))) {
                throw new IllegalArgumentException("仅草稿出库单可删除");
            }
            for (Map<String, Object> detail : Db.query(conn, "SELECT in_order_detail_uuid, out_package_qty, out_qty FROM out_order_detail WHERE out_order_uuid=?", uuid)) {
                Db.update(conn,
                        "UPDATE in_order_detail SET stock_package_qty=stock_package_qty+?, stock_qty=stock_qty+? WHERE in_order_detail_uuid=?",
                        number(detail.get("outPackageQty")), number(detail.get("outQty")), detail.get("inOrderDetailUuid"));
            }
            Db.update(conn, "DELETE FROM in_order_attachment WHERE cost_order_uuid IN (SELECT cost_order_uuid FROM cost_order WHERE out_order_uuid=?)", uuid);
            Db.update(conn, "DELETE FROM out_order_log WHERE out_order_uuid=?", uuid);
            Db.update(conn, "DELETE FROM out_order_detail WHERE out_order_uuid=?", uuid);
            Db.update(conn, "DELETE FROM cost_order WHERE out_order_uuid=?", uuid);
            Db.update(conn, "DELETE FROM out_order WHERE out_order_uuid=?", uuid);
            return null;
        });
    }

    public List<Map<String, Object>> stock(HttpServletRequest request) throws Exception {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" AND d.stock_package_qty > 0 ");
        addLike(where, params, "d.track_no", Params.str(request, "trackNo"));
        addLike(where, params, "d.product_name", Params.str(request, "productName"));
        addLike(where, params, "d.in_order_no", Params.str(request, "inOrderNo"));
        addEq(where, params, "o.customer", Params.str(request, "customer"));
        addEq(where, params, "o.sales", Params.str(request, "sales"));
        addDateRange(where, params, "o.in_date", Params.str(request, "inStartDate"), Params.str(request, "inEndDate"));
        try (Connection conn = Db.getConnection()) {
            return Db.query(conn,
                    "SELECT d.in_order_detail_uuid, d.in_order_no, d.track_no, d.product_name, o.customer, o.sales, o.in_date, d.marks, d.stock_package_qty, d.package_type, d.weight, d.volume, d.stock_qty, d.income_amount, d.cost_amount " +
                            "FROM in_order_detail d JOIN in_order o ON o.in_order_uuid=d.in_order_uuid WHERE o.status!='草稿' " + where +
                            " ORDER BY d.create_time DESC LIMIT 100",
                    params.toArray());
        }
    }

    public List<Map<String, Object>> details(String orderNo) throws Exception {
        try (Connection conn = Db.getConnection()) {
            Map<String, Object> order = requireOrder(conn, orderNo);
            return details(conn, value(order.get("outOrderUuid")));
        }
    }

    public void addDetails(String orderNo, JSONArray items, UserSession user) throws Exception {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("请选择出库明细");
        }
        Db.tx(conn -> {
            Map<String, Object> order = requireOrder(conn, orderNo);
            String uuid = value(order.get("outOrderUuid"));
            for (int i = 0; i < items.size(); i++) {
                JSONObject item = items.getJSONObject(i);
                addDetail(conn, uuid, orderNo, item, user.getName());
            }
            log(conn, uuid, "", "添加明细", "添加出库明细：" + items.size() + "条", user.getName());
            return null;
        });
    }

    public void updateDetail(JSONObject body, UserSession user) throws Exception {
        Db.tx(conn -> {
            String outDetailUuid = required(body, "outOrderDetailUuid", "出库明细不能为空");
            Map<String, Object> old = Db.queryOne(conn, "SELECT * FROM out_order_detail WHERE out_order_detail_uuid=?", outDetailUuid);
            if (old == null) {
                throw new IllegalArgumentException("出库明细不存在");
            }
            double newPackageQty = body.getDouble("outPackageQty") == null ? 0 : body.getDouble("outPackageQty");
            double newQty = body.getDouble("outQty") == null ? 0 : body.getDouble("outQty");
            double diffPackage = newPackageQty - number(old.get("outPackageQty")).doubleValue();
            double diffQty = newQty - number(old.get("outQty")).doubleValue();
            if (diffPackage != 0 || diffQty != 0) {
                int updated = Db.update(conn,
                        "UPDATE in_order_detail SET stock_package_qty=stock_package_qty-?, stock_qty=stock_qty-? WHERE in_order_detail_uuid=? AND stock_package_qty>=? AND stock_qty>=?",
                        diffPackage, diffQty, old.get("inOrderDetailUuid"), diffPackage, diffQty);
                if (updated == 0) {
                    throw new IllegalArgumentException("库存不足，无法修改出库数量");
                }
            }
            Db.update(conn, "UPDATE out_order_detail SET out_package_qty=?, out_qty=? WHERE out_order_detail_uuid=?", newPackageQty, newQty, outDetailUuid);
            log(conn, value(old.get("outOrderUuid")), "", "编辑明细", "修改出库件数/数量", user.getName());
            return null;
        });
    }

    public void deleteDetail(String outDetailUuid, UserSession user) throws Exception {
        Db.tx(conn -> {
            Map<String, Object> old = Db.queryOne(conn, "SELECT * FROM out_order_detail WHERE out_order_detail_uuid=?", outDetailUuid);
            if (old == null) {
                throw new IllegalArgumentException("出库明细不存在");
            }
            Db.update(conn,
                    "UPDATE in_order_detail SET stock_package_qty=stock_package_qty+?, stock_qty=stock_qty+? WHERE in_order_detail_uuid=?",
                    number(old.get("outPackageQty")), number(old.get("outQty")), old.get("inOrderDetailUuid"));
            Db.update(conn, "DELETE FROM out_order_detail WHERE out_order_detail_uuid=?", outDetailUuid);
            log(conn, value(old.get("outOrderUuid")), "", "删除明细", "删除出库明细并回补库存", user.getName());
            return null;
        });
    }

    public List<Map<String, Object>> costs(String orderNo) throws Exception {
        try (Connection conn = Db.getConnection()) {
            Map<String, Object> order = requireOrder(conn, orderNo);
            return costs(conn, value(order.get("outOrderUuid")));
        }
    }

    public List<Map<String, Object>> logs(String orderNo) throws Exception {
        try (Connection conn = Db.getConnection()) {
            Map<String, Object> order = requireOrder(conn, orderNo);
            return logs(conn, value(order.get("outOrderUuid")));
        }
    }

    public String addCost(String orderNo, JSONObject body, UserSession user) throws Exception {
        return Db.tx(conn -> {
            Map<String, Object> order = requireOrder(conn, orderNo);
            String costUuid = UUID.randomUUID().toString();
            Db.update(conn,
                    "INSERT INTO cost_order (cost_order_uuid, out_order_uuid, cost_name, amount, remark) VALUES (?, ?, ?, ?, ?)",
                    costUuid, order.get("outOrderUuid"), required(body, "costName", "费用名称不能为空"),
                    body.getDouble("amount") == null ? 0 : body.getDouble("amount"), Params.str(body, "remark"));
            log(conn, value(order.get("outOrderUuid")), "", "添加费用", "添加费用：" + Params.str(body, "costName"), user.getName());
            return costUuid;
        });
    }

    public void deleteCost(String costUuid, UserSession user) throws Exception {
        Db.tx(conn -> {
            Map<String, Object> cost = Db.queryOne(conn, "SELECT * FROM cost_order WHERE cost_order_uuid=?", costUuid);
            if (cost == null) {
                throw new IllegalArgumentException("费用不存在");
            }
            Db.update(conn, "DELETE FROM in_order_attachment WHERE cost_order_uuid=?", costUuid);
            Db.update(conn, "DELETE FROM cost_order WHERE cost_order_uuid=?", costUuid);
            log(conn, value(cost.get("outOrderUuid")), "", "删除费用", "删除费用：" + value(cost.get("costName")), user.getName());
            return null;
        });
    }

    public void updateCost(String costUuid, JSONObject body, UserSession user) throws Exception {
        Db.tx(conn -> {
            Map<String, Object> cost = Db.queryOne(conn, "SELECT * FROM cost_order WHERE cost_order_uuid=?", costUuid);
            if (cost == null) {
                throw new IllegalArgumentException("费用不存在");
            }
            String costName = required(body, "costName", "费用名称不能为空");
            double amount = body.getDouble("amount") == null ? 0 : body.getDouble("amount");
            Db.update(conn, "UPDATE cost_order SET cost_name=?, amount=?, remark=? WHERE cost_order_uuid=?",
                    costName, amount, Params.str(body, "remark"), costUuid);
            log(conn, value(cost.get("outOrderUuid")), "", "编辑费用", "编辑费用：" + costName, user.getName());
            return null;
        });
    }

    public List<Map<String, Object>> receivables(String orderNo) throws Exception {
        try (Connection conn = Db.getConnection()) {
            Map<String, Object> order = requireOrder(conn, orderNo);
            return receivables(conn, value(order.get("outOrderUuid")));
        }
    }

    public void saveReceivable(JSONObject body, UserSession user) throws Exception {
        Db.tx(conn -> {
            String detailUuid = required(body, "outOrderDetailUuid", "出库明细不能为空");
            Map<String, Object> detail = Db.queryOne(conn,
                    "SELECT ood.out_order_uuid, ood.in_order_detail_uuid, iod.in_order_uuid, iod.income_price, iod.income_currency, iod.income_amount, iod.cost_price, iod.cost_currency, iod.cost_amount " +
                            "FROM out_order_detail ood JOIN in_order_detail iod ON iod.in_order_detail_uuid=ood.in_order_detail_uuid WHERE ood.out_order_detail_uuid=?",
                    detailUuid);
            if (detail == null) {
                throw new IllegalArgumentException("出库明细不存在");
            }
            String mode = Params.str(body, "mode").isEmpty() ? "payable" : Params.str(body, "mode");
            if ("receivable".equals(mode)) {
                double recePrice = body.getDouble("recePrice") == null ? number(detail.get("incomePrice")).doubleValue() : body.getDouble("recePrice");
                String receCurrency = Params.str(body, "receCurrency").isEmpty() ? value(detail.get("incomeCurrency")) : Params.str(body, "receCurrency");
                double receAmount = body.getDouble("receAmount") == null ? number(detail.get("incomeAmount")).doubleValue() : body.getDouble("receAmount");
                Db.update(conn,
                        "UPDATE in_order_detail SET rece_price=?, rece_currency=?, rece_amount=? WHERE in_order_detail_uuid=?",
                        recePrice, receCurrency, receAmount, detail.get("inOrderDetailUuid"));
                Db.update(conn,
                        "INSERT INTO in_order_log (in_order_log_uuid, in_order_uuid, in_order_detail_uuid, operate_type, operate_desc, operate_time, operate_contact, remark, control_word, warehouse_code, creator, create_time) " +
                                "VALUES (?, ?, ?, 'send', '完成收款', NOW(), ?, '录入实收金额', '0000000000', '', ?, NOW())",
                        UUID.randomUUID().toString(), detail.get("inOrderUuid"), detail.get("inOrderDetailUuid"), user.getName(), user.getName());
                log(conn, value(detail.get("outOrderUuid")), "", "录入实收", "录入实收金额", user.getName());
                return null;
            }
            double sfPrice = body.getDouble("sfPrice") == null ? number(detail.get("costPrice")).doubleValue() : body.getDouble("sfPrice");
            String sfCurrency = Params.str(body, "sfCurrency").isEmpty() ? value(detail.get("costCurrency")) : Params.str(body, "sfCurrency");
            double sfAmount = body.getDouble("sfAmount") == null ? number(detail.get("costAmount")).doubleValue() : body.getDouble("sfAmount");
            Db.update(conn,
                    "UPDATE out_order_detail SET sf_price=?, sf_currency=?, sf_amount=? WHERE out_order_detail_uuid=?",
                    sfPrice, sfCurrency, sfAmount, detailUuid);
            log(conn, value(detail.get("outOrderUuid")), "", "录入实付", "录入实付金额", user.getName());
            return null;
        });
    }

    public byte[] exportDetails(String orderNo) throws Exception {
        try (Connection conn = Db.getConnection();
             XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Map<String, Object> order = requireOrder(conn, orderNo);
            Sheet sheet = workbook.createSheet("出库明细");
            writeRow(sheet.createRow(0), "进仓时间", "客户", "货物品名", "货物英文品名", "跟踪单号", "唛头", "出库件数", "出库数量", "重量", "方数", "编码", "备注");
            List<Map<String, Object>> rows = Db.query(conn,
                    "SELECT o.loading_date, io.customer, d.product_name, iod.product_en_name, d.track_no, d.marks, d.out_package_qty, d.out_qty, d.weight, d.volume, iod.in_order_detail_uuid " +
                            "FROM out_order_detail d LEFT JOIN out_order o ON o.out_order_uuid=d.out_order_uuid LEFT JOIN in_order_detail iod ON iod.in_order_detail_uuid=d.in_order_detail_uuid LEFT JOIN in_order io ON io.in_order_uuid=iod.in_order_uuid " +
                            "WHERE d.out_order_uuid=? ORDER BY d.create_time",
                    order.get("outOrderUuid"));
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> row = rows.get(i);
                writeRow(sheet.createRow(i + 1), row.get("loadingDate"), row.get("customer"), row.get("productName"), row.get("productEnName"),
                        row.get("trackNo"), row.get("marks"), row.get("outPackageQty"), row.get("outQty"), row.get("weight"), row.get("volume"),
                        row.get("inOrderDetailUuid"), "");
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    public byte[] exportAccounts(String orderNo) throws Exception {
        try (Connection conn = Db.getConnection();
             XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Map<String, Object> order = requireOrder(conn, orderNo);
            Sheet sheet = workbook.createSheet("账款");
            writeRow(sheet.createRow(0), "出库单号", value(order.get("outOrderNo")), "SO号", value(order.get("soNo")), "柜号", value(order.get("containerNo")));
            writeRow(sheet.createRow(2), "跟踪单号", "品名", "应付金额", "应收金额", "实收金额", "实付金额");
            List<Map<String, Object>> rows = receivables(conn, value(order.get("outOrderUuid")));
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> row = rows.get(i);
                writeRow(sheet.createRow(i + 3), row.get("trackNo"), row.get("productName"), row.get("costAmount"), row.get("incomeAmount"),
                        row.get("receAmount"), row.get("sfAmount"));
            }
            writeRow(sheet.createRow(rows.size() + 4), "合计", "", sum(rows, "costAmount"), sum(rows, "incomeAmount"), sum(rows, "receAmount"), sum(rows, "sfAmount"));
            workbook.write(out);
            return out.toByteArray();
        }
    }

    public void sendNode(String orderNo, JSONObject body, UserSession user) throws Exception {
        String node = required(body, "node", "物流节点不能为空");
        if (!SEND_NODES.contains(node)) {
            throw new IllegalArgumentException("物流节点无效");
        }
        LocalDate operateDate = requireDate(Params.str(body, "operateDate"), "节点日期不能为空");
        if (operateDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("节点日期不能晚于当天");
        }
        LocalDate atd = null;
        LocalDate eta = null;
        LocalDate ata = null;
        if ("启运".equals(node)) {
            atd = requireDate(Params.str(body, "atd"), "ATD不能为空");
            eta = requireDate(Params.str(body, "eta"), "ETA不能为空");
            if (eta.isBefore(atd)) {
                throw new IllegalArgumentException("ETA不能早于ATD");
            }
            if (atd.isAfter(LocalDate.now()) || eta.isAfter(LocalDate.now())) {
                throw new IllegalArgumentException("ATD/ETA不能晚于当天");
            }
        }
        if ("到港".equals(node)) {
            ata = requireDate(Params.str(body, "ata"), "ATA不能为空");
            if (ata.isAfter(LocalDate.now())) {
                throw new IllegalArgumentException("ATA不能晚于当天");
            }
        }
        LocalDate finalAtd = atd;
        LocalDate finalEta = eta;
        LocalDate finalAta = ata;
        Db.tx(conn -> {
            Map<String, Object> order = requireOrder(conn, orderNo);
            String uuid = value(order.get("outOrderUuid"));
            if (finalAta != null && order.get("etaTime") != null) {
                LocalDate etaInDb = LocalDate.parse(value(order.get("etaTime")).substring(0, 10));
                if (finalAta.isBefore(etaInDb)) {
                    throw new IllegalArgumentException("ATA不能早于ETA");
                }
            }
            if (finalAtd != null && finalEta != null) {
                Db.update(conn, "UPDATE out_order SET atd_time=?, eta_time=? WHERE out_order_uuid=?", finalAtd.toString(), finalEta.toString(), uuid);
            }
            if (finalAta != null) {
                Db.update(conn, "UPDATE out_order SET ata_time=? WHERE out_order_uuid=?", finalAta.toString(), uuid);
            }
            Db.update(conn, "UPDATE out_order SET wljd=? WHERE out_order_uuid=?", node, uuid);
            log(conn, uuid, "send", node, Params.str(body, "remark"), user.getName(), operateDate.toString());
            for (Map<String, Object> inOrder : relatedInOrders(conn, uuid)) {
                Db.update(conn,
                        "INSERT INTO in_order_log (in_order_log_uuid, in_order_uuid, in_order_detail_uuid, operate_type, operate_desc, operate_time, operate_contact, remark, atd, eta, ata, control_word, warehouse_code, creator, create_time) " +
                                "VALUES (?, ?, NULL, 'send', ?, ?, ?, ?, ?, ?, ?, '0000000000', '', ?, NOW())",
                        UUID.randomUUID().toString(), inOrder.get("inOrderUuid"), node, operateDate.toString(), user.getName(), Params.str(body, "remark"),
                        finalAtd == null ? null : finalAtd.toString(), finalEta == null ? null : finalEta.toString(), finalAta == null ? null : finalAta.toString(), user.getName());
            }
            return null;
        });
    }

    private void addDetail(Connection conn, String outOrderUuid, String outOrderNo, JSONObject item, String creator) throws Exception {
        String inDetailUuid = required(item, "inOrderDetailUuid", "入库明细不能为空");
        double outPackageQty = item.getDouble("outPackageQty") == null ? 0 : item.getDouble("outPackageQty");
        double outQty = item.getDouble("outQty") == null ? 0 : item.getDouble("outQty");
        if (outPackageQty <= 0 || outQty <= 0) {
            throw new IllegalArgumentException("出库件数和数量必须大于0");
        }
        Map<String, Object> stock = Db.queryOne(conn,
                "SELECT in_order_detail_uuid, track_no, product_name, marks, stock_package_qty, package_type, weight, volume, stock_qty FROM in_order_detail WHERE in_order_detail_uuid=? FOR UPDATE",
                inDetailUuid);
        if (stock == null) {
            throw new IllegalArgumentException("入库明细不存在");
        }
        if (number(stock.get("stockPackageQty")).doubleValue() < outPackageQty || number(stock.get("stockQty")).doubleValue() < outQty) {
            throw new IllegalArgumentException("库存不足，无法出库：" + value(stock.get("productName")));
        }
        Map<String, Object> amount = Db.queryOne(conn, "SELECT cost_amount, income_amount FROM in_order_detail WHERE in_order_detail_uuid=?", inDetailUuid);
        if (amount == null || (number(amount.get("costAmount")).doubleValue() <= 0 && number(amount.get("incomeAmount")).doubleValue() <= 0)) {
            throw new IllegalArgumentException("应付和应收金额均为空，不能出库：" + value(stock.get("productName")));
        }
        int updated = Db.update(conn,
                "UPDATE in_order_detail SET stock_package_qty=stock_package_qty-?, stock_qty=stock_qty-? WHERE in_order_detail_uuid=? AND stock_package_qty>=? AND stock_qty>=?",
                outPackageQty, outQty, inDetailUuid, outPackageQty, outQty);
        if (updated == 0) {
            throw new IllegalArgumentException("库存不足，无法出库：" + value(stock.get("productName")));
        }
        Db.update(conn,
                "INSERT INTO out_order_detail (out_order_detail_uuid, out_order_uuid, out_order_no, in_order_detail_uuid, track_no, product_name, marks, out_package_qty, package_type, weight, volume, out_qty, creator, create_time, status) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), 'Active')",
                UUID.randomUUID().toString(), outOrderUuid, outOrderNo, inDetailUuid, stock.get("trackNo"), stock.get("productName"), stock.get("marks"),
                outPackageQty, stock.get("packageType"), stock.get("weight"), stock.get("volume"), outQty, creator);
    }

    private List<Map<String, Object>> details(Connection conn, String outOrderUuid) throws Exception {
        return Db.query(conn,
                "SELECT d.*, (SELECT COUNT(*) FROM in_order_attachment a WHERE a.in_order_detail_uuid=d.in_order_detail_uuid) AS attach_count FROM out_order_detail d WHERE d.out_order_uuid=? ORDER BY d.create_time",
                outOrderUuid);
    }

    private List<Map<String, Object>> costs(Connection conn, String outOrderUuid) throws Exception {
        return Db.query(conn,
                "SELECT c.*, (SELECT COUNT(*) FROM in_order_attachment a WHERE a.cost_order_uuid=c.cost_order_uuid) AS attach_count FROM cost_order c WHERE c.out_order_uuid=? ORDER BY c.cost_name",
                outOrderUuid);
    }

    private List<Map<String, Object>> logs(Connection conn, String outOrderUuid) throws Exception {
        return Db.query(conn, "SELECT * FROM out_order_log WHERE out_order_uuid=? ORDER BY operate_time DESC, create_time DESC", outOrderUuid);
    }

    private List<Map<String, Object>> receivables(Connection conn, String outOrderUuid) throws Exception {
        return Db.query(conn,
                "SELECT ood.out_order_detail_uuid, ood.track_no, ood.product_name, ood.marks, ood.out_package_qty, ood.out_qty, iod.in_order_detail_uuid, iod.cost_price, iod.cost_currency, iod.cost_amount, iod.income_price, iod.income_currency, iod.income_amount, iod.rece_price, iod.rece_currency, iod.rece_amount, ood.sf_price, ood.sf_currency, ood.sf_amount " +
                        "FROM out_order_detail ood LEFT JOIN in_order_detail iod ON iod.in_order_detail_uuid=ood.in_order_detail_uuid WHERE ood.out_order_uuid=? ORDER BY ood.create_time",
                outOrderUuid);
    }

    private List<Map<String, Object>> relatedInOrders(Connection conn, String outOrderUuid) throws Exception {
        return Db.query(conn,
                "SELECT iod.in_order_uuid FROM out_order_detail ood JOIN in_order_detail iod ON iod.in_order_detail_uuid=ood.in_order_detail_uuid WHERE ood.out_order_uuid=? GROUP BY iod.in_order_uuid",
                outOrderUuid);
    }

    private Map<String, Object> summary(Connection conn, String where, List<Object> params) throws Exception {
        return Db.queryOne(conn,
                "SELECT COALESCE(SUM(t.total_package_qty),0) AS total_package_qty, COALESCE(SUM(t.total_qty),0) AS total_qty, COALESCE(SUM(t.total_weight),0) AS total_weight, COALESCE(SUM(t.total_volume),0) AS total_volume, COALESCE(SUM(t.total_cost),0) AS total_cost " +
                        "FROM (SELECT o.out_order_uuid, COALESCE(SUM(od.out_package_qty),0) AS total_package_qty, COALESCE(SUM(od.out_qty),0) AS total_qty, COALESCE(SUM(od.weight),0) AS total_weight, COALESCE(SUM(od.volume),0) AS total_volume, " +
                        "(SELECT COALESCE(SUM(c.amount),0) FROM cost_order c WHERE c.out_order_uuid=o.out_order_uuid) AS total_cost FROM out_order o LEFT JOIN out_order_detail od ON od.out_order_uuid=o.out_order_uuid WHERE 1=1 " + where + " GROUP BY o.out_order_uuid) t",
                params.toArray());
    }

    private Map<String, Object> summaryFromRows(List<Map<String, Object>> rows) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalPackageQty", sum(rows, "totalPackageQty"));
        summary.put("totalQty", sum(rows, "totalQty"));
        summary.put("totalWeight", sum(rows, "totalWeight"));
        summary.put("totalVolume", sum(rows, "totalVolume"));
        summary.put("totalCost", sum(rows, "totalCost"));
        return summary;
    }

    private String where(HttpServletRequest request, List<Object> params) {
        StringBuilder where = new StringBuilder();
        addLike(where, params, "o.so_no", Params.str(request, "soNo"));
        addLike(where, params, "o.out_order_no", Params.str(request, "orderNo"));
        addLike(where, params, "od.track_no", Params.str(request, "trackNo"));
        addLike(where, params, "o.container_no", Params.str(request, "containerNo"));
        addEq(where, params, "o.status", Params.str(request, "status"));
        addEq(where, params, "o.customs_broker", Params.str(request, "customsBroker"));
        addEq(where, params, "o.export_port", Params.str(request, "exportPort"));
        addEq(where, params, "o.wljd", Params.str(request, "node"));
        addDateRange(where, params, "o.loading_date", Params.str(request, "loadingStart"), Params.str(request, "loadingEnd"));
        addDateRange(where, params, "o.atd_time", Params.str(request, "atdStart"), Params.str(request, "atdEnd"));
        addDateRange(where, params, "o.eta_time", Params.str(request, "etaStart"), Params.str(request, "etaEnd"));
        addDateRange(where, params, "o.ata_time", Params.str(request, "ataStart"), Params.str(request, "ataEnd"));
        return where.toString();
    }

    private void addDateRange(StringBuilder where, List<Object> params, String column, String start, String end) {
        if (!start.isEmpty() && !end.isEmpty()) {
            where.append(" AND ").append(column).append(" BETWEEN ? AND ? ");
            params.add(start);
            params.add(end);
        } else if (!start.isEmpty()) {
            where.append(" AND ").append(column).append(">=? ");
            params.add(start);
        } else if (!end.isEmpty()) {
            where.append(" AND ").append(column).append("<=? ");
            params.add(end);
        }
    }

    private int count(Connection conn, String where, List<Object> params) throws Exception {
        return number(Db.queryOne(conn,
                "SELECT COUNT(*) AS total FROM (SELECT o.out_order_uuid FROM out_order o LEFT JOIN out_order_detail od ON od.out_order_uuid=o.out_order_uuid WHERE 1=1 " + where + " GROUP BY o.out_order_uuid) t",
                params.toArray()).get("total")).intValue();
    }

    private Map<String, Object> requireOrder(Connection conn, String orderNo) throws Exception {
        Map<String, Object> order = Db.queryOne(conn, "SELECT * FROM out_order WHERE out_order_no=?", orderNo);
        if (order == null) {
            throw new IllegalArgumentException("出库单不存在");
        }
        return order;
    }

    private String nextNo(Connection conn) throws Exception {
        String day = LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
        String like = "E" + day + "%";
        Map<String, Object> row = Db.queryOne(conn, "SELECT out_order_no FROM out_order WHERE out_order_no LIKE ? ORDER BY out_order_no DESC LIMIT 1 FOR UPDATE", like);
        String lastNo = row == null ? "" : value(row.get("outOrderNo"));
        int serial = lastNo.length() < 3 ? 1 : Integer.parseInt(lastNo.substring(lastNo.length() - 3)) + 1;
        return "E" + day + String.format("%03d", serial);
    }

    private void log(Connection conn, String orderUuid, String type, String desc, String remark, String contact) throws Exception {
        log(conn, orderUuid, type, desc, remark, contact, null);
    }

    private void log(Connection conn, String orderUuid, String type, String desc, String remark, String contact, String operateDate) throws Exception {
        if (operateDate == null) {
            Db.update(conn,
                    "INSERT INTO out_order_log (out_order_log_uuid, out_order_uuid, operate_contact, operate_desc, remark, operate_time, operate_type, create_time) VALUES (?, ?, ?, ?, ?, NOW(), ?, NOW())",
                    UUID.randomUUID().toString(), orderUuid, contact, desc, remark, type);
        } else {
            Db.update(conn,
                    "INSERT INTO out_order_log (out_order_log_uuid, out_order_uuid, operate_contact, operate_desc, remark, operate_time, operate_type, create_time) VALUES (?, ?, ?, ?, ?, ?, ?, NOW())",
                    UUID.randomUUID().toString(), orderUuid, contact, desc, remark, operateDate, type);
        }
    }

    private void writeRow(Row row, Object... values) {
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(value(values[i]));
        }
    }

    private LocalDate requireDate(String value, String message) {
        LocalDate date = Params.date(value);
        if (date == null) {
            throw new IllegalArgumentException(message);
        }
        return date;
    }

    private String required(JSONObject body, String key, String message) {
        String value = Params.str(body, key);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private void validateContainerNo(String containerNo) {
        if (!containerNo.matches("[A-Z]{4}\\d{7}")) {
            throw new IllegalArgumentException("柜号格式应为4位大写字母加7位数字");
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

    private Number number(Object value) {
        return value instanceof Number ? (Number) value : 0;
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private double sum(List<Map<String, Object>> rows, String key) {
        return rows.stream().mapToDouble(row -> number(row.get(key)).doubleValue()).sum();
    }
}
