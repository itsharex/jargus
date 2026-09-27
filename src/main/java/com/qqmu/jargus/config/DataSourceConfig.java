package com.qqmu.jargus.config;

import com.qqmu.jargus.datasource.ActiveDialectHolder;
import com.qqmu.jargus.datasource.CustomDriverLoader;
import com.qqmu.jargus.datasource.DataSourceFactory;
import com.qqmu.jargus.datasource.DynamicDataSource;
import com.qqmu.jargus.datasource.SqlDialectAdapter;
import com.qqmu.jargus.service.ControlPlaneRepository;
import com.qqmu.jargus.service.SchemaInitService;
import com.qqmu.jargus.util.CryptoUtil;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * 动态数据源配置。
 * 控制面 = 启动 H2 的 database_config 表（唯一真源）：
 * 启动时读 is_active 行恢复上次活库（探活 + 结构校验，失败自愈翻回内置 H2，
 * 应用必须永远能启动）；运行期切换由 DatabaseSwitchService 负责。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DataSourceConfig {

    @Value("${datasource.default.driver-class-name}")
    private String defaultDriver;

    @Value("${datasource.default.url}")
    private String defaultUrl;

    @Value("${datasource.default.username}")
    private String defaultUsername;

    @Value("${datasource.default.password:}")
    private String defaultPassword;

    @Value("${datasource.default.hikari.maximum-pool-size:10}")
    private int maxPoolSize;

    @Value("${datasource.default.hikari.minimum-idle:5}")
    private int minIdle;

    @Value("${datasource.default.hikari.connection-timeout:30000}")
    private long connectionTimeout;

    private final DataSourceFactory dataSourceFactory;

    /**
     * 控制面连接池：固定挂在启动 H2 上，ControlPlaneRepository 专用，绝不经过路由。
     * 活库为 H2 时与默认池同键复用同一个 Hikari 池（同库同参数）。
     */
    @Bean(name = "controlPlaneDataSource")
    public DataSource controlPlaneDataSource() {
        return dataSourceFactory.createDataSource(
                defaultDriver,
                defaultUrl,
                defaultUsername,
                defaultPassword,
                "default-pool",
                maxPoolSize,
                minIdle,
                connectionTimeout
        );
    }

    /**
     * 路由数据源（@Primary，业务流量入口）。
     * @DependsOn("encryptionConfig")：恢复流程要用 CryptoUtil 解密密码，
     * 必须保证静态密钥已由 EncryptionConfig#init 设置（否则用错密钥解密失败）。
     */
    @Bean
    @Primary
    @DependsOn("encryptionConfig")
    public DynamicDataSource dynamicDataSource(ControlPlaneRepository controlPlane,
                                               SchemaInitService schemaInitService) {
        DataSource bootDs = controlPlaneDataSource();

        Map<Object, Object> targetDataSources = new HashMap<>();
        DataSource active = bootDs;
        SqlDialectAdapter activeDialect = SqlDialectAdapter.H2;

        Map<String, Object> row = controlPlane.findActive();
        if (row != null && !isBuiltInBootRow(row)) {
            long id = ControlPlaneRepository.longVal(row, "id");
            String name = ControlPlaneRepository.str(row, "name");
            HikariDataSource probe = null;
            try {
                loadDriverJarIfNeeded(row);
                String driver = ControlPlaneRepository.str(row, "driver_class");
                String url = ControlPlaneRepository.str(row, "jdbc_url");
                String user = ControlPlaneRepository.str(row, "username");
                String password = CryptoUtil.decrypt(ControlPlaneRepository.str(row, "password"));

                // 探活用一次性临时池（10s 超时）：失败不污染工厂缓存
                probe = dataSourceFactory.createTemporaryDataSource(
                        driver, url, user, password, "boot-probe-" + id, 10000);
                try (Connection c = probe.getConnection()) {
                    if (!c.isValid(10)) {
                        throw new SQLException("连接探活失败");
                    }
                }
                SqlDialectAdapter dialect = SqlDialectAdapter.resolve(
                        ControlPlaneRepository.str(row, "dialect"),
                        ControlPlaneRepository.str(row, "db_type"));
                if (!schemaInitService.checkSchemaExists(probe, dialect)) {
                    throw new IllegalStateException("目标库缺少系统表结构");
                }

                // 正式池（进工厂缓存，与运行期切换共用）
                Integer maxPool = ControlPlaneRepository.integer(row, "max_pool_size");
                Integer idle = ControlPlaneRepository.integer(row, "min_idle");
                Integer timeout = ControlPlaneRepository.integer(row, "connection_timeout");
                DataSource restored = dataSourceFactory.createDataSource(
                        driver, url, user, password, "db-pool-" + id,
                        maxPool != null ? maxPool : maxPoolSize,
                        idle != null ? idle : minIdle,
                        timeout != null ? timeout : connectionTimeout);
                targetDataSources.put("db_" + id, restored);
                active = restored;
                activeDialect = dialect;
                log.info("已恢复上次使用的活库: id={}, name={}, 方言={}", id, name, dialect);
            } catch (Exception e) {
                log.error("======================================================");
                log.error("启动恢复活库失败（{}），已自愈回退到内置 H2 库: {}", name, e.getMessage());
                log.error("请在【数据库管理】页面确认目标库可用后重新切换");
                log.error("======================================================");
                try {
                    Map<String, Object> h2Row = controlPlane.findBuiltInH2Row();
                    if (h2Row != null) {
                        controlPlane.setActive(ControlPlaneRepository.longVal(h2Row, "id"));
                    }
                } catch (Exception ignore) {
                    log.error("自愈翻回 H2 激活标记失败，控制面可能仍指向不可用的库");
                }
                active = bootDs;
                activeDialect = SqlDialectAdapter.H2;
            } finally {
                if (probe != null && !probe.isClosed()) {
                    probe.close();
                }
            }
        }

        targetDataSources.put("default", active);
        ActiveDialectHolder.set(activeDialect);
        log.info("动态数据源初始化完成，活库方言: {}", activeDialect);
        return new DynamicDataSource(active, targetDataSources);
    }

    /** 控制面激活行是否就是启动内置 H2 库（同类型且同 URL） */
    private boolean isBuiltInBootRow(Map<String, Object> row) {
        String dbType = ControlPlaneRepository.str(row, "db_type");
        String url = ControlPlaneRepository.str(row, "jdbc_url");
        return "H2".equalsIgnoreCase(dbType) && defaultUrl.equalsIgnoreCase(url);
    }

    /** 配置了驱动 JAR 路径且文件存在时先加载（Oscar / 自定义库） */
    private void loadDriverJarIfNeeded(Map<String, Object> row) throws Exception {
        String jarPath = ControlPlaneRepository.str(row, "driver_jar_path");
        if (jarPath != null && !jarPath.isBlank()) {
            File jarFile = new File(jarPath);
            if (jarFile.exists()) {
                CustomDriverLoader.loadDriver(jarPath, ControlPlaneRepository.str(row, "driver_class"));
            }
        }
    }

    @Bean
    public PlatformTransactionManager transactionManager(DynamicDataSource dynamicDataSource) {
        return new DataSourceTransactionManager(dynamicDataSource);
    }
}
