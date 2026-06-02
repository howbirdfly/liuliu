package com.liuliu.citywalk;

import com.liuliu.citywalk.config.AmapProperties;
import com.liuliu.citywalk.config.AgentMemoryProperties;
import com.liuliu.citywalk.config.CoCreateRoomProperties;
import com.liuliu.citywalk.config.CommunityCacheProperties;
import com.liuliu.citywalk.config.DeepSeekProperties;
import com.liuliu.citywalk.config.MissionVerifyAiProperties;
import com.liuliu.citywalk.config.MilvusProperties;
import com.liuliu.citywalk.config.NotificationCacheProperties;
import com.liuliu.citywalk.config.SingleWalkSessionProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.liuliu.citywalk.mapper")
@EnableConfigurationProperties({
        DeepSeekProperties.class,
        AmapProperties.class,
        MissionVerifyAiProperties.class,
        MilvusProperties.class,
        NotificationCacheProperties.class,
        CommunityCacheProperties.class,
        AgentMemoryProperties.class,
        CoCreateRoomProperties.class,
        SingleWalkSessionProperties.class
})
public class CityWalkBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(CityWalkBackendApplication.class, args);
    }
}
