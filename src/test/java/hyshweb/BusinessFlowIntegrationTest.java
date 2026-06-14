package hyshweb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import hyshweb.attachment.AttachmentService;
import hyshweb.auth.UserSession;
import hyshweb.common.Db;
import hyshweb.common.Page;
import hyshweb.inbound.InboundService;
import hyshweb.masterdata.MasterDataService;
import hyshweb.outbound.OutboundService;
import hyshweb.tracking.TrackingService;

class BusinessFlowIntegrationTest {
    private final UserSession admin = new UserSession("admin", "管理员", "管理员", "S001");
    private final InboundService inbound = new InboundService();
    private final OutboundService outbound = new OutboundService();
    private final MasterDataService master = new MasterDataService();
    private final AttachmentService attachments = new AttachmentService();

    @BeforeEach
    void resetDatabase() throws Exception {
        try (Connection conn = Db.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS in_order_attachment");
            st.execute("DROP TABLE IF EXISTS out_order_log");
            st.execute("DROP TABLE IF EXISTS out_order_detail");
            st.execute("DROP TABLE IF EXISTS cost_order");
            st.execute("DROP TABLE IF EXISTS out_order");
            st.execute("DROP TABLE IF EXISTS in_order_log");
            st.execute("DROP TABLE IF EXISTS in_order_detail");
            st.execute("DROP TABLE IF EXISTS in_order");
            st.execute("DROP TABLE IF EXISTS sys_dict");
            st.execute("DROP TABLE IF EXISTS customer_info");
            st.execute("DROP TABLE IF EXISTS sys_user");
            st.execute("CREATE TABLE sys_user (id INT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(64), password VARCHAR(128), name VARCHAR(64), tel VARCHAR(64), usertype VARCHAR(64), status VARCHAR(32), creator VARCHAR(64), create_time TIMESTAMP, seal_code VARCHAR(64))");
            st.execute("CREATE TABLE customer_info (id INT AUTO_INCREMENT PRIMARY KEY, customer_code VARCHAR(64), customer_en_name VARCHAR(128), customer_cn_name VARCHAR(128), customer_type VARCHAR(32), status VARCHAR(32), creator VARCHAR(64), create_time TIMESTAMP, superior_code VARCHAR(64))");
            st.execute("CREATE TABLE sys_dict (dict_type VARCHAR(64), dict_code VARCHAR(64), dict_name VARCHAR(128), sort_order INT, status VARCHAR(16), remark VARCHAR(255))");
            st.execute("CREATE TABLE in_order (in_order_uuid VARCHAR(64) PRIMARY KEY, in_order_no VARCHAR(64), track_no VARCHAR(128), customer VARCHAR(128), sales VARCHAR(128), in_date DATE, status VARCHAR(32), creator VARCHAR(64), create_time TIMESTAMP, operator VARCHAR(64), operate_time TIMESTAMP, send_type VARCHAR(32), send_no VARCHAR(128))");
            st.execute("CREATE TABLE in_order_detail (in_order_detail_uuid VARCHAR(64) PRIMARY KEY, in_order_uuid VARCHAR(64), in_order_no VARCHAR(64), status VARCHAR(32), track_no VARCHAR(128), control_word VARCHAR(32), warehouse_code VARCHAR(32), creator VARCHAR(64), create_time TIMESTAMP, operator VARCHAR(64), operate_time TIMESTAMP, product_name VARCHAR(128), product_en_name VARCHAR(128), package_qty DOUBLE, package_type VARCHAR(64), weight DOUBLE, volume DOUBLE, qty DOUBLE, stock_package_qty DOUBLE, stock_qty DOUBLE, marks VARCHAR(255), cost_price DOUBLE, cost_currency VARCHAR(16), yf_unit VARCHAR(32), cost_amount DOUBLE, income_price DOUBLE, income_currency VARCHAR(16), ys_unit VARCHAR(32), income_amount DOUBLE, rece_price DOUBLE, rece_currency VARCHAR(16), rece_amount DOUBLE)");
            st.execute("CREATE TABLE in_order_log (in_order_log_uuid VARCHAR(64) PRIMARY KEY, in_order_uuid VARCHAR(64), in_order_detail_uuid VARCHAR(64), operate_type VARCHAR(32), operate_desc VARCHAR(128), operate_time TIMESTAMP, operate_contact VARCHAR(64), remark VARCHAR(255), atd DATE, eta DATE, ata DATE, control_word VARCHAR(32), warehouse_code VARCHAR(32), creator VARCHAR(64), create_time TIMESTAMP)");
            st.execute("CREATE TABLE out_order (out_order_uuid VARCHAR(64) PRIMARY KEY, out_order_no VARCHAR(64), status VARCHAR(32), so_no VARCHAR(128), loading_date DATE, container_no VARCHAR(128), car_plate VARCHAR(128), customs_broker VARCHAR(128), export_port VARCHAR(128), warehouse_code VARCHAR(32), creator VARCHAR(64), create_time TIMESTAMP, atd_time DATE, eta_time DATE, ata_time DATE, wljd VARCHAR(128))");
            st.execute("CREATE TABLE out_order_detail (out_order_detail_uuid VARCHAR(64) PRIMARY KEY, out_order_uuid VARCHAR(64), out_order_no VARCHAR(64), in_order_detail_uuid VARCHAR(64), track_no VARCHAR(128), product_name VARCHAR(128), marks VARCHAR(255), out_package_qty DOUBLE, package_type VARCHAR(64), weight DOUBLE, volume DOUBLE, out_qty DOUBLE, creator VARCHAR(64), create_time TIMESTAMP, status VARCHAR(32), sf_price DOUBLE, sf_currency VARCHAR(16), sf_amount DOUBLE)");
            st.execute("CREATE TABLE out_order_log (out_order_log_uuid VARCHAR(64) PRIMARY KEY, out_order_uuid VARCHAR(64), operate_contact VARCHAR(64), operate_desc VARCHAR(128), remark VARCHAR(255), operate_time TIMESTAMP, operate_type VARCHAR(32), create_time TIMESTAMP)");
            st.execute("CREATE TABLE cost_order (cost_order_uuid VARCHAR(64) PRIMARY KEY, out_order_uuid VARCHAR(64), cost_name VARCHAR(128), amount DOUBLE, remark VARCHAR(255))");
            st.execute("CREATE TABLE in_order_attachment (attachment_uuid VARCHAR(64) PRIMARY KEY, in_order_detail_uuid VARCHAR(64), cost_order_uuid VARCHAR(64), attachment_name VARCHAR(255), file_size BIGINT, file_content BLOB, uploader VARCHAR(64), upload_time TIMESTAMP, control_word VARCHAR(32), warehouse_code VARCHAR(32), create_time TIMESTAMP)");
        }
    }

    @Test
    void inboundLifecycleWritesStockDeliveryCollectionAndBlocksUnsafeCancel() throws Exception {
        String orderNo = createInboundOrder();
        inbound.submit(orderNo, admin);
        String detailUuid = single("SELECT in_order_detail_uuid FROM in_order_detail").get("inOrderDetailUuid").toString();

        inbound.finishDelivery(orderNo, json("sendType", "派送", "sendNo", "D001"), admin);
        inbound.finishCollection(json("detailUuid", detailUuid, "recePrice", 3, "receCurrency", "USD", "receAmount", 30), admin);

        Map<String, Object> detail = single("SELECT stock_package_qty, stock_qty, rece_amount FROM in_order_detail WHERE in_order_detail_uuid='" + detailUuid + "'");
        assertEquals(10.0, ((Number) detail.get("stockPackageQty")).doubleValue());
        assertEquals(100.0, ((Number) detail.get("stockQty")).doubleValue());
        assertEquals(30.0, ((Number) detail.get("receAmount")).doubleValue());
        assertEquals(4, count("SELECT COUNT(*) AS total FROM in_order_log"));

        String outNo = createOutboundOrder();
        outbound.addDetails(outNo, array(json("inOrderDetailUuid", detailUuid, "outPackageQty", 2, "outQty", 20)), admin);
        assertThrows(IllegalArgumentException.class, () -> inbound.cancel(orderNo, admin));
    }

    @Test
    void outboundFlowGuardsStockRollsBackSyncsNodeAndExportsWorkbooks() throws Exception {
        String inOrderNo = createInboundOrder();
        inbound.submit(inOrderNo, admin);
        String detailUuid = single("SELECT in_order_detail_uuid FROM in_order_detail").get("inOrderDetailUuid").toString();
        String outNo = createOutboundOrder();

        outbound.addDetails(outNo, array(json("inOrderDetailUuid", detailUuid, "outPackageQty", 4, "outQty", 40)), admin);
        assertStock(detailUuid, 6, 60);

        String outDetailUuid = single("SELECT out_order_detail_uuid FROM out_order_detail").get("outOrderDetailUuid").toString();
        outbound.updateDetail(json("outOrderDetailUuid", outDetailUuid, "outPackageQty", 5, "outQty", 50), admin);
        assertStock(detailUuid, 5, 50);

        assertThrows(IllegalArgumentException.class,
                () -> outbound.updateDetail(json("outOrderDetailUuid", outDetailUuid, "outPackageQty", 99, "outQty", 50), admin));
        assertStock(detailUuid, 5, 50);

        outbound.sendNode(outNo, json("node", "启运", "operateDate", LocalDate.now().toString(), "atd", LocalDate.now().toString(), "eta", LocalDate.now().toString()), admin);
        assertEquals("启运", single("SELECT wljd FROM out_order").get("wljd"));
        assertEquals(2, count("SELECT COUNT(*) AS total FROM in_order_log WHERE operate_type='send'"));

        outbound.saveReceivable(json("mode", "receivable", "outOrderDetailUuid", outDetailUuid, "recePrice", 8, "receCurrency", "USD", "receAmount", 80), admin);
        outbound.saveReceivable(json("mode", "payable", "outOrderDetailUuid", outDetailUuid, "sfPrice", 6, "sfCurrency", "USD", "sfAmount", 60), admin);
        Map<String, Object> money = single("SELECT iod.rece_amount, ood.sf_amount FROM in_order_detail iod JOIN out_order_detail ood ON ood.in_order_detail_uuid=iod.in_order_detail_uuid");
        assertEquals(80.0, ((Number) money.get("receAmount")).doubleValue());
        assertEquals(60.0, ((Number) money.get("sfAmount")).doubleValue());

        assertTrue(outbound.exportDetails(outNo).length > 1000);
        assertTrue(outbound.exportAccounts(outNo).length > 1000);

        outbound.deleteDetail(outDetailUuid, admin);
        assertStock(detailUuid, 10, 100);
    }

    @Test
    void inboundValidationCalculationAttachmentCleanupAndLogsEndpointWork() throws Exception {
        JSONObject invalid = json("trackNo", "T-BAD", "customer", "客户A", "sales", "销售A", "inDate", LocalDate.now().toString(), "sendType", "自提");
        invalid.put("details", array(json("trackNo", "T-BAD", "packageQty", 1, "packageType", "箱", "weight", 1, "volume", 1, "qty", 1)));
        assertThrows(IllegalArgumentException.class, () -> inbound.save(invalid, admin));

        String orderNo = createInboundOrder();
        String detailUuid = single("SELECT in_order_detail_uuid FROM in_order_detail").get("inOrderDetailUuid").toString();
        Map<String, Object> amount = single("SELECT cost_amount, income_amount FROM in_order_detail WHERE in_order_detail_uuid='" + detailUuid + "'");
        assertEquals(100.0, ((Number) amount.get("costAmount")).doubleValue());
        assertEquals(200.0, ((Number) amount.get("incomeAmount")).doubleValue());

        exec("INSERT INTO in_order_attachment (attachment_uuid, in_order_detail_uuid, attachment_name, file_size, file_content, uploader, upload_time, control_word, warehouse_code, create_time) VALUES ('in-a1','" + detailUuid + "','a.png',1,X'01','u',NOW(),'0000000000','',NOW())");
        JSONObject update = json("inOrderNo", orderNo, "trackNo", "T002", "customer", "客户A", "sales", "销售A", "inDate", LocalDate.now().toString(), "sendType", "自提");
        update.put("details", array(json("trackNo", "T002", "productName", "新品", "productEnName", "New", "packageQty", 2, "packageType", "箱", "weight", 2, "volume", 2, "qty", 2, "marks", "N", "costPrice", 1, "costCurrency", "USD", "yfUnit", "按重量", "incomePrice", 1, "incomeCurrency", "USD", "ysUnit", "按体积")));
        inbound.save(update, admin);
        assertEquals(0, count("SELECT COUNT(*) AS total FROM in_order_attachment WHERE attachment_uuid='in-a1'"));

        inbound.submit(orderNo, admin);
        assertFalse(inbound.logListByOrderNo(orderNo).isEmpty());
    }

    @Test
    void inboundEditIsBlockedWhenOutboundAlreadyOccupiesDetail() throws Exception {
        String orderNo = createInboundOrder();
        inbound.submit(orderNo, admin);
        String detailUuid = single("SELECT in_order_detail_uuid FROM in_order_detail").get("inOrderDetailUuid").toString();
        String outNo = createOutboundOrder();
        outbound.addDetails(outNo, array(json("inOrderDetailUuid", detailUuid, "outPackageQty", 2, "outQty", 20)), admin);

        JSONObject update = json("inOrderNo", orderNo, "trackNo", "T-EDIT", "customer", "客户A", "sales", "销售A", "inDate", LocalDate.now().toString(), "sendType", "自提");
        update.put("details", array(json("trackNo", "T-EDIT", "productName", "替换品", "productEnName", "Changed", "packageQty", 1, "packageType", "箱", "weight", 1, "volume", 1, "qty", 1, "marks", "C", "costPrice", 1, "costCurrency", "USD", "yfUnit", "按数量", "incomePrice", 2, "incomeCurrency", "USD", "ysUnit", "按数量")));

        assertThrows(IllegalArgumentException.class, () -> inbound.save(update, admin));
        assertEquals(1, count("SELECT COUNT(*) AS total FROM in_order_detail WHERE in_order_no='" + orderNo + "'"));
        assertStock(detailUuid, 8, 80);
    }

    @Test
    void inboundListFiltersByLogisticsSendNoAndCollectionStatus() throws Exception {
        String matched = createInboundOrder("T-FILTER-1", "客户A", "销售A", LocalDate.now().toString());
        inbound.submit(matched, admin);
        String matchedDetail = single("SELECT in_order_detail_uuid FROM in_order_detail WHERE in_order_no='" + matched + "'").get("inOrderDetailUuid").toString();
        inbound.finishDelivery(matched, json("sendType", "派送", "sendNo", "D001"), admin);
        exec("INSERT INTO in_order_attachment (attachment_uuid, in_order_detail_uuid, attachment_name, file_size, file_content, uploader, upload_time, control_word, warehouse_code, create_time) VALUES ('paid-a1','" + matchedDetail + "','paid.png',1,X'01','u',NOW(),'0000000000','',NOW())");

        String other = createInboundOrder("T-FILTER-2", "客户B", "销售B", LocalDate.now().toString());
        inbound.submit(other, admin);
        inbound.finishDelivery(other, json("sendType", "派送", "sendNo", "D002"), admin);
        String amountOnly = createInboundOrder("T-FILTER-3", "客户C", "销售C", LocalDate.now().toString());
        inbound.submit(amountOnly, admin);
        String amountOnlyDetail = single("SELECT in_order_detail_uuid FROM in_order_detail WHERE in_order_no='" + amountOnly + "'").get("inOrderDetailUuid").toString();
        inbound.finishDelivery(amountOnly, json("sendType", "派送", "sendNo", "D003"), admin);
        inbound.finishCollection(json("detailUuid", amountOnlyDetail, "recePrice", 3, "receCurrency", "USD", "receAmount", 30), admin);

        Page page = inbound.list(request("logisticsNode", "完成派送", "sendNo", "D001", "collectionStatus", "已收款"));

        assertEquals(1, page.getTotal());
        assertEquals(matched, page.getItems().getFirst().get("inOrderNo"));
        assertEquals(0, inbound.list(request("sendNo", "D003", "collectionStatus", "已收款")).getTotal());
    }

    @Test
    void inboundStateMachineCurrentNodeFilterTemplateAndInventoryRemarkMatchDesign() throws Exception {
        String orderNo = createInboundOrder();
        inbound.submit(orderNo, admin);
        assertThrows(IllegalArgumentException.class, () -> inbound.submit(orderNo, admin));
        inbound.cancel(orderNo, admin);
        assertThrows(IllegalArgumentException.class, () -> inbound.cancel(orderNo, admin));

        inbound.submit(orderNo, admin);
        inbound.finishDelivery(orderNo, json("sendType", "派送", "sendNo", "D009"), admin);
        assertEquals(0, inbound.list(request("logisticsNode", "入库")).getTotal());
        assertEquals(1, inbound.list(request("logisticsNode", "完成派送")).getTotal());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(inbound.template()))) {
            StringBuilder headerText = new StringBuilder();
            workbook.getSheetAt(0).getRow(0).forEach(cell -> headerText.append(cell.getStringCellValue()).append(","));
            assertTrue(headerText.toString().contains("应付单价"));
            assertTrue(headerText.toString().contains("应收金额"));
        }

        String detailUuid = single("SELECT in_order_detail_uuid FROM in_order_detail WHERE in_order_no='" + orderNo + "'").get("inOrderDetailUuid").toString();
        inbound.adjustInventory(json("detailUuid", detailUuid, "packageQty", 11, "stockPackageQty", 11, "qty", 110, "stockQty", 110, "weight", 13, "volume", 2), admin);
        String remark = single("SELECT remark FROM in_order_log WHERE operate_desc='修改库存'").get("remark").toString();
        assertTrue(remark.contains("packageQty"));
        assertTrue(remark.contains("10.0 -> 11.0"));
    }

    @Test
    void outboundListFiltersByLoadingAtdEtaAndAtaDates() throws Exception {
        String matched = createOutboundOrder();
        String other = createOutboundOrder();
        exec("UPDATE out_order SET loading_date='2026-01-10', atd_time='2026-01-11', eta_time='2026-01-12', ata_time='2026-01-13' WHERE out_order_no='" + matched + "'");
        exec("UPDATE out_order SET loading_date='2026-02-10', atd_time='2026-02-11', eta_time='2026-02-12', ata_time='2026-02-13' WHERE out_order_no='" + other + "'");

        Page page = outbound.list(request(
                "loadingStart", "2026-01-01", "loadingEnd", "2026-01-31",
                "atdStart", "2026-01-11", "atdEnd", "2026-01-11",
                "etaStart", "2026-01-12", "etaEnd", "2026-01-12",
                "ataStart", "2026-01-13", "ataEnd", "2026-01-13"));

        assertEquals(1, page.getTotal());
        assertEquals(matched, page.getItems().getFirst().get("outOrderNo"));
    }

    @Test
    void outboundStockFiltersByCustomerSalesAndInboundDate() throws Exception {
        String matched = createInboundOrder("T-STOCK-1", "客户A", "销售A", "2026-01-10");
        String other = createInboundOrder("T-STOCK-2", "客户B", "销售B", "2026-02-10");
        inbound.submit(matched, admin);
        inbound.submit(other, admin);

        List<Map<String, Object>> rows = outbound.stock(request("customer", "客户A", "sales", "销售A", "inStartDate", "2026-01-01", "inEndDate", "2026-01-31"));

        assertEquals(1, rows.size());
        assertEquals("T-STOCK-1", rows.getFirst().get("trackNo"));
    }

    @Test
    void receivableEmptyAmountsDefaultToExpectedReceivableAndPayable() throws Exception {
        String inNo = createInboundOrder();
        inbound.submit(inNo, admin);
        String detailUuid = single("SELECT in_order_detail_uuid FROM in_order_detail").get("inOrderDetailUuid").toString();
        String outNo = createOutboundOrder();
        outbound.addDetails(outNo, array(json("inOrderDetailUuid", detailUuid, "outPackageQty", 1, "outQty", 10)), admin);
        outbound.sendNode(outNo, json("node", "出库", "operateDate", LocalDate.now().toString()), admin);
        String outDetailUuid = single("SELECT out_order_detail_uuid FROM out_order_detail").get("outOrderDetailUuid").toString();

        outbound.saveReceivable(json("mode", "receivable", "outOrderDetailUuid", outDetailUuid), admin);
        outbound.saveReceivable(json("mode", "payable", "outOrderDetailUuid", outDetailUuid), admin);

        Map<String, Object> money = single("SELECT iod.rece_amount, ood.sf_amount FROM in_order_detail iod JOIN out_order_detail ood ON ood.in_order_detail_uuid=iod.in_order_detail_uuid");
        assertEquals(200.0, ((Number) money.get("receAmount")).doubleValue());
        assertEquals(100.0, ((Number) money.get("sfAmount")).doubleValue());
    }

    @Test
    void costsCanBeEditedAndInboundCanListRelatedOutbounds() throws Exception {
        String inNo = createInboundOrder();
        inbound.submit(inNo, admin);
        String detailUuid = single("SELECT in_order_detail_uuid FROM in_order_detail").get("inOrderDetailUuid").toString();
        String outNo = createOutboundOrder();
        outbound.addDetails(outNo, array(json("inOrderDetailUuid", detailUuid, "outPackageQty", 1, "outQty", 10)), admin);
        outbound.sendNode(outNo, json("node", "出库", "operateDate", LocalDate.now().toString()), admin);

        String costUuid = outbound.addCost(outNo, json("costName", "拖车费", "amount", 20, "remark", "old"), admin);
        outbound.updateCost(costUuid, json("costName", "港杂费", "amount", 30, "remark", "new"), admin);

        Map<String, Object> cost = single("SELECT cost_name, amount, remark FROM cost_order WHERE cost_order_uuid='" + costUuid + "'");
        assertEquals("港杂费", cost.get("costName"));
        assertEquals(30.0, ((Number) cost.get("amount")).doubleValue());
        assertEquals("new", cost.get("remark"));
        assertEquals(outNo, inbound.relatedOutbounds(inNo).getFirst().get("outOrderNo"));

        List<Map<String, Object>> nodes = inbound.logisticsNodes(inNo);
        assertTrue(nodes.stream().anyMatch(row -> "入库".equals(row.get("operateDesc"))));
        assertTrue(nodes.stream().anyMatch(row -> "出库".equals(row.get("operateDesc"))));
    }

    @Test
    void outboundWarehouseIsFixedAndEnabledDictsOnlyFeedCostSelector() throws Exception {
        String outNo = outbound.save(json("soNo", "SO-FIX", "loadingDate", LocalDate.now().toString(), "containerNo", "WXYZ1234567", "warehouseCode", "BAD"), admin);
        assertEquals("WH001", single("SELECT warehouse_code FROM out_order WHERE out_order_no='" + outNo + "'").get("warehouseCode"));

        master.saveDict(json("dictType", "cost_item", "dictCode", "ON", "dictName", "启用费", "sortOrder", 1, "status", "1", "remark", ""));
        master.saveDict(json("dictType", "cost_item", "dictCode", "OFF", "dictName", "禁用费", "sortOrder", 2, "status", "0", "remark", ""));

        List<Map<String, Object>> dicts = master.enabledDicts("cost_item");
        assertEquals(1, dicts.size());
        assertEquals("启用费", dicts.getFirst().get("dictName"));
    }

    @Test
    void frontendExposesRequiredControlsForMigratedBehaviors() throws Exception {
        String app = Files.readString(Path.of("src/main/webapp/assets/app.js"));
        String tracking = Files.readString(Path.of("src/main/webapp/tracking.html"));
        String index = Files.readString(Path.of("src/main/webapp/index.html"));

        assertTrue(app.contains("name=\"costPrice\""));
        assertTrue(app.contains("name=\"costCurrency\""));
        assertTrue(app.contains("name=\"yfUnit\""));
        assertTrue(app.contains("name=\"costAmount\""));
        assertTrue(app.contains("name=\"incomePrice\""));
        assertTrue(app.contains("name=\"incomeCurrency\""));
        assertTrue(app.contains("name=\"ysUnit\""));
        assertTrue(app.contains("name=\"incomeAmount\""));
        assertTrue(app.contains("tr.querySelectorAll(\"input, select\")"));
        assertTrue(app.contains("function pager("));
        assertTrue(app.contains("缺少金额"));
        assertTrue(app.contains("collectionStatus"));
        assertTrue(app.contains("openRelatedOutbounds"));
        assertTrue(app.contains("openInboundNodes"));
        assertTrue(app.contains("openCostEdit"));
        assertTrue(app.contains("api/master/dicts/enabled?type=cost_item"));
        assertTrue(app.contains("api/master/dicts/enabled?type=package_type"));
        assertTrue(app.contains("api/master/dicts/enabled?type=customs_broker"));
        assertTrue(app.contains("api/master/dicts/enabled?type=export_port"));
        assertTrue(app.contains("syncAmountFields"));
        assertTrue(app.contains("max=\"${esc(row.stockPackageQty)}\""));
        assertTrue(app.contains("max=\"${esc(row.stockQty)}\""));
        assertTrue(app.contains("openAttachment('detail','${esc(d.inOrderDetailUuid)}',false)"));
        assertTrue(app.contains("renderUsers"));
        assertTrue(app.contains("loadCustomers(qs(form))"));
        assertTrue(app.contains("loadUsers(qs(form))"));
        assertTrue(app.contains("name=\"customsBroker\""));
        assertTrue(app.contains("name=\"exportPort\""));
        assertTrue(app.contains("name=\"node\""));
        assertTrue(index.contains("data-view=\"users\""));
        assertTrue(tracking.contains("name=\"inOrderNo\""));
        assertTrue(tracking.contains("languageMode"));
        assertTrue(tracking.contains("trackPage"));
        assertTrue(tracking.contains("loadTrackingOrders"));
    }

    @Test
    void collectionAttachmentRequiresReceivableAmountBeforeReplacingDetailAttachments() throws Exception {
        String inNo = createInboundOrder();
        inbound.submit(inNo, admin);
        String detailUuid = single("SELECT in_order_detail_uuid FROM in_order_detail").get("inOrderDetailUuid").toString();

        assertThrows(IllegalArgumentException.class,
                () -> attachments.upload("detail", detailUuid, imagePart("paid.png"), admin, true));

        inbound.finishCollection(json("detailUuid", detailUuid, "recePrice", 1, "receCurrency", "USD", "receAmount", 10), admin);
        attachments.upload("detail", detailUuid, imagePart("paid.png"), admin, true);

        assertEquals(1, count("SELECT COUNT(*) AS total FROM in_order_attachment WHERE in_order_detail_uuid='" + detailUuid + "'"));
    }

    @Test
    void outboundRejectsZeroAmountStockAndAtaBeforeEta() throws Exception {
        String orderNo = createInboundOrder();
        inbound.submit(orderNo, admin);
        String detailUuid = single("SELECT in_order_detail_uuid FROM in_order_detail").get("inOrderDetailUuid").toString();
        exec("UPDATE in_order_detail SET cost_amount=0, income_amount=0 WHERE in_order_detail_uuid='" + detailUuid + "'");
        String outNo = createOutboundOrder();
        assertThrows(IllegalArgumentException.class,
                () -> outbound.addDetails(outNo, array(json("inOrderDetailUuid", detailUuid, "outPackageQty", 1, "outQty", 1)), admin));

        exec("UPDATE in_order_detail SET cost_amount=10 WHERE in_order_detail_uuid='" + detailUuid + "'");
        outbound.addDetails(outNo, array(json("inOrderDetailUuid", detailUuid, "outPackageQty", 1, "outQty", 1)), admin);
        String today = LocalDate.now().toString();
        outbound.sendNode(outNo, json("node", "启运", "operateDate", today, "atd", today, "eta", today), admin);
        exec("UPDATE out_order SET eta_time='" + LocalDate.now().plusDays(1) + "' WHERE out_order_no='" + outNo + "'");
        assertThrows(IllegalArgumentException.class,
                () -> outbound.sendNode(outNo, json("node", "到港", "operateDate", today, "ata", today), admin));
        assertFalse(outbound.logs(outNo).isEmpty());
    }

    @Test
    void trackingDetailIncludesOutboundNodes() throws Exception {
        seedSales();
        String orderNo = createInboundOrder();
        inbound.submit(orderNo, admin);
        String detailUuid = single("SELECT in_order_detail_uuid FROM in_order_detail").get("inOrderDetailUuid").toString();
        String outNo = createOutboundOrder();
        outbound.addDetails(outNo, array(json("inOrderDetailUuid", detailUuid, "outPackageQty", 1, "outQty", 1)), admin);
        outbound.sendNode(outNo, json("node", "出库", "operateDate", LocalDate.now().toString()), admin);

        TrackingService tracking = new TrackingService();
        Map<String, Object> detail = tracking.detail("T001", new UserSession("sale", "销售", "员工", "S001"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) detail.get("nodes");
        assertTrue(nodes.stream().anyMatch(row -> "入库".equals(row.get("operateDesc"))));
        assertTrue(nodes.stream().anyMatch(row -> "出库".equals(row.get("operateDesc"))));
    }

    @Test
    void costDeletionCascadesAttachmentsAndTrackingSalesUsesSealHierarchy() throws Exception {
        seedSales();
        TrackingService tracking = new TrackingService();
        List<Map<String, Object>> sales = tracking.sales(new UserSession("sale", "销售", "员工", "S001"));
        assertEquals(2, sales.size());
        assertTrue(sales.stream().anyMatch(row -> "S001".equals(row.get("customerCode"))));
        assertTrue(sales.stream().anyMatch(row -> "S002".equals(row.get("customerCode"))));

        String inNo = createInboundOrder();
        inbound.submit(inNo, admin);
        String detailUuid = single("SELECT in_order_detail_uuid FROM in_order_detail").get("inOrderDetailUuid").toString();
        String outNo = createOutboundOrder();
        outbound.addDetails(outNo, array(json("inOrderDetailUuid", detailUuid, "outPackageQty", 1, "outQty", 10)), admin);
        String costUuid = outbound.addCost(outNo, json("costName", "拖车费", "amount", 20, "remark", "x"), admin);
        exec("INSERT INTO in_order_attachment (attachment_uuid, cost_order_uuid, attachment_name, file_size, file_content, uploader, upload_time, control_word, warehouse_code, create_time) VALUES ('a1','" + costUuid + "','a.png',1,X'01','u',NOW(),'0000000000','',NOW())");

        outbound.deleteCost(costUuid, admin);
        assertEquals(0, count("SELECT COUNT(*) AS total FROM cost_order"));
        assertEquals(0, count("SELECT COUNT(*) AS total FROM in_order_attachment"));
    }

    @Test
    void masterDataPreservesCompatibilityFieldsAndSupportsStatusesAndDictDeletion() throws Exception {
        master.saveCustomer(json("customerCode", "C001", "customerCnName", "客户一", "customerEnName", "Customer 1", "customerType", "Customer", "superiorCode", "S001"), admin);
        master.setCustomerStatus("C001", "禁用");
        Map<String, Object> customer = single("SELECT customer_code, status, superior_code FROM customer_info WHERE customer_code='C001'");
        assertEquals("禁用", customer.get("status"));
        assertEquals("S001", customer.get("superiorCode"));

        master.saveUser(json("username", "u1", "password", "plain", "name", "用户一", "tel", "1", "userType", "员工", "status", "启用", "sealCode", "S001"), admin);
        assertEquals("plain", single("SELECT password FROM sys_user WHERE username='u1'").get("password"));
        master.setUserStatus("u1", "禁用");
        assertEquals("禁用", single("SELECT status FROM sys_user WHERE username='u1'").get("status"));

        master.saveDict(json("dictType", "package_type", "dictCode", "BOX", "dictName", "箱", "sortOrder", 1, "status", "1", "remark", ""));
        master.setDictStatus("package_type", "BOX", "0");
        assertEquals("0", single("SELECT status FROM sys_dict WHERE dict_type='package_type' AND dict_code='BOX'").get("status"));
        master.deleteDict("package_type", "BOX");
        assertEquals(0, count("SELECT COUNT(*) AS total FROM sys_dict WHERE dict_type='package_type' AND dict_code='BOX'"));
    }

    private String createInboundOrder() throws Exception {
        return createInboundOrder("T001", "客户A", "销售A", LocalDate.now().toString());
    }

    private String createInboundOrder(String trackNo, String customer, String sales, String inDate) throws Exception {
        JSONObject body = json("trackNo", trackNo, "customer", customer, "sales", sales, "inDate", inDate, "sendType", "自提");
        body.put("details", array(json("trackNo", trackNo, "productName", "配件", "productEnName", "Parts", "packageQty", 10, "packageType", "箱", "weight", 12.5, "volume", 1.5, "qty", 100, "marks", "M", "costPrice", 1, "costCurrency", "USD", "yfUnit", "按数量", "costAmount", 100, "incomePrice", 2, "incomeCurrency", "USD", "ysUnit", "按数量", "incomeAmount", 200)));
        return inbound.save(body, admin);
    }

    private String createOutboundOrder() throws Exception {
        return outbound.save(json("soNo", "SO001", "loadingDate", LocalDate.now().toString(), "containerNo", "ABCD1234567", "carPlate", "沪A1", "customsBroker", "报关行", "exportPort", "上海", "warehouseCode", "WH001"), admin);
    }

    private void seedSales() throws Exception {
        exec("INSERT INTO customer_info (customer_code, customer_en_name, customer_cn_name, customer_type, status, creator, create_time, superior_code) VALUES ('S001','Sale 1','销售A','Sale','启用','u',NOW(),NULL)");
        exec("INSERT INTO customer_info (customer_code, customer_en_name, customer_cn_name, customer_type, status, creator, create_time, superior_code) VALUES ('S002','Sale 2','销售B','Sale','启用','u',NOW(),'S001')");
        exec("INSERT INTO customer_info (customer_code, customer_en_name, customer_cn_name, customer_type, status, creator, create_time, superior_code) VALUES ('S003','Sale 3','销售C','Sale','启用','u',NOW(),'OTHER')");
    }

    private void assertStock(String detailUuid, double packages, double qty) throws Exception {
        Map<String, Object> row = single("SELECT stock_package_qty, stock_qty FROM in_order_detail WHERE in_order_detail_uuid='" + detailUuid + "'");
        assertEquals(packages, ((Number) row.get("stockPackageQty")).doubleValue());
        assertEquals(qty, ((Number) row.get("stockQty")).doubleValue());
    }

    private JSONObject json(Object... kv) {
        JSONObject object = new JSONObject();
        for (int i = 0; i < kv.length; i += 2) {
            object.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return object;
    }

    private JSONArray array(JSONObject... rows) {
        JSONArray array = new JSONArray();
        for (JSONObject row : rows) {
            array.add(row);
        }
        return array;
    }

    private Part imagePart(String fileName) {
        byte[] content = new byte[]{1, 2, 3};
        return (Part) Proxy.newProxyInstance(
                Part.class.getClassLoader(),
                new Class<?>[]{Part.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getSize" -> (long) content.length;
                    case "getInputStream" -> new ByteArrayInputStream(content);
                    case "getHeader" -> "form-data; name=\"file\"; filename=\"" + fileName + "\"";
                    case "getSubmittedFileName" -> fileName;
                    case "getName" -> "file";
                    default -> null;
                });
    }

    private HttpServletRequest request(Object... kv) {
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            params.put(String.valueOf(kv[i]), String.valueOf(kv[i + 1]));
        }
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> {
                    if ("getParameter".equals(method.getName())) {
                        return params.get(String.valueOf(args[0]));
                    }
                    if ("getParameterMap".equals(method.getName())) {
                        return Map.copyOf(params);
                    }
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) {
                        return false;
                    }
                    if (returnType == int.class) {
                        return 0;
                    }
                    return null;
                });
    }

    private Map<String, Object> single(String sql) throws Exception {
        try (Connection conn = Db.getConnection()) {
            Map<String, Object> row = Db.queryOne(conn, sql);
            assertFalse(row == null || row.isEmpty());
            return row;
        }
    }

    private int count(String sql) throws Exception {
        return ((Number) single(sql).get("total")).intValue();
    }

    private void exec(String sql) throws Exception {
        try (Connection conn = Db.getConnection(); Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
