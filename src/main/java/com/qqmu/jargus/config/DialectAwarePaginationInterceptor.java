package com.qqmu.jargus.config;

import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.dialects.DB2Dialect;
import com.baomidou.mybatisplus.extension.plugins.pagination.dialects.GBase8sDialect;
import com.baomidou.mybatisplus.extension.plugins.pagination.dialects.IDialect;
import com.baomidou.mybatisplus.extension.plugins.pagination.dialects.MySqlDialect;
import com.baomidou.mybatisplus.extension.plugins.pagination.dialects.OracleDialect;
import com.baomidou.mybatisplus.extension.plugins.pagination.dialects.Oracle12cDialect;
import com.baomidou.mybatisplus.extension.plugins.pagination.dialects.PostgreDialect;
import com.qqmu.jargus.datasource.ActiveDialectHolder;
import com.qqmu.jargus.datasource.SqlDialectAdapter;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.Executor;

/**
 * 按应用当前活库方言选择分页实现的拦截器。
 * 不能用裸 PaginationInnerInterceptor 的自动探测：MyBatis-Plus 的 JdbcUtils.getDbType
 * 不认识 jdbc:vastbase:/jdbc:yashandb: 等前缀（→OTHER→直接抛错），
 * 且把 jdbc:sqlserver: 映射到 2005 老方言。
 * 方言实例与 MP 官方 DialectFactory 的映射保持一致：
 * H2/PG 系→PostgreDialect，MySQL 系→MySqlDialect，Oracle 系→OracleDialect(ROWNUM)，
 * SQL Server→Oracle12cDialect(OFFSET/FETCH，MP 对 SQL_SERVER 的官方选择)，
 * DB2→DB2Dialect，神通 Oscar→MySqlDialect(MP 官方)，GBase 8s→GBase8sDialect。
 */
@Slf4j
public class DialectAwarePaginationInterceptor extends PaginationInnerInterceptor {

    private static final IDialect MYSQL = new MySqlDialect();
    private static final IDialect POSTGRE = new PostgreDialect();
    private static final IDialect ORACLE = new OracleDialect();
    private static final IDialect SQLSERVER = new Oracle12cDialect();
    private static final IDialect DB2 = new DB2Dialect();
    private static final IDialect GBASE8S = new GBase8sDialect();

    @Override
    protected IDialect findIDialect(Executor executor) {
        SqlDialectAdapter dialect = ActiveDialectHolder.get();
        switch (dialect) {
            case OSCAR:
                // 神通 Oscar 走 MySQL 协议（与 MP 官方 DbType.OSCAR 的处理一致）
                return MYSQL;
            case GBASE8S:
                // GBase 8s（Informix 系）：SKIP/FIRST 分页
                return GBASE8S;
            default:
                break;
        }
        switch (dialect.getFamily()) {
            case MYSQL:
                return MYSQL;
            case POSTGRESQL:
            case H2:
                return POSTGRE;
            case ORACLE:
                return ORACLE;
            case SQLSERVER:
                return SQLSERVER;
            case DB2:
                return DB2;
            case CUSTOM:
            default:
                // 自定义库无法可靠推断，兜底 LIMIT/OFFSET 语法（最常见）
                log.warn("自定义方言 {} 无精确分页实现，兜底使用 PostgreSQL 风格 LIMIT/OFFSET", dialect);
                return POSTGRE;
        }
    }
}
