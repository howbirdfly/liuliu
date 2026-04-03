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
                .allowedOrigins("http://localhost:3000", "http://127.0.0.1:3000")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false);
    }

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
                        "/api/v1/auth/wechat/**"
                );
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String uploadPath = Paths.get("uploads").toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(uploadPath);
    }
}
