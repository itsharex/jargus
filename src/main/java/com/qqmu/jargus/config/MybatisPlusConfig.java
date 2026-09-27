package com.qqmu.jargus.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置
 */
@Configuration
@MapperScan("com.qqmu.jargus.mapper")
public class MybatisPlusConfig {

    /**
     * 分页插件：按 ActiveDialectHolder 的当前活库方言选择分页实现
     * （不能写死 DbType，也不能依赖 MP 的 JDBC URL 自动探测——详见拦截器注释）
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new DialectAwarePaginationInterceptor());
        return interceptor;
    }
}
