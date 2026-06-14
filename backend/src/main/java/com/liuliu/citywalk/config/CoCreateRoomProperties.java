package com.liuliu.citywalk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "liuliu.co-create-room")
public class CoCreateRoomProperties {

    private long memberInactiveTimeoutMillis = 4L * 60L * 60L * 1000L;
    private boolean clusterBroadcastEnabled = true;
    private String clusterBroadcastChannel = "liuliu:co-create:realtime";

    public long getMemberInactiveTimeoutMillis() {
        return memberInactiveTimeoutMillis;
    }

    public void setMemberInactiveTimeoutMillis(long memberInactiveTimeoutMillis) {
        this.memberInactiveTimeoutMillis = memberInactiveTimeoutMillis;
    }

    public boolean isClusterBroadcastEnabled() {
        return clusterBroadcastEnabled;
    }

    public void setClusterBroadcastEnabled(boolean clusterBroadcastEnabled) {
        this.clusterBroadcastEnabled = clusterBroadcastEnabled;
    }

    public String getClusterBroadcastChannel() {
        return clusterBroadcastChannel;
    }

    public void setClusterBroadcastChannel(String clusterBroadcastChannel) {
        this.clusterBroadcastChannel = clusterBroadcastChannel;
    }
}
