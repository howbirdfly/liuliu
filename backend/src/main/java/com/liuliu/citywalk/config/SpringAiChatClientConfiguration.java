package com.liuliu.citywalk.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpringAiChatClientConfiguration {

    @Bean("deepSeekChatClient")
    public ChatClient deepSeekChatClient(@Qualifier("liuliuDeepSeekChatModel") ChatModel chatModel) {
        return ChatClient.create(chatModel);
    }

    @Bean("missionVerifyChatClient")
    public ChatClient missionVerifyChatClient(@Qualifier("missionVerifyChatModel") ChatModel chatModel) {
        return ChatClient.create(chatModel);
    }
}
