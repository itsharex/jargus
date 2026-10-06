package com.qqmu.jargus.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 * - 静态资源（/css、/js、/assets、/vendor）
 *
 * 不配 CORS：本应用是同源 SSR 单体，前端全部走相对路径 /api 调用，
 * 曾经的 allowedOriginPatterns("*") + allowCredentials(true) 会让任意第三方站点
 * 发起带凭据跨域请求并读取响应；webhook 为服务端到服务端调用，与 CORS 无关。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCachePeriod(3600);
    }
}
