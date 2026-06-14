package hyshweb.attachment;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.servlet.http.Part;

import hyshweb.auth.UserSession;
import hyshweb.common.Db;

public class AttachmentService {
    private static final long MAX_SIZE = 2L * 1024L * 1024L;

    public List<Map<String, Object>> list(String ownerType, String ownerId) throws Exception {
        String column = ownerColumn(ownerType);
        try (Connection conn = Db.getConnection()) {
            if ("detail".equals(ownerType)) {
                return Db.query(conn,
                        "SELECT a.attachment_uuid, a.attachment_name, a.file_size, a.uploader, a.upload_time, d.product_name, d.marks FROM in_order_attachment a LEFT JOIN in_order_detail d ON a.in_order_detail_uuid=d.in_order_detail_uuid WHERE a." + column + "=? ORDER BY a.upload_time DESC",
                        ownerId);
            }
            return Db.query(conn,
                    "SELECT attachment_uuid, attachment_name, file_size, uploader, upload_time FROM in_order_attachment WHERE " + column + "=? ORDER BY upload_time DESC",
                    ownerId);
        }
    }

    public Map<String, Object> meta(String uuid) throws Exception {
        try (Connection conn = Db.getConnection()) {
            return Db.queryOne(conn,
                    "SELECT attachment_uuid, attachment_name, file_size, file_content FROM in_order_attachment WHERE attachment_uuid=?",
                    uuid);
        }
    }

    public void upload(String ownerType, String ownerId, Part file, UserSession user, boolean replaceOwner) throws Exception {
        if (file == null || file.getSize() == 0) {
            throw new IllegalArgumentException("附件不能为空");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new IllegalArgumentException("附件大小不能超过2MB");
        }
        String fileName = submittedFileName(file);
        if (!fileName.toLowerCase().matches(".*\\.(jpg|jpeg|png|gif|bmp)$")) {
            throw new IllegalArgumentException("仅支持图片附件");
        }
        String column = ownerColumn(ownerType);
        Db.tx(conn -> {
            if ("detail".equals(ownerType) && replaceOwner) {
                Map<String, Object> detail = Db.queryOne(conn, "SELECT rece_amount FROM in_order_detail WHERE in_order_detail_uuid=?", ownerId);
                if (detail == null) {
                    throw new IllegalArgumentException("入库明细不存在");
                }
                Object amount = detail.get("receAmount");
                if (!(amount instanceof Number) || ((Number) amount).doubleValue() <= 0) {
                    throw new IllegalArgumentException("请先录入实收金额再上传收款附件");
                }
            }
            if (replaceOwner) {
                Db.update(conn, "DELETE FROM in_order_attachment WHERE " + column + "=?", ownerId);
            }
            String uuid = UUID.randomUUID().toString();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO in_order_attachment (attachment_uuid, " + column + ", attachment_name, file_size, file_content, uploader, upload_time, control_word, warehouse_code, create_time) " +
                            "VALUES (?, ?, ?, ?, ?, ?, NOW(), '0000000000', '', NOW())")) {
                ps.setString(1, uuid);
                ps.setString(2, ownerId);
                ps.setString(3, fileName);
                ps.setLong(4, file.getSize());
                try (InputStream in = file.getInputStream()) {
                    ps.setBinaryStream(5, in, file.getSize());
                    ps.setString(6, user.getName());
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    public void delete(String uuid) throws Exception {
        try (Connection conn = Db.getConnection()) {
            Db.update(conn, "DELETE FROM in_order_attachment WHERE attachment_uuid=?", uuid);
        }
    }

    private String ownerColumn(String ownerType) {
        if ("detail".equals(ownerType)) {
            return "in_order_detail_uuid";
        }
        if ("cost".equals(ownerType)) {
            return "cost_order_uuid";
        }
        throw new IllegalArgumentException("附件归属类型无效");
    }

    private String submittedFileName(Part part) {
        String header = part.getHeader("content-disposition");
        if (header == null) {
            return "attachment";
        }
        for (String token : header.split(";")) {
            String trimmed = token.trim();
            if (trimmed.startsWith("filename=")) {
                return trimmed.substring("filename=".length()).replace("\"", "");
            }
        }
        return "attachment";
    }
}
