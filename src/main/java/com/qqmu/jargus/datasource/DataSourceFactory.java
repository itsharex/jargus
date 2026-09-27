package com.qqmu.jargus.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据源工厂
 * 根据配置动态创建 HikariCP 数据源
 */
@Slf4j
@Component
public class DataSourceFactory {

    /**
     * 已创建的数据源缓存
     */
    private final Map<String, DataSource> dataSourceCache = new ConcurrentHashMap<>();

    /**
     * 创建数据源
     *
     * @param driverClass 驱动类名
     * @param jdbcUrl     JDBC URL
     * @param username    用户名
     * @param password    密码
     * @param poolName    连接池名称
     * @param maxPoolSize 最大连接数
     * @param minIdle     最小空闲连接
     * @return DataSource
     */
    public DataSource createDataSource(
            String driverClass,
            String jdbcUrl,
            String username,
            String password,
            String poolName,
            int maxPoolSize,
            int minIdle,
            long connectionTimeout
    ) {
        // 缓存键包含密码哈希前 12 位：改密码后不再命中旧池；
        // 同前缀（driver+url+user）的旧密码池被驱逐关闭，避免泄漏
        String prefix = driverClass + "|" + jdbcUrl + "|" + username;
        String cacheKey = prefix + "|" + passwordFingerprint(password);
        DataSource cached = dataSourceCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        evictByPrefix(prefix, cacheKey);

        HikariConfig config = new HikariConfig();
        config.setDriverClassName(driverClass);
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setPoolName(poolName != null ? poolName : "HikariPool-" + System.currentTimeMillis());
        config.setMaximumPoolSize(maxPoolSize > 0 ? maxPoolSize : 10);
        config.setMinimumIdle(minIdle > 0 ? minIdle : 5);
        config.setConnectionTimeout(connectionTimeout > 0 ? connectionTimeout : 30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        HikariDataSource dataSource = new HikariDataSource(config);
        dataSourceCache.put(cacheKey, dataSource);

        log.info("创建数据源成功: driver={}, url={}, pool={}", driverClass, jdbcUrl, config.getPoolName());
        return dataSource;
    }

    /**
     * 创建一次性临时数据源（不进缓存；连接检查等场景用完必须 close）。
     */
    public HikariDataSource createTemporaryDataSource(
            String driverClass, String jdbcUrl, String username, String password,
            String poolName, long connectionTimeout
    ) {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName(driverClass);
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setPoolName(poolName != null ? poolName : "temp-pool-" + System.currentTimeMillis());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(connectionTimeout > 0 ? connectionTimeout : 10000);
        return new HikariDataSource(config);
    }

    /** 密码指纹：SHA-256 前 12 位十六进制（缓存键用，不可逆推出密码） */
    private String passwordFingerprint(String password) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((password == null ? "" : password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 12);
        } catch (Exception e) {
            return "000000000000";
        }
    }

    /** 驱逐同前缀（同库同用户、不同密码）的旧池并关闭 */
    private void evictByPrefix(String prefix, String keepKey) {
        for (String key : dataSourceCache.keySet()) {
            if (key.startsWith(prefix + "|") && !key.equals(keepKey)) {
                DataSource old = dataSourceCache.remove(key);
                if (old instanceof HikariDataSource hds && !hds.isClosed()) {
                    log.info("驱逐旧密码数据源池: {}", hds.getPoolName());
                    hds.close();
                }
            }
        }
    }

    /**
     * 测试连接
     *
     * @param driverClass 驱动类名
     * @param jdbcUrl     JDBC URL
     * @param username    用户名
     * @param password    密码
     * @return null 表示连接成功；否则返回失败原因
     */
    public String testConnection(String driverClass, String jdbcUrl, String username, String password) {
        HikariDataSource dataSource = null;
        try {
            HikariConfig config = new HikariConfig();
            config.setDriverClassName(driverClass);
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(username);
            config.setPassword(password);
            config.setPoolName("test-pool-" + System.currentTimeMillis());
            config.setMaximumPoolSize(1);
            config.setMinimumIdle(0);
            config.setConnectionTimeout(10000);

            dataSource = new HikariDataSource(config);
            dataSource.getConnection().close();
            return null;
        } catch (Exception e) {
            log.error("测试连接失败: {}", e.getMessage(), e);
            // 优先取第一层厂商驱动异常（如 ORA-xxxxx、DMException、KSQLException），
            // 而不是连接池包装信息或最底层的 ConnectException
            Throwable c = e.getCause();
            String msg = (c != null && c.getMessage() != null && !c.getMessage().isBlank())
                    ? c.getMessage() : e.getMessage();
            return msg != null ? msg : "未知错误";
        } finally {
            if (dataSource != null) {
                dataSource.close();
            }
        }
    }
}
