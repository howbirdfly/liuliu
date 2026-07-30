package com.liuliu.citywalk.service;

import org.springframework.stereotype.Service;

@Service
public class AgentExecutionPromptGateService {

    public String buildClarificationQuestion(AgentIntentAnalysisService.AgentIntent intent) {
        if (intent == null || intent.isEmpty()) {
            return "先告诉我你想在哪个城市或区域逛、偏什么风格，以及大概想走多久，我再帮你开始规划。";
        }
        if (intent.missingLocationContext() && intent.missingThemeDirection()) {
            return "先告诉我你想在哪个城市或区域逛，或者是否直接用当前位置；另外也说一下你更想要什么风格或目标，比如拍照、夜景、安静散步，我再继续规划。";
        }
        if (intent.missingLocationContext()) {
            return "先告诉我你想在哪个城市或区域逛，或者是否直接用当前位置，我再帮你继续规划路线。";
        }
        if (intent.missingThemeDirection() && intent.missingDuration()) {
            return "先补两点我就能更稳地规划：你更想看什么或怎么玩，以及这次大概想走多久。";
        }
        if (intent.missingThemeDirection()) {
            return "先告诉我你这次更偏什么风格或目标，比如拍照、夜景、安静散步或觅食，我再继续规划。";
        }
        if (intent.missingDuration()) {
            return "先告诉我你这次大概想走多久，比如 1 小时、半天或一整晚，我再把路线收紧一点。";
        }
        return "我还差一点关键信息。你可以补一下城市、区域、风格偏好或预计时长，我再继续规划。";
    }

    public String buildValidInputPrompt(AgentIntentAnalysisService.AgentIntent intent) {
        if (intent != null && intent.acknowledgementOnly()) {
            return """
                    我还没拿到新的有效需求，所以这轮先不开始规划。
                    你可以随时再叫我，直接补这 2 到 3 类信息里的任意几项就行：
                    - 城市、区域，或者直接说“用当前位置”
                    - 想要的主题或玩法，比如夜景、拍照、老街、咖啡、安静散步
                    - 大概时长，比如 1 小时、半天、一个晚上
                    例如你可以直接说：
                    “在上海徐汇，想走一条适合晚上拍照的 2 小时 City Walk。”
                    """.trim();
        }
        return """
                这轮输入还不足以开始有效规划，我先不继续生成路线。
                你可以随时来找我，只要补一点有效信息我就能继续：
                - 城市、区域，或者直接说“用当前位置”
                - 想看的主题或目标，比如夜景、拍照、老街、咖啡、放空散步
                - 大概时长，比如 1 小时、半天、一个晚上
                例如：
                “我在杭州，想找一条适合傍晚散步和拍照的 City Walk，2 小时左右。”
                """.trim();
    }
}
