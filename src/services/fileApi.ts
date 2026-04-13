import { getApiBaseUrlForDebug, getAuthTokenStorageKey } from './apiClient';

export interface UploadFileResult {
  fileId: string;
  url: string;
  contentType: string;
  size: number;
}

function dataUrlToFile(dataUrl: string, fileName: string): File {
  const [header, base64] = dataUrl.split(',');
  const mimeMatch = header.match(/data:(.*?);base64/);
  const mimeType = mimeMatch?.[1] || 'application/octet-stream';
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);

  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.charCodeAt(i);
  }

  return new File([bytes], fileName, { type: mimeType });
}

export async function uploadDataUrl(
  dataUrl: string,
  bizType: 'walk_cover' | 'mission_media' | 'audio' | 'video' | 'avatar',
  fileName: string,
): Promise<UploadFileResult> {
  const formData = new FormData();
  formData.append('file', dataUrlToFile(dataUrl, fileName));
  formData.append('bizType', bizType);
  const token = typeof window !== 'undefined' ? window.localStorage.getItem(getAuthTokenStorageKey()) : null;

  const response = await fetch(`${getApiBaseUrlForDebug()}/api/v1/files/upload`, {
    method: 'POST',
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: formData,
  });

  if (!response.ok) {
    throw new Error(`Upload failed: ${response.status}`);
  }

  const json = await response.json();
  if (json?.code !== 0) {
    throw new Error(json?.message || 'Upload failed');
  }

  return json.data as UploadFileResult;
}
