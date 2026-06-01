package com.liuliu.citywalk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "liuliu.co-create-room")
public class CoCreateRoomProperties {

    private long memberInactiveTimeoutMillis = 4L * 60L * 60L * 1000L;

    public long getMemberInactiveTimeoutMillis() {
        return memberInactiveTimeoutMillis;
    }

    public void setMemberInactiveTimeoutMillis(long memberInactiveTimeoutMillis) {
        this.memberInactiveTimeoutMillis = memberInactiveTimeoutMillis;
    }
}
