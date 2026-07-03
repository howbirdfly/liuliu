package com.liuliu.citywalk;

import com.liuliu.citywalk.config.AmapProperties;
import com.liuliu.citywalk.config.AgentMemoryProperties;
import com.liuliu.citywalk.config.AgentToolCacheProperties;
import com.liuliu.citywalk.config.CoCreateRoomProperties;
import com.liuliu.citywalk.config.CommunityCacheProperties;
import com.liuliu.citywalk.config.EmbeddingAiProperties;
import com.liuliu.citywalk.config.MilvusProperties;
import com.liuliu.citywalk.config.MissionVerifyAiProperties;
import com.liuliu.citywalk.config.NotificationCacheProperties;
import com.liuliu.citywalk.config.RagProperties;
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
        AmapProperties.class,
        MilvusProperties.class,
        EmbeddingAiProperties.class,
        MissionVerifyAiProperties.class,
        NotificationCacheProperties.class,
        CommunityCacheProperties.class,
        AgentMemoryProperties.class,
        AgentToolCacheProperties.class,
        CoCreateRoomProperties.class,
        SingleWalkSessionProperties.class,
        RagProperties.class
})
public class CityWalkBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(CityWalkBackendApplication.class, args);
    }
}
