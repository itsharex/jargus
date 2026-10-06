package com.qqmu.jargus.datasource;

/**
 * 数据源上下文持有者。
 *
 * <p>当前实现：只有一个活库（"default"），多库切换走 DynamicDataSource.replaceDefault
 * 原子替换默认路由；ThreadLocal key 永远保持 null，determineCurrentLookupKey 总返回 default。
 * 保留 get/set 骨架是为了未来多活库场景（读写分离 / 租户分库）可以直接启用；
 * 但 write 端 set/clear 目前没有任何调用方——调用方要么直接 replaceDefault，要么在 filter 里
 * ThreadLocal.set 后必须 finally remove（线程池复用，残留会路由到错库）。
 */
public class DataSourceContextHolder {

    private static final ThreadLocal<String> CONTEXT_HOLDER = new ThreadLocal<>();

    /**
     * 默认数据源 key
     */
    public static final String DEFAULT_DATASOURCE = "default";

    /**
     * 获取当前线程的数据源 key
     */
    public static String getDataSourceKey() {
        String key = CONTEXT_HOLDER.get();
        return key != null ? key : DEFAULT_DATASOURCE;
    }
}
