package com.qqmu.jargus.datasource;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 当前活库方言持有者（进程级单例）。
 * 启动恢复（DataSourceConfig）与切换成功（DatabaseSwitchService）时设置；
 * 分页拦截器（DialectAwarePaginationInterceptor）等运行时组件读取。
 * 默认 H2：未发生任何切换时与内置库行为一致。
 */
public final class ActiveDialectHolder {

    private static final AtomicReference<SqlDialectAdapter> CURRENT =
            new AtomicReference<>(SqlDialectAdapter.H2);

    private ActiveDialectHolder() {
    }

    public static SqlDialectAdapter get() {
        return CURRENT.get();
    }

    public static void set(SqlDialectAdapter dialect) {
        CURRENT.set(dialect != null ? dialect : SqlDialectAdapter.H2);
    }

    /** 当前活库的方言家族 */
    public static SqlDialectAdapter.Family family() {
        return CURRENT.get().getFamily();
    }
}
