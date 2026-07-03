package com.liuliu.citywalk.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
public class DeepSeekThemeService {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekThemeService.class);
    private static final String PROVIDER = "deepseek";
    private static final String SYSTEM_PROMPT =
            "你是一名擅长中文表达的 City Walk 策划助手。严格按照要求返回内容，不要输出多余解释。";

    private final ObjectMapper objectMapper;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectProvider<StreamingChatModel> streamingChatModelProvider;
    private final MapSearchService mapSearchService;
    private final String configuredModel;
    private final String configuredApiKey;

    public DeepSeekThemeService(
            ObjectMapper objectMapper,
            ObjectProvider<ChatModel> chatModelProvider,
            ObjectProvider<StreamingChatModel> streamingChatModelProvider,
            MapSearchService mapSearchService,
            @Value("${spring.ai.deepseek.chat.model:deepseek-chat}") String configuredModel,
            @Value("${spring.ai.deepseek.api-key:}") String configuredApiKey
    ) {
        this.objectMapper = objectMapper;
        this.chatModelProvider = chatModelProvider;
        this.streamingChatModelProvider = streamingChatModelProvider;
        this.mapSearchService = mapSearchService;
        this.configuredModel = configuredModel == null || configuredModel.isBlank() ? "deepseek-chat" : configuredModel.trim();
        this.configuredApiKey = configuredApiKey == null ? "" : configuredApiKey.trim();
    }

    public ThemeResponse generateTheme(GenerateThemeRequest request) {
        ThemePayload fallback = new ThemePayload(
                "此刻城市散步提案",
                "围绕你当前的心情与地点，安排一条适合边走边看的轻量 City Walk，让沿途的小变化自然成为这次漫步的亮点。",
                "探索",
                List.of("先选一段最容易进入状态的街区慢慢开走", "沿路记录一个最打动你的街角、气味或光线", "在收尾点留出十分钟坐下来整理这次感受"),
                "#f59e0b"
        );
        return toThemeResponse(callThemePrompt(buildGenerateThemePrompt(request), fallback), 1L);
    }

    public ThemeResponse streamGenerateTheme(GenerateThemeRequest request, ThemeStreamListener listener) {
        ThemePayload fallback = new ThemePayload(
                "此刻城市散步提案",
                "围绕你当前的心情与地点，安排一条适合边走边看的轻量 City Walk，让沿途的小变化自然成为这次漫步的亮点。",
                "探索",
                List.of("先选一段最容易进入状态的街区慢慢开走", "沿路记录一个最打动你的街角、气味或光线", "在收尾点留出十分钟坐下来整理这次感受"),
                "#f59e0b"
        );
        return toThemeResponse(callThemePromptStreaming(buildGenerateThemePrompt(request), fallback, listener), 1L);
    }

    public ThemeResponse generatePreset(GeneratePresetThemeRequest request) {
        ThemePayload fallback = new ThemePayload(
                request.category() + "漫步提案",
                "从当前位置周边挑选一片和主题气质契合、适合自然展开的步行区域，用轻松的节奏把这次主题感拉出来。",
                request.category(),
                List.of("先找到最能代表这个主题的第一眼场景", "途中记录一个最符合主题的细节瞬间", "在结尾点给这次散步下一个自己的定义"),
                "#3b82f6"
        );
        return toThemeResponse(callThemePrompt(buildPresetPrompt(request), fallback), 2L);
    }

    public ThemeResponse combineTheme(CombineThemeRequest request) {
        String categoriesText = String.join("、", request.categories());
        ThemePayload fallback = new ThemePayload(
                "混搭漫游提案",
                "把多种主题气质压进一条可执行的城市散步线里，让路线既有变化感，也能保持整体节奏和情绪一致。",
                "混搭",
                List.of("先找到最适合作为开场的主题入口", "中段故意安排一次气质切换，制造层次变化", "最后在最容易回味的场景收尾"),
                "#8b5cf6"
        );
        return toThemeResponse(callThemePrompt(buildCombinePrompt(request, categoriesText), fallback), 3L);
    }

    public WalkRecordCardTextResponse generateWalkRecordCardText(GenerateWalkRecordCardRequest request) {
        WalkRecordCardPayload fallback = new WalkRecordCardPayload(
                "今天这段路，刚好把心情放慢了一点。",
                "小六六在%s慢慢走着，把一路上的风、光影和细碎感受都收进了今天这张记录卡里。这不是刻意完成任务的一次打卡，更像是城市在某个瞬间给出的温柔回应。"
                        .formatted(request.locationName())
        );

        WalkRecordCardPayload payload = callWalkRecordCardPrompt(buildWalkRecordCardPrompt(request), fallback);
        return new WalkRecordCardTextResponse(
                payload.shortNote(),
                payload.story(),
                PROVIDER
        );
    }

    public LocationContextResponse locationContext(Double lat, Double lng) {
        String fallback = "这里像是一个适合边走边停的城市片区，周边既有可观察的街景，也有适合临时转向的小去处。";
        List<PoiResponse> nearbyPois = mapSearchService.nearbyPois(lat, lng);
        String poiSummary = buildPoiSummary(nearbyPois);
        String placeName = pickPlaceName(nearbyPois, null);
        String prompt = """
                请根据坐标和周边 POI，总结这片区域适合做 City Walk 的环境气质。
                纬度：%s
                经度：%s
                周边 POI 摘要：%s

                要求：
                1. 不要只描述单个点位，要概括成一个适合步行展开的片区印象。
                2. 输出 15 到 30 个中文字符的一小段描述。
                3. 不要使用列表，不要解释。
                """.formatted(lat, lng, poiSummary);
        return new LocationContextResponse(callTextPrompt(prompt, fallback), placeName);
    }

    public LocationContextResponse searchContext(String query) {
        String fallback = query + "周边像是一个适合随走随看的城市片区。";
        String prompt = """
                请根据地点名称，生成一句适合 City Walk 使用的区域氛围描述。
                地点：%s

                要求：
                1. 把它描述成一个适合步行探索的片区，而不是只解释这个地点本身。
                2. 输出 15 到 30 个中文字符。
                3. 不要使用列表，不要补充额外说明。
                """.formatted(query);
        return new LocationContextResponse(callTextPrompt(prompt, fallback), safeText(query, null));
    }

    public String provider() {
        return PROVIDER;
    }

    public String model() {
        return configuredModel;
    }

    private String buildGenerateThemePrompt(GenerateThemeRequest request) {
        return """
                请生成一个 City Walk 主题卡片，结合用户心情、天气、季节、偏好和地点信息，产出一个能直接拿去展示与执行的主题方案。

                用户心情：%s
                当前天气：%s
                当前季节：%s
                用户偏好：%s
                地点名称：%s
                地点环境：%s
                漫步模式：%s

                规划要求：
                1. 主题要围绕一个可步行展开的片区来设计，不要只围着单个点位打转。
                2. 默认按步行可承受的 2 到 3 公里范围组织体验。
                3. missions 必须是 3 条简短、可执行的小任务。
                4. description 写成一段有画面感但不空泛的中文介绍。
                5. 只返回一个 JSON 对象。

                JSON 结构：
                {
                  "title": "不超过 12 个字",
                  "description": "30 到 60 字",
                  "category": "一句话类别",
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
    }

    private String buildPresetPrompt(GeneratePresetThemeRequest request) {
        return """
                请围绕主题“%s”，结合地点“%s”和环境“%s”，生成一个可执行的 City Walk 主题卡片。
                漫步模式：%s

                规划要求：
                1. 主题要落在一个适合步行展开的片区，不要只围绕单个店或单个景点。
                2. 默认按步行可承受的 2 到 3 公里范围组织体验。
                3. missions 必须是 3 条简短、可执行的小任务。
                4. category 保持为“%s”或与其非常接近的表达。
                5. 只返回一个 JSON 对象。

                JSON 结构：
                {
                  "title": "不超过 12 个字",
                  "description": "30 到 60 字",
                  "category": "%s",
                  "missions": ["任务1", "任务2", "任务3"],
                  "vibeColor": "#RRGGBB"
                }
                """.formatted(
                request.category(),
                request.locationName(),
                request.locationContext(),
                request.walkMode(),
                request.category(),
                request.category()
        );
    }

    private String buildCombinePrompt(CombineThemeRequest request, String categoriesText) {
        return """
                请把这些 City Walk 主题融合成一个新的漫步卡片：%s
                地点名称：%s
                地点环境：%s
                漫步模式：%s

                规划要求：
                1. 组合后的主题要能在同一片步行区域自然展开。
                2. 默认按步行可承受的 2 到 3 公里范围组织体验。
                3. 不要机械拼接关键词，要让整体气质统一、可执行。
                4. missions 必须是 3 条简短、可执行的小任务。
                5. 只返回一个 JSON 对象。

                JSON 结构：
                {
                  "title": "不超过 12 个字",
                  "description": "30 到 60 字",
                  "category": "融合后的类别",
                  "missions": ["任务1", "任务2", "任务3"],
                  "vibeColor": "#RRGGBB"
                }
                """.formatted(
                categoriesText,
                request.locationName(),
                request.locationContext(),
                request.walkMode()
        );
    }

    private String buildWalkRecordCardPrompt(GenerateWalkRecordCardRequest request) {
        return """
                请以“小六六”的第一人称视角，为一张 City Walk 记录卡生成文案。
                主题标题：%s
                主题描述：%s
                任务内容：%s
                地点名称：%s
                地点环境：%s
                用户备注：%s
                是否有照片：%s

                写作要求：
                1. shortNote 写成一句可直接放在卡片上的短句，10 到 24 个中文字符。
                2. story 写成一段 70 到 120 字的中文小记录，要像真实散步后的感受，不要像 AI 说明书。
                3. 语气要自然、轻松、有一点画面感，不要每句都很满。
                4. 如果用户备注为空，也要根据主题和地点自然补全情绪。
                5. 只返回一个 JSON 对象。

                JSON 结构：
                {
                  "shortNote": "一句短句",
                  "story": "一段第一人称记录"
                }
                """.formatted(
                request.themeTitle(),
                safeText(request.themeDescription(), "一次围绕城市步行展开的小主题"),
                request.missionText(),
                request.locationName(),
                request.locationContext(),
                safeText(request.noteText(), "无"),
                request.hasPhoto() ? "有" : "没有"
        );
    }

    private String buildPoiSummary(List<PoiResponse> nearbyPois) {
        List<String> poiTitles = nearbyPois.stream()
                .map(PoiResponse::title)
                .filter(title -> title != null && !title.isBlank())
                .limit(6)
                .collect(Collectors.toList());
        if (poiTitles.isEmpty()) {
            return "暂无明确 POI，可按普通城市街区理解。";
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
            log.info("DeepSeek walk card text generated successfully with model {}", model());
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
            log.info("DeepSeek theme generated successfully with model {}", model());
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
            log.info("DeepSeek theme streamed successfully with model {}", model());
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
            log.info("DeepSeek text generated successfully with model {}", model());
            return raw.isBlank() ? fallback : raw;
        } catch (Exception error) {
            log.warn("DeepSeek text generation failed, using fallback: {}", error.getMessage());
            return fallback;
        }
    }

    private boolean isConfigured() {
        return configuredApiKey != null
                && !configuredApiKey.isBlank()
                && chatModelProvider.getIfAvailable() != null;
    }

    private String callDeepSeekStreaming(String prompt, boolean expectJson, ThemeStreamListener listener) {
        AtomicReference<ChatResponse> aggregatedResponse = new AtomicReference<>();
        new MessageAggregator()
                .aggregate(getStreamingChatModel().stream(buildPrompt(prompt, expectJson)), aggregatedResponse::set)
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

    private ChatModel getChatModel() {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new IllegalStateException("ChatModel is not available");
        }
        return chatModel;
    }

    private StreamingChatModel getStreamingChatModel() {
        StreamingChatModel streamingChatModel = streamingChatModelProvider.getIfAvailable();
        if (streamingChatModel != null) {
            return streamingChatModel;
        }
        ChatModel chatModel = getChatModel();
        if (chatModel instanceof StreamingChatModel compatibleStreamingChatModel) {
            return compatibleStreamingChatModel;
        }
        throw new IllegalStateException("StreamingChatModel is not available");
    }

    private Prompt buildPrompt(String prompt, boolean expectJson) {
        String normalizedPrompt = expectJson
                ? prompt + "\n\n只返回一个 JSON 对象，不要使用 markdown 代码块。"
                : prompt;
        return new Prompt(
                List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(normalizedPrompt)),
                ChatOptions.builder()
                        .model(model())
                        .temperature(0.8d)
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
            listener.onContentDelta("{\"title\":\"城市漫步提案\"}");
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
