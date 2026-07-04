package com.liuliu.citywalk.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpringAiChatClientConfiguration {

    @Bean("deepSeekChatClient")
    public ChatClient deepSeekChatClient(@Qualifier("deepSeekChatModel") ChatModel chatModel) {
        return ChatClient.create(chatModel);
    }

    @Bean("missionVerifyChatClient")
    public ChatClient missionVerifyChatClient(@Qualifier("missionVerifyChatModel") ChatModel chatModel) {
        return ChatClient.create(chatModel);
    }

    @Bean("agentChatMemoryAdvisor")
    public MessageChatMemoryAdvisor agentChatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }
}
