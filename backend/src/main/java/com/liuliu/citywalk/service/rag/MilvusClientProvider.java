package com.liuliu.citywalk.service.rag;

import com.liuliu.citywalk.config.MilvusProperties;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

@Component
public class MilvusClientProvider {

    private final MilvusProperties properties;
    private volatile MilvusClientV2 client;

    public MilvusClientProvider(MilvusProperties properties) {
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public MilvusProperties getProperties() {
        return properties;
    }

    public MilvusClientV2 getClient() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("milvus_disabled");
        }
        MilvusClientV2 snapshot = client;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (this) {
            if (client == null) {
                ConnectConfig connectConfig = ConnectConfig.builder()
                        .uri(properties.getUri())
                        .token(properties.getToken())
                        .dbName(properties.getDatabase())
                        .connectTimeoutMs(3_000L)
                        .rpcDeadlineMs(10_000L)
                        .enablePrecheck(false)
                        .build();
                client = new MilvusClientV2(connectConfig);
            }
            return client;
        }
    }

    @PreDestroy
    public void close() {
        MilvusClientV2 snapshot = client;
        if (snapshot == null) {
            return;
        }
        try {
            snapshot.close();
        } catch (Exception ignored) {
        }
    }
}
