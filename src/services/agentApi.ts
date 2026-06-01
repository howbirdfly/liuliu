import { apiRequest, getApiBaseUrlForDebug, readAuthToken } from './apiClient';

export type AgentStreamEventType = 'start' | 'tool_call' | 'tool_result' | 'final_answer' | 'complete';

export interface AgentStreamEvent {
  type: AgentStreamEventType;
  name: string;
  input?: string | null;
  output?: string | null;
  iteration?: number | null;
  provider?: string | null;
  model?: string | null;
}

export function openAgentStream(prompt: string): EventSource {
  // 这个项目里 EventSource 不方便自定义 Authorization 头，
  // 所以后端允许把登录 token 放在 query 参数里。
  const token = readAuthToken();
  const params = new URLSearchParams({
    prompt,
    ...(token ? { token } : {}),
  });
  const streamUrl = `${getApiBaseUrlForDebug()}/api/v1/agent/stream?${params.toString()}`;
  return new EventSource(streamUrl);
}

export async function clearAgentMemory(): Promise<void> {
  // 清空当前登录用户最近几轮 Agent 对话记忆。
  await apiRequest('/api/v1/agent/memory/clear', {
    method: 'POST',
  });
}
