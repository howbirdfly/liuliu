import { apiRequest, getApiBaseUrlForDebug } from './apiClient';

export type AgentStreamEventType =
  | 'start'
  | 'intent_analysis'
  | 'pipeline_strategy'
  | 'tool_call'
  | 'tool_result'
  | 'progress'
  | 'answer_delta'
  | 'final_answer'
  | 'complete'
  | 'agent_error';

export interface AgentStreamEvent {
  type: AgentStreamEventType;
  name: string;
  input?: string | null;
  output?: string | null;
  iteration?: number | null;
  provider?: string | null;
  model?: string | null;
  code?: string | null;
  operationId?: string | null;
  phase?: string | null;
  message?: string | null;
}

export interface AgentChatStep {
  type: string;
  name: string;
  input?: string | null;
  output?: string | null;
}

export interface AgentChatResponse {
  answer: string;
  steps: AgentChatStep[];
  iterations?: number | null;
  provider?: string | null;
  model?: string | null;
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

export async function requestAgentChat(prompt: string): Promise<AgentChatResponse> {
  return apiRequest<AgentChatResponse>('/api/v1/agent/chat', {
    method: 'POST',
    body: JSON.stringify({ prompt }),
  });
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
