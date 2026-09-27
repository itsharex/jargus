package com.qqmu.jargus.datasource;

import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 目标库表/列结构的一次性快照（DatabaseMetaData 全量读取）。
 * 迁移逻辑用它做幂等守卫，替代 H2/MySQL 专属的 INFORMATION_SCHEMA 查询
 * （Oracle/DB2 等库没有该视图或列名大小写行为不同）。
 * 表名/列名统一转小写比较，兼容 Oracle/DB2 的大写存储与 PG 的小写存储。
 */
@Slf4j
public final class SchemaMetadata {

    private final Set<String> tables = new HashSet<>();
    private final Map<String, Set<String>> columns = new HashMap<>();

    private SchemaMetadata() {
    }

    /**
     * 抓取当前连接用户所属 schema 的全部表与列。
     * catalog/schema 取连接的当前值（MySQL=catalog、Oracle/PG/SQLServer=schema 均正确），
     * 驱动不支持时回退全库扫描。
     */
    public static SchemaMetadata capture(DataSource dataSource) throws SQLException {
        SchemaMetadata meta = new SchemaMetadata();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            String catalog = safeCatalog(conn);
            String schema = safeSchema(conn);
            collect(md, meta, catalog, schema);
            if (meta.tables.isEmpty()) {
                // 兜底：部分驱动对 catalog/schema 组合返回空（如老版本达梦），全量扫描一次
                collect(md, meta, null, null);
            }
        }
        return meta;
    }

    private static void collect(DatabaseMetaData md, SchemaMetadata meta, String catalog, String schema)
            throws SQLException {
        try (ResultSet rs = md.getTables(catalog, schema, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                meta.tables.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            }
        }
        try (ResultSet rs = md.getColumns(catalog, schema, "%", "%")) {
            while (rs.next()) {
                String table = rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT);
                String column = rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT);
                meta.columns.computeIfAbsent(table, k -> new HashSet<>()).add(column);
            }
        }
    }

    private static String safeCatalog(Connection conn) {
        try {
            return conn.getCatalog();
        } catch (Exception e) {
            return null;
        }
    }

    private static String safeSchema(Connection conn) {
        try {
            return conn.getSchema();
        } catch (Exception e) {
            return null;
        }
    }

    /** 表是否存在（大小写不敏感） */
    public boolean tableExists(String tableName) {
        return tableName != null && tables.contains(tableName.toLowerCase(Locale.ROOT));
    }

    /** 列是否存在（表名、列名均大小写不敏感） */
    public boolean columnExists(String tableName, String columnName) {
        if (tableName == null || columnName == null) {
            return false;
        }
        Set<String> cols = columns.get(tableName.toLowerCase(Locale.ROOT));
        return cols != null && cols.contains(columnName.toLowerCase(Locale.ROOT));
    }
}
