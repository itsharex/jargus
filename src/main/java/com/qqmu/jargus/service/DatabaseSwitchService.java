package com.qqmu.jargus.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.qqmu.jargus.datasource.ActiveDialectHolder;
import com.qqmu.jargus.datasource.CustomDriverLoader;
import com.qqmu.jargus.datasource.DataSourceFactory;
import com.qqmu.jargus.datasource.DialectDdl;
import com.qqmu.jargus.datasource.DynamicDataSource;
import com.qqmu.jargus.datasource.SqlDialectAdapter;
import com.qqmu.jargus.entity.ScanTask;
import com.qqmu.jargus.mapper.ScanTaskMapper;
import com.qqmu.jargus.util.CryptoUtil;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库切换服务。
 * 切换语义：控制面（启动 H2 的 database_config 表）为唯一真源，
 * 目标库要么全新初始化、要么复用已有 1.0.0 结构；不迁移业务数据。
 * 流程（去 @Transactional，跨库操作没有统一事务可言）：
 * ①控制面读目标行 → ②加载驱动 JAR → ③解析方言 → ④建池 → ⑤结构探测 →
 * ⑥无表则建（或报错）→ ⑦增量迁移 → ⑧database_config 整表复制到目标 →
 * ⑨目标副本翻 is_active → ⑩控制面翻 is_active → ⑪替换路由默认池+设置方言 →
 * ⑫初始化用户/刷新缓存 → ⑬成功返回。
 * ≤⑩ 失败无副作用（控制面未翻转）；⑪后失败回滚控制面标记、路由与方言。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseSwitchService {

    private final DynamicDataSource dynamicDataSource;
    private final DataSourceFactory dataSourceFactory;
    private final SchemaInitService schemaInitService;
    private final SchemaMigrations schemaMigrations;
    private final ControlPlaneRepository controlPlane;
    private final AuthService authService;
    private final IgnoreRuleService ignoreRuleService;
    private final ScanTaskMapper scanTaskMapper;

    /**
     * 检查目标数据库状态（在切换前调用）。
     * 目标行从控制面读（活库非 H2 时目标库可能还是空库，mapper 读不到）。
     */
    public Map<String, Object> checkTargetDatabase(Long targetId) {
        Map<String, Object> result = new HashMap<>();
        result.put("canSwitch", false);
        result.put("status", "UNKNOWN");
        result.put("message", "");
        result.put("schemaVersion", null);

        Map<String, Object> row = controlPlane.findById(targetId);
        if (row == null) {
            result.put("message", "目标数据库配置不存在");
            return result;
        }

        // dialect 存量值安全解析，未知回退 dbType 推断（探测与建表共用）
        SqlDialectAdapter dialect = SqlDialectAdapter.resolve(
                ControlPlaneRepository.str(row, "dialect"),
                ControlPlaneRepository.str(row, "db_type"));

        // 一次性临时池：用完必须关闭（旧实现走工厂缓存池，永不关闭而泄漏）
        HikariDataSource tempDs = null;
        try {
            loadDriverJarIfNeeded(row);

            tempDs = dataSourceFactory.createTemporaryDataSource(
                    ControlPlaneRepository.str(row, "driver_class"),
                    ControlPlaneRepository.str(row, "jdbc_url"),
                    ControlPlaneRepository.str(row, "username"),
                    CryptoUtil.decrypt(ControlPlaneRepository.str(row, "password")),
                    "temp-check-" + targetId,
                    10000);

            boolean schemaExists = schemaInitService.checkSchemaExists(tempDs, dialect);

            if (!schemaExists) {
                result.put("status", "NO_SCHEMA");
                result.put("needsInit", true);
                result.put("dialect", dialect.getDialect());
                if (dialect.hasBuiltinDdl()) {
                    result.put("canSwitch", true);
                    result.put("message", "目标数据库中未检测到系统表，切换时将自动初始化表结构");
                } else {
                    result.put("canSwitch", false);
                    result.put("message", "目标数据库中未检测到系统表，且该类型暂不支持自动建表，请手工执行 DDL 后重试");
                }
            } else {
                String version = schemaInitService.getSchemaVersion(tempDs, dialect);
                result.put("schemaVersion", version);

                if ("1.0.0".equals(version)) {
                    result.put("status", "SCHEMA_OK");
                    result.put("canSwitch", true);
                    result.put("needsInit", false);
                    result.put("message", "检测到已有表结构（版本 " + version + "），可直接切换");
                } else {
                    result.put("status", "SCHEMA_VERSION_MISMATCH");
                    result.put("canSwitch", false);
                    result.put("needsMigration", true);
                    result.put("message", "表结构版本不一致（当前版本: " + version + "，系统版本: 1.0.0），需要迁移");
                }
            }
        } catch (Exception e) {
            result.put("status", "CONNECTION_FAILED");
            result.put("message", "连接失败: " + e.getMessage());
            log.error("检查目标数据库失败: {}", e.getMessage(), e);
        } finally {
            if (tempDs != null && !tempDs.isClosed()) {
                tempDs.close();
            }
        }

        return result;
    }

    /**
     * 切换到目标数据库（真实生效：路由默认池与分页方言同步替换，重启后自动恢复）
     */
    public Map<String, Object> switchDatabase(Long targetId, boolean initIfNeeded) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", "");

        // ⓪ 守卫：存在运行中/等待中的扫描任务时拒绝切换（避免 in-flight 流量跨库错乱）
        Long busy = scanTaskMapper.selectCount(
                new QueryWrapper<ScanTask>().in("status", "RUNNING", "PENDING"));
        if (busy != null && busy > 0) {
            result.put("message", "存在运行中或等待中的扫描任务，请等待其完成后再切换数据库");
            result.put("busy", true);
            return result;
        }

        // ① 控制面读目标行（唯一真源）
        Map<String, Object> row = controlPlane.findById(targetId);
        if (row == null) {
            result.put("message", "目标数据库配置不存在");
            return result;
        }
        Map<String, Object> oldActive = controlPlane.findActive();
        Long oldActiveId = oldActive != null ? ControlPlaneRepository.longVal(oldActive, "id") : null;
        SqlDialectAdapter oldDialect = ActiveDialectHolder.get();
        DataSource oldDefault = dynamicDataSource.getCurrentDefaultDataSource();

        // ③ 方言解析（②驱动加载在下方 try 内，与建池相邻）
        SqlDialectAdapter dialect = SqlDialectAdapter.resolve(
                ControlPlaneRepository.str(row, "dialect"),
                ControlPlaneRepository.str(row, "db_type"));
        String targetName = ControlPlaneRepository.str(row, "name");

        DataSource newDataSource;
        try {
            // ② 加载驱动 JAR（Oscar / 自定义库）
            loadDriverJarIfNeeded(row);

            // ④ 创建目标池（解密密码；进工厂缓存，重启恢复复用同键）
            Integer maxPool = ControlPlaneRepository.integer(row, "max_pool_size");
            Integer minIdle = ControlPlaneRepository.integer(row, "min_idle");
            Integer connTimeout = ControlPlaneRepository.integer(row, "connection_timeout");
            newDataSource = dataSourceFactory.createDataSource(
                    ControlPlaneRepository.str(row, "driver_class"),
                    ControlPlaneRepository.str(row, "jdbc_url"),
                    ControlPlaneRepository.str(row, "username"),
                    CryptoUtil.decrypt(ControlPlaneRepository.str(row, "password")),
                    "db-pool-" + targetId,
                    maxPool != null ? maxPool : 10,
                    minIdle != null ? minIdle : 5,
                    connTimeout != null ? connTimeout : 30000);

            // ⑤ 结构探测
            boolean schemaExists = schemaInitService.checkSchemaExists(newDataSource, dialect);

            // ⑥ 无表：按需初始化或报错
            if (!schemaExists) {
                if (!initIfNeeded) {
                    result.put("message", "目标数据库未初始化，请确认后重试");
                    return result;
                }
                if (!dialect.hasBuiltinDdl()) {
                    result.put("message", "该数据库类型暂不支持自动建表，请手工执行 DDL 后重试");
                    return result;
                }
                if (!schemaInitService.initializeSchema(newDataSource, dialect)) {
                    result.put("message", "表结构初始化失败");
                    return result;
                }
                // 初始化标记先写回控制面，第⑧步整表复制时带上最新状态
                Map<String, Object> updated = new HashMap<>(row);
                updated.put("is_initialized", Boolean.TRUE);
                updated.put("schema_version", "1.0.0");
                controlPlane.upsert(updated);
                row = updated;
            }

            // ⑦ 增量迁移（幂等，方言中立；全新库跑一遍无害）
            schemaMigrations.runAll(newDataSource, dialect);

            // ⑧ database_config 整表复制到目标库（密文原样；SQL Server 自动包 IDENTITY_INSERT）
            List<Map<String, Object>> allRows = controlPlane.findAll();
            controlPlane.copyAllTo(newDataSource, dialect.getFamily(), allRows);

            // ⑨ 目标库副本翻转 is_active（原生 JDBC，布尔字面量按方言渲染）
            flipActiveOnTarget(newDataSource, dialect, targetId);

            // ⑩ 控制面翻转激活标记（此刻尚未替换路由，失败可整体回退）
            controlPlane.setActive(targetId);
        } catch (Exception e) {
            result.put("message", "切换失败: " + e.getMessage());
            log.error("数据库切换失败（控制面未变更或已回退）: {}", e.getMessage(), e);
            return result;
        }

        // ⑪-⑬ 替换运行时路由；此后失败需回滚控制面标记与路由
        try {
            dynamicDataSource.addDataSource("db_" + targetId, newDataSource);
            dynamicDataSource.replaceDefault(newDataSource);
            ActiveDialectHolder.set(dialect);

            // ⑫ 新库必须有默认用户，否则登录即死；缓存也要指向新库
            try {
                authService.initDefaultUsers();
                ignoreRuleService.refreshCache();
            } catch (Exception e) {
                log.error("切换后初始化默认用户/刷新缓存失败（请尽快检查目标库数据）: {}", e.getMessage(), e);
            }

            // ⑬ 成功
            result.put("success", true);
            result.put("message", "已切换至 " + targetName + "，重启后将自动恢复该数据库");
            result.put("activeDbName", targetName);
            log.info("数据库切换成功: id={}, name={}, 方言={}", targetId, targetName, dialect);
        } catch (Exception e) {
            log.error("替换运行时路由失败，回滚到原活库: {}", e.getMessage(), e);
            try {
                if (oldActiveId != null) {
                    controlPlane.setActive(oldActiveId);
                }
                if (oldDefault != null) {
                    dynamicDataSource.replaceDefault(oldDefault);
                }
                ActiveDialectHolder.set(oldDialect);
            } catch (Exception re) {
                log.error("回滚失败，请重启应用自愈（启动恢复会探活并翻回可用库）: {}", re.getMessage());
            }
            result.put("message", "切换失败（已回滚到原数据库）: " + e.getMessage());
        }
        return result;
    }

    /** 目标库 database_config 副本翻转 is_active（布尔字面量按方言：1/0 或 TRUE/FALSE） */
    private void flipActiveOnTarget(DataSource targetDs, SqlDialectAdapter dialect, Long targetId)
            throws Exception {
        String falseLit = DialectDdl.boolLiteral(dialect.getFamily(), false);
        String trueLit = DialectDdl.boolLiteral(dialect.getFamily(), true);
        try (Connection c = targetDs.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE database_config SET is_active = " + falseLit);
            s.executeUpdate("UPDATE database_config SET is_active = " + trueLit + " WHERE id = " + targetId);
        }
    }

    /**
     * 配置了驱动 JAR 路径且文件存在时加载驱动（自定义库、或驱动未内置的类型如神通 Oscar）
     */
    private void loadDriverJarIfNeeded(Map<String, Object> row) throws Exception {
        String jarPath = ControlPlaneRepository.str(row, "driver_jar_path");
        if (jarPath != null && !jarPath.isBlank()) {
            File jarFile = new File(jarPath);
            if (jarFile.exists()) {
                CustomDriverLoader.loadDriver(jarPath, ControlPlaneRepository.str(row, "driver_class"));
            }
        }
    }
}
