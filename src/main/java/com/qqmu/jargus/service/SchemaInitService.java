package com.qqmu.jargus.service;

import com.qqmu.jargus.datasource.SqlDialectAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 数据库表结构初始化服务（多方言）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaInitService {

    /**
     * 检查目标数据库中是否存在系统表（schema_version）。
     * 表名存储大小写因库而异：Oracle/DB2/openGauss(A 兼容模式) 为大写，
     * PG 系默认为小写，MySQL/H2 视平台而定 → 大小写双探测 + 全量兜底比较。
     *
     * @param dialect 目标库方言（保留参数：后续可按方言精确探测）
     */
    public boolean checkSchemaExists(DataSource dataSource, SqlDialectAdapter dialect) {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            if (tableNamedExists(meta, "SCHEMA_VERSION") || tableNamedExists(meta, "schema_version")) {
                return true;
            }
            // 兜底：全量遍历表名做大小写不敏感比较（部分驱动的 getTables 模式匹配行为不一致）
            try (ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    if ("schema_version".equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                        return true;
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("检查表结构失败: {}", e.getMessage());
        }
        return false;
    }

    private boolean tableNamedExists(DatabaseMetaData meta, String tableName) throws SQLException {
        try (ResultSet rs = meta.getTables(null, null, tableName, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    /**
     * 获取目标数据库的 schema 版本。
     * 行限制语法按方言家族选择：Oracle 系用 ROWNUM 子查询，DB2 用 FETCH FIRST，
     * SQL Server 用 TOP 1，其余（MySQL/PG/H2 系）用 LIMIT 1。
     */
    public String getSchemaVersion(DataSource dataSource, SqlDialectAdapter dialect) {
        String sql = switch (dialect.getFamily()) {
            case ORACLE -> "SELECT version FROM (SELECT version FROM schema_version ORDER BY id DESC) WHERE ROWNUM = 1";
            case DB2 -> "SELECT version FROM schema_version ORDER BY id DESC FETCH FIRST 1 ROWS ONLY";
            case SQLSERVER -> "SELECT TOP 1 version FROM schema_version ORDER BY id DESC";
            default -> "SELECT version FROM schema_version ORDER BY id DESC LIMIT 1";
        };
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getString(1);
            }
        } catch (SQLException e) {
            log.warn("获取 schema 版本失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 在目标数据源初始化表结构（脚本含中文种子数据，显式按 UTF-8 读取，
     * 避免容器/服务端默认字符集非 UTF-8 时乱码）
     */
    public boolean initializeSchema(DataSource dataSource, SqlDialectAdapter dialect) {
        String scriptPath = dialect.getDdlScriptPath();
        if (scriptPath == null) {
            log.error("方言 {} 没有内置 DDL 脚本，需要用户自定义", dialect);
            return false;
        }

        try {
            ClassPathResource resource = new ClassPathResource(scriptPath);
            if (!resource.exists()) {
                log.error("DDL 脚本不存在: {}", scriptPath);
                return false;
            }

            try (Connection conn = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(conn, new EncodedResource(resource, StandardCharsets.UTF_8));
                log.info("表结构初始化成功: {}", scriptPath);
                return true;
            }
        } catch (Exception e) {
            log.error("表结构初始化失败: {}", e.getMessage(), e);
            return false;
        }
    }
}
