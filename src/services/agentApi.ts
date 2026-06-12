import { apiRequest, getApiBaseUrlForDebug } from './apiClient';

export type AgentStreamEventType = 'start' | 'tool_call' | 'tool_result' | 'answer_delta' | 'final_answer' | 'complete' | 'agent_error';

export interface AgentStreamEvent {
  type: AgentStreamEventType;
  name: string;
  input?: string | null;
  output?: string | null;
  iteration?: number | null;
  provider?: string | null;
  model?: string | null;
  code?: string | null;
}

interface AgentStreamInitResponse {
  executionId: string;
  streamToken: string;
  expiresInSeconds: number;
}

export async function openAgentStream(prompt: string, executionId: string): Promise<EventSource> {
  const init = await apiRequest<AgentStreamInitResponse>('/api/v1/agent/stream/init', {
    method: 'POST',
    body: JSON.stringify({ executionId }),
  });
  const params = new URLSearchParams({
    prompt,
    executionId,
    streamToken: init.streamToken,
  });
  const streamUrl = `${getApiBaseUrlForDebug()}/api/v1/agent/stream?${params.toString()}`;
  return new EventSource(streamUrl);
}

export async function cancelAgentExecution(executionId: string): Promise<boolean> {
  const response = await apiRequest<{ success?: boolean }>('/api/v1/agent/cancel', {
    method: 'POST',
    body: JSON.stringify({ executionId }),
  });
  return Boolean(response?.success);
}

export async function clearAgentMemory(): Promise<void> {
  await apiRequest('/api/v1/agent/memory/clear', {
    method: 'POST',
  });
}
