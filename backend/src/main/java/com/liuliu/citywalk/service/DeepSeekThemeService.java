package com.liuliu.citywalk.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.config.DeepSeekProperties;
import com.liuliu.citywalk.model.dto.request.CombineThemeRequest;
import com.liuliu.citywalk.model.dto.request.GeneratePresetThemeRequest;
import com.liuliu.citywalk.model.dto.request.GenerateThemeRequest;
import com.liuliu.citywalk.model.dto.request.GenerateWalkRecordCardRequest;
import com.liuliu.citywalk.model.dto.response.LocationContextResponse;
import com.liuliu.citywalk.model.dto.response.PoiResponse;
import com.liuliu.citywalk.model.dto.response.ThemeResponse;
import com.liuliu.citywalk.model.dto.response.WalkRecordCardTextResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.ResponseFormat;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
public class DeepSeekThemeService {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekThemeService.class);
    private static final String PROVIDER = "deepseek";
    private static final String SYSTEM_PROMPT =
            "You are a helpful Chinese City Walk planning assistant. Follow the requested output format exactly.";

    private final DeepSeekProperties properties;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<DeepSeekChatModel> chatModelProvider;
    private final MapSearchService mapSearchService;

    public DeepSeekThemeService(
            DeepSeekProperties properties,
            ObjectMapper objectMapper,
            ObjectProvider<DeepSeekChatModel> chatModelProvider,
            MapSearchService mapSearchService
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.chatModelProvider = chatModelProvider;
        this.mapSearchService = mapSearchService;
    }

    public ThemeResponse generateTheme(GenerateThemeRequest request) {
        ThemePayload fallback = new ThemePayload(
                "城市灵感漫步",
                "沿着今天的城市氛围慢慢走，去观察那些只在此刻出现的细节。",
                "探索",
                List.of("找到一个让你停下来的街景", "记录一种今天最明显的颜色或声音", "用一句话总结这段路的气质"),
                "#f59e0b"
        );

        String prompt = """
                你是一个 City Walk 主题策划助手。请根据下面信息，生成一个适合散步探索的中文主题。
                心情：%s
                天气：%s
                季节：%s
                偏好：%s
                地点：%s
                地点环境：%s
                漫步模式：%s

                请把“地点”理解为“以这个地址为中心，向周围 3 公里范围扩展”的探索区域，
                不要只盯着单一门牌或单个点位，要从周边街区、路口、店铺、公园、街景和生活氛围里设计主题与任务。

                请严格输出 JSON，不要输出额外解释。
                JSON 结构：
                {
                  "title": "不超过12个字",
                  "description": "1段30-60字的中文描述",
                  "category": "一个短分类词",
                  "missions": ["任务1", "任务2", "任务3"],
                  "vibeColor": "#RRGGBB"
                }
                """.formatted(
                request.mood(),
                request.weather(),
                request.season(),
                request.preference(),
                request.locationName(),
                request.locationContext(),
                request.walkMode()
        );
        return toThemeResponse(callThemePrompt(prompt, fallback), 1L);
    }

    public ThemeResponse streamGenerateTheme(GenerateThemeRequest request, ThemeStreamListener listener) {
        ThemePayload fallback = new ThemePayload(
                "城市灵感漫步",
                "沿着今天的城市氛围慢慢走，去观察那些只在此刻出现的细节。",
                "探索",
                List.of("找到一个让你停下来的街景", "记录一种今天最明显的颜色或声音", "用一句话总结这段路的气质"),
                "#f59e0b"
        );

        String prompt = """
                你是一个 City Walk 主题策划助手。请根据下面信息，生成一个适合散步探索的中文主题。
                心情：%s
                天气：%s
                季节：%s
                偏好：%s
                地点：%s
                地点环境：%s
                漫步模式：%s

                请把“地点”理解为“以这个地址为中心，向周围 3 公里范围扩展”的探索区域，
                不要只围绕单一地点，要面向周边街区整体氛围来设计主题与任务。

                请严格输出 JSON，不要输出额外解释。
                JSON 结构：
                {
                  "title": "不超过12个字",
                  "description": "1段30-60字的中文描述",
                  "category": "一个短分类词",
                  "missions": ["任务1", "任务2", "任务3"],
                  "vibeColor": "#RRGGBB"
                }
                """.formatted(
                request.mood(),
                request.weather(),
                request.season(),
                request.preference(),
                request.locationName(),
                request.locationContext(),
                request.walkMode()
        );
        return toThemeResponse(callThemePromptStreaming(prompt, fallback, listener), 1L);
    }

    public ThemeResponse generatePreset(GeneratePresetThemeRequest request) {
        ThemePayload fallback = new ThemePayload(
                request.category() + "主题",
                "从眼前的街区里选一个角度慢慢走，看见这片地方最有意思的层次。",
                request.category(),
                List.of("找到一个最符合这个主题的细节", "记录一个容易被忽略的瞬间", "总结这里给你的第一印象"),
                "#3b82f6"
        );

        String prompt = """
                你是一个 City Walk 主题策划助手。请围绕“%s”这个方向，为地点“%s”生成一个中文漫步主题。
                地点环境：%s
                漫步模式：%s

                请把“地点”理解为“以这个地址为中心，向周围 3 公里范围扩展”的区域，
                让主题和任务尽量覆盖周边街区，而不是只围绕一个点。

                请严格输出 JSON，不要输出额外解释。
                JSON 结构：
                {
                  "title": "不超过12个字",
                  "description": "1段30-60字的中文描述",
                  "category": "%s",
                  "missions": ["任务1", "任务2", "任务3"],
                  "vibeColor": "#RRGGBB"
                }
                """.formatted(
                request.category(),
                request.locationName(),
                request.locationContext(),
                request.walkMode(),
                request.category()
        );
        return toThemeResponse(callThemePrompt(prompt, fallback), 2L);
    }

    public ThemeResponse combineTheme(CombineThemeRequest request) {
        String categoriesText = String.join("、", request.categories());
        ThemePayload fallback = new ThemePayload(
                "组合漫步",
                "把两个观察角度叠在一起，让这次散步同时有层次感和惊喜感。",
                "组合",
                List.of("找到一个同时符合多个主题的细节", "记录一次意外发现", "总结这段路线的整体气质"),
                "#8b5cf6"
        );

        String prompt = """
                你是一个 City Walk 主题策划助手。请把这些方向融合成一个新的中文漫步主题：%s。
                地点：%s
                地点环境：%s
                漫步模式：%s

                请把“地点”理解为“以这个地址为中心，向周围 3 公里范围扩展”的探索区域，
                任务设计要适合在周边多个街区或多个观察点之间步行探索。

                请严格输出 JSON，不要输出额外解释。
                JSON 结构：
                {
                  "title": "不超过12个字",
                  "description": "1段30-60字的中文描述",
                  "category": "组合",
                  "missions": ["任务1", "任务2", "任务3"],
                  "vibeColor": "#RRGGBB"
                }
                """.formatted(
                categoriesText,
                request.locationName(),
                request.locationContext(),
                request.walkMode()
        );
        return toThemeResponse(callThemePrompt(prompt, fallback), 3L);
    }

    public WalkRecordCardTextResponse generateWalkRecordCardText(GenerateWalkRecordCardRequest request) {
        WalkRecordCardPayload fallback = new WalkRecordCardPayload(
                "今天先把这一刻留给自己。",
                "小狸66陪你在%s慢慢晃悠，把路上的风、树影和烟火气都轻轻记在了今天的散步里。"
                        .formatted(request.locationName())
        );

        String prompt = """
                你是城市漫步吉祥物“小狸66”，正在帮用户写一张陪伴记录卡里的“66 的日记”。
                请根据下面信息，输出适合放进卡片里的中文 JSON：
                主题：%s
                主题描述：%s
                漫步任务：%s
                地点：%s
                地点环境：%s
                用户备注：%s
                是否上传照片：%s

                写作要求：
                1. 必须使用“小狸66”的第一视角，像它一路陪着用户散步。
                2. story 只能把“用户备注”当作参考线索，不能直接照抄，不要复述成“我听到”“我写下”这种句式。
                3. 文风要温柔、灵动、天真一点，像小狸在认真碎碎念，可以自然加入少量小动物语气，但不要每句都堆。
                4. 不要编造夸张剧情，不要出现“AI”“模型”“生成”等词。
                5. shortNote 写成 1 句短短的话，10 到 24 个中文字符。
                6. story 写成 1 段 70 到 120 个中文字符，适合放进卡片“66 的记录”区域。
                7. 优先结合主题、任务、地点环境和用户备注；如果没有备注，也要自然成文。
                8. 严格输出 JSON，不要输出额外解释。

                JSON 结构：
                {
                  "shortNote": "一句短句",
                  "story": "一段小狸66视角的日记"
                }
                """.formatted(
                request.themeTitle(),
                safeText(request.themeDescription(), "今天的城市漫步"),
                request.missionText(),
                request.locationName(),
                request.locationContext(),
                safeText(request.noteText(), "无"),
                request.hasPhoto() ? "是" : "否"
        );

        WalkRecordCardPayload payload = callWalkRecordCardPrompt(prompt, fallback);
        return new WalkRecordCardTextResponse(
                payload.shortNote(),
                payload.story(),
                PROVIDER
        );
    }

    public LocationContextResponse locationContext(Double lat, Double lng) {
        String fallback = "城市街区与日常生活场景混合环境";
        List<PoiResponse> nearbyPois = mapSearchService.nearbyPois(lat, lng);
        String poiSummary = buildPoiSummary(nearbyPois);
        String placeName = pickPlaceName(nearbyPois, null);
        String prompt = """
                你是一个地点环境描述助手。请根据经纬度推测这个地点适合 City Walk 的环境氛围。
                纬度：%s
                经度：%s
                周边可逛点摘要：%s

                请不要只描述单一地点，而是把它理解成“以该位置为中心、周边 3 公里范围”的城市环境。
                只输出一行中文短句，15 到 30 个字，不要解释。
                """.formatted(lat, lng, poiSummary);
        return new LocationContextResponse(callTextPrompt(prompt, fallback), placeName);
    }

    public LocationContextResponse searchContext(String query) {
        String fallback = query + "附近以城市街区和生活场景为主";
        String prompt = """
                你是一个地点环境描述助手。请根据地点关键词生成一句适合 City Walk 的中文环境描述。
                地点关键词：%s

                请把这个地点理解成“以该地址为中心、周边 3 公里范围”的区域，
                描述这片区域整体的街区氛围与漫步感受，不要只写单个建筑。
                只输出一行中文短句，15 到 30 个字，不要解释。
                """.formatted(query);
        return new LocationContextResponse(callTextPrompt(prompt, fallback), safeText(query, null));
    }

    public String provider() {
        return PROVIDER;
    }

    public String model() {
        return properties.getModel();
    }

    private String buildPoiSummary(List<PoiResponse> nearbyPois) {
        List<String> poiTitles = nearbyPois.stream()
                .map(PoiResponse::title)
                .filter(title -> title != null && !title.isBlank())
                .limit(6)
                .collect(Collectors.toList());
        if (poiTitles.isEmpty()) {
            return "暂无明显 POI，可按普通城市街区理解";
        }
        return String.join("、", poiTitles);
    }

    private String pickPlaceName(List<PoiResponse> nearbyPois, String fallback) {
        return nearbyPois.stream()
                .map(PoiResponse::title)
                .filter(title -> title != null && !title.isBlank())
                .findFirst()
                .orElse(fallback);
    }

    private WalkRecordCardPayload callWalkRecordCardPrompt(String prompt, WalkRecordCardPayload fallback) {
        if (!isConfigured()) {
            log.info("DeepSeek skipped: api key not configured, using fallback walk card text");
            return fallback;
        }

        try {
            String raw = callDeepSeek(prompt, true);
            WalkRecordCardPayload parsed = objectMapper.readValue(extractJsonObject(raw), WalkRecordCardPayload.class);
            log.info("DeepSeek walk card text generated successfully with model {}", properties.getModel());
            return sanitizeWalkRecordCardPayload(parsed, fallback);
        } catch (Exception error) {
            log.warn("DeepSeek walk card text generation failed, using fallback: {}", error.getMessage());
            return fallback;
        }
    }

    private ThemeResponse toThemeResponse(ThemePayload payload, Long id) {
        return new ThemeResponse(
                id,
                payload.title(),
                payload.description(),
                payload.category(),
                payload.missions(),
                payload.vibeColor(),
                PROVIDER,
                null
        );
    }

    private ThemePayload callThemePrompt(String prompt, ThemePayload fallback) {
        if (!isConfigured()) {
            log.info("DeepSeek skipped: api key not configured, using fallback theme");
            return fallback;
        }

        try {
            String raw = callDeepSeek(prompt, true);
            ThemePayload parsed = objectMapper.readValue(extractJsonObject(raw), ThemePayload.class);
            log.info("DeepSeek theme generated successfully with model {}", properties.getModel());
            return sanitizeThemePayload(parsed, fallback);
        } catch (Exception error) {
            log.warn("DeepSeek theme generation failed, using fallback: {}", error.getMessage());
            return fallback;
        }
    }

    private ThemePayload callThemePromptStreaming(String prompt, ThemePayload fallback, ThemeStreamListener listener) {
        if (!isConfigured()) {
            log.info("DeepSeek skipped: api key not configured, using fallback streaming theme");
            emitFallbackTheme(listener, fallback);
            return fallback;
        }

        try {
            String raw = callDeepSeekStreaming(prompt, true, listener);
            ThemePayload parsed = objectMapper.readValue(extractJsonObject(raw), ThemePayload.class);
            log.info("DeepSeek theme streamed successfully with model {}", properties.getModel());
            return sanitizeThemePayload(parsed, fallback);
        } catch (Exception error) {
            log.warn("DeepSeek theme streaming failed, using fallback: {}", error.getMessage());
            emitFallbackTheme(listener, fallback);
            return fallback;
        }
    }

    private String callTextPrompt(String prompt, String fallback) {
        if (!isConfigured()) {
            log.info("DeepSeek skipped: api key not configured, using fallback text");
            return fallback;
        }

        try {
            String raw = callDeepSeek(prompt, false).trim();
            log.info("DeepSeek text generated successfully with model {}", properties.getModel());
            return raw.isBlank() ? fallback : raw;
        } catch (Exception error) {
            log.warn("DeepSeek text generation failed, using fallback: {}", error.getMessage());
            return fallback;
        }
    }

    private boolean isConfigured() {
        return properties.getApiKey() != null
                && !properties.getApiKey().isBlank()
                && chatModelProvider.getIfAvailable() != null;
    }

    private String callDeepSeekStreaming(String prompt, boolean expectJson, ThemeStreamListener listener) {
        AtomicReference<ChatResponse> aggregatedResponse = new AtomicReference<>();
        new MessageAggregator()
                .aggregate(getChatModel().stream(buildPrompt(prompt, expectJson)), aggregatedResponse::set)
                .doOnNext(chunk -> emitStreamingDelta(chunk, listener))
                .blockLast();

        String content = extractText(aggregatedResponse.get());
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("DeepSeek returned empty streamed content");
        }
        return content;
    }

    private String callDeepSeek(String prompt, boolean expectJson) {
        String content = extractText(getChatModel().call(buildPrompt(prompt, expectJson)));
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("DeepSeek returned empty content");
        }
        return content;
    }

    private DeepSeekChatModel getChatModel() {
        DeepSeekChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new IllegalStateException("DeepSeekChatModel is not available");
        }
        return chatModel;
    }

    private Prompt buildPrompt(String prompt, boolean expectJson) {
        return new Prompt(
                List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(prompt)),
                DeepSeekChatOptions.builder()
                        .model(properties.getModel())
                        .temperature(0.8)
                        .responseFormat(ResponseFormat.builder()
                                .type(expectJson ? ResponseFormat.Type.JSON_OBJECT : ResponseFormat.Type.TEXT)
                                .build())
                        .build()
        );
    }

    private void emitStreamingDelta(ChatResponse chunk, ThemeStreamListener listener) {
        if (listener == null) {
            return;
        }
        String delta = extractText(chunk);
        if (delta != null && !delta.isEmpty()) {
            listener.onContentDelta(delta);
        }
    }

    private String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }

    private ThemePayload sanitizeThemePayload(ThemePayload payload, ThemePayload fallback) {
        if (payload == null) {
            return fallback;
        }

        List<String> missions = payload.missions() == null
                ? fallback.missions()
                : payload.missions().stream().filter(item -> item != null && !item.isBlank()).limit(3).toList();

        if (missions.isEmpty()) {
            missions = fallback.missions();
        }

        return new ThemePayload(
                isBlank(payload.title()) ? fallback.title() : payload.title().trim(),
                isBlank(payload.description()) ? fallback.description() : payload.description().trim(),
                isBlank(payload.category()) ? fallback.category() : payload.category().trim(),
                missions,
                isBlank(payload.vibeColor()) ? fallback.vibeColor() : payload.vibeColor().trim()
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String safeText(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private WalkRecordCardPayload sanitizeWalkRecordCardPayload(WalkRecordCardPayload payload, WalkRecordCardPayload fallback) {
        if (payload == null) {
            return fallback;
        }

        return new WalkRecordCardPayload(
                safeText(payload.shortNote(), fallback.shortNote()),
                safeText(payload.story(), fallback.story())
        );
    }

    private String extractJsonObject(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("No JSON object found in DeepSeek response");
        }
        return trimmed.substring(start, end + 1);
    }

    private void emitFallbackTheme(ThemeStreamListener listener, ThemePayload fallback) {
        if (listener == null) {
            return;
        }
        try {
            listener.onContentDelta(objectMapper.writeValueAsString(fallback));
        } catch (Exception ignored) {
            listener.onContentDelta("{\"title\":\"城市灵感漫步\"}");
        }
    }

    public interface ThemeStreamListener {
        void onContentDelta(String delta);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ThemePayload(
            String title,
            String description,
            String category,
            List<String> missions,
            String vibeColor
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WalkRecordCardPayload(
            String shortNote,
            String story
    ) {
    }
}
