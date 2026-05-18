import { getApiBaseUrlForDebug, readAuthToken } from './apiClient';

export interface UploadFileResult {
  fileId: string;
  url: string;
  contentType: string;
  size: number;
}

interface UploadInitResult {
  fileId: string;
  objectName: string;
  host: string;
  policy: string;
  signature: string;
  accessKeyId: string;
  url: string;
  expireAt: number;
  successActionStatus: string;
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
  const file = dataUrlToFile(dataUrl, fileName);
  const token = readAuthToken();

  const initResponse = await fetch(`${getApiBaseUrlForDebug()}/api/v1/files/upload/init`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({
      bizType,
      fileName: file.name,
      contentType: file.type,
      size: file.size,
    }),
  });

  if (!initResponse.ok) {
    throw new Error(`Upload init failed: ${initResponse.status}`);
  }

  const initJson = await initResponse.json();
  if (initJson?.code !== 0) {
    throw new Error(initJson?.message || 'Upload init failed');
  }

  const initData = initJson.data as UploadInitResult;
  const formData = new FormData();
  formData.append('key', initData.objectName);
  formData.append('policy', initData.policy);
  formData.append('OSSAccessKeyId', initData.accessKeyId);
  formData.append('Signature', initData.signature);
  formData.append('success_action_status', initData.successActionStatus);
  formData.append('file', file);

  const ossResponse = await fetch(initData.host, {
    method: 'POST',
    body: formData,
  });

  if (!ossResponse.ok) {
    throw new Error(`OSS upload failed: ${ossResponse.status}`);
  }

  const completeResponse = await fetch(`${getApiBaseUrlForDebug()}/api/v1/files/upload/complete`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({
      fileId: initData.fileId,
      bizType,
      fileName: file.name,
      objectName: initData.objectName,
      url: initData.url,
      contentType: file.type,
      size: file.size,
    }),
  });

  if (!completeResponse.ok) {
    throw new Error(`Upload complete failed: ${completeResponse.status}`);
  }

  const completeJson = await completeResponse.json();
  if (completeJson?.code !== 0) {
    throw new Error(completeJson?.message || 'Upload complete failed');
  }

  return completeJson.data as UploadFileResult;
}
