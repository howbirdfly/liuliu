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
  const token = readAuthToken();
  const params = new URLSearchParams({
    prompt,
    ...(token ? { token } : {}),
  });
  const streamUrl = `${getApiBaseUrlForDebug()}/api/v1/agent/stream?${params.toString()}`;
  return new EventSource(streamUrl);
}

export async function clearAgentMemory(): Promise<void> {
  await apiRequest('/api/v1/agent/memory/clear', {
    method: 'POST',
  });
}
