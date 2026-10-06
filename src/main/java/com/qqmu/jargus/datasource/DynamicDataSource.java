package com.qqmu.jargus.datasource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态数据源
 * 根据 ThreadLocal 中的 key 路由到对应的数据源；
 * "default" 目标即当前活库，切换/启动恢复时通过 replaceDefault 原子替换。
 */
public class DynamicDataSource extends AbstractRoutingDataSource {

    private final Map<Object, Object> resolvedDataSources = new ConcurrentHashMap<>();

    public DynamicDataSource(DataSource defaultTargetDataSource, Map<Object, Object> targetDataSources) {
        super.setDefaultTargetDataSource(defaultTargetDataSource);
        super.setTargetDataSources(targetDataSources);
        super.afterPropertiesSet();
        this.resolvedDataSources.putAll(targetDataSources);
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return DataSourceContextHolder.getDataSourceKey();
    }

    /**
     * 动态添加数据源
     */
    public synchronized void addDataSource(String key, DataSource dataSource) {
        this.resolvedDataSources.put(key, dataSource);
        super.setTargetDataSources(this.resolvedDataSources);
        super.afterPropertiesSet();
    }

    /**
     * 替换默认（活库）数据源：路由 key 为空的所有流量立即落到新库。
     * 与 addDataSource 共用 synchronized，避免并发切换时 afterPropertiesSet 交叉。
     * 注意：本方法不负责关闭旧池 —— 调用方（DatabaseSwitchService）在确认切换成功后
     * 再显式 close 旧池，这样切换失败回滚时旧池仍可用。
     */
    public synchronized void replaceDefault(DataSource dataSource) {
        this.resolvedDataSources.put("default", dataSource);
        super.setDefaultTargetDataSource(dataSource);
        super.setTargetDataSources(this.resolvedDataSources);
        super.afterPropertiesSet();
    }

    /** 当前默认（活库）数据源——切换失败回滚用 */
    public DataSource getCurrentDefaultDataSource() {
        return super.getResolvedDefaultDataSource();
    }
}
