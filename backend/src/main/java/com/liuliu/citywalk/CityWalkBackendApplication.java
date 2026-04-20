package com.liuliu.citywalk;

import com.liuliu.citywalk.config.AmapProperties;
import com.liuliu.citywalk.config.DeepSeekProperties;
import com.liuliu.citywalk.config.MissionVerifyAiProperties;
import com.liuliu.citywalk.config.WechatOpenProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        WechatOpenProperties.class,
        DeepSeekProperties.class,
        AmapProperties.class,
        MissionVerifyAiProperties.class
})
public class CityWalkBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(CityWalkBackendApplication.class, args);
    }
}
