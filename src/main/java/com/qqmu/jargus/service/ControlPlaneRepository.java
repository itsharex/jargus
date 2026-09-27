package com.qqmu.jargus.service;

import com.qqmu.jargus.datasource.SqlDialectAdapter.Family;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 控制面仓库：database_config 表的唯一真源访问层。
 * 只走原生 JDBC 挂在启动 H2 的控制池上（@Qualifier 精确注入，绝不经过动态路由）——
 * 无论当前活库是哪个，控制面数据永远可读可写：
 * - 启动恢复时读 is_active 行决定挂哪个库；
 * - 切换时整表复制到目标库（copyAllTo）并翻转控制面标记（setActive）；
 * - 活库非 H2 时 CRUD 写穿镜像（upsert/deleteById）。
 * 首次启动表可能尚不存在，所有读方法容错返回空。
 */
@Slf4j
@Service
public class ControlPlaneRepository {

    /** database_config 全列（与 schema-*.sql 一致，显式列序保证复制稳定） */
    private static final String[] COLUMNS = {
            "id", "name", "db_type", "driver_class", "jdbc_url", "username", "password",
            "max_pool_size", "min_idle", "connection_timeout", "dialect", "driver_jar_path",
            "connection_properties", "is_custom", "is_active", "schema_version",
            "is_initialized", "sort_order", "created_at", "updated_at"
    };

    private static final String SELECT_ALL_COLUMNS = String.join(", ", COLUMNS);

    private final DataSource controlPlane;

    public ControlPlaneRepository(@Qualifier("controlPlaneDataSource") DataSource controlPlane) {
        this.controlPlane = controlPlane;
    }

    /** 当前控制面标记的激活行；表不存在或无激活行返回 null */
    public Map<String, Object> findActive() {
        return queryOne("SELECT " + SELECT_ALL_COLUMNS + " FROM database_config WHERE is_active = ?", true);
    }

    public Map<String, Object> findById(long id) {
        return queryOne("SELECT " + SELECT_ALL_COLUMNS + " FROM database_config WHERE id = ?", id);
    }

    /** 内置 H2 种子行（自愈回退目标）；表不存在返回 null */
    public Map<String, Object> findBuiltInH2Row() {
        return queryOne("SELECT " + SELECT_ALL_COLUMNS
                + " FROM database_config WHERE db_type = 'H2' ORDER BY id", null);
    }

    public List<Map<String, Object>> findAll() {
        List<Map<String, Object>> rows = new ArrayList<>();
        String sql = "SELECT " + SELECT_ALL_COLUMNS + " FROM database_config ORDER BY sort_order, id";
        try (Connection c = controlPlane.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(toRow(rs));
            }
        } catch (SQLException e) {
            log.debug("控制面读取失败（首次启动表可能不存在）: {}", e.getMessage());
        }
        return rows;
    }

    /** 控制面激活标记翻到目标行（先全置 false 再置 true） */
    public void setActive(long id) {
        try (Connection c = controlPlane.getConnection()) {
            try (Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE database_config SET is_active = FALSE");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE database_config SET is_active = TRUE WHERE id = ?")) {
                ps.setObject(1, id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("控制面激活标记更新失败: " + e.getMessage(), e);
        }
    }

    /** 控制面中当前激活的是否就是启动内置 H2 库（决定 CRUD 是否需要写穿镜像） */
    public boolean isDefaultOnControl(String bootJdbcUrl) {
        Map<String, Object> active = findActive();
        if (active == null) {
            return true;
        }
        String dbType = str(active, "db_type");
        String url = str(active, "jdbc_url");
        return "H2".equalsIgnoreCase(dbType)
                && (url == null || url.equalsIgnoreCase(bootJdbcUrl));
    }

    /**
     * 写穿镜像：把一行配置按显式 id upsert 进控制面（活库非 H2 时 CRUD 调用）。
     * row 的 key 为小写列名；密码等敏感值以密文原样存储。
     */
    public void upsert(Map<String, Object> row) {
        Object id = row.get("id");
        if (id == null) {
            log.warn("控制面 upsert 缺少 id，跳过");
            return;
        }
        boolean exists = findById(((Number) id).longValue()) != null;
        String sql;
        if (exists) {
            StringBuilder sb = new StringBuilder("UPDATE database_config SET ");
            for (int i = 1; i < COLUMNS.length; i++) {
                if (i > 1) {
                    sb.append(", ");
                }
                sb.append(COLUMNS[i]).append(" = ?");
            }
            sb.append(" WHERE id = ?");
            sql = sb.toString();
        } else {
            sql = "INSERT INTO database_config (" + SELECT_ALL_COLUMNS + ") VALUES ("
                    + String.join(", ", java.util.Collections.nCopies(COLUMNS.length, "?")) + ")";
        }
        try (Connection c = controlPlane.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            int idx = 1;
            if (exists) {
                for (int i = 1; i < COLUMNS.length; i++) {
                    ps.setObject(idx++, row.get(COLUMNS[i]));
                }
                ps.setObject(idx, id);
            } else {
                for (String col : COLUMNS) {
                    ps.setObject(idx++, row.get(col));
                }
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("控制面 upsert 失败 id={}: {}", id, e.getMessage());
        }
    }

    /** 写穿镜像：从控制面删除一行 */
    public void deleteById(long id) {
        try (Connection c = controlPlane.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM database_config WHERE id = ?")) {
            ps.setObject(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("控制面删除失败 id={}: {}", id, e.getMessage());
        }
    }

    /**
     * 把控制面全部行整表复制到目标库（切换流程第 ⑧ 步）：
     * DELETE + 显式 id INSERT；SQL Server 需临时打开 IDENTITY_INSERT；
     * 复制后按家族对齐自增序列，避免后续 CRUD 插入主键冲突。
     */
    public void copyAllTo(DataSource targetDs, Family targetFamily, List<Map<String, Object>> rows)
            throws SQLException {
        String insertSql = "INSERT INTO database_config (" + SELECT_ALL_COLUMNS + ") VALUES ("
                + String.join(", ", java.util.Collections.nCopies(COLUMNS.length, "?")) + ")";
        try (Connection c = targetDs.getConnection()) {
            boolean identityInsert = targetFamily == Family.SQLSERVER;
            try (Statement s = c.createStatement()) {
                if (identityInsert) {
                    s.execute("SET IDENTITY_INSERT database_config ON");
                }
                s.executeUpdate("DELETE FROM database_config");
            }
            try (PreparedStatement ps = c.prepareStatement(insertSql)) {
                for (Map<String, Object> row : rows) {
                    int idx = 1;
                    for (String col : COLUMNS) {
                        ps.setObject(idx++, row.get(col));
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
            } finally {
                if (identityInsert) {
                    try (Statement s = c.createStatement()) {
                        s.execute("SET IDENTITY_INSERT database_config OFF");
                    }
                }
            }
            realignIdentity(c, targetFamily);
        }
    }

    /**
     * 显式 id 复制后对齐自增起点（MySQL/H2 自动推进无需处理）：
     * PG 系 setval；Oracle 系 START WITH LIMIT VALUE；SQL Server DBCC CHECKIDENT RESEED。
     */
    private void realignIdentity(Connection c, Family f) {
        try (Statement s = c.createStatement()) {
            switch (f) {
                case POSTGRESQL -> s.execute("SELECT setval(pg_get_serial_sequence('database_config', 'id'), "
                        + "(SELECT COALESCE(MAX(id), 1) FROM database_config))");
                case ORACLE -> s.execute("ALTER TABLE database_config MODIFY id "
                        + "GENERATED BY DEFAULT ON NULL AS IDENTITY (START WITH LIMIT VALUE)");
                case SQLSERVER -> {
                    // DBCC CHECKIDENT 的 RESEED 值必须是常量，先查 MAX(id)
                    long maxId = 0;
                    try (ResultSet rs = s.executeQuery(
                            "SELECT COALESCE(MAX(id), 0) FROM database_config")) {
                        if (rs.next()) {
                            maxId = rs.getLong(1);
                        }
                    }
                    s.execute("DBCC CHECKIDENT ('database_config', RESEED, " + maxId + ")");
                }
                default -> {
                    // MySQL/H2 显式插入后自动推进 AUTO_INCREMENT，无需处理
                }
            }
        } catch (SQLException e) {
            log.error("目标库 database_config 自增序列对齐失败（后续新增配置可能主键冲突）: {}", e.getMessage());
        }
    }

    private Map<String, Object> queryOne(String sql, Object param) {
        try (Connection c = controlPlane.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (param != null) {
                ps.setObject(1, param);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return toRow(rs);
                }
            }
        } catch (SQLException e) {
            log.debug("控制面查询失败（首次启动表可能不存在）: {}", e.getMessage());
        }
        return null;
    }

    private Map<String, Object> toRow(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            row.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT), rs.getObject(i));
        }
        return row;
    }

    /** 行内取字符串值（null 安全） */
    public static String str(Map<String, Object> row, String column) {
        Object v = row.get(column);
        return v == null ? null : String.valueOf(v);
    }

    /** 行内取整数值（null 安全） */
    public static Integer integer(Map<String, Object> row, String column) {
        Object v = row.get(column);
        return v instanceof Number n ? n.intValue() : null;
    }

    /** 行内取长整数值（null 安全） */
    public static Long longVal(Map<String, Object> row, String column) {
        Object v = row.get(column);
        return v instanceof Number n ? n.longValue() : null;
    }

    /** 行内取布尔值（数值 1/0 与 Boolean 均兼容） */
    public static boolean bool(Map<String, Object> row, String column) {
        Object v = row.get(column);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.intValue() != 0;
        }
        return false;
    }
}
