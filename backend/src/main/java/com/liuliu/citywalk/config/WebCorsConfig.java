package com.liuliu.citywalk.config;

import com.liuliu.citywalk.interceptor.MiniappJwtInterceptor;
import com.liuliu.citywalk.interceptor.WebJwtInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    private final MiniappJwtInterceptor miniappJwtInterceptor;
    private final WebJwtInterceptor webJwtInterceptor;

    public WebCorsConfig(MiniappJwtInterceptor miniappJwtInterceptor, WebJwtInterceptor webJwtInterceptor) {
        this.miniappJwtInterceptor = miniappJwtInterceptor;
        this.webJwtInterceptor = webJwtInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(
                        "https://liu--liu.com",
                        "https://www.liu--liu.com",
                        "http://liu--liu.com",
                        "http://www.liu--liu.com",
                        "http://localhost:3000",
                        "http://127.0.0.1:3000",
                        "http://192.168.*.*:3000",
                        "http://10.*.*.*:3000",
                        "http://172.16.*.*:3000",
                        "http://172.17.*.*:3000",
                        "http://172.18.*.*:3000",
                        "http://172.19.*.*:3000",
                        "http://172.20.*.*:3000",
                        "http://172.21.*.*:3000",
                        "http://172.22.*.*:3000",
                        "http://172.23.*.*:3000",
                        "http://172.24.*.*:3000",
                        "http://172.25.*.*:3000",
                        "http://172.26.*.*:3000",
                        "http://172.27.*.*:3000",
                        "http://172.28.*.*:3000",
                        "http://172.29.*.*:3000",
                        "http://172.30.*.*:3000",
                        "http://172.31.*.*:3000"
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false);
    }
    //拦截器
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(miniappJwtInterceptor)
                .addPathPatterns(
                        "/api/v1/miniapp/auth/me",
                        "/api/v1/miniapp/walks",
                        "/api/v1/miniapp/walks/me"
                );

        registry.addInterceptor(webJwtInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns(
                        "/api/v1/auth/login",
                        "/api/v1/auth/mock-login",
                        "/api/v1/auth/email/send-code",
                        "/api/v1/auth/email/register",
                        "/api/v1/auth/email/login",
                        "/api/v1/auth/email/reset-password",
                        "/api/v1/agent/stream",
                        "/api/v1/notifications/stream",
                        "/api/v1/community/**"
                );
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String uploadPath = Paths.get("uploads").toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(uploadPath);
    }
}
