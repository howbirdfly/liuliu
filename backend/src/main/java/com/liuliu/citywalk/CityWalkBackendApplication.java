package com.liuliu.citywalk;

import com.liuliu.citywalk.config.AmapProperties;
import com.liuliu.citywalk.config.DeepSeekProperties;
import com.liuliu.citywalk.config.MissionVerifyAiProperties;
import com.liuliu.citywalk.config.NotificationCacheProperties;
import com.liuliu.citywalk.config.WechatOpenProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@MapperScan("com.liuliu.citywalk.mapper")
@EnableConfigurationProperties({
        WechatOpenProperties.class,
        DeepSeekProperties.class,
        AmapProperties.class,
        MissionVerifyAiProperties.class,
        NotificationCacheProperties.class
})
public class CityWalkBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(CityWalkBackendApplication.class, args);
    }
}
