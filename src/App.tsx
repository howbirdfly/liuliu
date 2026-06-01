import React, { useEffect, useMemo, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import {
  Bell,
  Compass,
  History,
  ImagePlus,
  LoaderCircle,
  LogIn,
  LogOut,
  MapPin,
  LocateFixed,
  Search,
  Shuffle,
  Sparkles,
  UserRound,
  Users,
  Check,
  Heart,
  Bookmark,
  SlidersHorizontal,
  Pencil,
  X,
} from 'lucide-react';
import {
  AppUser,
  deleteAccountFromServer,
  getStoredToken,
  loadCurrentUser,
  logoutFromServer,
  loginWithEmail,
  registerWithEmail,
  resetPasswordWithEmail,
  sendEmailCode,
  updateUserProfile,
} from './services/authApi';
import {
  PRESET_THEMES,
  WalkTheme,
  generateAITheme,
  generateCombinedTheme,
  generateDynamicPreset,
  generateWalkRecordCardText,
  MapPOI,
  getLocationContext,
  getLocationContextDetails,
  searchLocationContext,
} from './services/themeService';
import {
  createCommunityComment,
  deleteCommunityComment,
  favoriteCommunityWalk,
  fetchCommunityComments,
  fetchCommunityFeed,
  fetchCommunityWalkDetail,
  fetchMyFavoritedCommunityWalks,
  fetchMyLikedCommunityWalks,
  likeCommunityWalk,
  searchCommunityWalks,
  unfavoriteCommunityWalk,
  unlikeCommunityWalk,
  type CommunityCommentItem,
  type CommunityEngagementState,
  type CommunityFeedTab,
  type CommunityWalkItem,
} from './services/communityApi';
import {
  fetchNotificationUnreadCount,
  fetchNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  openNotificationStream,
  type NotificationStreamEvent,
  type UserNotificationItem,
} from './services/notificationApi';
import { clearAgentMemory, openAgentStream, type AgentStreamEvent } from './services/agentApi';
import { createWalk, deleteWalk, fetchMyWalks, fetchWalkDetail, updateWalk, WalkItem } from './services/walkApi';
import { fetchNearbyPois, searchLocations } from './services/mapApi';
import { uploadDataUrl } from './services/fileApi';
import { getAuthRequiredEventName } from './services/apiClient';
import {
  CoCreateRoom,
  CoCreateRoomMember,
  createCoCreateRoom,
  fetchCurrentCoCreateRoom,
  fetchCoCreateRoom,
  joinCoCreateRoom,
  leaveCoCreateRoom,
  openCoCreateRoomSocket,
  type CoCreateRoomSocketEvent,
  updateCoCreateRoomState,
  updateCoCreateRoomTheme,
} from './services/roomApi';
import { ProfileCollectionTabs } from './components/ProfileCollectionTabs';
import { NotificationCenter } from './components/NotificationCenter';
import { ProfileCommunityEngagementCard } from './components/ProfileCommunityEngagementCard';
import { ProfileStatsGrid } from './components/ProfileStatsGrid';
import { ProfileWalkDetailBody } from './components/ProfileWalkDetailBody';
import { ProfileWalkCardList } from './components/ProfileWalkCardList';
import { WalkCommentSection } from './components/WalkCommentSection';
import remarkGfm from 'remark-gfm';

type SearchLocation = {
  name: string;
  lat: number;
  lng: number;
};

type PathPoint = {
  lat: number;
  lng: number;
  timestamp: number;
};

type CommunityReplyTarget = {
  id: number;
  authorNickname: string;
};

type WalkRecordCard = {
  title: string;
  missionText: string;
  shortNote: string;
  story: string;
  locationLabel: string;
  dateLabel: string;
  photoUrl?: string;
  serialNumber: string;
};

type EmailAuthMode = 'login' | 'register' | 'reset';

type RoomMapMember = {
  userId: number;
  nickname: string;
  avatarUrl?: string;
  trackColor: string;
  path: [number, number][];
  currentPosition: [number, number] | null;
};

const AGENT_QUICK_PROMPTS = [
  {
    label: '日落拍照',
    prompt: '我想找一条适合傍晚散步、看日落、拍照好看的 City Walk 路线，节奏轻松一点。',
  },
  {
    label: '咖啡散步',
    prompt: '帮我规划一条适合边走边逛、顺便喝咖啡的 City Walk 路线，最好街区氛围感强一点。',
  },
  {
    label: '校园轻松走',
    prompt: '我想在校园附近找一条轻松好走的散步路线，适合放松、拍照和随便逛逛。',
  },
  {
    label: '低体力 1 小时',
    prompt: '帮我规划一条总时长控制在 1 小时左右、步行压力不要太大的 City Walk 路线。',
  },
] as const;

function buildSavedRoomMembers(
  room: CoCreateRoom | null,
  currentUser: AppUser | null,
  currentPath: PathPoint[],
  currentLocation: SearchLocation | null,
  checkedMissionLabels: string[],
  tracking: boolean,
) {
  if (!room) {
    return [];
  }

  return room.members.map((member) => {
    const isCurrentUser = !!currentUser && member.userId === currentUser.id;
    const memberPath = isCurrentUser ? currentPath : member.path || [];
    const memberCurrentPosition = isCurrentUser
      ? currentLocation
        ? {
            lat: currentLocation.lat,
            lng: currentLocation.lng,
            timestamp: currentPath[currentPath.length - 1]?.timestamp ?? Date.now(),
          }
        : null
      : member.currentPosition || null;

    return {
      userId: member.userId,
      nickname: member.nickname,
      avatarUrl: isCurrentUser ? currentUser?.avatar : member.avatarUrl,
      trackColor: member.trackColor,
      isOwner: member.isOwner,
      isTracking: isCurrentUser ? tracking : member.isTracking,
      currentPosition: memberCurrentPosition,
      path: memberPath,
      completedMissions: isCurrentUser ? checkedMissionLabels : member.completedMissions || [],
    };
  });
}

function toRoomMapMembers(roomMembers?: WalkItem['roomMembers']): RoomMapMember[] {
  if (!Array.isArray(roomMembers)) {
    return [];
  }

  return roomMembers
    .filter((member) => member && typeof member.userId === 'number')
    .map((member) => ({
      userId: member.userId,
      avatarUrl: member.avatarUrl,
      nickname: member.nickname || '队友',
      trackColor: member.trackColor || '#2563eb',
      path: Array.isArray(member.path)
        ? member.path
            .filter((point) => typeof point?.lat === 'number' && typeof point?.lng === 'number')
            .map((point) => [point.lat, point.lng] as [number, number])
        : [],
      currentPosition:
        member.currentPosition && typeof member.currentPosition.lat === 'number' && typeof member.currentPosition.lng === 'number'
          ? ([member.currentPosition.lat, member.currentPosition.lng] as [number, number])
          : null,
    }));
}

const RANDOM_CATEGORIES = ['形状漫步', '颜色漫步', '声音漫步', '街区漫步', '自然漫步', '动物漫步'];
const COMBINE_CATEGORIES = ['形状漫步', '颜色漫步', '声音漫步', '街区漫步', '自然漫步', '动物漫步'];
const DEFAULT_CENTER: [number, number] = [31.2304, 121.4737];
const DEFAULT_MAP_ZOOM = 16;
const TRACKING_MAP_ZOOM = 19;
const MIN_TRACKING_DISTANCE_METERS = 2.5;
const MAX_ACCEPTABLE_POSITION_ACCURACY_METERS = 150;
const MAX_TIMED_TRACK_POINT_INTERVAL_MS = 5000;
const MAX_REASONABLE_WALKING_SPEED_MPS = 6;
const SHORT_INTERVAL_JUMP_WINDOW_MS = 15000;
const MIN_SHORT_INTERVAL_JUMP_DISTANCE_METERS = 120;
const ACTIVE_ROOM_CODE_STORAGE_KEY = 'citywalk_active_room_code';

declare global {
  interface Window {
    AMap?: any;
    __amapLoaderPromise?: Promise<any>;
  }
}

function getAmapJsKey() {
  return import.meta.env.VITE_AMAP_JS_KEY?.trim() || '';
}

function readStoredActiveRoomCode() {
  if (typeof window === 'undefined') {
    return '';
  }
  return window.sessionStorage.getItem(ACTIVE_ROOM_CODE_STORAGE_KEY)?.trim().toUpperCase() || '';
}

function writeStoredActiveRoomCode(roomCode: string) {
  if (typeof window === 'undefined') {
    return;
  }
  const normalized = roomCode.trim().toUpperCase();
  if (!normalized) {
    return;
  }
  window.sessionStorage.setItem(ACTIVE_ROOM_CODE_STORAGE_KEY, normalized);
}

function clearStoredActiveRoomCode() {
  if (typeof window === 'undefined') {
    return;
  }
  window.sessionStorage.removeItem(ACTIVE_ROOM_CODE_STORAGE_KEY);
}

function loadAmapJsApi(): Promise<any> {
  if (typeof window === 'undefined') {
    return Promise.reject(new Error('Current environment cannot load AMap.'));
  }

  if (window.AMap) {
    return Promise.resolve(window.AMap);
  }

  if (window.__amapLoaderPromise) {
    return window.__amapLoaderPromise;
  }

  const amapJsKey = getAmapJsKey();
  if (!amapJsKey) {
    return Promise.reject(new Error('Missing VITE_AMAP_JS_KEY.'));
  }

  window.__amapLoaderPromise = new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(amapJsKey)}&plugin=AMap.Scale,AMap.ToolBar`;
    script.async = true;
    script.onload = () => {
      if (window.AMap) {
        resolve(window.AMap);
        return;
      }
      reject(new Error('AMap loaded without global AMap.'));
    };
    script.onerror = () => reject(new Error('Failed to load AMap JS API.'));
    document.head.appendChild(script);
  });

  return window.__amapLoaderPromise;
}

function createMarkerContent(color: string, size = 18, label?: string) {
  const safeLabel = label ? escapeHtml(label) : '';
  return `
    <div style="position:relative;width:0;height:0;">
      ${
        safeLabel
          ? `<div style="
              position:absolute;
              left:50%;
              bottom:${size / 2 + 12}px;
              transform:translateX(-50%);
              max-width:140px;
              padding:4px 8px;
              border-radius:9999px;
              background:rgba(255,255,255,0.96);
              border:1px solid rgba(148,163,184,0.35);
              box-shadow:0 6px 18px rgba(15,23,42,0.14);
              color:#0f172a;
              font-size:12px;
              line-height:1.2;
              white-space:nowrap;
              overflow:hidden;
              text-overflow:ellipsis;
            ">${safeLabel}</div>`
          : ''
      }
      <div style="
        position:absolute;
        left:50%;
        top:50%;
        transform:translate(-50%,-50%);
        width:${size}px;
        height:${size}px;
        border-radius:9999px;
        background:${color};
        border:3px solid white;
        box-shadow:0 4px 12px rgba(15,23,42,0.28);
      "></div>
    </div>
  `;
}

function getMarkerFallbackText(label?: string) {
  const text = (label || '').trim();
  return text ? text.slice(0, 1).toUpperCase() : 'U';
}

function createAvatarMarkerContent(options: {
  color: string;
  size?: number;
  label?: string;
  avatarUrl?: string;
  fallbackText?: string;
}) {
  const { color, size = 22, label, avatarUrl, fallbackText } = options;
  const safeLabel = label ? escapeHtml(label) : '';
  const safeAvatarUrl = avatarUrl ? escapeHtml(avatarUrl) : '';
  const safeFallbackText = escapeHtml(getMarkerFallbackText(fallbackText || label));
  return `
    <div style="position:relative;width:0;height:0;">
      ${
        safeLabel
          ? `<div style="
              position:absolute;
              left:50%;
              bottom:${size / 2 + 12}px;
              transform:translateX(-50%);
              max-width:140px;
              padding:4px 8px;
              border-radius:9999px;
              background:rgba(255,255,255,0.96);
              border:1px solid rgba(148,163,184,0.35);
              box-shadow:0 6px 18px rgba(15,23,42,0.14);
              color:#0f172a;
              font-size:12px;
              line-height:1.2;
              white-space:nowrap;
              overflow:hidden;
              text-overflow:ellipsis;
            ">${safeLabel}</div>`
          : ''
      }
      <div style="
        position:absolute;
        left:50%;
        top:50%;
        transform:translate(-50%,-50%);
        width:${size}px;
        height:${size}px;
        border-radius:9999px;
        background:${color};
        border:3px solid white;
        box-shadow:0 8px 20px rgba(15,23,42,0.24);
        overflow:hidden;
        display:flex;
        align-items:center;
        justify-content:center;
        color:white;
        font-size:${Math.max(10, Math.floor(size * 0.42))}px;
        font-weight:700;
        line-height:1;
      ">
        <span>${safeFallbackText}</span>
        ${
          safeAvatarUrl
            ? `<img
                src="${safeAvatarUrl}"
                alt="${safeFallbackText}"
                style="position:absolute;inset:0;width:100%;height:100%;object-fit:cover;"
                onerror="this.style.display='none';"
              />`
            : ''
        }
      </div>
    </div>
  `;
}

function escapeHtml(value: string) {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function calculatePathDistance(points: PathPoint[]) {
  if (points.length < 2) {
    return 0;
  }

  let totalMeters = 0;
  for (let index = 1; index < points.length; index += 1) {
    totalMeters += calculateDistanceMeters(points[index - 1], points[index]);
  }

  return totalMeters;
}

function getWalkDistanceToReference(
  walk: WalkItem,
  referencePoint: { lat: number; lng: number } | null,
) {
  if (!referencePoint || !Array.isArray(walk.path) || walk.path.length === 0) {
    return Number.POSITIVE_INFINITY;
  }

  return walk.path.reduce((closestDistance, point) => {
    const distance = calculateDistanceMeters(referencePoint, point);
    return Math.min(closestDistance, distance);
  }, Number.POSITIVE_INFINITY);
}

function calculateDistanceMeters(
  start: { lat: number; lng: number },
  end: { lat: number; lng: number },
) {
  const earthRadius = 6371000;
  const dLat = ((end.lat - start.lat) * Math.PI) / 180;
  const dLng = ((end.lng - start.lng) * Math.PI) / 180;
  const lat1 = (start.lat * Math.PI) / 180;
  const lat2 = (end.lat * Math.PI) / 180;
  const haversine =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);

  return 2 * earthRadius * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
}

function isLikelyTrackJump(params: {
  distanceMeters: number;
  elapsedMs: number;
  accuracyMeters?: number;
}) {
  const { distanceMeters, elapsedMs, accuracyMeters } = params;
  const accuracyValue = Number.isFinite(accuracyMeters) ? Math.max(0, accuracyMeters ?? 0) : 999;

  if (!Number.isFinite(distanceMeters) || distanceMeters <= 0) {
    return false;
  }

  if (!Number.isFinite(elapsedMs) || elapsedMs <= 0) {
    return distanceMeters > Math.max(MIN_SHORT_INTERVAL_JUMP_DISTANCE_METERS, accuracyValue * 3);
  }

  if (
    elapsedMs <= SHORT_INTERVAL_JUMP_WINDOW_MS &&
    distanceMeters > Math.max(MIN_SHORT_INTERVAL_JUMP_DISTANCE_METERS, accuracyValue * 3)
  ) {
    return true;
  }

  const elapsedSeconds = elapsedMs / 1000;
  const maxReasonableDistance = Math.max(
    elapsedSeconds * MAX_REASONABLE_WALKING_SPEED_MPS + accuracyValue * 1.5,
    accuracyValue <= 25 ? 35 : accuracyValue <= 60 ? 60 : 90,
  );

  return distanceMeters > maxReasonableDistance;
}

function sanitizeCardText(value: string) {
  return value.replace(/\s+/g, ' ').trim();
}

const GENERIC_LOCATION_LABELS = new Set(['当前位置', '地图选点']);
const BROAD_LOCATION_PATTERN =
  /([\u4e00-\u9fa5A-Za-z0-9·]{2,40}(?:大学(?:[\u4e00-\u9fa5A-Za-z0-9·]{0,10}校区)?|学院(?:[\u4e00-\u9fa5A-Za-z0-9·]{0,10}校区)?|校区|科技园|软件园|工业园|园区|公园|商圈|街区|景区|新区|开发区|街道|镇|乡|村|社区))/g;

function isGenericLocationName(value?: string) {
  if (!value) {
    return true;
  }

  const trimmed = value.trim();
  if (!trimmed) {
    return true;
  }

  return GENERIC_LOCATION_LABELS.has(trimmed) || trimmed.startsWith('地图选点');
}

function isTooSpecificPoiName(value: string) {
  const trimmed = value.trim();
  if (!trimmed) {
    return false;
  }

  if (/[（(].+[)）]/.test(trimmed)) {
    return true;
  }

  const specificKeywords = ['咖啡', '便利店', '快递站', '驿站', '奶茶', '餐厅', '饭店', '超市', '店'];
  return specificKeywords.some((keyword) => trimmed.includes(keyword));
}

function scoreBroadLocationCandidate(value: string) {
  if (value.includes('大学') || value.includes('校区')) {
    return 4;
  }
  if (value.includes('园区') || value.includes('科技园') || value.includes('软件园') || value.includes('工业园')) {
    return 3;
  }
  if (value.includes('区') || value.includes('镇') || value.includes('街道') || value.includes('乡') || value.includes('村')) {
    return 2;
  }
  return 1;
}

function extractBroadLocationName(text?: string) {
  if (!text) {
    return '';
  }

  const normalized = sanitizeCardText(text).replace(/[，。；：]/g, ' ');
  if (!normalized) {
    return '';
  }

  const candidates: string[] = [];
  const bracketMatches = normalized.match(/[（(]([^()（）]{2,40})[)）]/g) || [];
  bracketMatches.forEach((match: string) => {
    const inner = match.slice(1, -1).trim();
    if (inner) {
      candidates.push(inner);
    }
  });

  const pushPatternMatches = (source: string) => {
    const matches = source.match(BROAD_LOCATION_PATTERN) || [];
    matches.forEach((item: string) => {
      const candidate = item.trim();
      if (candidate) {
        candidates.push(candidate);
      }
    });
  };

  pushPatternMatches(normalized);
  bracketMatches.forEach((match: string) => pushPatternMatches(match.slice(1, -1)));

  if (candidates.length === 0) {
    return '';
  }

  return [...new Set(candidates)]
    .sort((left, right) => {
      const scoreDelta = scoreBroadLocationCandidate(right) - scoreBroadLocationCandidate(left);
      if (scoreDelta !== 0) {
        return scoreDelta;
      }
      return right.length - left.length;
    })[0];
}

function deriveDisplayLocationName(rawName?: string, locationContextText?: string) {
  const trimmedName = rawName?.trim() || '';
  const broadFromName = extractBroadLocationName(trimmedName);
  const broadFromContext = extractBroadLocationName(locationContextText);

  if (isGenericLocationName(trimmedName)) {
    return broadFromContext || broadFromName || '当前区域';
  }

  if (isTooSpecificPoiName(trimmedName)) {
    return broadFromName || broadFromContext || trimmedName;
  }

  return trimmedName || broadFromContext || '当前区域';
}

function hasBroadLocationHint(value?: string) {
  return extractBroadLocationName(value).length > 0;
}

function escapeXml(value: string) {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&apos;');
}

function formatCardDate(timestamp = Date.now()) {
  const date = new Date(timestamp);
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}.${month}.${day}`;
}

function splitCardTitle(title: string) {
  const cleanTitle = sanitizeCardText(title) || '漫步记录卡';
  const parts = cleanTitle.split(/[：:]/);
  if (parts.length === 1) {
    return cleanTitle;
  }
  return sanitizeCardText(parts.slice(1).join(' ')) || cleanTitle;
}

function wrapCardText(text: string, maxCharsPerLine: number, maxLines?: number) {
  const cleanText = sanitizeCardText(text);
  if (!cleanText) {
    return [];
  }

  const lines: string[] = [];
  let current = '';
  for (const char of cleanText) {
    current += char;
    if (current.length >= maxCharsPerLine) {
      lines.push(current);
      current = '';
      if (maxLines && lines.length >= maxLines) {
        break;
      }
    }
  }

  if ((!maxLines || lines.length < maxLines) && current) {
    lines.push(current);
  }

  if (maxLines && lines.length > maxLines) {
    return lines.slice(0, maxLines);
  }

  if (maxLines && cleanText.length > maxCharsPerLine * maxLines && lines.length > 0) {
    const visibleLines = lines.slice(0, maxLines);
    const lastLine = visibleLines[visibleLines.length - 1];
    visibleLines[visibleLines.length - 1] = `${lastLine.slice(0, Math.max(0, lastLine.length - 1))}…`;
    return visibleLines;
  }

  return lines;
}

function renderSvgLines(lines: string[], x: number, y: number, lineHeight: number) {
  return lines
    .map((line, index) => `<tspan x="${x}" y="${y + index * lineHeight}">${escapeXml(line)}</tspan>`)
    .join('');
}

function formatWalkTime(timestamp = Date.now()) {
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(timestamp);
}

function getAgentEventLabel(type: AgentStreamEvent['type']) {
  switch (type) {
    case 'start':
      return '开始规划';
    case 'tool_call':
      return '调用工具';
    case 'tool_result':
      return '工具结果';
    case 'final_answer':
      return '最终回答';
    case 'complete':
      return '规划完成';
    default:
      return 'Agent 事件';
  }
}

function truncateAgentEventText(value?: string | null, maxLength = 180) {
  const normalized = sanitizeCardText(value || '');
  if (!normalized) {
    return '';
  }
  return normalized.length > maxLength ? `${normalized.slice(0, maxLength)}...` : normalized;
}

function normalizeAgentMarkdown(value: string) {
  return value
    .replace(/＊＊/g, '**')
    .replace(/＃/g, '#')
    .replace(/，---/g, '\n---\n')
    .trim();
}

function buildAgentPoiUri(title: string, lng?: number, lat?: number) {
  if (typeof lng !== 'number' || typeof lat !== 'number') {
    return '';
  }
  return `https://uri.amap.com/marker?position=${lng},${lat}&name=${encodeURIComponent(title)}`;
}

function calculateDistanceKm(lat1: number, lng1: number, lat2: number, lng2: number) {
  const toRadians = (value: number) => (value * Math.PI) / 180;
  const earthRadiusKm = 6371;
  const deltaLat = toRadians(lat2 - lat1);
  const deltaLng = toRadians(lng2 - lng1);
  const a =
    Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
    Math.cos(toRadians(lat1)) * Math.cos(toRadians(lat2)) * Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return earthRadiusKm * c;
}

function parseAgentEventJson(value?: string | null) {
  if (!value) {
    return null;
  }
  try {
    return JSON.parse(value) as Record<string, unknown>;
  } catch {
    return null;
  }
}

function getAgentResultItems(payload: Record<string, unknown>, key = 'results') {
  const value = payload[key];
  return Array.isArray(value) ? value : [];
}

function pickAgentItemLabel(item: unknown) {
  if (!item || typeof item !== 'object') {
    return '';
  }
  const record = item as Record<string, unknown>;
  return String(
    record.name ||
      record.title ||
      record.themeTitle ||
      record.locationName ||
      record.authorNickname ||
      record.id ||
      ''
  ).trim();
}

function summarizeAgentInput(event: AgentStreamEvent) {
  const payload = parseAgentEventJson(event.input);
  if (!payload) {
    return truncateAgentEventText(event.input, 180);
  }

  if (event.name === 'search_poi') {
    return payload.query ? `搜索关键词：${payload.query}` : '';
  }
  if (event.name === 'nearby_pois') {
    const lat = typeof payload.lat === 'number' ? payload.lat.toFixed(4) : payload.lat;
    const lng = typeof payload.lng === 'number' ? payload.lng.toFixed(4) : payload.lng;
    return lat && lng ? `搜索坐标：${lat}, ${lng}` : '';
  }
  if (event.name === 'search_community_guides') {
    const pageSize = payload.pageSize ? `，最多 ${payload.pageSize} 条` : '';
    return payload.keyword ? `检索关键词：${payload.keyword}${pageSize}` : '';
  }
  if (event.name === 'get_walk_detail') {
    return payload.walkId ? `查看 Walk 详情：#${payload.walkId}` : '';
  }

  return truncateAgentEventText(event.input, 180);
}

function summarizeAgentOutput(event: AgentStreamEvent) {
  if (event.type !== 'tool_result') {
    return truncateAgentEventText(event.output, 280);
  }

  const payload = parseAgentEventJson(event.output);
  if (!payload) {
    return truncateAgentEventText(event.output, 280);
  }

  if (event.name === 'search_poi') {
    const items = getAgentResultItems(payload);
    const names = items.map(pickAgentItemLabel).filter(Boolean).slice(0, 3);
    return items.length > 0
      ? `找到 ${items.length} 个候选地点，优先包括 ${names.join('、')}。`
      : '这次没有找到合适的地点候选。';
  }

  if (event.name === 'nearby_pois') {
    const items = getAgentResultItems(payload);
    const names = items.map(pickAgentItemLabel).filter(Boolean).slice(0, 4);
    return items.length > 0
      ? `附近共发现 ${items.length} 个兴趣点，可重点考虑 ${names.join('、')}。`
      : '附近暂时没有召回到合适的兴趣点。';
  }

  if (event.name === 'search_community_guides') {
    const items = getAgentResultItems(payload);
    const names = items.map(pickAgentItemLabel).filter(Boolean).slice(0, 3);
    return items.length > 0
      ? `从社区召回了 ${items.length} 条公开攻略，较相关的有 ${names.join('、')}。`
      : '社区里暂时没有找到强相关的公开攻略。';
  }

  if (event.name === 'get_walk_detail') {
    const found = Boolean(payload.found);
    const result = payload.result;
    if (!found || !result || typeof result !== 'object') {
      return payload.walkId ? `没有找到可公开查看的 Walk #${payload.walkId}。` : '没有找到可公开查看的 Walk。';
    }
    const walk = result as Record<string, unknown>;
    const themeTitle = walk.themeTitle || walk.locationName || '这条 Walk';
    const authorNickname = walk.authorNickname ? `，作者 ${walk.authorNickname}` : '';
    const tags = Array.isArray(walk.tags) ? walk.tags.slice(0, 3).join('、') : '';
    const tagText = tags ? `，标签包括 ${tags}` : '';
    return `已读取 ${themeTitle} 的详情${authorNickname}${tagText}。`;
  }

  return truncateAgentEventText(event.output, 280);
}

function extractAgentSuggestedPois(events: AgentStreamEvent[]): MapPOI[] {
  const ordered = [...events].reverse();
  const results: MapPOI[] = [];
  const seen = new Set<string>();

  for (const event of ordered) {
    if (event.type !== 'tool_result' || !event.output) {
      continue;
    }

    const payload = parseAgentEventJson(event.output);
    if (!payload) {
      continue;
    }

    const items = getAgentResultItems(payload);
    for (const item of items) {
      if (!item || typeof item !== 'object') {
        continue;
      }
      const record = item as Record<string, unknown>;
      const title = String(record.title || record.name || '').trim();
      const lat = typeof record.lat === 'number' ? record.lat : undefined;
      const lng = typeof record.lng === 'number' ? record.lng : undefined;
      if (!title || typeof lat !== 'number' || typeof lng !== 'number') {
        continue;
      }

      const key = `${title}-${lat}-${lng}`;
      if (seen.has(key)) {
        continue;
      }
      seen.add(key);
      results.push({
        title,
        lat,
        lng,
        uri: typeof record.uri === 'string' && record.uri.trim() ? record.uri.trim() : buildAgentPoiUri(title, lng, lat),
      });
    }
  }

  return results.slice(0, 8);
}

function mergeMapPois(primary: MapPOI[], secondary: MapPOI[]) {
  const merged: MapPOI[] = [];
  const seen = new Set<string>();
  for (const poi of [...primary, ...secondary]) {
    const key = `${poi.title}-${poi.lat ?? 'na'}-${poi.lng ?? 'na'}`;
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);
    merged.push(poi);
  }
  return merged;
}

function cleanAgentRouteCandidate(value: string) {
  return value
    .replace(/[`*#>]/g, ' ')
    .replace(/[📍🗺️🚶🏫☕🌇✨👉]/g, ' ')
    .replace(/第[一二三四五六七八九十0-9]+站[:：]?/g, ' ')
    .replace(/(漫步路线|路线顺序|建议路线顺序|适合区域|推荐区域|起点|终点)/g, ' ')
    .replace(/[()（）【】\[\]]/g, ' ')
    .replace(/[，,。；;：:]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function extractRoutePointCandidatesFromAnswer(answer: string) {
  const lines = normalizeAgentMarkdown(answer)
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);

  const candidates: string[] = [];

  for (const line of lines) {
    if (line.includes('→')) {
      line
        .split('→')
        .map(cleanAgentRouteCandidate)
        .map((item) => item.replace(/\s*漫步路线\s*/g, '').trim())
        .filter((item) => item.length >= 2 && item.length <= 24)
        .forEach((item) => candidates.push(item));
    }

    const stationMatch = line.match(/第[一二三四五六七八九十0-9]+站[:：]?\s*(.+)/);
    if (stationMatch?.[1]) {
      const cleaned = cleanAgentRouteCandidate(stationMatch[1])
        .split(/[，,]/)[0]
        .split(/\s+/)[0]
        .trim();
      if (cleaned.length >= 2 && cleaned.length <= 24) {
        candidates.push(cleaned);
      }
    }
  }

  return [...new Set(candidates)].slice(0, 6);
}

function normalizeSearchName(value: string) {
  return value.replace(/[()（）·•\s-]/g, '').toLowerCase();
}

function scoreRouteSearchResult(
  candidate: string,
  resultName: string,
  distanceKm: number
) {
  const normalizedCandidate = normalizeSearchName(candidate);
  const normalizedResult = normalizeSearchName(resultName);
  let score = -distanceKm;

  if (normalizedResult === normalizedCandidate) {
    score += 1000;
  } else if (normalizedResult.startsWith(normalizedCandidate)) {
    score += 600;
  } else if (normalizedResult.includes(normalizedCandidate)) {
    score += 260;
  }

  if (normalizedResult.includes('店') || normalizedResult.includes('酒店') || normalizedResult.includes('咖啡')) {
    score -= 120;
  }

  return score;
}

function isConfidentRouteSearchMatch(score: number, distanceKm: number) {
  if (distanceKm > 120) {
    return false;
  }
  if (distanceKm > 60 && score < 400) {
    return false;
  }
  if (distanceKm > 20 && score < 180) {
    return false;
  }
  return score > 0;
}

function stripMarkdownSyntax(value: string) {
  return value
    .replace(/[`*_>#-]/g, ' ')
    .replace(/\[(.*?)\]\((.*?)\)/g, '$1')
    .replace(/\s+/g, ' ')
    .trim();
}

function extractAgentThemeTitle(answer: string, routeCandidates: string[], fallbackLocationName: string) {
  const lines = normalizeAgentMarkdown(answer)
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);

  const headingLine = lines.find((line) => /^#{1,4}\s+/.test(line));
  const cleanedHeading = headingLine ? stripMarkdownSyntax(headingLine.replace(/^#{1,4}\s+/, '')) : '';
  if (cleanedHeading && cleanedHeading.length <= 36) {
    return cleanedHeading;
  }

  if (routeCandidates.length >= 2) {
    return `${routeCandidates[0]} -> ${routeCandidates[routeCandidates.length - 1]} 漫步路线`;
  }

  if (routeCandidates.length === 1) {
    return `${routeCandidates[0]} 周边漫步`;
  }

  return `${fallbackLocationName} Agent 漫步建议`;
}

function extractAgentThemeDescription(answer: string, title: string) {
  const lines = normalizeAgentMarkdown(answer)
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);

  for (const line of lines) {
    if (/^#{1,6}\s+/.test(line)) {
      continue;
    }
    if (/^[-*]\s+/.test(line)) {
      continue;
    }
    const cleaned = stripMarkdownSyntax(line);
    if (cleaned && cleaned !== title && cleaned.length >= 12) {
      return cleaned.length > 80 ? `${cleaned.slice(0, 80)}...` : cleaned;
    }
  }

  return '沿着 Agent 推荐的路线慢慢走，把沿途最想停下来的画面、气味和节奏记下来。';
}

function inferAgentThemeCategory(answer: string, fallback = 'Agent 漫步') {
  const normalized = normalizeAgentMarkdown(answer);
  if (/(日落|晚霞|拍照|光影)/.test(normalized)) {
    return '日落漫步';
  }
  if (/(咖啡|店铺|街区|古镇|老街)/.test(normalized)) {
    return '街区漫步';
  }
  if (/(校园|大学|图书馆|宿舍)/.test(normalized)) {
    return '校园漫步';
  }
  if (/(海边|公园|绿地|自然)/.test(normalized)) {
    return '自然漫步';
  }
  return fallback;
}

function inferAgentThemeColor(answer: string) {
  const normalized = normalizeAgentMarkdown(answer);
  if (/(日落|晚霞|夜景|灯光)/.test(normalized)) {
    return '#f97316';
  }
  if (/(海边|海岸|海风|公园|绿地|自然)/.test(normalized)) {
    return '#0ea5a4';
  }
  if (/(校园|大学|图书馆)/.test(normalized)) {
    return '#2563eb';
  }
  if (/(古镇|街区|店铺|老街)/.test(normalized)) {
    return '#f59e0b';
  }
  return '#8b5cf6';
}

function buildAgentThemeMissions(routeCandidates: string[], answer: string) {
  const routeMissions = routeCandidates.slice(0, 3).map((point, index, points) => {
    if (index === 0 && points.length > 1) {
      return `从 ${point} 出发，先记录这条路线最吸引你的第一眼氛围`;
    }
    if (index === points.length - 1 && points.length > 1) {
      return `走到 ${point} 时停留片刻，记下这条路线最值得分享的瞬间`;
    }
    return `经过 ${point} 时观察周边最有记忆点的细节，并拍下一张代表画面`;
  });

  if (routeMissions.length >= 3) {
    return routeMissions;
  }

  const fallbackMissions = normalizeAgentMarkdown(answer)
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => /^[-*]\s+/.test(line))
    .map((line) => stripMarkdownSyntax(line.replace(/^[-*]\s+/, '')))
    .filter((line) => line.length >= 6 && line.length <= 40);

  const merged = [...routeMissions];
  for (const mission of fallbackMissions) {
    if (!merged.includes(mission)) {
      merged.push(mission);
    }
    if (merged.length >= 3) {
      break;
    }
  }

  while (merged.length < 3) {
    if (merged.length === 0) {
      merged.push('沿着 Agent 推荐路线出发，先找到今天最想慢下来的一个位置');
    } else if (merged.length === 1) {
      merged.push('在途中记录一处最有氛围感的街角、建筑或风景');
    } else {
      merged.push('结束前总结这条路线最适合分享给朋友的理由');
    }
  }

  return merged.slice(0, 3);
}

function buildThemeFromAgentAnswer(answer: string, routeCandidates: string[], fallbackLocationName: string): WalkTheme {
  const title = extractAgentThemeTitle(answer, routeCandidates, fallbackLocationName);
  return {
    title,
    description: extractAgentThemeDescription(answer, title),
    category: inferAgentThemeCategory(answer),
    missions: buildAgentThemeMissions(routeCandidates, answer),
    vibeColor: inferAgentThemeColor(answer),
    provider: 'agent',
  };
}

function generateWalkRecordCard(params: {
  theme: WalkTheme;
  locationName: string;
  locationContext: string;
  noteText: string;
  photoUrl?: string;
  completedMissions?: string[];
  timestamp?: number;
}) {
  const { theme, locationName, locationContext, noteText, photoUrl, completedMissions = [], timestamp = Date.now() } = params;
  const title = splitCardTitle(theme.title);
  const taskList = completedMissions.length > 0 ? completedMissions : theme.missions;
  const missionText =
    taskList[0] ||
    theme.description ||
    '找到这一段散步里最想留下来的细节，并轻轻说出它像什么。';
  const shortNote =
    sanitizeCardText(noteText) ||
    `今天在${locationName}，先把这一刻静静地留给自己。`;
  const storySeed = sanitizeCardText(locationContext || theme.description);
  const story =
    sanitizeCardText(noteText)
      ? `小猫66在${locationName}跟着你轻轻晃了一圈喵，${storySeed}慢慢把这段路铺成了软乎乎的节奏。你留意到的细节也被我叼进了怀里，最后一起变成今天这张有点暖、有点黏人的城市切片。`
      : `小猫66跟着你在${locationName}慢慢散步喵，${storySeed}像替这次${title}补上了会发光的旁白。那些被风、树影和路口悄悄拎出来的小细节，最后都落进了今天这张陪伴记录卡里。`;

  return {
    title,
    missionText,
    shortNote,
    story,
    locationLabel: locationName,
    dateLabel: formatCardDate(timestamp),
    photoUrl,
    serialNumber: String(new Date(timestamp).getDate()).padStart(2, '0'),
  } satisfies WalkRecordCard;
}

async function generateWalkRecordCardWithAi(params: {
  theme: WalkTheme;
  locationName: string;
  locationContext: string;
  noteText: string;
  photoUrl?: string;
  completedMissions?: string[];
  timestamp?: number;
}) {
  const fallbackCard = generateWalkRecordCard(params);
  const taskList = params.completedMissions?.length ? params.completedMissions : params.theme.missions;
  const missionText =
    taskList?.[0] ||
    params.theme.description ||
    '找到这一段散步里最想留下来的细节，并轻轻说出它像什么。';

  try {
    const aiText = await generateWalkRecordCardText({
      themeTitle: params.theme.title,
      themeDescription: params.theme.description,
      missionText,
      locationName: params.locationName,
      locationContext: params.locationContext,
      noteText: params.noteText,
      hasPhoto: Boolean(params.photoUrl),
    });

    return {
      ...fallbackCard,
      story: sanitizeCardText(aiText.story) || fallbackCard.story,
    } satisfies WalkRecordCard;
  } catch (error) {
    console.error('Generate walk record card text error:', error);
    return fallbackCard;
  }
}

function buildWalkRecordCardSvg(card: WalkRecordCard) {
  const titleLines = wrapCardText(card.title, 12, 2);
  const missionLines = wrapCardText(`任务：${card.missionText}`, 18, 3);
  const shortNoteLines = wrapCardText(card.shortNote, 14, 2);
  const storyLines = wrapCardText(card.story, 14, 9);
  const locationLines = wrapCardText(card.locationLabel, 12, 1);
  const storyStartY = 748;
  const storyLineHeight = 46;
  const storyContentBottom = storyStartY + Math.max(storyLines.length - 1, 0) * storyLineHeight;
  const storyPanelBottom = Math.max(1130, storyContentBottom + 120);
  const photoSectionTop = storyPanelBottom + 116;
  const photoFrameY = photoSectionTop + 32;
  const footerY = photoFrameY + 458;
  const photoBlock = card.photoUrl
    ? `
      <clipPath id="photoClip">
        <rect x="44" y="${photoFrameY}" width="552" height="420" rx="28" ry="28" />
      </clipPath>
      <image href="${escapeXml(card.photoUrl)}" x="44" y="${photoFrameY}" width="552" height="420" preserveAspectRatio="xMidYMid slice" clip-path="url(#photoClip)" />
    `
    : `
      <rect x="44" y="${photoFrameY}" width="552" height="420" rx="28" ry="28" fill="#f8efe4" stroke="#d8c5ab" stroke-width="2" />
      <text x="320" y="${photoFrameY + 220}" text-anchor="middle" font-size="28" fill="#aa8c6d" font-family="'Noto Serif SC','STSong',serif">等你把这一刻拍下来</text>
    `;

  return `
    <svg xmlns="http://www.w3.org/2000/svg" width="640" height="${footerY + 110}" viewBox="0 0 640 ${footerY + 110}">
      <rect width="640" height="${footerY + 110}" rx="34" fill="#f9edd8" />
      <rect x="52" y="58" width="92" height="146" rx="14" fill="#cfe3f4" />
      <rect x="70" y="76" width="78" height="110" rx="10" fill="#c76c49" />
      <text x="109" y="118" text-anchor="middle" font-size="18" font-weight="700" fill="#fffaf2" font-family="'Trebuchet MS',sans-serif">POST</text>
      <text x="109" y="160" text-anchor="middle" font-size="46" font-weight="700" fill="#fffaf2" font-family="'Trebuchet MS',sans-serif">${escapeXml(card.serialNumber)}</text>
      <text x="109" y="188" text-anchor="middle" font-size="16" font-weight="700" fill="#fffaf2" font-family="'Trebuchet MS',sans-serif">84</text>

      <circle cx="228" cy="90" r="42" fill="none" stroke="#c97b5d" stroke-width="5" />
      <circle cx="228" cy="90" r="29" fill="none" stroke="#c97b5d" stroke-width="3" stroke-dasharray="4 6" />
      <text x="228" y="97" text-anchor="middle" font-size="18" fill="#8d5e43" font-family="'Noto Serif SC','STSong',serif">记录</text>

      <rect x="378" y="70" width="190" height="68" rx="18" fill="#f7f1e7" stroke="#d8c8b6" stroke-width="2" />
      <text x="473" y="114" text-anchor="middle" font-size="20" fill="#b59b7f" letter-spacing="4" font-family="'Courier New',monospace">AIR MAIL NOTE</text>

      <g transform="rotate(-8 392 104)">
        <rect x="312" y="74" width="150" height="52" rx="12" fill="none" stroke="#7d6caa" stroke-width="4" />
        <text x="387" y="108" text-anchor="middle" font-size="20" font-weight="700" fill="#5e4e92" font-family="'Trebuchet MS',sans-serif">AIR</text>
      </g>
      <g transform="rotate(35 530 160)">
        <rect x="468" y="120" width="116" height="150" rx="12" fill="none" stroke="#4f79b4" stroke-width="5" />
        <text x="526" y="202" text-anchor="middle" font-size="18" font-weight="700" fill="#4f79b4" font-family="'Trebuchet MS',sans-serif">LIVELY</text>
      </g>

      <text x="56" y="126" font-size="20" fill="#9d8b78" font-family="'Noto Serif SC','STSong',serif">陪你记录这一站</text>
      <text x="184" y="150" font-size="34" font-weight="800" fill="#201b18" font-family="'Noto Sans SC','Microsoft YaHei',sans-serif">${renderSvgLines(titleLines, 184, 150, 42)}</text>

      <text x="58" y="236" font-size="24" fill="#aa8c6d">三</text>
      <text x="186" y="264" font-size="22" fill="#665142" font-family="'Noto Serif SC','STSong',serif">${renderSvgLines(missionLines, 186, 264, 32)}</text>

      <path d="M42 315 C72 328 102 302 132 315 S192 328 222 315 S282 302 312 315 S372 328 402 315 S462 302 492 315 S552 328 582 315" fill="none" stroke="#dc8a73" stroke-width="4" />
      <path d="M42 323 C72 336 102 310 132 323 S192 336 222 323 S282 310 312 323 S372 336 402 323 S462 310 492 323 S552 336 582 323" fill="none" stroke="#7da0d9" stroke-width="4" />

      <text x="42" y="388" font-size="22" font-weight="700" fill="#9d6a3d" font-family="'Noto Sans SC','Microsoft YaHei',sans-serif">我 / 我的记录</text>
      <path d="M46 442 H432 Q472 442 472 482 V532 Q472 572 432 572 H72 Q42 572 42 542 V482 Q42 442 82 442 Z" fill="#fffaf4" stroke="#e2bb91" stroke-width="2" />
      <path d="M88 572 L70 610 L114 572" fill="#fffaf4" stroke="#e2bb91" stroke-width="2" />
      <text x="84" y="496" font-size="22" fill="#503729" font-family="'Noto Serif SC','STSong',serif">${renderSvgLines(shortNoteLines, 84, 496, 34)}</text>

      <text x="462" y="644" font-size="20" font-weight="700" fill="#cc6f46" font-family="'Trebuchet MS',sans-serif">66 的记录 66</text>
      <path d="M160 674 H550 Q590 674 590 714 V${storyPanelBottom - 40} Q590 ${storyPanelBottom} 550 ${storyPanelBottom} H190 Q160 ${storyPanelBottom} 160 ${storyPanelBottom - 30} V714 Q160 674 200 674 Z" fill="#fff0e3" stroke="#e8a37e" stroke-width="2" />
      <path d="M546 ${storyPanelBottom} L584 ${storyPanelBottom + 28} L548 ${storyPanelBottom - 42}" fill="#fff0e3" stroke="#e8a37e" stroke-width="2" />
      <text x="194" y="${storyStartY}" font-size="18" fill="#5b4337" font-family="'Noto Serif SC','STSong',serif">${renderSvgLines(storyLines, 194, storyStartY, storyLineHeight)}</text>

      <text x="44" y="${photoSectionTop}" font-size="20" font-weight="700" fill="#8b673e" font-family="'Noto Sans SC','Microsoft YaHei',sans-serif">我拍下的样子</text>
      ${photoBlock}
      <rect x="44" y="${footerY}" width="172" height="44" rx="18" fill="#fff8f0" stroke="#d8c5ab" stroke-width="2" />
      <text x="130" y="${footerY + 29}" text-anchor="middle" font-size="16" fill="#7f644f" font-family="'Courier New',monospace">${escapeXml(card.dateLabel)}</text>
      <text x="268" y="${footerY + 29}" font-size="16" fill="#8c755c" font-family="'Noto Sans SC','Microsoft YaHei',sans-serif">${renderSvgLines(locationLines, 268, footerY + 29, 24)}</text>

      <g transform="rotate(-12 542 ${footerY + 12})">
        <rect x="468" y="${footerY - 18}" width="110" height="54" rx="12" fill="none" stroke="#4f79b4" stroke-width="5" />
        <text x="523" y="${footerY + 18}" text-anchor="middle" font-size="18" font-weight="700" fill="#4f79b4" font-family="'Trebuchet MS',sans-serif">SORT</text>
      </g>
    </svg>
  `;
}

function svgToDataUrl(svg: string) {
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
}

function convertWgs84ToGcj02(lat: number, lng: number) {
  if (isOutOfChina(lat, lng)) {
    return { lat, lng };
  }

  const a = 6378245.0;
  const ee = 0.00669342162296594323;
  let dLat = transformLat(lng - 105.0, lat - 35.0);
  let dLng = transformLng(lng - 105.0, lat - 35.0);
  const radLat = (lat / 180.0) * Math.PI;
  let magic = Math.sin(radLat);
  magic = 1 - ee * magic * magic;
  const sqrtMagic = Math.sqrt(magic);
  dLat = (dLat * 180.0) / (((a * (1 - ee)) / (magic * sqrtMagic)) * Math.PI);
  dLng = (dLng * 180.0) / ((a / sqrtMagic) * Math.cos(radLat) * Math.PI);

  return {
    lat: lat + dLat,
    lng: lng + dLng,
  };
}

function isOutOfChina(lat: number, lng: number) {
  return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
}

function transformLat(x: number, y: number) {
  let result =
    -100.0 +
    2.0 * x +
    3.0 * y +
    0.2 * y * y +
    0.1 * x * y +
    0.2 * Math.sqrt(Math.abs(x));
  result +=
    ((20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0) / 3.0;
  result +=
    ((20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin((y / 3.0) * Math.PI)) * 2.0) / 3.0;
  result +=
    ((160.0 * Math.sin((y / 12.0) * Math.PI) + 320 * Math.sin((y * Math.PI) / 30.0)) * 2.0) / 3.0;
  return result;
}

function transformLng(x: number, y: number) {
  let result =
    300.0 +
    x +
    2.0 * y +
    0.1 * x * x +
    0.1 * x * y +
    0.1 * Math.sqrt(Math.abs(x));
  result +=
    ((20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0) / 3.0;
  result +=
    ((20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin((x / 3.0) * Math.PI)) * 2.0) / 3.0;
  result +=
    ((150.0 * Math.sin((x / 12.0) * Math.PI) + 300.0 * Math.sin((x / 30.0) * Math.PI)) * 2.0) / 3.0;
  return result;
}

function AmapScene(props: {
  center: [number, number];
  selectedLocation: SearchLocation | null;
  currentPosition: SearchLocation | null;
  currentUserAvatar?: string;
  currentUserNickname?: string;
  followCurrentPosition: boolean;
  pathCoordinates: [number, number][];
  roomMembers?: RoomMapMember[];
  nearbyPois: MapPOI[];
  fitPoisToView?: boolean;
  selectedPoiKey: string | null;
  onSelectMapPoint: (lat: number, lng: number) => void;
  onSelectPoi: (poi: MapPOI) => void;
}) {
  const {
    center,
    selectedLocation,
    currentPosition,
    currentUserAvatar,
    currentUserNickname,
    followCurrentPosition,
    pathCoordinates,
    roomMembers = [],
    nearbyPois,
    fitPoisToView = false,
    selectedPoiKey,
    onSelectMapPoint,
    onSelectPoi,
  } = props;
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<any>(null);
  const overlaysRef = useRef<any[]>([]);
  const infoWindowRef = useRef<any>(null);
  const onSelectMapPointRef = useRef(onSelectMapPoint);
  const previousFollowCurrentPositionRef = useRef(followCurrentPosition);
  const [isRecenteringCurrentPosition, setIsRecenteringCurrentPosition] = useState(false);
  const [mapReadyVersion, setMapReadyVersion] = useState(0);
  const isSameCoordinate =
    selectedLocation &&
    currentPosition &&
    Math.abs(selectedLocation.lat - currentPosition.lat) < 0.000001 &&
    Math.abs(selectedLocation.lng - currentPosition.lng) < 0.000001;
  const hasRoomTracks = roomMembers.length > 0;

  useEffect(() => {
    onSelectMapPointRef.current = onSelectMapPoint;
  }, [onSelectMapPoint]);

  useEffect(() => {
    let isDisposed = false;

    loadAmapJsApi()
      .then((AMap) => {
        if (isDisposed || !containerRef.current || mapRef.current) {
          return;
        }

        const map = new AMap.Map(containerRef.current, {
          zoom: DEFAULT_MAP_ZOOM,
          center: [center[1], center[0]],
          resizeEnable: true,
          viewMode: '2D',
        });

        map.addControl(new AMap.Scale());
        map.addControl(new AMap.ToolBar());
        map.on('click', (event: any) => {
          onSelectMapPointRef.current(event.lnglat.getLat(), event.lnglat.getLng());
        });

        mapRef.current = map;
        setMapReadyVersion((value) => value + 1);
      })
      .catch((error) => {
        console.error('Load AMap error:', error);
      });

    return () => {
      isDisposed = true;
      if (infoWindowRef.current) {
        infoWindowRef.current.close();
      }
      if (mapRef.current) {
        mapRef.current.destroy();
        mapRef.current = null;
      }
      setMapReadyVersion(0);
    };
  }, []);

  useEffect(() => {
    if (!mapRef.current) {
      return;
    }

    const map = mapRef.current;
    const nextCenter: [number, number] = [center[1], center[0]];
    const wasFollowing = previousFollowCurrentPositionRef.current;

    if (followCurrentPosition) {
      if (!wasFollowing) {
        map.setZoomAndCenter(TRACKING_MAP_ZOOM, nextCenter);
      } else {
        map.setCenter(nextCenter);
      }
    } else {
      map.setZoomAndCenter(DEFAULT_MAP_ZOOM, nextCenter);
    }

    previousFollowCurrentPositionRef.current = followCurrentPosition;
  }, [center, followCurrentPosition]);

  useEffect(() => {
    const map = mapRef.current;
    const AMap = window.AMap;
    if (!map || !AMap) {
      return;
    }

    if (overlaysRef.current.length > 0) {
      map.remove(overlaysRef.current);
      overlaysRef.current = [];
    }

    const overlays: any[] = [];

    if (selectedLocation && !followCurrentPosition && !isSameCoordinate) {
      overlays.push(
        new AMap.Marker({
          position: [selectedLocation.lng, selectedLocation.lat],
          anchor: 'center',
          offset: new AMap.Pixel(0, 0),
          content: createMarkerContent('#0f172a', 20, selectedLocation.name),
          title: selectedLocation.name,
        }),
      );
    }

    if (currentPosition && !hasRoomTracks) {
      overlays.push(
        new AMap.Marker({
          position: [currentPosition.lng, currentPosition.lat],
          anchor: 'center',
          offset: new AMap.Pixel(0, 0),
          content: createAvatarMarkerContent({
            color: '#f97316',
            size: 22,
            label: currentPosition.name,
            avatarUrl: currentUserAvatar,
            fallbackText: currentUserNickname || currentPosition.name,
          }),
          title: currentPosition.name,
        }),
      );
    } else if (selectedLocation && !followCurrentPosition && isSameCoordinate) {
      overlays.push(
        new AMap.Marker({
          position: [selectedLocation.lng, selectedLocation.lat],
          anchor: 'center',
          offset: new AMap.Pixel(0, 0),
          content: createMarkerContent('#0f172a', 20, selectedLocation.name),
          title: selectedLocation.name,
        }),
      );
    }

    const poiOverlays: any[] = [];

    nearbyPois
      .filter((poi) => typeof poi.lat === 'number' && typeof poi.lng === 'number')
      .forEach((poi) => {
        const poiKey = `${poi.title}-${poi.lat}-${poi.lng}`;
        const marker = new AMap.Marker({
          position: [poi.lng as number, poi.lat as number],
          anchor: 'center',
          offset: new AMap.Pixel(0, 0),
          content: createMarkerContent(selectedPoiKey === poiKey ? '#f59e0b' : '#2563eb', 18, poi.title),
          title: poi.title,
        });

        marker.on('click', () => {
          onSelectPoi(poi);
          if (!infoWindowRef.current) {
            infoWindowRef.current = new AMap.InfoWindow({
              offset: new AMap.Pixel(0, -24),
            });
          }

          infoWindowRef.current.setContent(`
            <div style="padding:4px 2px;min-width:180px;">
              <div style="font-weight:600;color:#0f172a;">${escapeHtml(poi.title)}</div>
              <div style="margin-top:6px;font-size:12px;color:#475569;">已切换为当前地点，AI 会围绕这里继续生成内容。</div>
              <a href="${poi.uri}" target="_blank" rel="noreferrer" style="display:inline-block;margin-top:8px;font-size:12px;color:#2563eb;text-decoration:underline;">在高德中查看</a>
            </div>
          `);
          infoWindowRef.current.open(map, [poi.lng as number, poi.lat as number]);
        });

        overlays.push(marker);
        poiOverlays.push(marker);
      });

    if (pathCoordinates.length > 1 && !hasRoomTracks) {
      overlays.push(
        new AMap.Polyline({
          path: pathCoordinates.map(([lat, lng]) => [lng, lat]),
          strokeColor: '#f59e0b',
          strokeWeight: 5,
          strokeOpacity: 0.95,
          lineJoin: 'round',
          lineCap: 'round',
        }),
      );
    }

    roomMembers.forEach((member) => {
      if (member.path.length > 1) {
        overlays.push(
          new AMap.Polyline({
            path: member.path.map(([lat, lng]) => [lng, lat]),
            strokeColor: member.trackColor,
            strokeWeight: 4,
            strokeOpacity: 0.85,
            lineJoin: 'round',
            lineCap: 'round',
            strokeStyle: 'dashed',
          }),
        );
      }

      if (member.currentPosition) {
        overlays.push(
          new AMap.Marker({
            position: [member.currentPosition[1], member.currentPosition[0]],
            anchor: 'center',
            offset: new AMap.Pixel(0, 0),
            content: createAvatarMarkerContent({
              color: member.trackColor,
              size: 22,
              label: `${member.nickname}位置`,
              avatarUrl: member.avatarUrl,
              fallbackText: member.nickname,
            }),
            title: member.nickname,
          }),
        );
      }
    });

    if (overlays.length > 0) {
      map.add(overlays);
    }

    overlaysRef.current = overlays;
    if (fitPoisToView && poiOverlays.length > 1) {
      map.setFitView(poiOverlays, false, [64, 64, 64, 64]);
    }
  }, [currentPosition, fitPoisToView, hasRoomTracks, isSameCoordinate, mapReadyVersion, nearbyPois, onSelectPoi, pathCoordinates, roomMembers, selectedLocation, selectedPoiKey]);

  const handleRecenterCurrentPosition = () => {
    if (!mapRef.current || isRecenteringCurrentPosition) {
      return;
    }

    if (currentPosition) {
      mapRef.current.setZoomAndCenter(TRACKING_MAP_ZOOM, [currentPosition.lng, currentPosition.lat]);
      return;
    }

    if (!navigator.geolocation) {
      return;
    }

    setIsRecenteringCurrentPosition(true);
    navigator.geolocation.getCurrentPosition(
      (position) => {
        const gcjPosition = convertWgs84ToGcj02(position.coords.latitude, position.coords.longitude);
        mapRef.current?.setZoomAndCenter(TRACKING_MAP_ZOOM, [gcjPosition.lng, gcjPosition.lat]);
        setIsRecenteringCurrentPosition(false);
      },
      (error) => {
        console.error('Recenter current position error:', error);
        setIsRecenteringCurrentPosition(false);
      },
      { enableHighAccuracy: true, timeout: 10000, maximumAge: 1000 },
    );
  };

  return (
    <div className="relative h-full w-full">
      <div ref={containerRef} className="h-full w-full" />
      <button
        type="button"
        onClick={handleRecenterCurrentPosition}
        disabled={isRecenteringCurrentPosition}
        className="absolute bottom-24 right-4 z-20 flex h-12 w-12 items-center justify-center rounded-2xl border border-slate-200 bg-white text-sky-600 shadow-[0_10px_24px_rgba(15,23,42,0.14)] transition hover:scale-[1.02] hover:text-sky-700 disabled:cursor-wait disabled:opacity-75"
        aria-label="回到当前位置"
        title="回到当前位置"
      >
        {isRecenteringCurrentPosition ? <LoaderCircle className="h-5 w-5 animate-spin" /> : <LocateFixed className="h-5 w-5" />}
      </button>
    </div>
  );
}

function normalizeCompletedMissionLabels(completedMissions?: WalkItem['completedMissions']) {
  if (!Array.isArray(completedMissions)) {
    return [];
  }

  return completedMissions
    .map((mission) => {
      if (typeof mission === 'string') {
        return sanitizeCardText(mission);
      }
      return sanitizeCardText(mission?.mission || '');
    })
    .filter((mission) => mission.length > 0);
}

function WalkDetailMap(props: {
  path: PathPoint[];
  locationLabel: string;
  roomMembers?: RoomMapMember[];
}) {
  const { path, locationLabel, roomMembers = [] } = props;
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<any>(null);
  const overlaysRef = useRef<any[]>([]);
  const [nearbyPois, setNearbyPois] = useState<MapPOI[]>([]);
  const hasRoomTracks = roomMembers.length > 0;
  const primaryPath = useMemo(() => {
    if (!hasRoomTracks) {
      return path;
    }
    const firstMemberWithPath = roomMembers.find((member) => member.path.length > 0);
    if (firstMemberWithPath) {
      return firstMemberWithPath.path.map(([lat, lng], index) => ({
        lat,
        lng,
        timestamp: index,
      }));
    }
    const firstMemberWithPosition = roomMembers.find((member) => member.currentPosition);
    if (firstMemberWithPosition?.currentPosition) {
      return [
        {
          lat: firstMemberWithPosition.currentPosition[0],
          lng: firstMemberWithPosition.currentPosition[1],
          timestamp: Date.now(),
        },
      ];
    }
    return [];
  }, [hasRoomTracks, path, roomMembers]);

  useEffect(() => {
    if (primaryPath.length === 0) {
      setNearbyPois([]);
      return;
    }

    const lastPoint = primaryPath[primaryPath.length - 1];
    fetchNearbyPois(lastPoint.lat, lastPoint.lng)
      .then((pois) => setNearbyPois(pois.slice(0, 8)))
      .catch((error) => {
        console.error('Fetch detail nearby POIs error:', error);
        setNearbyPois([]);
      });
  }, [primaryPath]);

  useEffect(() => {
    if (!containerRef.current || mapRef.current || primaryPath.length === 0) {
      return;
    }

    let isDisposed = false;

    loadAmapJsApi()
      .then((AMap) => {
        if (isDisposed || !containerRef.current || mapRef.current) {
          return;
        }

        const lastPoint = primaryPath[primaryPath.length - 1];
        const map = new AMap.Map(containerRef.current, {
          zoom: 17,
          center: [lastPoint.lng, lastPoint.lat],
          resizeEnable: true,
          viewMode: '2D',
        });

        map.addControl(new AMap.Scale());
        map.addControl(new AMap.ToolBar());
        mapRef.current = map;
      })
      .catch((error) => {
        console.error('Load detail AMap error:', error);
      });

    return () => {
      isDisposed = true;
      if (mapRef.current) {
        mapRef.current.destroy();
        mapRef.current = null;
      }
      overlaysRef.current = [];
    };
  }, [primaryPath]);

  useEffect(() => {
    const map = mapRef.current;
    const AMap = window.AMap;
    if (!map || !AMap || primaryPath.length === 0) {
      return;
    }

    if (overlaysRef.current.length > 0) {
      map.remove(overlaysRef.current);
      overlaysRef.current = [];
    }

    const overlays: any[] = [];
    if (hasRoomTracks) {
      roomMembers.forEach((member) => {
        if (member.path.length > 1) {
          overlays.push(
            new AMap.Polyline({
              path: member.path.map(([lat, lng]) => [lng, lat]),
              strokeColor: member.trackColor,
              strokeWeight: 5,
              strokeOpacity: 0.92,
              lineJoin: 'round',
              lineCap: 'round',
            }),
          );
          const [startLat, startLng] = member.path[0];
          overlays.push(
            new AMap.Marker({
              position: [startLng, startLat],
              anchor: 'center',
              offset: new AMap.Pixel(0, 0),
              content: createAvatarMarkerContent({
                color: member.trackColor,
                size: 18,
                label: `${member.nickname}起点`,
                avatarUrl: member.avatarUrl,
                fallbackText: member.nickname,
              }),
              title: `${member.nickname}起点`,
            }),
          );
        }

        if (member.currentPosition) {
          overlays.push(
            new AMap.Marker({
              position: [member.currentPosition[1], member.currentPosition[0]],
              anchor: 'center',
              offset: new AMap.Pixel(0, 0),
              content: createAvatarMarkerContent({
                color: member.trackColor,
                size: 22,
                label: `${member.nickname}位置`,
                avatarUrl: member.avatarUrl,
                fallbackText: member.nickname,
              }),
              title: `${member.nickname}位置`,
            }),
          );
        } else if (member.path.length > 0) {
          const [endLat, endLng] = member.path[member.path.length - 1];
          overlays.push(
            new AMap.Marker({
              position: [endLng, endLat],
              anchor: 'center',
              offset: new AMap.Pixel(0, 0),
              content: createAvatarMarkerContent({
                color: member.trackColor,
                size: 20,
                label: `${member.nickname}轨迹`,
                avatarUrl: member.avatarUrl,
                fallbackText: member.nickname,
              }),
              title: `${member.nickname}轨迹`,
            }),
          );
        }
      });
    } else {
      const amapPath = path.map((point) => [point.lng, point.lat]);
      const startPoint = path[0];
      const endPoint = path[path.length - 1];

      if (amapPath.length > 1) {
        overlays.push(
          new AMap.Polyline({
            path: amapPath,
            strokeColor: '#f59e0b',
            strokeWeight: 6,
            strokeOpacity: 0.95,
            lineJoin: 'round',
            lineCap: 'round',
          }),
        );
      }

      overlays.push(
        new AMap.Marker({
          position: [startPoint.lng, startPoint.lat],
          anchor: 'center',
          offset: new AMap.Pixel(0, 0),
          content: createMarkerContent('#2563eb', 18, '起点'),
          title: '起点',
        }),
      );

      overlays.push(
        new AMap.Marker({
          position: [endPoint.lng, endPoint.lat],
          anchor: 'center',
          offset: new AMap.Pixel(0, 0),
          content: createMarkerContent('#0f172a', 20, locationLabel || '记录位置'),
          title: locationLabel || '记录位置',
        }),
      );
    }

    nearbyPois
      .filter((poi) => typeof poi.lat === 'number' && typeof poi.lng === 'number')
      .forEach((poi) => {
        overlays.push(
          new AMap.Marker({
            position: [poi.lng as number, poi.lat as number],
            anchor: 'center',
            offset: new AMap.Pixel(0, 0),
            content: createMarkerContent('#2563eb', 16, poi.title),
            title: poi.title,
          }),
        );
      });

    map.add(overlays);
    map.setFitView(overlays, false, [48, 48, 48, 48]);
    overlaysRef.current = overlays;
  }, [hasRoomTracks, locationLabel, nearbyPois, path, primaryPath, roomMembers]);

  const handleRecenterTrack = () => {
    if (!mapRef.current || primaryPath.length === 0) {
      return;
    }

    const lastPoint = primaryPath[primaryPath.length - 1];
    mapRef.current.setZoomAndCenter(TRACKING_MAP_ZOOM, [lastPoint.lng, lastPoint.lat]);
  };

  if (primaryPath.length === 0) {
    return (
      <div className="flex h-72 items-center justify-center rounded-[24px] border border-dashed border-slate-300 bg-white text-sm text-slate-500">
        这条记录里还没有可展示的轨迹。
      </div>
    );
  }

  return (
    <div className="relative h-72 w-full overflow-hidden rounded-[24px] border border-slate-200">
      <div ref={containerRef} className="h-full w-full" />
      <button
        type="button"
        onClick={handleRecenterTrack}
        className="absolute bottom-4 right-4 z-10 flex h-11 w-11 items-center justify-center rounded-2xl border border-slate-200 bg-white text-slate-700 shadow-lg transition hover:scale-[1.02] hover:text-slate-900"
        aria-label="回到轨迹位置"
        title="回到轨迹位置"
      >
        <LocateFixed className="h-5 w-5" />
      </button>
    </div>
  );
}

function formatProfilePostDate(timestamp?: number) {
  if (!timestamp) {
    return '刚刚记录';
  }

  const date = new Date(timestamp);
  return `${date.getFullYear()}.${String(date.getMonth() + 1).padStart(2, '0')}.${String(date.getDate()).padStart(2, '0')}`;
}

function buildProfilePostSummary(walk: WalkItem) {
  const note = sanitizeCardText(walk.noteText || '');
  if (note) {
    return note.length > 48 ? `${note.slice(0, 48)}...` : note;
  }

  const location = sanitizeCardText(walk.locationName || '');
  if (location) {
    return `在${location}留下了一段漫步记录。`;
  }

  return '今天也认真收藏了一段属于自己的漫步瞬间。';
}

function getProfilePostCoverHeightClass(walk: WalkItem) {
  const seed = (walk.id || 0) % 3;
  if (walk.photoUrl) {
    return seed === 0 ? 'h-28' : seed === 1 ? 'h-32' : 'h-24';
  }
  return seed === 1 ? 'h-28' : 'h-24';
}

function getProfilePostGradient(walk: WalkItem) {
  const category = sanitizeCardText(walk.themeCategory || '');
  if (category.includes('声音')) {
    return 'from-sky-100 via-cyan-50 to-white';
  }
  if (category.includes('自然')) {
    return 'from-lime-100 via-emerald-50 to-white';
  }
  if (category.includes('动物')) {
    return 'from-amber-100 via-rose-50 to-white';
  }
  return 'from-orange-100 via-amber-50 to-white';
}

export default function App() {
  const [user, setUser] = useState<AppUser | null>(null);
  const [showEmailLogin, setShowEmailLogin] = useState(false);
  const [emailLoginMode, setEmailLoginMode] = useState<EmailAuthMode>('login');
  const [emailInput, setEmailInput] = useState('');
  const [passwordInput, setPasswordInput] = useState('');
  const [emailCodeInput, setEmailCodeInput] = useState('');
  const [authError, setAuthError] = useState('');
  const [authInfo, setAuthInfo] = useState('');
  const [isAuthLoading, setIsAuthLoading] = useState(false);
  const [isSendingCode, setIsSendingCode] = useState(false);
  const [sendCodeCooldown, setSendCodeCooldown] = useState(0);
  const [walkUploadPreview, setWalkUploadPreview] = useState<string | null>(null);
  const [walkUploadName, setWalkUploadName] = useState<string>('');
  const [isWalkUploading, setIsWalkUploading] = useState(false);
  const [showRecordCardModal, setShowRecordCardModal] = useState(false);
  const [recordCardPreviewUrl, setRecordCardPreviewUrl] = useState<string | null>(null);
  const [recordCardFilename, setRecordCardFilename] = useState('walk-record-card.svg');
  const [activeTab, setActiveTab] = useState<'explore' | 'community' | 'profile'>('explore');
  const [currentTheme, setCurrentTheme] = useState<WalkTheme | null>(PRESET_THEMES[0]);
  const [checkedMissions, setCheckedMissions] = useState<string[]>([]);
  const [history, setHistory] = useState<WalkTheme[]>([]);
  const [myWalks, setMyWalks] = useState<WalkItem[]>([]);
  const [likedWalks, setLikedWalks] = useState<CommunityWalkItem[]>([]);
  const [favoritedWalks, setFavoritedWalks] = useState<CommunityWalkItem[]>([]);
  const [selectedProfileWalk, setSelectedProfileWalk] = useState<WalkItem | null>(null);
  const [profileViewMode, setProfileViewMode] = useState<'feed' | 'post'>('feed');
  const [profileCollectionTab, setProfileCollectionTab] = useState<'mine' | 'favorited' | 'liked'>('mine');
  const [communityWalks, setCommunityWalks] = useState<CommunityWalkItem[]>([]);
  const [selectedCommunityWalk, setSelectedCommunityWalk] = useState<CommunityWalkItem | null>(null);
  const [communityViewMode, setCommunityViewMode] = useState<'feed' | 'post'>('feed');
  const [editingWalk, setEditingWalk] = useState<WalkItem | null>(null);
  const [editWalkTitle, setEditWalkTitle] = useState('');
  const [editWalkNote, setEditWalkNote] = useState('');
  const [editWalkTags, setEditWalkTags] = useState('');
  const [editWalkIsPublic, setEditWalkIsPublic] = useState(true);
  const [isSavingWalkEdit, setIsSavingWalkEdit] = useState(false);
  const [walkEditError, setWalkEditError] = useState('');
  const [isLoadingCommunity, setIsLoadingCommunity] = useState(false);
  const [communityFeedTab, setCommunityFeedTab] = useState<CommunityFeedTab>('recommend');
  const [communitySearchInput, setCommunitySearchInput] = useState('');
  const [communitySearchKeyword, setCommunitySearchKeyword] = useState('');
  const [communityError, setCommunityError] = useState('');
  const [communityComments, setCommunityComments] = useState<CommunityCommentItem[]>([]);
  const [isLoadingCommunityComments, setIsLoadingCommunityComments] = useState(false);
  const [communityCommentInput, setCommunityCommentInput] = useState('');
  const [communityReplyTarget, setCommunityReplyTarget] = useState<CommunityReplyTarget | null>(null);
  const [isSubmittingCommunityComment, setIsSubmittingCommunityComment] = useState(false);
  const [communityCommentError, setCommunityCommentError] = useState('');
  const [showCommunityFilterMenu, setShowCommunityFilterMenu] = useState(false);
  const [isLoadingProfile, setIsLoadingProfile] = useState(false);
  const [isSavingProfile, setIsSavingProfile] = useState(false);
  const [profileNickname, setProfileNickname] = useState('');
  const [profileAvatarPreview, setProfileAvatarPreview] = useState<string | null>(null);
  const [profileAvatarName, setProfileAvatarName] = useState('');
  const [profileBio, setProfileBio] = useState('');
  const [profileMessage, setProfileMessage] = useState('');
  const [showProfileEditor, setShowProfileEditor] = useState(false);
  const [notifications, setNotifications] = useState<UserNotificationItem[]>([]);
  const [notificationUnreadCount, setNotificationUnreadCount] = useState(0);
  const [isLoadingNotifications, setIsLoadingNotifications] = useState(false);
  const [showNotificationCenter, setShowNotificationCenter] = useState(false);
  const [isGenerating, setIsGenerating] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [agentPrompt, setAgentPrompt] = useState('我想在上海找一条适合傍晚散步、拍照好看的 City Walk 路线');
  const [agentAnswer, setAgentAnswer] = useState('');
  const [agentEvents, setAgentEvents] = useState<AgentStreamEvent[]>([]);
  const [agentStatus, setAgentStatus] = useState('');
  const [isAgentStreaming, setIsAgentStreaming] = useState(false);
  const [isClearingAgentMemory, setIsClearingAgentMemory] = useState(false);
  const [isApplyingAgentResult, setIsApplyingAgentResult] = useState(false);
  const [showAgentPlannerModal, setShowAgentPlannerModal] = useState(false);
  const [showAgentTimelineModal, setShowAgentTimelineModal] = useState(false);
  const [mood, setMood] = useState('好奇');
  const [weather, setWeather] = useState('晴朗');
  const [season, setSeason] = useState('春季');
  const [preference, setPreference] = useState('城市生活');
  const [walkMode, setWalkMode] = useState<'pure' | 'advanced'>('pure');
  const [locationContext, setLocationContext] = useState('城市街道');
  const [searchLocation, setSearchLocation] = useState('');
  const [searchResults, setSearchResults] = useState<SearchLocation[]>([]);
  const [selectedLocation, setSelectedLocation] = useState<SearchLocation | null>(null);
  const [selectedThemesForCombine, setSelectedThemesForCombine] = useState<string[]>([]);
  const [nearbyPois, setNearbyPois] = useState<MapPOI[]>([]);
  const [agentSuggestedPois, setAgentSuggestedPois] = useState<MapPOI[]>([]);
  const [selectedPoiKey, setSelectedPoiKey] = useState<string | null>(null);
  const [noteText, setNoteText] = useState('');
  const [isPublic, setIsPublic] = useState(true);
  const [path, setPath] = useState<PathPoint[]>([]);
  const [isTracking, setIsTracking] = useState(false);
  const [livePosition, setLivePosition] = useState<SearchLocation | null>(null);
  const [roomCodeInput, setRoomCodeInput] = useState('');
  const [coCreateRoom, setCoCreateRoom] = useState<CoCreateRoom | null>(null);
  const [isRoomSubmitting, setIsRoomSubmitting] = useState(false);
  const [isRoomSocketConnected, setIsRoomSocketConnected] = useState(false);
  const [isRoomSocketConnecting, setIsRoomSocketConnecting] = useState(false);
  const [isRestoringCoCreateRoom, setIsRestoringCoCreateRoom] = useState(false);
  const [roomError, setRoomError] = useState('');
  const [roomMessage, setRoomMessage] = useState('');
  const searchTimeoutRef = useRef<number | null>(null);
  const hasAutoLocatedRef = useRef(false);
  const roomSyncTimeoutRef = useRef<number | null>(null);
  const roomThemeSyncTimeoutRef = useRef<number | null>(null);
  const roomRestoreAttemptedRef = useRef(false);
  const roomSocketRef = useRef<WebSocket | null>(null);
  const notificationStreamRef = useRef<EventSource | null>(null);
  const agentStreamRef = useRef<EventSource | null>(null);
  const noteTextareaRef = useRef<HTMLTextAreaElement | null>(null);
  const showNotificationCenterRef = useRef(false);

  useEffect(() => {
    setCheckedMissions([]);
  }, [currentTheme?.title]);

  useEffect(() => {
    const token = getStoredToken();
    if (!token) {
      return;
    }

    loadCurrentUser()
      .then(setUser)
      .catch((error) => {
        console.error('Error loading current user:', error);
        setUser(null);
      });
  }, []);

  useEffect(() => {
    if (user) {
      return;
    }
    roomRestoreAttemptedRef.current = false;
    setCoCreateRoom(null);
    setRoomCodeInput('');
    setRoomError('');
    setRoomMessage('');
    setNotifications([]);
    setNotificationUnreadCount(0);
    setShowNotificationCenter(false);
  }, [user]);

  useEffect(() => {
    void refreshRecentWalks();
  }, [user]);

  useEffect(() => {
    if (!user) {
      hasAutoLocatedRef.current = false;
      return;
    }
    if (hasAutoLocatedRef.current || selectedLocation || isTracking) {
      return;
    }

    hasAutoLocatedRef.current = true;
    resolveBrowserLocation().catch((error) => {
      console.error('Auto locate after login error:', error);
    });
  }, [isTracking, selectedLocation, user]);

  useEffect(() => {
    const eventName = getAuthRequiredEventName();
    const handleAuthRequired = () => {
      setAuthError('');
      setEmailLoginMode('login');
      setShowEmailLogin(true);
    };
    window.addEventListener(eventName, handleAuthRequired);
    return () => window.removeEventListener(eventName, handleAuthRequired);
  }, []);

  useEffect(() => {
    return () => {
      agentStreamRef.current?.close();
      agentStreamRef.current = null;
    };
  }, []);

  useEffect(() => {
    if (activeTab !== 'community') {
      return;
    }

    void loadCommunityWalks();
  }, [activeTab, communityFeedTab]);

  useEffect(() => {
    setProfileNickname(user?.nickname || '');
    setProfileAvatarPreview(user?.avatar || null);
    setProfileAvatarName('');
    setProfileBio(user?.bio || '');
  }, [user]);

  useEffect(() => {
    showNotificationCenterRef.current = showNotificationCenter;
  }, [showNotificationCenter]);

  useEffect(() => {
    notificationStreamRef.current?.close();
    notificationStreamRef.current = null;

    if (!user) {
      return;
    }

    const token = getStoredToken();
    if (!token) {
      return;
    }

    const stream = openNotificationStream(token);
    notificationStreamRef.current = stream;

    stream.onmessage = (event) => {
      try {
        const payload = JSON.parse(event.data) as NotificationStreamEvent;
        if (typeof payload.unreadCount === 'number') {
          setNotificationUnreadCount(payload.unreadCount);
        }

        if (payload.type === 'ping') {
          return;
        }

        if (payload.type === 'notification' && payload.notification) {
          setNotifications((prev) => {
            const nextItems = [payload.notification!, ...prev.filter((item) => item.id !== payload.notification!.id)];
            return nextItems.slice(0, 30);
          });
          return;
        }

        if (payload.type === 'unread_count' && showNotificationCenterRef.current) {
          void loadNotifications();
        }
      } catch (error) {
        console.error('Notification stream parse error:', error);
      }
    };

    stream.onerror = () => {
      console.error('Notification stream connection error');
    };

    return () => {
      stream.close();
      if (notificationStreamRef.current === stream) {
        notificationStreamRef.current = null;
      }
    };
  }, [user]);

  useEffect(() => {
    void loadNotificationUnreadStatus();
  }, [user]);

  useEffect(() => {
    if (!showNotificationCenter) {
      return;
    }
    void loadNotifications();
    void loadNotificationUnreadStatus();
  }, [showNotificationCenter, user]);

  useEffect(() => {
    if (sendCodeCooldown <= 0) {
      return;
    }
    const timer = window.setInterval(() => {
      setSendCodeCooldown((prev) => (prev > 1 ? prev - 1 : 0));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [sendCodeCooldown]);

  useEffect(() => {
    if (!isTracking || !navigator.geolocation) {
      return;
    }

    const applyPosition = (position: GeolocationPosition) => {
      const accuracy = position.coords.accuracy;
      const gcjPosition = convertWgs84ToGcj02(position.coords.latitude, position.coords.longitude);
      const nextPoint = {
        lat: gcjPosition.lat,
        lng: gcjPosition.lng,
        timestamp: Date.now(),
      };

      setLivePosition({
        name: '当前位置',
        lat: nextPoint.lat,
        lng: nextPoint.lng,
      });

      setPath((prev) => {
        if (prev.length === 0) {
          return [nextPoint];
        }

        const lastPoint = prev[prev.length - 1];
        const distance = calculateDistanceMeters(lastPoint, nextPoint);
        const elapsed = nextPoint.timestamp - lastPoint.timestamp;
        const accuracyValue = Number.isFinite(accuracy) ? accuracy : 999;
        const isJumpPoint = isLikelyTrackJump({
          distanceMeters: distance,
          elapsedMs: elapsed,
          accuracyMeters: accuracyValue,
        });

        if (isJumpPoint) {
          return prev;
        }

        const minDistance =
          accuracyValue <= 25
            ? MIN_TRACKING_DISTANCE_METERS
            : accuracyValue <= 60
              ? 4
              : accuracyValue <= MAX_ACCEPTABLE_POSITION_ACCURACY_METERS
                ? 7
                : Number.POSITIVE_INFINITY;

        if (distance >= minDistance) {
          return [...prev, nextPoint];
        }

        if (accuracyValue <= 45 && elapsed >= MAX_TIMED_TRACK_POINT_INTERVAL_MS && distance >= 1) {
          return [...prev, nextPoint];
        }

        return prev;
      });
    };

    navigator.geolocation.getCurrentPosition(
      applyPosition,
      (error) => {
        console.error('Initial track location error:', error);
      },
      { enableHighAccuracy: true, timeout: 10000, maximumAge: 1000 },
    );

    const watchId = navigator.geolocation.watchPosition(
      applyPosition,
      (error) => {
        console.error('Track location error:', error);
      },
      { enableHighAccuracy: true, timeout: 10000, maximumAge: 1000 },
    );

    return () => {
      navigator.geolocation.clearWatch(watchId);
    };
  }, [isTracking]);

  useEffect(() => {
    if (!selectedLocation) {
      setNearbyPois([]);
      setSelectedPoiKey(null);
      return;
    }

    fetchNearbyPois(selectedLocation.lat, selectedLocation.lng)
      .then(setNearbyPois)
      .catch((error) => {
        console.error('Fetch nearby POIs error:', error);
        setNearbyPois([]);
      });
  }, [selectedLocation]);

  const currentLocationName = useMemo(
    () => deriveDisplayLocationName(selectedLocation?.name || searchLocation || '当前位置', locationContext),
    [locationContext, searchLocation, selectedLocation],
  );

  const displayNearbyPois = useMemo(
    () => (agentSuggestedPois.length > 0 ? agentSuggestedPois : nearbyPois),
    [agentSuggestedPois, nearbyPois],
  );

  const currentPosition = useMemo<SearchLocation | null>(() => livePosition, [livePosition]);

  const visibleCurrentPosition = useMemo(
    () => (isTracking ? currentPosition : null),
    [currentPosition, isTracking],
  );

  const mapCenter = useMemo<[number, number]>(() => {
    if (isTracking && currentPosition) {
      return [currentPosition.lat, currentPosition.lng];
    }
    if (selectedLocation) {
      return [selectedLocation.lat, selectedLocation.lng];
    }
    if (path.length > 0) {
      const lastPoint = path[path.length - 1];
      return [lastPoint.lat, lastPoint.lng];
    }
    if (walkMode === 'advanced' && coCreateRoom) {
      const activeMember = coCreateRoom.members.find((member) => member.currentPosition);
      if (activeMember?.currentPosition) {
        return [activeMember.currentPosition.lat, activeMember.currentPosition.lng];
      }
    }
    return DEFAULT_CENTER;
  }, [coCreateRoom, currentPosition, isTracking, path, selectedLocation, walkMode]);

  const pathCoordinates = useMemo(() => path.map((point) => [point.lat, point.lng] as [number, number]), [path]);
  const visiblePathCoordinates = useMemo(() => {
    if (!isTracking) {
      return [];
    }

    if (!currentPosition) {
      return pathCoordinates;
    }

    const currentCoordinate: [number, number] = [currentPosition.lat, currentPosition.lng];
    if (pathCoordinates.length === 0) {
      return [currentCoordinate];
    }

    const lastCoordinate = pathCoordinates[pathCoordinates.length - 1];
    const tailDistance = calculateDistanceMeters(
      { lat: lastCoordinate[0], lng: lastCoordinate[1] },
      { lat: currentCoordinate[0], lng: currentCoordinate[1] },
    );
    const lastPathPoint = path[path.length - 1];
    const tailElapsed = lastPathPoint ? Date.now() - lastPathPoint.timestamp : 0;

    if (tailDistance < 0.8) {
      return pathCoordinates;
    }

    if (
      isLikelyTrackJump({
        distanceMeters: tailDistance,
        elapsedMs: tailElapsed,
      })
    ) {
      return pathCoordinates;
    }

    return [...pathCoordinates, currentCoordinate];
  }, [currentPosition, isTracking, path, pathCoordinates]);
  const pathDistanceKm = useMemo(() => calculatePathDistance(path) / 1000, [path]);
  const communityReferencePoint = useMemo(() => {
    if (selectedLocation) {
      return { lat: selectedLocation.lat, lng: selectedLocation.lng };
    }
    if (currentPosition) {
      return { lat: currentPosition.lat, lng: currentPosition.lng };
    }
    return null;
  }, [currentPosition, selectedLocation]);
  const visibleCommunityWalks = useMemo(() => {
    return [...communityWalks]
      .sort((left, right) => {
        const leftDistance = getWalkDistanceToReference(left, communityReferencePoint);
        const rightDistance = getWalkDistanceToReference(right, communityReferencePoint);
        if (leftDistance !== rightDistance) {
          return leftDistance - rightDistance;
        }

        const leftCreatedAt = left.createdAt || 0;
        const rightCreatedAt = right.createdAt || 0;
        return rightCreatedAt - leftCreatedAt;
      });
  }, [communityReferencePoint, communityWalks]);
  const communityPublisherName = user?.nickname?.trim() || '社区漫步者';
  const communityPublisherAvatar = user?.avatar?.trim() || '';
  const displayedCommunityWalks = useMemo(() => {
    if (!communityReferencePoint || communitySearchKeyword.trim() || communityFeedTab !== 'recommend') {
      return communityWalks;
    }

    return visibleCommunityWalks;
  }, [communityFeedTab, communityReferencePoint, communitySearchKeyword, communityWalks, visibleCommunityWalks]);
  const resolvedCommunityPublisherName = communityPublisherName;

  const roomThemeSnapshot = useMemo(
    () =>
      currentTheme
        ? {
            title: currentTheme.title,
            description: currentTheme.description,
            category: currentTheme.category,
            missions: currentTheme.missions,
            vibeColor: currentTheme.vibeColor,
            provider: currentTheme.provider,
            coverImageUrl: currentTheme.coverImageUrl,
          }
        : null,
    [currentTheme],
  );
  const roomThemeSnapshotKey = useMemo(
    () => (roomThemeSnapshot ? JSON.stringify(roomThemeSnapshot) : ''),
    [roomThemeSnapshot],
  );
  const activeRoomThemeKey = useMemo(
    () => (coCreateRoom?.theme ? JSON.stringify(coCreateRoom.theme) : ''),
    [coCreateRoom?.theme],
  );
  const isRoomOwner = !!(user && coCreateRoom && user.id === coCreateRoom.ownerUserId);
  const canModifySharedTheme = walkMode !== 'advanced' || !coCreateRoom || isRoomOwner;
  const roomMemberCount = coCreateRoom?.members.length ?? 0;
  const roomRealtimeStatus = isRestoringCoCreateRoom
    ? {
        text: '正在恢复房间',
        className: 'border border-amber-200 bg-amber-50 text-amber-700',
      }
    : coCreateRoom
      ? isRoomSocketConnected
        ? {
            text: '实时已连接',
            className: 'border border-emerald-200 bg-emerald-50 text-emerald-700',
          }
        : isRoomSocketConnecting
          ? {
              text: '实时连接中',
              className: 'border border-sky-200 bg-sky-50 text-sky-700',
            }
          : {
              text: '实时断开，轮询兜底',
              className: 'border border-slate-200 bg-slate-50 text-slate-600',
            }
      : null;
  const roomMapMembers = useMemo<RoomMapMember[]>(() => {
    if (!coCreateRoom) {
      return [];
    }

    return coCreateRoom.members
      .map((member) => ({
        userId: member.userId,
        nickname: member.nickname,
        avatarUrl: user && member.userId === user.id ? user.avatar : member.avatarUrl,
        trackColor: member.trackColor,
        path:
          user && member.userId === user.id
            ? path.map((point) => [point.lat, point.lng] as [number, number])
            : (member.path || []).map((point) => [point.lat, point.lng] as [number, number]),
        currentPosition:
          user && member.userId === user.id
            ? currentPosition
              ? ([currentPosition.lat, currentPosition.lng] as [number, number])
              : null
            : member.currentPosition
              ? ([member.currentPosition.lat, member.currentPosition.lng] as [number, number])
              : null,
      }));
  }, [coCreateRoom, currentPosition, path, user]);

  const closeCoCreateRoomSocket = () => {
    roomSocketRef.current?.close();
    roomSocketRef.current = null;
    setIsRoomSocketConnected(false);
    setIsRoomSocketConnecting(false);
  };

  const applyCoCreateRoom = (room: CoCreateRoom, options?: { syncTheme?: boolean }) => {
    writeStoredActiveRoomCode(room.roomCode);
    setWalkMode('advanced');
    setCoCreateRoom(room);
    setRoomCodeInput(room.roomCode);
    const nextRoomThemeKey = room.theme ? JSON.stringify(room.theme) : '';
    const shouldSyncTheme =
      options?.syncTheme !== false &&
      !!room.theme &&
      (!isRoomOwner ||
        !roomThemeSnapshotKey ||
        roomThemeSnapshotKey === activeRoomThemeKey ||
        nextRoomThemeKey === roomThemeSnapshotKey);

    if (shouldSyncTheme && room.theme) {
      setCurrentTheme({
        title: room.theme.title,
        description: room.theme.description,
        category: room.theme.category,
        missions: room.theme.missions || [],
        vibeColor: room.theme.vibeColor || '#334155',
        provider: room.theme.provider,
        coverImageUrl: room.theme.coverImageUrl,
      });
    }
  };

  const handleSelectWalkMode = (nextMode: 'pure' | 'advanced') => {
    if (nextMode === 'pure' && coCreateRoom) {
      setRoomMessage('当前已在共创房间中，请先退出房间再切回纯净模式。');
      setRoomError('');
      return;
    }

    setWalkMode(nextMode);
  };

  useEffect(() => {
    if (!user) {
      roomRestoreAttemptedRef.current = false;
      setIsRestoringCoCreateRoom(false);
      return;
    }
    if (coCreateRoom?.roomCode) {
      roomRestoreAttemptedRef.current = true;
      setIsRestoringCoCreateRoom(false);
      return;
    }
    if (roomRestoreAttemptedRef.current) {
      return;
    }

    const storedRoomCode = readStoredActiveRoomCode();
    roomRestoreAttemptedRef.current = true;
    setIsRestoringCoCreateRoom(true);
    if (storedRoomCode) {
      setWalkMode('advanced');
      setRoomCodeInput(storedRoomCode);
    }
    const restoreRoomPromise = storedRoomCode ? fetchCoCreateRoom(storedRoomCode) : fetchCurrentCoCreateRoom();
    restoreRoomPromise
      .then((room) => {
        if (!room) {
          setIsRestoringCoCreateRoom(false);
          return;
        }
        applyCoCreateRoom(room);
        setRoomMessage(`已自动恢复房间 ${room.roomCode}。`);
        setRoomError('');
        setRoomMessage(storedRoomCode ? `已自动恢复房间 ${room.roomCode}。` : `已从账号恢复房间 ${room.roomCode}。`);
        setIsRestoringCoCreateRoom(false);
      })
      .catch((error) => {
        console.error('Restore co-create room error:', error);
        if (storedRoomCode) {
          clearStoredActiveRoomCode();
        }
        if (error instanceof Error && (error.message === 'room_not_found' || error.message === 'room_membership_required')) {
          setRoomCodeInput('');
          setIsRestoringCoCreateRoom(false);
          return;
        }
        setRoomError('恢复共创房间失败，请稍后重试。');
      });
  }, [coCreateRoom?.roomCode, user]);

  useEffect(() => {
    if (walkMode !== 'advanced' || !coCreateRoom?.roomCode || !user) {
      closeCoCreateRoomSocket();
      return;
    }

    setIsRoomSocketConnecting(true);
    const socket = openCoCreateRoomSocket(coCreateRoom.roomCode);
    roomSocketRef.current = socket;

    socket.onopen = () => {
      setIsRoomSocketConnecting(false);
      setIsRoomSocketConnected(true);
    };

    socket.onmessage = (event) => {
      try {
        const payload = JSON.parse(event.data) as CoCreateRoomSocketEvent;
        if (payload.type === 'room_snapshot' && payload.room) {
          applyCoCreateRoom(payload.room);
          return;
        }
        if (payload.type === 'room_closed' && payload.roomCode === coCreateRoom.roomCode) {
          clearStoredActiveRoomCode();
          roomRestoreAttemptedRef.current = false;
          setCoCreateRoom(null);
          setRoomMessage('房间已解散。');
          setRoomError('');
        }
      } catch (error) {
        console.error('Parse co-create room websocket payload error:', error);
      }
    };

    socket.onclose = () => {
      if (roomSocketRef.current === socket) {
        roomSocketRef.current = null;
        setIsRoomSocketConnecting(false);
        setIsRoomSocketConnected(false);
      }
    };

    socket.onerror = () => {
      setIsRoomSocketConnecting(false);
      setIsRoomSocketConnected(false);
    };

    return () => {
      if (roomSocketRef.current === socket) {
        closeCoCreateRoomSocket();
      } else if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) {
        socket.close();
      }
    };
  }, [coCreateRoom?.roomCode, user, walkMode]);

  useEffect(() => {
    if (walkMode !== 'advanced' || !coCreateRoom?.roomCode || isRoomSocketConnected) {
      return;
    }

    let isDisposed = false;

    const syncRoom = async () => {
      try {
        const room = await fetchCoCreateRoom(coCreateRoom.roomCode);
        if (!isDisposed) {
          applyCoCreateRoom(room);
        }
      } catch (error) {
        if (!isDisposed) {
          if (error instanceof Error && (error.message === 'room_not_found' || error.message === 'room_membership_required')) {
            clearStoredActiveRoomCode();
            roomRestoreAttemptedRef.current = false;
            setCoCreateRoom(null);
            setRoomMessage('房间已自动解散。');
            setRoomError('');
            return;
          }
          console.error('Fetch co-create room error:', error);
        }
      }
    };

    void syncRoom();
    const timer = window.setInterval(() => {
      void syncRoom();
    }, 3000);

    return () => {
      isDisposed = true;
      window.clearInterval(timer);
    };
  }, [coCreateRoom?.roomCode, isRoomSocketConnected, walkMode]);

  useEffect(() => {
    if (walkMode !== 'advanced' || !coCreateRoom?.roomCode || !roomThemeSnapshot || !isRoomOwner) {
      return;
    }

    if (roomThemeSyncTimeoutRef.current) {
      window.clearTimeout(roomThemeSyncTimeoutRef.current);
    }

    if (activeRoomThemeKey === roomThemeSnapshotKey) {
      return;
    }

    roomThemeSyncTimeoutRef.current = window.setTimeout(() => {
      updateCoCreateRoomTheme(coCreateRoom.roomCode, roomThemeSnapshot)
        .then((room) => {
          applyCoCreateRoom(room, { syncTheme: false });
        })
        .catch((error) => {
          console.error('Sync co-create room theme error:', error);
        });
    }, 500);

    return () => {
      if (roomThemeSyncTimeoutRef.current) {
        window.clearTimeout(roomThemeSyncTimeoutRef.current);
      }
    };
  }, [activeRoomThemeKey, coCreateRoom?.roomCode, isRoomOwner, roomThemeSnapshot, roomThemeSnapshotKey, walkMode]);

  useEffect(() => {
    if (walkMode !== 'advanced' || !coCreateRoom?.roomCode || !user) {
      return;
    }

    if (roomSyncTimeoutRef.current) {
      window.clearTimeout(roomSyncTimeoutRef.current);
    }

    roomSyncTimeoutRef.current = window.setTimeout(() => {
      updateCoCreateRoomState(coCreateRoom.roomCode, {
        isTracking,
        currentPosition: currentPosition
          ? {
              lat: currentPosition.lat,
              lng: currentPosition.lng,
              timestamp: Date.now(),
            }
          : null,
        path,
        completedMissions: checkedMissions,
      }).catch((error) => {
        console.error('Sync co-create room state error:', error);
      });
    }, 600);

    return () => {
      if (roomSyncTimeoutRef.current) {
        window.clearTimeout(roomSyncTimeoutRef.current);
      }
    };
  }, [checkedMissions, coCreateRoom?.roomCode, currentPosition, isTracking, path, user, walkMode]);

  const toThemeFromWalk = (walk: WalkItem): WalkTheme => {
    const missions = Array.isArray(walk.completedMissions)
      ? walk.completedMissions
          .map((item) => {
            if (typeof item === 'string') {
              return item;
            }
            if (item && typeof item === 'object' && 'mission' in item) {
              const missionValue = (item as { mission?: string }).mission;
              return missionValue ? String(missionValue) : '';
            }
            return '';
          })
          .filter((item) => item.length > 0)
      : [];

    return {
      title: walk.themeTitle || '城市漫步',
      description: walk.noteText || '这次漫步没有填写备注。',
      category: walk.themeCategory || '城市',
      missions,
      vibeColor: '#5a5a40',
    };
  };

  const pushThemeHistory = (theme: WalkTheme) => {
    setCurrentTheme(theme);
  };

  const refreshRecentWalks = async (overrideUser?: AppUser | null) => {
    const currentUser = overrideUser ?? user;
    if (!currentUser) {
      setHistory([]);
      setMyWalks([]);
      setLikedWalks([]);
      setFavoritedWalks([]);
      setSelectedProfileWalk(null);
      return;
    }
    try {
      const [ownWalks, likedItems, favoritedItems] = await Promise.all([
        fetchMyWalks(1, 10),
        fetchMyLikedCommunityWalks(1, 20),
        fetchMyFavoritedCommunityWalks(1, 20),
      ]);
      setMyWalks(ownWalks);
      setLikedWalks(likedItems);
      setFavoritedWalks(favoritedItems);
      setHistory(ownWalks.map((item) => toThemeFromWalk(item)));
      setSelectedProfileWalk((prev) => {
        if (prev) {
          const mergedWalks = [...ownWalks, ...favoritedItems, ...likedItems];
          const matched = mergedWalks.find((item) => item.id === prev.id);
          return matched ?? ownWalks[0] ?? favoritedItems[0] ?? likedItems[0] ?? null;
        }
        return ownWalks[0] ?? null;
      });
      if (ownWalks.length === 0) {
        return;
      }
    } catch (error) {
      console.error('Fetch recent walks error:', error);
    }
  };

  const loadCommunityWalks = async (options?: {
    tab?: CommunityFeedTab;
    keyword?: string;
  }) => {
    const nextTab = options?.tab ?? communityFeedTab;
    const nextKeyword = (options?.keyword ?? communitySearchKeyword).trim();

    setIsLoadingCommunity(true);
    setCommunityError('');
    try {
      const data = nextKeyword
        ? await searchCommunityWalks(nextKeyword, 1, 30)
        : await fetchCommunityFeed(nextTab, 1, 30);
      setCommunityWalks(data);
      setSelectedCommunityWalk((prev) => {
        if (!prev) {
          return null;
        }
        return data.find((item) => item.id === prev.id) ?? null;
      });
    } catch (error) {
      console.error('Error fetching community walks:', error);
      setCommunityWalks([]);
      setCommunityError(nextKeyword ? '社区搜索失败，请稍后再试。' : '社区内容加载失败，请稍后再试。');
    } finally {
      setIsLoadingCommunity(false);
    }
  };

  const loadNotificationUnreadStatus = async (overrideUser?: AppUser | null) => {
    const currentUser = overrideUser ?? user;
    if (!currentUser) {
      setNotificationUnreadCount(0);
      return;
    }

    try {
      const unreadCount = await fetchNotificationUnreadCount();
      setNotificationUnreadCount(unreadCount);
    } catch (error) {
      console.error('Fetch notification unread count error:', error);
      setNotificationUnreadCount(0);
    }
  };

  const loadNotifications = async (overrideUser?: AppUser | null) => {
    const currentUser = overrideUser ?? user;
    if (!currentUser) {
      setNotifications([]);
      return;
    }

    setIsLoadingNotifications(true);
    try {
      const data = await fetchNotifications(1, 30);
      setNotifications(data);
    } catch (error) {
      console.error('Fetch notifications error:', error);
      setNotifications([]);
    } finally {
      setIsLoadingNotifications(false);
    }
  };

  const loadCommunityComments = async (walkId: number) => {
    setIsLoadingCommunityComments(true);
    setCommunityCommentError('');
    try {
      const data = await fetchCommunityComments(walkId);
      setCommunityComments(data);
    } catch (error) {
      console.error('Error fetching community comments:', error);
      setCommunityComments([]);
      setCommunityCommentError('评论加载失败，请稍后再试。');
    } finally {
      setIsLoadingCommunityComments(false);
    }
  };

  const resolveBrowserLocation = async () => {
    if (!navigator.geolocation) {
      throw new Error('当前浏览器不支持定位。');
    }

    const coords = await new Promise<GeolocationPosition>((resolve, reject) => {
      navigator.geolocation.getCurrentPosition(resolve, reject, {
        enableHighAccuracy: true,
        timeout: 15000,
      });
    });

    const lat = coords.coords.latitude;
    const lng = coords.coords.longitude;
    const gcjPosition = convertWgs84ToGcj02(lat, lng);
    const details = await getLocationContextDetails(gcjPosition.lat, gcjPosition.lng);

    setLocationContext(details.locationContext);
    setSelectedLocation({
      name: details.placeName,
      lat: gcjPosition.lat,
      lng: gcjPosition.lng,
    });
    setSearchLocation(details.placeName);
    setSearchResults([]);

    return {
      locationName: details.placeName,
      locationContextText: details.locationContext,
    };
  };

  const resolveCurrentContext = async (): Promise<{ locationName: string; locationContextText: string }> => {
    if (selectedLocation) {
      let nextLocationContext = locationContext;
      let resolvedPlaceName: string | null = isGenericLocationName(selectedLocation.name) ? null : selectedLocation.name;
      if (isGenericLocationName(selectedLocation.name) || !hasBroadLocationHint(nextLocationContext)) {
        try {
          const details = await getLocationContextDetails(selectedLocation.lat, selectedLocation.lng);
          nextLocationContext = details.locationContext;
          resolvedPlaceName = details.placeName || resolvedPlaceName;
          setLocationContext(nextLocationContext);
        } catch (error) {
          console.error('Refresh selected location context error:', error);
        }
      }
      return {
        locationName: resolvedPlaceName || deriveDisplayLocationName(selectedLocation.name, nextLocationContext),
        locationContextText: nextLocationContext,
      };
    }

    if (searchLocation.trim()) {
      let nextLocationContext = locationContext;
      let resolvedPlaceName: string | null = isGenericLocationName(searchLocation.trim()) ? null : searchLocation.trim();
      if ((isGenericLocationName(searchLocation.trim()) || !hasBroadLocationHint(nextLocationContext)) && currentPosition) {
        try {
          const details = await getLocationContextDetails(currentPosition.lat, currentPosition.lng);
          nextLocationContext = details.locationContext;
          resolvedPlaceName = details.placeName || resolvedPlaceName;
          setLocationContext(nextLocationContext);
        } catch (error) {
          console.error('Refresh current geolocation context error:', error);
        }
      }
      return {
        locationName: resolvedPlaceName || deriveDisplayLocationName(searchLocation.trim(), nextLocationContext),
        locationContextText: nextLocationContext,
      };
    }

    try {
      return await resolveBrowserLocation();
    } catch (error) {
      console.error('Get current geolocation error:', error);
    }

    return {
      locationName: deriveDisplayLocationName('当前位置', locationContext),
      locationContextText: locationContext,
    };
  };

  const handleSearchLocation = (query: string) => {
    setSearchLocation(query);
    setSelectedLocation(null);
    setAgentSuggestedPois([]);

    if (searchTimeoutRef.current) {
      window.clearTimeout(searchTimeoutRef.current);
    }

    if (!query.trim()) {
      setSearchResults([]);
      return;
    }

    searchTimeoutRef.current = window.setTimeout(async () => {
      try {
        const data = await searchLocations(query);
        setSearchResults(data);
      } catch (error) {
        console.error('Search location error:', error);
      }
    }, 400);
  };

  const handleSubmitSearch = async () => {
    const keyword = searchLocation.trim();
    if (!keyword) {
      return;
    }

    setIsGenerating(true);
    try {
      const results = await searchLocations(keyword);
      setSearchResults(results);
      if (results.length > 0) {
        await handleSelectLocation(results[0]);
      } else {
        alert('没有找到匹配的地点，请换个关键词试试。');
      }
    } catch (error) {
      console.error('Submit search error:', error);
      alert('地点搜索失败，请稍后重试。');
    } finally {
      setIsGenerating(false);
    }
  };

  const handleSelectLocation = async (location: SearchLocation) => {
    setAgentSuggestedPois([]);
    setSelectedLocation(location);
    setSelectedPoiKey(null);
    setSearchLocation(location.name);
    setSearchResults([]);
    setIsGenerating(true);
    try {
      const context = await getLocationContext(location.lat, location.lng);
      setLocationContext(context);
    } finally {
      setIsGenerating(false);
    }
  };

  const handleUseCurrentLocation = async () => {
    setAgentSuggestedPois([]);
    setIsGenerating(true);
    try {
      await resolveBrowserLocation();
    } catch (error) {
      console.error('Use current location error:', error);
      alert('获取当前位置失败，请检查浏览器定位权限。');
    } finally {
      setIsGenerating(false);
    }
  };

  const handleSelectMapPoint = async (lat: number, lng: number) => {
    setAgentSuggestedPois([]);
    setIsGenerating(true);
    try {
      const details = await getLocationContextDetails(lat, lng);
      const fallbackName = `地图选点 (${lat.toFixed(4)}, ${lng.toFixed(4)})`;
      const locationName = details.placeName || fallbackName;
      setSelectedLocation({ name: locationName, lat, lng });
      setSelectedPoiKey(null);
      setSearchLocation(locationName);
      setSearchResults([]);
      setLocationContext(details.locationContext);
    } catch (error) {
      console.error('Select map point error:', error);
      alert('地图选点失败，请稍后重试。');
    } finally {
      setIsGenerating(false);
    }
  };

  const handleSelectPoi = async (poi: MapPOI) => {
    if (typeof poi.lat !== 'number' || typeof poi.lng !== 'number') {
      return;
    }

    setAgentSuggestedPois([]);
    setIsGenerating(true);
    try {
      const [geoContext, nameContext] = await Promise.all([
        getLocationContext(poi.lat, poi.lng),
        searchLocationContext(poi.title),
      ]);
      const mergedContext = nameContext && nameContext !== poi.title ? nameContext : geoContext;
      const poiKey = `${poi.title}-${poi.lat}-${poi.lng}`;
      setSelectedLocation({
        name: poi.title,
        lat: poi.lat,
        lng: poi.lng,
      });
      setSelectedPoiKey(poiKey);
      setSearchLocation(poi.title);
      setSearchResults([]);
      setLocationContext(mergedContext);
    } catch (error) {
      console.error('Select POI error:', error);
      alert('切换到该 POI 失败，请稍后重试。');
    } finally {
      setIsGenerating(false);
    }
  };

  const handleCreateCoCreateRoom = async () => {
    if (!roomThemeSnapshot) {
      return;
    }
    setIsRoomSubmitting(true);
    setRoomError('');
    setRoomMessage('');
    try {
      const room = await createCoCreateRoom({
        roomCode: roomCodeInput.trim() || undefined,
        theme: roomThemeSnapshot,
      });
      applyCoCreateRoom(room);
      setRoomCodeInput(room.roomCode);
      setRoomMessage(`已创建共创房间 ${room.roomCode}，现在可以邀请好友加入了。`);
    } catch (error) {
      console.error('Create co-create room error:', error);
      setRoomError(error instanceof Error ? error.message : '创建房间失败，请稍后重试。');
    } finally {
      setIsRoomSubmitting(false);
    }
  };

  const handleJoinCoCreateRoom = async () => {
    const roomCode = roomCodeInput.trim();
    if (!roomCode) {
      setRoomError('先输入房间号，我们再一起入房。');
      return;
    }
    setIsRoomSubmitting(true);
    setRoomError('');
    setRoomMessage('');
    try {
      const room = await joinCoCreateRoom(roomCode);
      applyCoCreateRoom(room);
      setRoomCodeInput(room.roomCode);
      setRoomMessage(`已加入房间 ${room.roomCode}，可以开始一起记录轨迹了。`);
    } catch (error) {
      console.error('Join co-create room error:', error);
      setRoomError(error instanceof Error ? error.message : '加入房间失败，请检查房间号。');
    } finally {
      setIsRoomSubmitting(false);
    }
  };

  const handleLeaveCoCreateRoom = async () => {
    if (!coCreateRoom) {
      return;
    }
    setIsRoomSubmitting(true);
    setRoomError('');
    try {
      await leaveCoCreateRoom(coCreateRoom.roomCode);
      clearStoredActiveRoomCode();
      roomRestoreAttemptedRef.current = false;
      setCoCreateRoom(null);
      setRoomCodeInput('');
      setRoomMessage('已退出共创房间，纯净模式和个人记录不受影响。');
    } catch (error) {
      console.error('Leave co-create room error:', error);
      setRoomError(error instanceof Error ? error.message : '退出房间失败，请稍后重试。');
    } finally {
      setIsRoomSubmitting(false);
    }
  };

  const handleCopyRoomCode = async () => {
    if (!coCreateRoom?.roomCode) {
      return;
    }
    try {
      await navigator.clipboard.writeText(coCreateRoom.roomCode);
      setRoomMessage(`房间号 ${coCreateRoom.roomCode} 已复制。`);
    } catch (error) {
      console.error('Copy room code error:', error);
      setRoomMessage(`房间号：${coCreateRoom.roomCode}`);
    }
  };

  const handleGenerateRandomTheme = async () => {
    if (!canModifySharedTheme) {
      setRoomError('当前只有房主可以修改共创主题。');
      setRoomMessage('');
      return;
    }
    setIsGenerating(true);
    try {
      const { locationName, locationContextText } = await resolveCurrentContext();
      const category = RANDOM_CATEGORIES[Math.floor(Math.random() * RANDOM_CATEGORIES.length)];
      const theme = await generateDynamicPreset(category, locationName, locationContextText, walkMode);
      pushThemeHistory(theme);
    } finally {
      setIsGenerating(false);
    }
  };

  const handleGenerateAiTheme = async () => {
    if (!canModifySharedTheme) {
      setRoomError('当前只有房主可以修改共创主题。');
      setRoomMessage('');
      return;
    }
    setIsGenerating(true);
    try {
      const { locationName, locationContextText } = await resolveCurrentContext();
      const theme =
        selectedThemesForCombine.length === 1
          ? await generateDynamicPreset(selectedThemesForCombine[0], locationName, locationContextText, walkMode)
          : await generateAITheme(
              mood,
              weather,
              season,
              preference,
              locationName,
              locationContextText,
              walkMode,
            );
      pushThemeHistory(theme);
    } finally {
      setIsGenerating(false);
    }
  };

  const closeAgentPlanningStream = () => {
    agentStreamRef.current?.close();
    agentStreamRef.current = null;
  };

  const resetAgentWorkspace = (nextStatus = '') => {
    setAgentAnswer('');
    setAgentEvents([]);
    setAgentSuggestedPois([]);
    setShowAgentTimelineModal(false);
    setAgentStatus(nextStatus);
  };

  const handleStartAgentPlanning = () => {
    const prompt = agentPrompt.trim();
    if (!prompt) {
      setAgentStatus('先告诉 Agent 你想怎么逛，我再帮你开始规划。');
      return;
    }

    if (!getStoredToken()) {
      setAgentStatus('请先登录后再使用 Agent 路线规划。');
      setShowEmailLogin(true);
      setEmailLoginMode('login');
      return;
    }

    closeAgentPlanningStream();
    setIsAgentStreaming(true);
    resetAgentWorkspace('Agent 正在整理需求并准备调用工具...');

    const stream = openAgentStream(prompt);
    agentStreamRef.current = stream;
    let streamFinished = false;

    const handleAgentEvent = (event: Event) => {
      try {
        const messageEvent = event as MessageEvent<string>;
        const payload = JSON.parse(messageEvent.data) as AgentStreamEvent;

        if (payload.type !== 'complete') {
          setAgentEvents((prev) => [...prev, payload]);
        }

        if (payload.type === 'start') {
          setAgentStatus('Agent 已开始规划路线...');
          return;
        }

        if (payload.type === 'tool_call') {
          setAgentStatus(`正在调用工具：${payload.name}`);
          return;
        }

        if (payload.type === 'tool_result') {
          setAgentStatus(`已拿到工具结果：${payload.name}`);
          return;
        }

        if (payload.type === 'final_answer') {
          setAgentAnswer(payload.output || '');
          setAgentStatus('Agent 已生成路线建议。');
          return;
        }

        if (payload.type === 'complete') {
          streamFinished = true;
          setIsAgentStreaming(false);
          setAgentStatus('路线规划完成，可以继续追问或修改需求。');
          stream.close();
          if (agentStreamRef.current === stream) {
            agentStreamRef.current = null;
          }
        }
      } catch (error) {
        console.error('Agent stream parse error:', error);
      }
    };

    stream.addEventListener('start', handleAgentEvent);
    stream.addEventListener('tool_call', handleAgentEvent);
    stream.addEventListener('tool_result', handleAgentEvent);
    stream.addEventListener('final_answer', handleAgentEvent);
    stream.addEventListener('complete', handleAgentEvent);

    stream.onerror = () => {
      if (streamFinished) {
        return;
      }
      console.error('Agent stream connection error');
      setIsAgentStreaming(false);
      setAgentStatus('Agent 流式连接中断了，请稍后再试一次。');
      stream.close();
      if (agentStreamRef.current === stream) {
        agentStreamRef.current = null;
      }
    };
  };

  const handleStopAgentPlanning = () => {
    closeAgentPlanningStream();
    setIsAgentStreaming(false);
    setAgentStatus('已停止当前 Agent 规划。');
  };

  const handleClearAgentMemory = async () => {
    if (!getStoredToken()) {
      setAgentStatus('请先登录后再使用 Agent 路线规划。');
      setShowEmailLogin(true);
      setEmailLoginMode('login');
      return;
    }

    if (isAgentStreaming) {
      closeAgentPlanningStream();
      setIsAgentStreaming(false);
    }

    setIsClearingAgentMemory(true);
    try {
      await clearAgentMemory();
      resetAgentWorkspace('已开始新对话，Agent 记忆已清空。');
    } catch (error) {
      setAgentStatus(error instanceof Error ? error.message : '清空 Agent 记忆失败，请稍后再试。');
    } finally {
      setIsClearingAgentMemory(false);
    }
  };

  const handleApplyAgentResult = async () => {
    if (!canModifySharedTheme) {
      setAgentStatus('当前只有房主可以把 Agent 结果应用为房间主题。');
      return;
    }
    const normalizedAnswer = normalizeAgentMarkdown(agentAnswer || '');
    if (!normalizedAnswer) {
      setAgentStatus('当前还没有可应用的 Agent 结果。');
      return;
    }

    setIsApplyingAgentResult(true);
    try {
      const routeCandidates = extractRoutePointCandidatesFromAnswer(normalizedAnswer);
      // 把 Agent 的最终答案重新整理成主题/任务结构，
      // 这样就能直接复用页面上现有的 Current Theme 卡片。
      const agentTheme = buildThemeFromAgentAnswer(normalizedAnswer, routeCandidates, currentLocationName);
      const routeAnchor =
        selectedLocation ??
        currentPosition ?? {
          name: currentLocationName,
          lat: DEFAULT_CENTER[0],
          lng: DEFAULT_CENTER[1],
        };
      const routeContextKeyword =
        extractBroadLocationName(locationContext) ||
        extractBroadLocationName(currentLocationName) ||
        currentLocationName;
      const resolvedRoutePois = (
        await Promise.all(
          routeCandidates.map(async (candidate) => {
            try {
              // 先带着本地上下文搜一遍，尽量减少“同名地点落到别的城市”的误匹配。
              const queryOptions = [
                `${routeContextKeyword} ${candidate}`.trim(),
                `${currentLocationName} ${candidate}`.trim(),
                candidate,
              ].filter((item, index, array) => item && array.indexOf(item) === index);

              const mergedResults = [];
              for (const query of queryOptions) {
                const results = await searchLocations(query);
                mergedResults.push(...results);
                if (mergedResults.length >= 5) {
                  break;
                }
              }

              const dedupedResults = mergedResults.filter((item, index, array) => {
                const key = `${item.name}-${item.lat}-${item.lng}`;
                return index === array.findIndex((candidateItem) => `${candidateItem.name}-${candidateItem.lat}-${candidateItem.lng}` === key);
              });

              const best = dedupedResults
                .map((item) => {
                  const distanceKm = calculateDistanceKm(routeAnchor.lat, routeAnchor.lng, item.lat, item.lng);
                  return {
                    item,
                    distanceKm,
                    score: scoreRouteSearchResult(candidate, item.name, distanceKm),
                  };
                })
                .sort((left, right) => right.score - left.score || left.distanceKm - right.distanceKm)[0];

              if (!best || !isConfidentRouteSearchMatch(best.score, best.distanceKm)) {
                return null;
              }
              return {
                title: best.item.name,
                lat: best.item.lat,
                lng: best.item.lng,
                uri: buildAgentPoiUri(best.item.name, best.item.lng, best.item.lat),
              } satisfies MapPOI;
            } catch (error) {
              console.error('Resolve agent route point error:', candidate, error);
              return null;
            }
          }),
        )
      ).filter((item): item is NonNullable<typeof item> => item !== null);
      const toolSuggestedPois = extractAgentSuggestedPois(agentEvents);
      // 优先使用从最终答案里解析出来的路线点；
      // 如果解析不稳定，再退回到工具执行过程里拿到的点位结果。
      const suggestedPois = resolvedRoutePois.length > 0 ? resolvedRoutePois : toolSuggestedPois;
      const notePrefix = '【Agent 路线规划建议】';
      setNoteText((prev) => {
        const cleanedPrev = prev.includes(notePrefix) ? prev.split(notePrefix)[0].trimEnd() : prev.trim();
        const nextContent = `${notePrefix}\n${normalizedAnswer}`;
        return cleanedPrev ? `${cleanedPrev}\n\n${nextContent}` : nextContent;
      });
      setCurrentTheme(agentTheme);
      setCheckedMissions([]);

      if (suggestedPois.length > 0) {
        setAgentSuggestedPois(suggestedPois);
        const firstPoi = suggestedPois[0];
        if (typeof firstPoi.lat === 'number' && typeof firstPoi.lng === 'number') {
          setSelectedLocation({
            name: firstPoi.title,
            lat: firstPoi.lat,
            lng: firstPoi.lng,
          });
          setSelectedPoiKey(`${firstPoi.title}-${firstPoi.lat}-${firstPoi.lng}`);
          setSearchLocation(firstPoi.title);
          try {
            const [geoContext, nameContext] = await Promise.all([
              getLocationContext(firstPoi.lat, firstPoi.lng),
              searchLocationContext(firstPoi.title),
            ]);
            setLocationContext(nameContext && nameContext !== firstPoi.title ? nameContext : geoContext);
          } catch (error) {
            console.error('Refresh applied agent point context error:', error);
          }
        }
        const unresolvedCount = Math.max(0, routeCandidates.length - resolvedRoutePois.length);
        setAgentStatus(
          unresolvedCount > 0
            ? `已写入备注，并在地图上标出 ${suggestedPois.length} 个可信点位；另有 ${unresolvedCount} 个路线点未通过本地匹配校验，已跳过。`
            : `已将路线建议写入备注，并在地图上标出 ${suggestedPois.length} 个相关点位。`
        );
      } else {
        setAgentStatus('已将路线建议写入备注，但这次没有识别出足够可信的本地点位，所以没有直接上图。');
      }

      const unresolvedCount = Math.max(0, routeCandidates.length - resolvedRoutePois.length);
      if (suggestedPois.length > 0) {
        setAgentStatus(
          unresolvedCount > 0
            ? `已用 Agent 建议更新当前任务、写入备注，并在地图上标出 ${suggestedPois.length} 个可信点位；另有 ${unresolvedCount} 个路线点未通过本地匹配校验，已跳过。`
            : `已用 Agent 建议更新当前任务、写入备注，并在地图上标出 ${suggestedPois.length} 个相关点位。`,
        );
      } else {
        setAgentStatus('已用 Agent 建议更新当前任务，并写入备注；但这次没有识别出足够可信的本地点位，所以没有直接上图。');
      }

      setShowAgentTimelineModal(false);
      setShowAgentPlannerModal(false);
      setActiveTab('explore');
      window.setTimeout(() => {
        noteTextareaRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
        noteTextareaRef.current?.focus();
      }, 120);
    } catch (error) {
      console.error('Apply agent result error:', error);
      setAgentStatus('应用 Agent 结果失败，请稍后再试。');
    } finally {
      setIsApplyingAgentResult(false);
    }
  };

  const closeAgentPlannerModal = () => {
    if (isAgentStreaming) {
      closeAgentPlanningStream();
      setIsAgentStreaming(false);
      setAgentStatus('已关闭 Agent 窗口，本次规划已停止。');
    }
    setShowAgentTimelineModal(false);
    setShowAgentPlannerModal(false);
  };

  const handleCombineThemes = async () => {
    if (!canModifySharedTheme) {
      setRoomError('当前只有房主可以修改共创主题。');
      setRoomMessage('');
      return;
    }
    if (selectedThemesForCombine.length < 2) {
      alert('请至少选择两个主题方向。');
      return;
    }

    setIsGenerating(true);
    try {
      const { locationName, locationContextText } = await resolveCurrentContext();
      const theme = await generateCombinedTheme(selectedThemesForCombine, locationName, locationContextText, walkMode);
      pushThemeHistory(theme);
      setSelectedThemesForCombine([]);
    } finally {
      setIsGenerating(false);
    }
  };

  const handleSignIn = () => {
    setAuthError('');
    setAuthInfo('');
    setEmailLoginMode('login');
    setShowEmailLogin(true);
  };

  const getEmailSendErrorMessage = (mode: EmailAuthMode, error: unknown) => {
    const message = error instanceof Error ? error.message : '';
    if (message.includes('code_send_too_frequent')) {
      return '验证码刚发过，请等 60 秒后再试。';
    }
    if (message.includes('email_not_registered')) {
      return '这个 QQ 邮箱还没有注册账号，不能直接找回密码。';
    }
    if (message.includes('email_already_registered')) {
      return '这个 QQ 邮箱已经注册过了，请直接登录。';
    }
    if (message.includes('email_not_supported')) {
      return mode === 'reset' ? '目前只支持 QQ 邮箱找回密码。' : '目前只支持 QQ 邮箱获取验证码。';
    }
    if (message.includes('email_send_failed')) {
      return '验证码邮件发送失败，请稍后重试。';
    }
    return '验证码发送失败，请稍后重试。';
  };

  const getEmailAuthErrorMessage = (mode: EmailAuthMode, error: unknown) => {
    const message = error instanceof Error ? error.message : '';

    if (message.includes('login_required') || message.includes('invalid_token')) {
      return mode === 'reset' ? '重置密码请求被拦截了，请刷新页面后重试；如果后端刚更新，请重启后端服务。' : '登录状态已失效，请刷新页面后重试。';
    }

    if (mode === 'register') {
      if (message.includes('code_invalid')) {
        return '验证码无效或已过期。';
      }
      if (message.includes('email_already_registered')) {
        return '这个 QQ 邮箱已经注册过了。';
      }
      if (message.includes('email_not_supported')) {
        return '目前只支持 QQ 邮箱。';
      }
      if (message.includes('password_too_short')) {
        return '密码至少需要 6 位。';
      }
      return '注册失败，请检查邮箱、验证码和密码。';
    }

    if (mode === 'reset') {
      if (message.includes('code_invalid')) {
        return '验证码无效或已过期。';
      }
      if (message.includes('email_not_registered')) {
        return '这个 QQ 邮箱还没有注册账号。';
      }
      if (message.includes('email_not_supported')) {
        return '目前只支持 QQ 邮箱。';
      }
      if (message.includes('password_too_short')) {
        return '新密码至少需要 6 位。';
      }
      return '重置密码失败，请检查邮箱、验证码和新密码。';
    }

    if (message.includes('email_not_registered')) {
      return '这个 QQ 邮箱还没有注册账号。';
    }
    if (message.includes('invalid_password')) {
      return '密码不正确，请重新输入。';
    }
    if (message.includes('email_not_supported')) {
      return '目前只支持 QQ 邮箱。';
    }
    return '登录失败，请检查邮箱和密码。';
  };

  const handleEmailAuthSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const trimmedEmail = emailInput.trim().toLowerCase();
    if (!trimmedEmail || !trimmedEmail.endsWith('@qq.com')) {
      setAuthError('请使用 QQ 邮箱登录，例如 name@qq.com。');
      return;
    }
    if (!passwordInput || passwordInput.length < 6) {
      setAuthError(emailLoginMode === 'reset' ? '新密码至少需要 6 位。' : '密码至少需要 6 位。');
      return;
    }

    setIsAuthLoading(true);
    setAuthError('');
    setAuthInfo('');
    try {
      let profile: AppUser | null = null;
      if (emailLoginMode !== 'login') {
        if (!emailCodeInput.trim()) {
          setAuthError('请输入邮箱验证码。');
          setIsAuthLoading(false);
          return;
        }
        if (emailLoginMode === 'register') {
          profile = await registerWithEmail(trimmedEmail, passwordInput, emailCodeInput.trim());
        } else {
          await resetPasswordWithEmail(trimmedEmail, passwordInput, emailCodeInput.trim());
          setEmailLoginMode('login');
          setPasswordInput('');
          setEmailCodeInput('');
          setAuthInfo('密码已重置成功，现在可以使用新密码登录。');
          setIsAuthLoading(false);
          return;
        }
      } else {
        profile = await loginWithEmail(trimmedEmail, passwordInput);
      }
      if (!profile) {
        profile = await loadCurrentUser();
      }
      setUser(profile);
      await refreshRecentWalks(profile);
      setShowEmailLogin(false);
      setPasswordInput('');
      setEmailCodeInput('');
    } catch (error) {
      console.error('Email auth error:', error);
      setAuthError(getEmailAuthErrorMessage(emailLoginMode, error));
    } finally {
      setIsAuthLoading(false);
    }
  };

  const handleSendEmailCode = async () => {
    const trimmedEmail = emailInput.trim().toLowerCase();
    if (!trimmedEmail || !trimmedEmail.endsWith('@qq.com')) {
      setAuthError('请先填写正确的 QQ 邮箱。');
      return;
    }
    if (sendCodeCooldown > 0 || isSendingCode) {
      return;
    }
    setIsSendingCode(true);
    setAuthError('');
    setAuthInfo('');
    try {
      await sendEmailCode(trimmedEmail, emailLoginMode === 'reset' ? 'reset' : 'register');
      setSendCodeCooldown(60);
      setAuthInfo('验证码已发送，30 分钟内有效。请查看 QQ 邮箱收件箱或垃圾箱；如果重复发送，请以最后一次收到的验证码为准。');
    } catch (error) {
      console.error('Send email code error:', error);
      setAuthError(getEmailSendErrorMessage(emailLoginMode, error));
      setAuthInfo('');
    } finally {
      setIsSendingCode(false);
    }
  };

  const handleSignOut = async () => {
    try {
      await logoutFromServer();
    } catch (error) {
      console.error('Logout error:', error);
    } finally {
      clearStoredActiveRoomCode();
      roomRestoreAttemptedRef.current = false;
      setUser(null);
      setMyWalks([]);
      setLikedWalks([]);
      setFavoritedWalks([]);
      setSelectedProfileWalk(null);
      setProfileViewMode('feed');
      setProfileCollectionTab('mine');
      setShowProfileEditor(false);
      setProfileMessage('');
    }
  };

  const handleDeleteAccount = async () => {
    const confirmed = window.confirm('注销后会删除该账号的个人资料、漫步记录、评论、点赞与收藏，且无法恢复。确定继续吗？');
    if (!confirmed) {
      return;
    }

    try {
      await deleteAccountFromServer();
    } catch (error) {
      console.error('Delete account error:', error);
      const message = error instanceof Error ? error.message : '注销失败，请稍后重试。';
      setProfileMessage(message.includes('login_required') ? '登录状态已失效，请重新登录后再试。' : `注销失败：${message}`);
      return;
    }

    clearStoredActiveRoomCode();
    roomRestoreAttemptedRef.current = false;
    setUser(null);
    setMyWalks([]);
    setLikedWalks([]);
    setFavoritedWalks([]);
    setSelectedProfileWalk(null);
    setProfileViewMode('feed');
    setProfileCollectionTab('mine');
    setShowProfileEditor(false);
    setProfileMessage('账号已注销。');
  };

  const handleOpenProfileWalk = async (walkId: number) => {
    setIsLoadingProfile(true);
    setProfileViewMode('post');
    setCommunityCommentInput('');
    setCommunityReplyTarget(null);
    setCommunityCommentError('');
    setCommunityComments([]);
    try {
      const preview = profileWalkSource.find((walk) => walk.id === walkId) || myWalks.find((walk) => walk.id === walkId);
      if (preview) {
        setSelectedProfileWalk(preview);
      }
      if (profileCollectionTab === 'mine') {
        const detail = await fetchWalkDetail(walkId);
        setSelectedProfileWalk(preview ? { ...preview, ...detail } : detail);
        await loadCommunityComments(walkId);
        return;
      }

      const detail = await fetchCommunityWalkDetail(walkId);
      setCommunityWalks((prev) => prev.map((walk) => (walk.id === walkId ? { ...walk, ...detail } : walk)));
      setLikedWalks((prev) => prev.map((walk) => (walk.id === walkId ? { ...walk, ...detail } : walk)));
      setFavoritedWalks((prev) => prev.map((walk) => (walk.id === walkId ? { ...walk, ...detail } : walk)));
      setSelectedCommunityWalk((prev) => (prev && prev.id === walkId ? { ...prev, ...detail } : prev));
      setSelectedProfileWalk(preview ? { ...preview, ...detail } : detail);
      await loadCommunityComments(walkId);
    } catch (error) {
      console.error('Fetch walk detail error:', error);
      setProfileViewMode('feed');
    } finally {
      setIsLoadingProfile(false);
    }
  };

  const handleOpenCommunityWalk = async (walkId: number) => {
    setIsLoadingCommunity(true);
    setCommunityViewMode('post');
    setCommunityCommentInput('');
    setCommunityReplyTarget(null);
    setCommunityCommentError('');
    setCommunityComments([]);
    try {
      const preview = communityWalks.find((walk) => walk.id === walkId);
      if (preview) {
        setSelectedCommunityWalk(preview);
      }
      const detail = await fetchCommunityWalkDetail(walkId);
      setCommunityWalks((prev) => prev.map((walk) => (walk.id === walkId ? { ...walk, ...detail } : walk)));
      setSelectedCommunityWalk(preview ? { ...preview, ...detail } : detail);
      await loadCommunityComments(walkId);
    } catch (error) {
      console.error('Fetch community walk detail error:', error);
      setCommunityViewMode('feed');
    } finally {
      setIsLoadingCommunity(false);
    }
  };

  const profileWalkSource = useMemo(() => {
    if (profileCollectionTab === 'favorited') {
      return favoritedWalks;
    }
    if (profileCollectionTab === 'liked') {
      return likedWalks;
    }
    return myWalks;
  }, [favoritedWalks, likedWalks, myWalks, profileCollectionTab]);
  const profileCollectionMeta = useMemo(() => {
    if (profileCollectionTab === 'favorited') {
      return {
        title: '我的收藏',
        description: '把你收藏过的公开漫步帖子集中放在这里，方便随时回看和继续找灵感。',
        empty: '你还没有收藏过帖子，去社区逛逛，把喜欢的路线先收进这里吧。',
      };
    }
    if (profileCollectionTab === 'liked') {
      return {
        title: '我赞过的',
        description: '这里会展示你点过赞的帖子，适合回头翻翻那些曾经打动你的漫步瞬间。',
        empty: '你还没有点赞过帖子，看到喜欢的内容时可以先点个赞留痕。',
      };
    }
    return {
      title: '我的历史记录',
      description: '这里像你的漫步帖子流。每一张卡片都可以点开，进入更完整的帖子详情页。',
      empty: '还没有保存过漫步记录，先去探索页生成一条属于自己的城市帖子吧。',
    };
  }, [profileCollectionTab]);
  const profileStats = useMemo(() => {
    if (profileCollectionTab === 'favorited') {
      return [
        { label: '收藏总数', value: favoritedWalks.length },
        { label: '公开帖子', value: favoritedWalks.filter((item) => item.isPublic).length },
        { label: '带照片帖子', value: favoritedWalks.filter((item) => Boolean(item.photoUrl)).length },
      ];
    }
    if (profileCollectionTab === 'liked') {
      return [
        { label: '点赞总数', value: likedWalks.length },
        { label: '公开帖子', value: likedWalks.filter((item) => item.isPublic).length },
        { label: '带照片帖子', value: likedWalks.filter((item) => Boolean(item.photoUrl)).length },
      ];
    }
    return [
      { label: '记录总数', value: myWalks.length },
      { label: '公开记录', value: myWalks.filter((item) => item.isPublic).length },
      { label: '带照片记录', value: myWalks.filter((item) => Boolean(item.photoUrl)).length },
    ];
  }, [favoritedWalks, likedWalks, myWalks, profileCollectionTab]);

  const selectedProfileCommunityWalk = useMemo(() => {
    if (
      selectedProfileWalk &&
      'likeCount' in selectedProfileWalk &&
      'favoriteCount' in selectedProfileWalk &&
      'viewCount' in selectedProfileWalk
    ) {
      return selectedProfileWalk as CommunityWalkItem;
    }
    return null;
  }, [selectedProfileWalk]);

  const activeCommentWalk =
    activeTab === 'profile' && profileViewMode === 'post'
      ? selectedProfileWalk
      : activeTab === 'community' && communityViewMode === 'post'
      ? selectedCommunityWalk
      : null;

  const applyCommunityEngagementState = (nextState: CommunityEngagementState) => {
    const matchedCommunityWalk =
      (selectedCommunityWalk?.id === nextState.walkId ? selectedCommunityWalk : null) ||
      (selectedProfileCommunityWalk?.id === nextState.walkId ? selectedProfileCommunityWalk : null) ||
      communityWalks.find((walk) => walk.id === nextState.walkId) ||
      likedWalks.find((walk) => walk.id === nextState.walkId) ||
      favoritedWalks.find((walk) => walk.id === nextState.walkId) ||
      null;

    const nextWalkSnapshot = matchedCommunityWalk
      ? {
          ...matchedCommunityWalk,
          likeCount: nextState.likeCount,
          favoriteCount: nextState.favoriteCount,
          liked: nextState.liked,
          favorited: nextState.favorited,
        }
      : null;

    setCommunityWalks((prev) =>
      prev.map((walk) =>
        walk.id === nextState.walkId
          ? {
              ...walk,
              likeCount: nextState.likeCount,
              favoriteCount: nextState.favoriteCount,
              liked: nextState.liked,
              favorited: nextState.favorited,
            }
          : walk,
      ),
    );
    setSelectedCommunityWalk((prev) =>
      prev && prev.id === nextState.walkId
        ? {
            ...prev,
            likeCount: nextState.likeCount,
            favoriteCount: nextState.favoriteCount,
            liked: nextState.liked,
            favorited: nextState.favorited,
          }
        : prev,
    );
    setSelectedProfileWalk((prev) =>
      prev &&
      prev.id === nextState.walkId &&
      'likeCount' in prev &&
      'favoriteCount' in prev &&
      'viewCount' in prev
        ? {
            ...prev,
            likeCount: nextState.likeCount,
            favoriteCount: nextState.favoriteCount,
            liked: nextState.liked,
            favorited: nextState.favorited,
          }
        : prev,
    );
    setLikedWalks((prev) =>
      {
        const updated = prev
          .map((walk) =>
            walk.id === nextState.walkId
              ? {
                  ...walk,
                  likeCount: nextState.likeCount,
                  favoriteCount: nextState.favoriteCount,
                  liked: nextState.liked,
                  favorited: nextState.favorited,
                }
              : walk,
          )
          .filter((walk) => walk.liked || walk.id !== nextState.walkId);

        if (!nextState.liked || !nextWalkSnapshot || updated.some((walk) => walk.id === nextState.walkId)) {
          return updated;
        }

        return [nextWalkSnapshot, ...updated];
      },
    );
    setFavoritedWalks((prev) =>
      {
        const updated = prev
          .map((walk) =>
            walk.id === nextState.walkId
              ? {
                  ...walk,
                  likeCount: nextState.likeCount,
                  favoriteCount: nextState.favoriteCount,
                  liked: nextState.liked,
                  favorited: nextState.favorited,
                }
              : walk,
          )
          .filter((walk) => walk.favorited || walk.id !== nextState.walkId);

        if (!nextState.favorited || !nextWalkSnapshot || updated.some((walk) => walk.id === nextState.walkId)) {
          return updated;
        }

        return [nextWalkSnapshot, ...updated];
      },
    );
  };

  const removeWalkFromLocalState = (walkId: number) => {
    setMyWalks((prev) => prev.filter((walk) => walk.id !== walkId));
    setCommunityWalks((prev) => prev.filter((walk) => walk.id !== walkId));
    setLikedWalks((prev) => prev.filter((walk) => walk.id !== walkId));
    setFavoritedWalks((prev) => prev.filter((walk) => walk.id !== walkId));
    setSelectedCommunityWalk((prev) => (prev?.id === walkId ? null : prev));
    setSelectedProfileWalk((prev) => (prev?.id === walkId ? null : prev));
  };

  const mergeUpdatedWalk = <T extends WalkItem>(walk: T, updated: WalkItem): T => ({
    ...walk,
    ...updated,
    tags: updated.tags ?? walk.tags,
  } as T);

  const applyUpdatedWalk = (updated: WalkItem) => {
    setMyWalks((prev) => prev.map((walk) => (walk.id === updated.id ? mergeUpdatedWalk(walk, updated) : walk)));
    setCommunityWalks((prev) =>
      updated.isPublic
        ? prev.map((walk) => (walk.id === updated.id ? mergeUpdatedWalk(walk, updated) : walk))
        : prev.filter((walk) => walk.id !== updated.id),
    );
    setLikedWalks((prev) =>
      updated.isPublic
        ? prev.map((walk) => (walk.id === updated.id ? mergeUpdatedWalk(walk, updated) : walk))
        : prev.filter((walk) => walk.id !== updated.id),
    );
    setFavoritedWalks((prev) =>
      updated.isPublic
        ? prev.map((walk) => (walk.id === updated.id ? mergeUpdatedWalk(walk, updated) : walk))
        : prev.filter((walk) => walk.id !== updated.id),
    );
    setSelectedProfileWalk((prev) => (prev?.id === updated.id ? mergeUpdatedWalk(prev, updated) : prev));
    setSelectedCommunityWalk((prev) => {
      if (prev?.id !== updated.id) {
        return prev;
      }
      return updated.isPublic ? mergeUpdatedWalk(prev, updated) : null;
    });
  };

  const parseWalkEditTags = (value: string) =>
    value
      .split(/[#,，、\s]+/)
      .map((tag) => tag.trim())
      .filter(Boolean)
      .slice(0, 8);

  const openWalkEditor = (walk: WalkItem) => {
    setEditingWalk(walk);
    setEditWalkTitle(walk.themeTitle || '');
    setEditWalkNote(walk.noteText || '');
    setEditWalkTags((walk.tags || []).map((tag) => `#${tag}`).join(' '));
    setEditWalkIsPublic(Boolean(walk.isPublic));
    setWalkEditError('');
  };

  const closeWalkEditor = () => {
    if (isSavingWalkEdit) {
      return;
    }
    setEditingWalk(null);
    setWalkEditError('');
  };

  const handleSaveWalkEdit = async () => {
    if (!editingWalk) {
      return;
    }
    const nextTitle = editWalkTitle.trim();
    if (!nextTitle) {
      setWalkEditError('帖子标题不能为空。');
      return;
    }

    setIsSavingWalkEdit(true);
    setWalkEditError('');
    try {
      const updated = await updateWalk(editingWalk.id, {
        themeTitle: nextTitle,
        themeCategory: editingWalk.themeCategory || '',
        noteText: editWalkNote.trim(),
        isPublic: editWalkIsPublic,
        tags: parseWalkEditTags(editWalkTags),
      });
      applyUpdatedWalk(updated);
      if (!updated.isPublic && selectedCommunityWalk?.id === updated.id) {
        setCommunityViewMode('feed');
        setCommunityComments([]);
        setCommunityReplyTarget(null);
        setCommunityCommentInput('');
      }
      setEditingWalk(null);
      setProfileMessage('帖子已更新。');
      setCommunityError('');
    } catch (error) {
      console.error('Update walk error:', error);
      setWalkEditError(error instanceof Error ? error.message : '帖子更新失败，请稍后再试。');
    } finally {
      setIsSavingWalkEdit(false);
    }
  };

  const handleToggleCommunityLike = async (walk: CommunityWalkItem, event?: React.MouseEvent) => {
    event?.stopPropagation();
    event?.preventDefault();
    try {
      const nextState = walk.liked ? await unlikeCommunityWalk(walk.id) : await likeCommunityWalk(walk.id);
      applyCommunityEngagementState(nextState);
      setCommunityError('');
    } catch (error) {
      console.error('Toggle community like error:', error);
      setCommunityError('点赞失败，请先登录后重试。');
    }
  };

  const handleToggleCommunityFavorite = async (walk: CommunityWalkItem, event?: React.MouseEvent) => {
    event?.stopPropagation();
    event?.preventDefault();
    try {
      const nextState = walk.favorited ? await unfavoriteCommunityWalk(walk.id) : await favoriteCommunityWalk(walk.id);
      applyCommunityEngagementState(nextState);
      setCommunityError('');
    } catch (error) {
      console.error('Toggle community favorite error:', error);
      setCommunityError('收藏失败，请先登录后重试。');
    }
  };

  const handleOpenNotification = async (notification: UserNotificationItem) => {
    if (!notification.read) {
      try {
        await markNotificationRead(notification.id);
      } catch (error) {
        console.error('Mark notification read error:', error);
      }

      setNotifications((prev) => prev.map((item) => (item.id === notification.id ? { ...item, read: true } : item)));
      setNotificationUnreadCount((prev) => Math.max(0, prev - 1));
    }

    setShowNotificationCenter(false);
    if (!notification.walkId) {
      return;
    }

    setActiveTab('community');
    await handleOpenCommunityWalk(notification.walkId);
  };

  const handleMarkAllNotificationsRead = async () => {
    try {
      await markAllNotificationsRead();
      setNotifications((prev) => prev.map((item) => ({ ...item, read: true })));
      setNotificationUnreadCount(0);
    } catch (error) {
      console.error('Mark all notifications read error:', error);
    }
  };

  const handleSubmitCommunityComment = async () => {
    if (!activeCommentWalk) {
      return;
    }
    const content = communityCommentInput.trim();
    if (!content) {
      setCommunityCommentError('评论内容不能为空。');
      return;
    }

    setIsSubmittingCommunityComment(true);
    setCommunityCommentError('');
    try {
      await createCommunityComment(activeCommentWalk.id, {
        content,
        parentId: communityReplyTarget?.id ?? null,
      });
      setCommunityCommentInput('');
      setCommunityReplyTarget(null);
      await loadCommunityComments(activeCommentWalk.id);
    } catch (error) {
      console.error('Submit community comment error:', error);
      setCommunityCommentError(error instanceof Error ? error.message : '评论发布失败，请先登录后重试。');
    } finally {
      setIsSubmittingCommunityComment(false);
    }
  };

  const handleDeleteCommunityComment = async (commentId: number) => {
    if (!activeCommentWalk) {
      return;
    }
    const confirmed = window.confirm('删除后评论内容会变成“该评论已删除”，但楼层结构会保留。确定继续吗？');
    if (!confirmed) {
      return;
    }

    try {
      await deleteCommunityComment(commentId);
      await loadCommunityComments(activeCommentWalk.id);
    } catch (error) {
      console.error('Delete community comment error:', error);
      setCommunityCommentError(error instanceof Error ? error.message : '删除评论失败，请稍后再试。');
    }
  };

  const handleDeleteWalk = async (walkId: number, options?: { source?: 'community' | 'profile' }) => {
    const confirmed = window.confirm('删除后这条帖子将不会再出现在社区和个人主页中。确定继续吗？');
    if (!confirmed) {
      return;
    }

    try {
      await deleteWalk(walkId);
      removeWalkFromLocalState(walkId);

      if (options?.source === 'community') {
        setCommunityViewMode('feed');
        setSelectedCommunityWalk(null);
        setCommunityComments([]);
        setCommunityReplyTarget(null);
        setCommunityCommentInput('');
      }

      if (options?.source === 'profile') {
        setProfileViewMode('feed');
        setSelectedProfileWalk(null);
        setCommunityComments([]);
        setCommunityReplyTarget(null);
        setCommunityCommentInput('');
      }

      await refreshRecentWalks();
      setCommunityError('');
      setProfileMessage('帖子已删除。');
    } catch (error) {
      console.error('Delete walk error:', error);
      const message = error instanceof Error ? error.message : '删除帖子失败，请稍后再试。';
      if (options?.source === 'community') {
        setCommunityError(message);
      } else {
        setProfileMessage(message);
      }
    }
  };

  const getCommunityFeedTabLabel = (tab: CommunityFeedTab) => {
    if (tab === 'latest') {
      return '最新';
    }
    if (tab === 'hot') {
      return '最热';
    }
    return '推荐';
  };

  const handleCommunitySearchSubmit = async (event?: React.FormEvent<HTMLFormElement>) => {
    event?.preventDefault();
    const keyword = communitySearchInput.trim();
    setCommunitySearchKeyword(keyword);
    setCommunityViewMode('feed');
    setSelectedCommunityWalk(null);
    setShowCommunityFilterMenu(false);

    if (activeTab === 'community') {
      await loadCommunityWalks({ keyword });
    }
  };

  const handleCommunitySearchReset = async () => {
    setCommunitySearchInput('');
    setCommunitySearchKeyword('');
    setCommunityViewMode('feed');
    setSelectedCommunityWalk(null);
    setShowCommunityFilterMenu(false);

    if (activeTab === 'community') {
      await loadCommunityWalks({ keyword: '', tab: communityFeedTab });
    }
  };

  const handleSaveProfile = async () => {
    if (!user) {
      alert('请先登录后再编辑个人资料。');
      return;
    }

    const nextNickname = profileNickname.trim();
    const nextBio = profileBio.trim();
    if (!nextNickname) {
      alert('昵称不能为空。');
      return;
    }

    setIsSavingProfile(true);
    setProfileMessage('');
    try {
      let nextAvatar = user.avatar || '';
      if (profileAvatarPreview && profileAvatarPreview !== user.avatar && profileAvatarPreview.startsWith('data:')) {
        const uploadName = profileAvatarName || `avatar-${Date.now()}.png`;
        const uploadResult = await uploadDataUrl(profileAvatarPreview, 'avatar', uploadName);
        nextAvatar = uploadResult.url;
      } else if (profileAvatarPreview) {
        nextAvatar = profileAvatarPreview;
      }

      const updatedUser = await updateUserProfile({
        nickname: nextNickname,
        avatar: nextAvatar,
        bio: nextBio,
      });
      setUser(updatedUser);
      setProfileMessage('个人资料已更新。');
    } catch (error) {
      console.error('Update profile error:', error);
      alert('更新个人资料失败，请稍后重试。');
    } finally {
      setIsSavingProfile(false);
    }
  };

  const handleSaveWalk = async () => {
    if (!user) {
      alert('请先登录后再保存记录。');
      return;
    }

    if (!currentTheme) {
      alert('请先生成一个主题。');
      return;
    }

    setIsSaving(true);
    try {
      let photoUrl: string | undefined;
      const cardPhotoSource = walkUploadPreview || undefined;
      if (walkUploadPreview) {
        setIsWalkUploading(true);
        const uploadName = walkUploadName || `walk-${Date.now()}.png`;
        const uploadResult = await uploadDataUrl(walkUploadPreview, 'walk_cover', uploadName);
        photoUrl = uploadResult.url;
        setIsWalkUploading(false);
      }

      const recordUnit: 'location' | 'event' | 'image' = photoUrl
        ? 'image'
        : path.length > 0
          ? 'location'
          : 'event';
      const { locationName, locationContextText } = await resolveCurrentContext();
      const savedRoomMembers =
        walkMode === 'advanced'
          ? buildSavedRoomMembers(coCreateRoom, user, path, livePosition ?? selectedLocation, checkedMissions, isTracking)
          : [];

      await createWalk({
        themeTitle: currentTheme.title,
        themeCategory: currentTheme.category,
        locationName,
        recordUnit,
        isPublic,
        noteText,
        path,
        completedMissions: checkedMissions.map((mission) => ({
          mission,
          mediaUrl: '',
          mediaType: '',
        })),
        roomCode: walkMode === 'advanced' ? coCreateRoom?.roomCode : undefined,
        roomMembers: savedRoomMembers,
        photoUrl,
      });
      const card = await generateWalkRecordCardWithAi({
        theme: currentTheme,
        locationName,
        locationContext: locationContextText,
        noteText,
        photoUrl: cardPhotoSource || photoUrl,
        completedMissions: checkedMissions,
      });
      const cardSvg = buildWalkRecordCardSvg(card);
      setRecordCardPreviewUrl(svgToDataUrl(cardSvg));
      setRecordCardFilename(`walk-record-${card.dateLabel.replaceAll('.', '-')}.svg`);
      setShowRecordCardModal(true);
      await refreshRecentWalks();
      setNoteText('');
      setPath([]);
      setIsTracking(false);
      setLivePosition(null);
      setCheckedMissions([]);
      setWalkUploadPreview(null);
      setWalkUploadName('');
    } catch (error) {
      console.error('Save walk error:', error);
      alert('保存漫步记录失败，请稍后重试。');
    } finally {
      setIsSaving(false);
      setIsWalkUploading(false);
    }
  };

  const handleDownloadRecordCard = () => {
    if (!recordCardPreviewUrl) {
      return;
    }

    const link = document.createElement('a');
    link.href = recordCardPreviewUrl;
    link.download = recordCardFilename;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  };

  return (
    <div className="min-h-screen bg-[radial-gradient(circle_at_top,#fff7ed,white_42%,#f8fafc)] text-slate-900">
      {showRecordCardModal && recordCardPreviewUrl && (
        <div className="fixed inset-0 z-50 bg-black/55 px-4 py-5 md:px-6 md:py-6">
          <div className="mx-auto flex h-full w-full max-w-6xl flex-col overflow-hidden rounded-[36px] bg-[#fff7ec] shadow-2xl lg:flex-row">
            <div className="flex min-h-0 flex-1 items-start justify-center overflow-y-auto p-4 md:p-6">
              <img
                src={recordCardPreviewUrl}
                alt="漫步记录卡预览"
                className="self-start w-full max-w-[460px] rounded-[28px] border border-[#e8d1b6] bg-white shadow-sm"
              />
            </div>
            <div className="flex w-full flex-col justify-between border-t border-[#ecdcc7] bg-white/70 p-6 lg:w-[340px] lg:border-l lg:border-t-0">
              <div>
                <p className="text-xs uppercase tracking-[0.25em] text-[#bb8d62]">Record Card</p>
                <h3 className="mt-2 text-2xl font-semibold text-[#5b402d]">记录卡已经生成</h3>
                <p className="mt-3 text-sm leading-7 text-[#7c6351]">
                  这次漫步的主题、地点、任务和你的照片都已经整理成一张完整卡片，你可以先在这里看完整效果，再决定是否下载。
                </p>
              </div>
              <div className="mt-6 flex flex-col gap-3">
                <button
                  onClick={handleDownloadRecordCard}
                  className="rounded-2xl bg-[#c96f4a] px-4 py-3 text-sm font-medium text-white"
                >
                  下载记录卡
                </button>
                <button
                  onClick={() => setShowRecordCardModal(false)}
                  className="rounded-2xl border border-[#d7bea0] bg-white px-4 py-3 text-sm font-medium text-[#6f5846]"
                >
                  关闭
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
      <NotificationCenter
        open={showNotificationCenter}
        notifications={notifications}
        unreadCount={notificationUnreadCount}
        isLoading={isLoadingNotifications}
        onClose={() => setShowNotificationCenter(false)}
        onOpenNotification={(item) => void handleOpenNotification(item)}
        onMarkAllRead={() => void handleMarkAllNotificationsRead()}
        formatDate={formatProfilePostDate}
      />
      {editingWalk ? (
        <div className="fixed inset-0 z-50 flex items-end bg-slate-900/45 px-3 py-3 sm:items-center sm:justify-center sm:px-4">
          <form
            onSubmit={(event) => {
              event.preventDefault();
              void handleSaveWalkEdit();
            }}
            className="w-full rounded-[26px] bg-white p-4 shadow-2xl sm:max-w-lg"
          >
            <div className="flex items-center justify-between gap-3">
              <div>
                <div className="text-xs uppercase tracking-[0.18em] text-slate-400">Edit Post</div>
                <h3 className="mt-1 text-lg font-semibold text-slate-900">编辑帖子</h3>
              </div>
              <button
                type="button"
                onClick={closeWalkEditor}
                className="rounded-full border border-slate-200 p-2 text-slate-500 transition hover:bg-slate-50"
                aria-label="关闭编辑器"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            <div className="mt-4 space-y-3">
              <label className="block">
                <span className="mb-1.5 block text-xs font-medium text-slate-500">标题</span>
                <input
                  value={editWalkTitle}
                  onChange={(event) => setEditWalkTitle(event.target.value)}
                  maxLength={60}
                  className="w-full rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm text-slate-800 outline-none focus:border-slate-400"
                  placeholder="给这篇笔记起个标题"
                />
              </label>

              <label className="block">
                <span className="mb-1.5 block text-xs font-medium text-slate-500">正文</span>
                <textarea
                  value={editWalkNote}
                  onChange={(event) => setEditWalkNote(event.target.value)}
                  rows={5}
                  maxLength={500}
                  className="w-full resize-none rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm leading-6 text-slate-800 outline-none focus:border-slate-400"
                  placeholder="补充这次 citywalk 的感受、路线亮点或避坑提醒"
                />
              </label>

              <label className="block">
                <span className="mb-1.5 block text-xs font-medium text-slate-500">标签</span>
                <input
                  value={editWalkTags}
                  onChange={(event) => setEditWalkTags(event.target.value)}
                  className="w-full rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm text-slate-800 outline-none focus:border-slate-400"
                  placeholder="#周末去哪儿 #拍照路线 #城市漫步"
                />
              </label>

              <label className="flex items-center justify-between rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm text-slate-700">
                <span>公开到社区</span>
                <input
                  type="checkbox"
                  checked={editWalkIsPublic}
                  onChange={(event) => setEditWalkIsPublic(event.target.checked)}
                  className="h-4 w-4 accent-slate-900"
                />
              </label>

              {walkEditError ? <div className="rounded-2xl bg-rose-50 px-4 py-3 text-sm text-rose-600">{walkEditError}</div> : null}
            </div>

            <div className="mt-5 flex gap-2">
              <button
                type="button"
                onClick={closeWalkEditor}
                className="flex-1 rounded-2xl border border-slate-200 px-4 py-3 text-sm font-medium text-slate-600"
              >
                取消
              </button>
              <button
                type="submit"
                disabled={isSavingWalkEdit}
                className="flex-1 rounded-2xl bg-slate-900 px-4 py-3 text-sm font-medium text-white disabled:cursor-wait disabled:opacity-70"
              >
                {isSavingWalkEdit ? '保存中...' : '保存'}
              </button>
            </div>
          </form>
        </div>
      ) : null}
      <div className="mx-auto flex min-h-screen max-w-6xl flex-col gap-8 px-4 py-6 md:px-8">
        <header className="flex flex-col gap-4 rounded-[32px] border border-amber-100 bg-white/80 p-5 shadow-sm backdrop-blur md:flex-row md:items-center md:justify-between">
          <div className="flex items-center gap-3">
            <div className="rounded-2xl bg-amber-100 p-3 text-amber-700">
              <Compass className="h-6 w-6" />
            </div>
            <div>
              <h1 className="text-2xl font-semibold tracking-tight">城市漫步者</h1>
              <p className="text-sm text-slate-500">重新发现城市角落的 City Walk 工具</p>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-3">
            <div className="flex rounded-full bg-slate-100 p-1">
              <button
                onClick={() => setActiveTab('explore')}
                className={`rounded-full px-4 py-2 text-sm ${activeTab === 'explore' ? 'bg-white shadow text-slate-900' : 'text-slate-500'}`}
              >
                探索
              </button>
              <button
                onClick={() => setActiveTab('community')}
                className={`rounded-full px-4 py-2 text-sm ${activeTab === 'community' ? 'bg-white shadow text-slate-900' : 'text-slate-500'}`}
              >
                <span className="inline-flex items-center gap-1">
                  <Users className="h-4 w-4" />
                  社区
                </span>
              </button>
              <button
                onClick={() => setActiveTab('profile')}
                className={`rounded-full px-4 py-2 text-sm ${activeTab === 'profile' ? 'bg-white shadow text-slate-900' : 'text-slate-500'}`}
              >
                <span className="inline-flex items-center gap-1">
                  <UserRound className="h-4 w-4" />
                  个人主页
                </span>
              </button>
            </div>

            {user ? (
              <div className="flex items-center gap-3">
                <button
                  type="button"
                  onClick={() => setShowNotificationCenter(true)}
                  className="relative rounded-full border border-slate-200 bg-white p-3 text-slate-600 transition hover:bg-slate-50 hover:text-slate-900"
                  aria-label="打开通知中心"
                >
                  <Bell className="h-4 w-4" />
                  {notificationUnreadCount > 0 ? (
                    <span className="absolute -right-1 -top-1 inline-flex min-w-5 items-center justify-center rounded-full bg-rose-500 px-1.5 py-0.5 text-[10px] font-medium text-white">
                      {notificationUnreadCount > 99 ? '99+' : notificationUnreadCount}
                    </span>
                  ) : null}
                </button>
                <button
                  onClick={() => setActiveTab('profile')}
                  className="flex items-center gap-2 rounded-full border border-slate-200 bg-white px-3 py-2"
                >
                  <img
                    src={user.avatar || 'https://placehold.co/40x40?text=U'}
                    alt="avatar"
                    className="h-8 w-8 rounded-full object-cover"
                  />
                  <span className="text-sm">{user.nickname}</span>
                </button>
                <button onClick={handleSignOut} className="rounded-full border border-slate-200 bg-white p-3">
                  <LogOut className="h-4 w-4" />
                </button>
              </div>
            ) : (
              <button
                onClick={handleSignIn}
                className="inline-flex items-center gap-2 rounded-full bg-slate-900 px-4 py-2 text-sm font-medium text-white"
              >
                <LogIn className="h-4 w-4" />
                QQ 邮箱登录
              </button>
            )}
          </div>
        </header>
        {showAgentPlannerModal ? (
          <div className="fixed inset-0 z-50 flex items-end bg-slate-900/45 px-3 py-3 sm:items-center sm:justify-center sm:px-4">
            <div className="flex max-h-[88vh] w-full flex-col overflow-hidden rounded-[30px] bg-white shadow-2xl sm:max-w-4xl">
              <div className="flex items-center justify-between gap-3 border-b border-slate-100 px-4 py-4 sm:px-6">
                <div>
                  <div className="text-xs uppercase tracking-[0.18em] text-slate-400">Agent Planner</div>
                  <h3 className="mt-1 text-lg font-semibold text-slate-900">Agent 路线规划窗口</h3>
                  <p className="mt-1 text-xs text-slate-500">在这里专门和 Agent 对话、看工具调用过程、查看最终路线建议。</p>
                </div>
                <button
                  type="button"
                  onClick={closeAgentPlannerModal}
                  className="rounded-full border border-slate-200 p-2 text-slate-500 transition hover:bg-slate-50"
                  aria-label="关闭 Agent 规划窗口"
                >
                  <X className="h-4 w-4" />
                </button>
              </div>

              <div className="overflow-y-auto px-4 py-4 sm:px-6 sm:py-5">
                <div className="grid gap-4 lg:grid-cols-[minmax(0,0.95fr)_minmax(0,1.25fr)]">
                  <div className="rounded-[28px] border border-slate-200 bg-slate-50/80 p-4">
                    <div className="flex flex-wrap items-start justify-between gap-3">
                      <div>
                        <p className="text-sm font-semibold text-slate-900">Agent 路线规划</p>
                        <p className="mt-1 text-xs leading-6 text-slate-500">
                          在左侧描述需求、发起规划、查看状态；右侧会固定显示当前路线建议。
                        </p>
                        {!canModifySharedTheme ? <p className="mt-2 text-xs text-amber-700">当前为共创房员身份，可以查看 Agent 规划结果，但不能把结果应用为房间主题。</p> : null}
                      </div>
                      <span className="rounded-full bg-white px-3 py-1 text-xs text-slate-500">
                        {isAgentStreaming ? '流式规划中' : '工作台模式'}
                      </span>
                    </div>

                    <div className="mt-4">
                      <p className="text-xs font-medium uppercase tracking-[0.18em] text-slate-400">Quick Prompts</p>
                      <div className="mt-2 flex flex-wrap gap-2">
                        {AGENT_QUICK_PROMPTS.map((item) => (
                          <button
                            key={item.label}
                            type="button"
                            onClick={() => setAgentPrompt(item.prompt)}
                            className="rounded-full border border-slate-200 bg-white px-3 py-2 text-xs font-medium text-slate-700 transition hover:border-amber-200 hover:bg-amber-50 hover:text-amber-700"
                          >
                            {item.label}
                          </button>
                        ))}
                      </div>
                    </div>

                    <textarea
                      value={agentPrompt}
                      onChange={(event) => setAgentPrompt(event.target.value)}
                      placeholder="比如：我想在上海找一条适合傍晚散步、拍照好看、能顺便喝咖啡的 City Walk 路线"
                      className="mt-4 min-h-36 w-full rounded-2xl border border-slate-200 bg-white p-4 text-sm text-slate-700 outline-none transition focus:border-amber-300 focus:ring-2 focus:ring-amber-100"
                    />

                    <div className="mt-3 flex flex-wrap gap-3">
                      <button
                        type="button"
                        onClick={handleStartAgentPlanning}
                        disabled={isAgentStreaming}
                        className="inline-flex items-center gap-2 rounded-full bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-60"
                      >
                        {isAgentStreaming && <LoaderCircle className="h-4 w-4 animate-spin" />}
                        {isAgentStreaming ? '规划中...' : '开始 Agent 规划'}
                      </button>
                      <button
                        type="button"
                        onClick={handleStopAgentPlanning}
                        disabled={!isAgentStreaming}
                        className="inline-flex items-center gap-2 rounded-full border border-slate-200 bg-white px-4 py-2 text-sm font-medium text-slate-600 disabled:opacity-50"
                      >
                        停止规划
                      </button>
                      <button
                        type="button"
                        onClick={() => void handleClearAgentMemory()}
                        disabled={isClearingAgentMemory}
                        className="inline-flex items-center gap-2 rounded-full border border-amber-200 bg-amber-50 px-4 py-2 text-sm font-medium text-amber-700 disabled:opacity-50"
                      >
                        {isClearingAgentMemory && <LoaderCircle className="h-4 w-4 animate-spin" />}
                        {isClearingAgentMemory ? '清空中...' : '开始新对话'}
                      </button>
                    </div>

                    {agentStatus ? (
                      <div className="mt-4 rounded-2xl border border-slate-200 bg-white px-4 py-3">
                        <p className="text-xs font-medium uppercase tracking-[0.18em] text-slate-400">Status</p>
                        <p className="mt-2 text-sm leading-6 text-slate-600">{agentStatus}</p>
                      </div>
                    ) : null}

                    <div className="mt-4 rounded-2xl border border-slate-200 bg-white px-4 py-3">
                      <div className="flex flex-wrap items-center justify-between gap-3">
                        <div>
                          <p className="text-sm font-medium text-slate-900">执行过程</p>
                          <p className="mt-1 text-xs leading-5 text-slate-500">
                            {agentEvents.length > 0
                              ? `已记录 ${agentEvents.length} 条 Agent 事件，建议单独打开时间线查看。`
                              : '开始规划后，这里会记录工具调用和结果摘要。'}
                          </p>
                        </div>
                        <button
                          type="button"
                          onClick={() => setShowAgentTimelineModal(true)}
                          disabled={agentEvents.length === 0}
                          className="inline-flex items-center gap-2 rounded-full border border-slate-200 bg-slate-50 px-4 py-2 text-sm font-medium text-slate-700 disabled:opacity-50"
                        >
                          查看执行过程
                        </button>
                      </div>
                    </div>
                  </div>

                  <div className="rounded-[28px] border border-amber-200 bg-white p-4">
                    <div className="flex flex-wrap items-start justify-between gap-3 border-b border-amber-100 pb-3">
                      <div>
                        <p className="text-xs font-medium uppercase tracking-[0.2em] text-amber-500">Agent Final Answer</p>
                        <p className="mt-1 text-xs leading-5 text-slate-500">
                          这里固定展示当前规划结果，你可以一边改需求一边对照查看。
                        </p>
                      </div>
                      <div className="flex flex-wrap items-center gap-2">
                        {agentAnswer ? (
                          <button
                            type="button"
                            onClick={() => void handleApplyAgentResult()}
                            disabled={isApplyingAgentResult || !canModifySharedTheme}
                            className="inline-flex items-center gap-2 rounded-full border border-amber-200 bg-amber-50 px-3 py-2 text-xs font-medium text-amber-700 disabled:opacity-50"
                          >
                            {isApplyingAgentResult && <LoaderCircle className="h-3.5 w-3.5 animate-spin" />}
                            {isApplyingAgentResult ? '应用中...' : '应用为当前任务与地图'}
                          </button>
                        ) : null}
                        <span className="rounded-full bg-amber-50 px-3 py-1 text-xs text-amber-700">
                          {agentAnswer ? '已生成路线建议' : '等待生成结果'}
                        </span>
                      </div>
                    </div>

                    {agentAnswer ? (
                      <div className="mt-4 max-h-[60vh] overflow-y-auto pr-1">
                        <div className="agent-markdown">
                          <ReactMarkdown remarkPlugins={[remarkGfm]}>
                            {normalizeAgentMarkdown(agentAnswer)}
                          </ReactMarkdown>
                        </div>
                      </div>
                    ) : (
                      <div className="mt-4 flex min-h-[300px] items-center justify-center rounded-2xl border border-dashed border-slate-200 bg-slate-50 px-6 text-center text-sm leading-6 text-slate-500">
                        还没有生成路线建议。输入你的需求后开始规划，结果会固定显示在这里。
                      </div>
                    )}
                  </div>
                </div>
              </div>
            </div>
          </div>
        ) : null}
        {showAgentTimelineModal ? (
          <div className="fixed inset-0 z-[60] flex items-end bg-slate-900/45 px-3 py-3 sm:items-center sm:justify-center sm:px-4">
            <div className="flex max-h-[82vh] w-full flex-col overflow-hidden rounded-[26px] bg-white shadow-2xl sm:max-w-3xl">
              <div className="flex items-center justify-between gap-3 border-b border-slate-100 px-4 py-4 sm:px-5">
                <div>
                  <div className="text-xs uppercase tracking-[0.18em] text-slate-400">Agent Timeline</div>
                  <h3 className="mt-1 text-lg font-semibold text-slate-900">执行过程详情</h3>
                  <p className="mt-1 text-xs text-slate-500">这里保留工具调用和结果摘要，主页面默认只展示最终答案。</p>
                </div>
                <button
                  type="button"
                  onClick={() => setShowAgentTimelineModal(false)}
                  className="rounded-full border border-slate-200 p-2 text-slate-500 transition hover:bg-slate-50"
                  aria-label="关闭 Agent 执行过程"
                >
                  <X className="h-4 w-4" />
                </button>
              </div>

              <div className="overflow-y-auto px-4 py-4 sm:px-5">
                {agentEvents.length > 0 ? (
                  <div className="space-y-3">
                    {agentEvents.map((event, index) => (
                      <div key={`${event.type}-${event.name}-${index}`} className="rounded-2xl border border-slate-200 bg-slate-50/70 px-4 py-4">
                        <div className="flex items-center justify-between gap-3">
                          <span className="text-xs font-medium text-slate-700">{getAgentEventLabel(event.type)}</span>
                          <span className="text-[11px] text-slate-400">
                            {event.iteration ? `第 ${event.iteration} 轮` : '实时'}
                          </span>
                        </div>
                        <div className="mt-1 text-sm font-medium text-slate-900">{event.name || 'agent'}</div>
                        {summarizeAgentInput(event) ? (
                          <p className="mt-2 whitespace-pre-wrap break-all text-xs leading-5 text-slate-500">
                            输入：{summarizeAgentInput(event)}
                          </p>
                        ) : null}
                        {summarizeAgentOutput(event) ? (
                          <p className="mt-2 whitespace-pre-wrap break-all text-xs leading-5 text-slate-600">
                            输出：{summarizeAgentOutput(event)}
                          </p>
                        ) : null}
                      </div>
                    ))}
                  </div>
                ) : (
                  <div className="rounded-2xl border border-dashed border-slate-200 bg-slate-50 px-4 py-8 text-center text-sm text-slate-500">
                    还没有可展示的执行过程。
                  </div>
                )}
              </div>
            </div>
          </div>
        ) : null}
        {showEmailLogin && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 px-4 py-6">
            <div className="w-full max-w-md rounded-3xl bg-white p-6 shadow-xl">
              <div className="mb-4 flex items-center justify-between">
                <div>
                  <h2 className="text-xl font-semibold text-slate-900">QQ 邮箱登录</h2>
                  <p className="text-sm text-slate-500">
                    {emailLoginMode === 'register' ? '创建账号并绑定邮箱' : emailLoginMode === 'reset' ? '通过验证码重置密码' : '使用邮箱 + 密码登录'}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => {
                    setShowEmailLogin(false);
                    setAuthError('');
                    setAuthInfo('');
                    setEmailCodeInput('');
                  }}
                  className="rounded-full border border-slate-200 px-3 py-1 text-sm text-slate-600"
                >
                  关闭
                </button>
              </div>

              <form onSubmit={handleEmailAuthSubmit} className="space-y-4">
                <div>
                  <label className="mb-2 block text-sm font-medium text-slate-700">QQ 邮箱</label>
                  <input
                    type="email"
                    value={emailInput}
                    onChange={(event) => setEmailInput(event.target.value)}
                    placeholder="name@qq.com"
                    className="w-full rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm outline-none"
                  />
                </div>
                <div>
                  <label className="mb-2 block text-sm font-medium text-slate-700">密码</label>
                  <input
                    type="password"
                    value={passwordInput}
                    onChange={(event) => setPasswordInput(event.target.value)}
                    placeholder="至少 6 位"
                    className="w-full rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm outline-none"
                  />
                </div>
                {emailLoginMode === 'reset' ? (
                  <div className="rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
                    重置密码模式：先获取邮箱验证码，输入新密码后提交即可完成修改。
                  </div>
                ) : null}
                {emailLoginMode !== 'login' ? (
                  <div>
                    <label className="mb-2 block text-sm font-medium text-slate-700">邮箱验证码</label>
                    <div className="flex gap-3">
                      <input
                        type="text"
                        value={emailCodeInput}
                        onChange={(event) => setEmailCodeInput(event.target.value)}
                        placeholder="6 位验证码"
                        className="flex-1 rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm outline-none"
                      />
                      <button
                        type="button"
                        onClick={handleSendEmailCode}
                        disabled={isSendingCode || sendCodeCooldown > 0}
                        className="rounded-2xl border border-slate-200 px-3 text-sm text-slate-600 disabled:opacity-60"
                      >
                        {sendCodeCooldown > 0 ? `${sendCodeCooldown}s` : isSendingCode ? '发送中' : '发送验证码'}
                      </button>
                    </div>
                  </div>
                ) : null}
                {authInfo ? <div className="text-sm text-emerald-600">{authInfo}</div> : null}
                {authError ? <div className="text-sm text-rose-500">{authError}</div> : null}
                <button
                  type="submit"
                  disabled={isAuthLoading}
                  className="flex w-full items-center justify-center rounded-2xl bg-slate-900 py-3 text-sm font-medium text-white disabled:opacity-60"
                >
                  {isAuthLoading ? '处理中...' : emailLoginMode === 'register' ? '创建账号' : emailLoginMode === 'reset' ? '重置密码' : '登录'}
                </button>
              </form>

              {emailLoginMode === 'login' ? (
                <div className="mt-3 flex justify-end">
                  <button
                    type="button"
                    onClick={() => {
                      setAuthError('');
                      setAuthInfo('');
                      setPasswordInput('');
                      setEmailCodeInput('');
                      setEmailLoginMode('reset');
                    }}
                    className="text-sm text-slate-500 transition hover:text-slate-700"
                  >
                    忘记密码？
                  </button>
                </div>
              ) : null}

              <div className="mt-4 flex items-center justify-between text-sm">
                <span className="text-slate-500">
                  {emailLoginMode === 'register' ? '已有账号？' : emailLoginMode === 'reset' ? '想起密码了？' : '没有账号？'}
                </span>
                <button
                  type="button"
                  onClick={() => {
                    setEmailLoginMode(emailLoginMode === 'login' ? 'register' : 'login');
                    setAuthError('');
                    setAuthInfo('');
                    setEmailCodeInput('');
                  }}
                  className="rounded-full border border-slate-200 px-3 py-1 text-slate-600"
                >
                  {emailLoginMode === 'register' ? '去登录' : emailLoginMode === 'reset' ? '返回登录' : '去注册'}
                </button>
              </div>
            </div>
          </div>
        )}

        {activeTab === 'explore' ? (
          <main className="grid gap-6 lg:grid-cols-[1.15fr_0.85fr]">
            <section className="space-y-6">
              <div className="rounded-[32px] border border-slate-200 bg-white p-5 shadow-sm">
                <div className="flex flex-col gap-4">
                  <div className="flex flex-wrap gap-3">
                    <div className="flex rounded-full bg-slate-100 p-1">
                      <button
                        onClick={() => handleSelectWalkMode('pure')}
                        disabled={!!coCreateRoom}
                        className={`rounded-full px-4 py-2 text-sm ${walkMode === 'pure' ? 'bg-white shadow text-slate-900' : 'text-slate-500'} disabled:cursor-not-allowed disabled:opacity-50`}
                      >
                        纯净模式
                      </button>
                      <button
                        onClick={() => handleSelectWalkMode('advanced')}
                        className={`rounded-full px-4 py-2 text-sm ${walkMode === 'advanced' ? 'bg-white shadow text-slate-900' : 'text-slate-500'}`}
                      >
                        进阶模式
                      </button>
                    </div>
                    <button
                      onClick={handleUseCurrentLocation}
                      className="rounded-full border border-slate-200 px-4 py-2 text-sm"
                    >
                      使用当前定位
                    </button>
                  </div>

                  {walkMode === 'advanced' && (
                    <div className="rounded-[28px] border border-slate-200 bg-slate-50 p-4">
                      <div className="flex flex-wrap items-start justify-between gap-3">
                        <div>
                          <div className="inline-flex items-center gap-2 text-sm font-medium text-slate-800">
                            <Users className="h-4 w-4" />
                            共创房间
                          </div>
                          <p className="mt-1 text-sm text-slate-500">
                            进阶模式下可以建房或输入房间号加入，大家一起参与任务并共享轨迹。
                          </p>
                        </div>
                        {coCreateRoom && (
                          <div className="rounded-full bg-white px-3 py-1 text-xs text-slate-600 shadow-sm">
                            成员 {roomMemberCount}/{coCreateRoom.memberLimit}
                          </div>
                        )}
                      </div>

                      {roomRealtimeStatus && (
                        <div className={`mt-4 inline-flex items-center rounded-full px-3 py-1 text-xs font-medium ${roomRealtimeStatus.className}`}>
                          {roomRealtimeStatus.text}
                        </div>
                      )}

                      {!coCreateRoom ? (
                        <div className="mt-4 flex flex-col gap-3 lg:flex-row">
                          <input
                            value={roomCodeInput}
                            onChange={(event) => setRoomCodeInput(event.target.value.toUpperCase())}
                            placeholder="输入房间号，或直接创建新房间"
                            className="flex-1 rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm outline-none"
                          />
                          <div className="flex flex-wrap gap-3">
                            <button
                              onClick={() => void handleCreateCoCreateRoom()}
                              disabled={isRoomSubmitting}
                              className="rounded-full bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-60"
                            >
                              {isRoomSubmitting ? '处理中...' : '创建房间'}
                            </button>
                            <button
                              onClick={() => void handleJoinCoCreateRoom()}
                              disabled={isRoomSubmitting}
                              className="rounded-full border border-slate-200 bg-white px-4 py-2 text-sm disabled:opacity-60"
                            >
                              加入房间
                            </button>
                          </div>
                        </div>
                      ) : (
                        <div className="mt-4 space-y-3">
                          <div className="flex flex-wrap items-center gap-3">
                            <div className="rounded-2xl bg-white px-4 py-3 text-sm shadow-sm">
                              <div className="text-xs uppercase tracking-[0.2em] text-slate-400">Room Code</div>
                              <div className="mt-1 text-xl font-semibold tracking-[0.2em] text-slate-900">{coCreateRoom.roomCode}</div>
                            </div>
                            <button
                              onClick={() => void handleCopyRoomCode()}
                              className="rounded-full border border-slate-200 bg-white px-4 py-2 text-sm"
                            >
                              复制房间号
                            </button>
                            <button
                              onClick={() => void handleLeaveCoCreateRoom()}
                              disabled={isRoomSubmitting}
                              className="rounded-full border border-rose-200 bg-rose-50 px-4 py-2 text-sm text-rose-600 disabled:opacity-60"
                            >
                              退出房间
                            </button>
                          </div>
                          <div className="flex flex-wrap gap-2">
                            {coCreateRoom.members.map((member) => (
                              <div
                                key={member.userId}
                                className="inline-flex items-center gap-2 rounded-full border border-slate-200 bg-white px-3 py-2 text-sm"
                              >
                                <span className="inline-flex h-2.5 w-2.5 rounded-full" style={{ backgroundColor: member.trackColor }} />
                                <span>{member.nickname}</span>
                                {member.isOwner && <span className="text-xs text-amber-600">房主</span>}
                                {member.isTracking && <span className="text-xs text-emerald-600">记录中</span>}
                              </div>
                            ))}
                          </div>
                        </div>
                      )}

                      {roomMessage && <p className="mt-3 text-sm text-emerald-600">{roomMessage}</p>}
                      {roomError && <p className="mt-3 text-sm text-rose-600">{roomError}</p>}
                    </div>
                  )}

                  <div className="relative">
                    <Search className="pointer-events-none absolute left-4 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
                    <input
                      value={searchLocation}
                      onChange={(event) => handleSearchLocation(event.target.value)}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter') {
                          event.preventDefault();
                          void handleSubmitSearch();
                        }
                      }}
                      placeholder="搜索地点，例如：上海武康路"
                      className="w-full rounded-2xl border border-slate-200 bg-slate-50 py-3 pl-11 pr-4 outline-none ring-0"
                    />
                    {searchResults.length > 0 && (
                      <div className="absolute z-10 mt-2 w-full overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-lg">
                        {searchResults.map((location) => (
                          <button
                            key={`${location.lat}-${location.lng}`}
                            onClick={() => handleSelectLocation(location)}
                            className="block w-full border-b border-slate-100 px-4 py-3 text-left text-sm hover:bg-slate-50 last:border-b-0"
                          >
                            {location.name}
                          </button>
                        ))}
                      </div>
                    )}
                  </div>

                  <div className="flex gap-3">
                    <button
                      onClick={() => void handleSubmitSearch()}
                      className="rounded-full border border-slate-200 px-4 py-2 text-sm"
                    >
                      搜索并定位
                    </button>
                  </div>

                  <div className="grid gap-3 md:grid-cols-2">
                    <InfoSelect label="当前心情" value={mood} onChange={setMood} options={['好奇', '平静', '活力', '怀旧']} />
                    <InfoSelect label="天气" value={weather} onChange={setWeather} options={['晴朗', '多云', '雨天', '大风']} />
                    <InfoSelect label="季节" value={season} onChange={setSeason} options={['春季', '夏季', '秋季', '冬季']} />
                    <InfoSelect label="偏好" value={preference} onChange={setPreference} options={['城市生活', '街区观察', '自然角落', '建筑细节']} />
                  </div>
                </div>
              </div>

              <div className="overflow-hidden rounded-[32px] border border-slate-200 bg-white shadow-sm">
                <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
                  <div>
                    <h2 className="text-lg font-semibold">地图与路线</h2>
                    <p className="text-sm text-slate-500">查看当前地点和漫步轨迹</p>
                  </div>
                  <div className="flex flex-wrap gap-2 text-xs text-slate-500">
                    <div className="rounded-full bg-slate-100 px-3 py-1">轨迹点 {path.length}</div>
                    <div className="rounded-full bg-slate-100 px-3 py-1">距离 {pathDistanceKm.toFixed(2)} km</div>
                    <div className="rounded-full bg-slate-100 px-3 py-1">POI {displayNearbyPois.length}</div>
                  </div>
                </div>

                <div className="h-[360px]">
                  <AmapScene
                    center={mapCenter}
                    selectedLocation={selectedLocation}
                    currentPosition={visibleCurrentPosition}
                    currentUserAvatar={user?.avatar}
                    currentUserNickname={user?.nickname}
                    followCurrentPosition={isTracking}
                    pathCoordinates={visiblePathCoordinates}
                    roomMembers={walkMode === 'advanced' ? roomMapMembers : []}
                    nearbyPois={displayNearbyPois}
                    fitPoisToView={agentSuggestedPois.length > 1}
                    selectedPoiKey={selectedPoiKey}
                    onSelectMapPoint={(lat, lng) => void handleSelectMapPoint(lat, lng)}
                    onSelectPoi={(poi) => void handleSelectPoi(poi)}
                  />
                </div>

                <div className="border-t border-slate-100 px-5 py-4">
                  <div className="mb-4 flex flex-wrap gap-3">
                    <button
                      onClick={handleUseCurrentLocation}
                      className="rounded-full border border-slate-200 px-4 py-2 text-sm"
                    >
                      定位到当前位置
                    </button>
                    <button
                      onClick={() => {
                        setPath([]);
                        setIsTracking(false);
                        setLivePosition(null);
                      }}
                      className="rounded-full border border-slate-200 px-4 py-2 text-sm"
                    >
                      清空轨迹
                    </button>
                    <div className="rounded-full bg-amber-50 px-4 py-2 text-sm text-amber-900">
                      点击地图也可以直接选点
                    </div>
                  </div>

                  <h3 className="text-sm font-medium text-slate-700">附近可逛点</h3>
                  {displayNearbyPois.length === 0 ? (
                    <p className="mt-2 text-sm text-slate-500">选择地点后，这里会显示附近推荐点位。</p>
                  ) : (
                    <div className="mt-3 grid gap-3 md:grid-cols-2">
                      {displayNearbyPois.map((poi, index) => (
                        <button
                          key={`${poi.title}-${index}`}
                          onClick={() => void handleSelectPoi(poi)}
                          className={`rounded-2xl border px-4 py-3 text-left text-sm transition ${
                            selectedPoiKey === `${poi.title}-${poi.lat}-${poi.lng}`
                              ? 'border-amber-300 bg-amber-50 shadow-sm'
                              : 'border-slate-200 bg-slate-50 hover:bg-slate-100'
                          }`}
                        >
                          <div className="font-medium text-slate-800">{poi.title}</div>
                          <div className="mt-1 text-xs text-slate-500">
                            {selectedPoiKey === `${poi.title}-${poi.lat}-${poi.lng}`
                              ? '当前已选中，AI 将围绕这里生成环境和主题'
                              : '点击切换到这里并刷新 AI 地点环境'}
                          </div>
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              </div>

              <div className="rounded-[32px] border border-slate-200 bg-white p-6 shadow-sm">
                <div className="mb-4 flex items-center justify-between">
                  <div>
                    <p className="text-sm uppercase tracking-[0.2em] text-slate-400">Current Theme</p>
                    <h2 className="mt-1 text-2xl font-semibold">{currentTheme?.title || '等待生成主题'}</h2>
                  </div>
                  {isGenerating && <LoaderCircle className="h-5 w-5 animate-spin text-amber-500" />}
                </div>

                <div
                  className="rounded-[28px] p-5 text-white"
                  style={{ background: `linear-gradient(135deg, ${currentTheme?.vibeColor || '#334155'}, #0f172a)` }}
                >
                  <p className="text-sm opacity-85">{currentTheme?.category || '探索'}</p>
                  <p className="mt-3 text-lg leading-8">{currentTheme?.description || '点击按钮生成新的漫步主题。'}</p>
                </div>

                <div className="mt-5 rounded-2xl border border-amber-100 bg-amber-50 px-4 py-3 text-sm text-amber-900">
                  <div className="inline-flex items-center gap-2">
                    <MapPin className="h-4 w-4" />
                    当前地点：{currentLocationName}
                  </div>
                  <div className="mt-1 text-slate-600">地点环境：{locationContext}</div>
                </div>

                <div className="mt-5 space-y-3">
                  {(currentTheme?.missions || []).map((mission, index) => (
                    <button
                      key={`${mission}-${index}`}
                      type="button"
                      onClick={() => {
                        setCheckedMissions((prev) =>
                          prev.includes(mission) ? prev.filter((item) => item !== mission) : [...prev, mission],
                        );
                      }}
                      className={`flex w-full items-start gap-3 rounded-2xl border px-4 py-3 text-left text-sm transition ${
                        checkedMissions.includes(mission)
                          ? 'border-emerald-300 bg-emerald-50 shadow-[0_10px_24px_rgba(16,185,129,0.12)]'
                          : 'border-slate-200 bg-slate-50 hover:bg-slate-100'
                      }`}
                    >
                      <span
                        className={`mt-0.5 inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full border ${
                          checkedMissions.includes(mission)
                            ? 'border-emerald-500 bg-emerald-500 text-white'
                            : 'border-slate-300 bg-white text-transparent'
                        }`}
                      >
                        <Check className="h-4 w-4" />
                      </span>
                      <span className={`flex-1 ${checkedMissions.includes(mission) ? 'text-emerald-900' : 'text-slate-700'}`}>
                        <span className={`mr-1 ${checkedMissions.includes(mission) ? 'text-emerald-500' : 'text-slate-400'}`}>{index + 1}.</span>
                        {mission}
                      </span>
                      <span
                        className={`rounded-full px-2 py-1 text-xs ${
                          checkedMissions.includes(mission)
                            ? 'bg-white text-emerald-700 shadow-sm'
                            : 'bg-white text-slate-400'
                        }`}
                      >
                        {checkedMissions.includes(mission) ? '已打卡' : '待打卡'}
                      </span>
                    </button>
                  ))}
                </div>

                <div className="mt-6">
                  <p className="mb-2 text-sm font-medium text-slate-600">组合主题方向</p>
                  <div className="flex flex-wrap gap-2">
                    {COMBINE_CATEGORIES.map((category) => {
                      const selected = selectedThemesForCombine.includes(category);
                      return (
                        <button
                          key={category}
                          disabled={!canModifySharedTheme}
                          onClick={() => {
                            setSelectedThemesForCombine((prev) => {
                              if (prev.includes(category)) {
                                return prev.filter((item) => item !== category);
                              }
                              if (prev.length >= 2) {
                                return prev;
                              }
                              return [...prev, category];
                            });
                          }}
                          className={`rounded-full px-4 py-2 text-sm disabled:cursor-not-allowed disabled:opacity-50 ${selected ? 'bg-slate-900 text-white' : 'border border-slate-200 bg-white'}`}
                        >
                          {category}
                        </button>
                      );
                    })}
                  </div>
                </div>

                {!canModifySharedTheme ? (
                  <p className="mt-3 text-xs text-amber-700">当前在共创房间内只有房主可以修改主题，房员仍然可以继续打卡和记录轨迹。</p>
                ) : null}

                <div className="mt-6 flex flex-wrap gap-3">
                  <button
                    onClick={handleCombineThemes}
                    disabled={!canModifySharedTheme}
                    className="inline-flex items-center gap-2 rounded-full bg-emerald-500 px-4 py-2 text-sm font-medium text-white disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    组合生成主题
                  </button>
                  <button
                    onClick={handleGenerateAiTheme}
                    disabled={!canModifySharedTheme}
                    className="inline-flex items-center gap-2 rounded-full bg-amber-500 px-4 py-2 text-sm font-medium text-white disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    <Sparkles className="h-4 w-4" />
                    AI 生成
                  </button>
                  <button
                    onClick={handleGenerateRandomTheme}
                    disabled={!canModifySharedTheme}
                    className="inline-flex items-center gap-2 rounded-full bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    <Shuffle className="h-4 w-4" />
                    随机生成
                  </button>
                  <button
                    onClick={() => {
                      if (isTracking) {
                        setIsTracking(false);
                        setLivePosition(null);
                        return;
                      }
                      setPath([]);
                      setLivePosition(null);
                      setIsTracking(true);
                    }}
                    className={`rounded-full px-4 py-2 text-sm font-medium transition ${
                      isTracking
                        ? 'bg-rose-500 text-white'
                        : 'border border-slate-200 bg-sky-50 text-sky-700'
                    }`}
                  >
                    {isTracking ? '停止轨迹记录' : '开始轨迹记录'}
                  </button>
                </div>

                <div className="mt-6 rounded-[28px] border border-slate-200 bg-slate-50/80 p-4">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div>
                      <p className="text-sm font-semibold text-slate-900">Agent 路线规划</p>
                      <p className="mt-1 text-xs leading-6 text-slate-500">
                        用自然语言描述你想怎么逛，Agent 会在独立窗口里实时调用地图、社区和 Walk 工具来规划。
                      </p>
                    </div>
                    <button
                      type="button"
                      onClick={() => setShowAgentPlannerModal(true)}
                      className="inline-flex items-center gap-2 rounded-full bg-slate-900 px-4 py-2 text-sm font-medium text-white"
                    >
                      <Sparkles className="h-4 w-4" />
                      打开 Agent 窗口
                    </button>
                  </div>
                  <div className="mt-4 rounded-2xl border border-slate-200 bg-white px-4 py-4">
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <div>
                        <p className="text-sm font-medium text-slate-900">{isAgentStreaming ? 'Agent 正在规划中' : '适合放到单独窗口里专注查看'}</p>
                        <p className="mt-1 text-xs leading-5 text-slate-500">
                          {agentAnswer
                            ? '上次规划结果还保留着，打开窗口可以继续追问、查看过程和最终路线。'
                            : '打开后可以输入需求、看实时步骤、查看最终路线建议，不会再把当前页面撑长。'}
                        </p>
                      </div>
                      <span className="rounded-full bg-slate-50 px-3 py-1 text-xs text-slate-500">
                        {isAgentStreaming ? '流式规划中' : agentAnswer ? '已有规划结果' : '支持实时步骤输出'}
                      </span>
                    </div>
                  </div>
                </div>
              </div>
            </section>

            <aside className="space-y-6">
              <div className="rounded-[32px] border border-slate-200 bg-white p-5 shadow-sm">
                <div className="mb-4 flex items-center justify-between">
                  <h3 className="text-lg font-semibold">保存本次漫步</h3>
                  <History className="h-5 w-5 text-slate-400" />
                </div>

                <textarea
                  ref={noteTextareaRef}
                  value={noteText}
                  onChange={(event) => setNoteText(event.target.value)}
                  placeholder="写一点这次漫步的感受"
                  className="min-h-32 w-full rounded-2xl border border-slate-200 bg-slate-50 p-4 outline-none"
                />

                <div className="mt-4 rounded-2xl border border-dashed border-slate-200 bg-slate-50 px-4 py-4 text-sm">
                  <div className="flex items-center justify-between">
                    <span className="font-medium text-slate-700">上传本次漫步照片</span>
                    <label className="cursor-pointer rounded-full border border-slate-200 bg-white px-3 py-1 text-xs text-slate-600">
                      选择照片
                      <input
                        type="file"
                        accept="image/*"
                        className="hidden"
                        onChange={(event) => {
                          const file = event.target.files?.[0];
                          if (!file) {
                            return;
                          }
                          const reader = new FileReader();
                          reader.onload = () => {
                            if (typeof reader.result === 'string') {
                              setWalkUploadPreview(reader.result);
                              setWalkUploadName(file.name);
                            }
                          };
                          reader.readAsDataURL(file);
                        }}
                      />
                    </label>
                  </div>
                  {walkUploadPreview ? (
                    <div className="mt-3 overflow-hidden rounded-2xl border border-slate-200 bg-white">
                      <img src={walkUploadPreview} alt="漫步照片预览" className="h-40 w-full object-cover" />
                    </div>
                  ) : (
                    <p className="mt-2 text-xs text-slate-500">可选，保存时会自动上传到 OSS。</p>
                  )}
                </div>

                <label className="mt-4 flex items-center gap-2 text-sm text-slate-600">
                  <input type="checkbox" checked={isPublic} onChange={(event) => setIsPublic(event.target.checked)} />
                  同时发布到社区
                </label>

                {isPublic ? (
                  <div className="mt-4 rounded-2xl border border-slate-200 bg-slate-50 px-4 py-4 text-sm text-slate-600">
                    <div className="flex items-start justify-between gap-4">
                      <div>
                        <div className="font-medium text-slate-800">发布者设定</div>
                        <p className="mt-1 text-xs leading-6 text-slate-500">
                          社区模式会使用当前已保存的真实作者资料发布，包括昵称和头像。
                        </p>
                      </div>
                      <span className="rounded-full bg-white px-3 py-1 text-xs text-slate-500">真实作者</span>
                    </div>

                    <div className="mt-4 flex items-center gap-3 rounded-2xl border border-white/80 bg-white px-3 py-3">
                      {communityPublisherAvatar ? (
                        <img
                          src={communityPublisherAvatar}
                          alt={resolvedCommunityPublisherName}
                          className="h-12 w-12 rounded-full object-cover"
                        />
                      ) : (
                        <div className="flex h-12 w-12 items-center justify-center rounded-full bg-slate-100 text-slate-500">
                          <UserRound className="h-5 w-5" />
                        </div>
                      )}
                      <div>
                        <div className="font-medium text-slate-900">{resolvedCommunityPublisherName}</div>
                        <div className="mt-1 text-xs text-slate-500">头像将显示为真实作者头像</div>
                      </div>
                    </div>
                  </div>
                ) : null}

                <div className="mt-3 text-sm text-slate-500">当前轨迹点数量：{path.length}</div>

                <button
                  onClick={handleSaveWalk}
                  disabled={isSaving || isWalkUploading}
                  className="mt-4 inline-flex w-full items-center justify-center gap-2 rounded-2xl bg-slate-900 px-4 py-3 text-sm font-medium text-white disabled:opacity-60"
                >
                  {(isSaving || isWalkUploading) && <LoaderCircle className="h-4 w-4 animate-spin" />}
                  {isWalkUploading ? '上传中...' : '保存漫步记录'}
                </button>
              </div>

              <div className="rounded-[32px] border border-slate-200 bg-white p-5 shadow-sm">
                <h3 className="text-lg font-semibold">最近生成历史</h3>
                <div className="mt-4 space-y-3">
                  {history.length === 0 ? (
                    <p className="text-sm text-slate-500">还没有生成历史。</p>
                  ) : (
                    history.map((theme, index) => (
                      <button
                        key={`${theme.title}-${index}`}
                        onClick={() => setCurrentTheme(theme)}
                        className="block w-full rounded-2xl border border-slate-200 px-4 py-3 text-left hover:bg-slate-50"
                      >
                        <div className="text-sm font-medium">{theme.title}</div>
                        <div className="mt-1 text-xs text-slate-500">{theme.category}</div>
                      </button>
                    ))
                  )}
                </div>
              </div>

            </aside>
          </main>
        ) : activeTab === 'community' ? (
          communityViewMode === 'post' && selectedCommunityWalk ? (
            <main className="space-y-3">
              <section className="rounded-[24px] border border-slate-200 bg-white p-4 shadow-sm sm:rounded-[32px] sm:p-8">
                <button
                  onClick={() => {
                    setCommunityViewMode('feed');
                    setSelectedCommunityWalk(null);
                    setCommunityComments([]);
                    setCommunityReplyTarget(null);
                    setCommunityCommentInput('');
                    setCommunityCommentError('');
                  }}
                  className="mb-5 rounded-full border border-slate-200 px-3 py-2 text-xs text-slate-600 transition hover:bg-slate-50 sm:mb-6 sm:px-4 sm:text-sm"
                >
                  返回社区
                </button>

                <article className="mx-auto flex max-w-3xl flex-col gap-6">
                  <div className="flex items-center gap-3">
                    {selectedCommunityWalk.authorAvatar ? (
                      <img
                        src={selectedCommunityWalk.authorAvatar}
                        alt={selectedCommunityWalk.authorNickname || '社区漫步者'}
                        className="h-10 w-10 rounded-full object-cover sm:h-12 sm:w-12"
                      />
                    ) : (
                      <div className="flex h-10 w-10 items-center justify-center rounded-full bg-slate-100 text-sm font-medium text-slate-600 sm:h-12 sm:w-12">
                        {(selectedCommunityWalk.authorNickname || '社区').slice(0, 1)}
                      </div>
                    )}
                    <div>
                      <div className="font-medium text-slate-900">{selectedCommunityWalk.authorNickname || '社区漫步者'}</div>
                      <div className="text-sm text-slate-500">{formatProfilePostDate(selectedCommunityWalk.createdAt)}</div>
                    </div>
                  </div>

                  <div className="overflow-hidden rounded-[24px] border border-slate-200 bg-slate-50 sm:rounded-[32px]">
                    {selectedCommunityWalk.photoUrl ? (
                      <img
                        src={selectedCommunityWalk.photoUrl}
                        alt={selectedCommunityWalk.themeTitle}
                        className="h-[260px] w-full object-cover sm:h-[460px]"
                      />
                    ) : (
                      <div
                        className={`${getProfilePostGradient(selectedCommunityWalk)} flex h-[220px] flex-col justify-end px-5 py-5 text-white sm:h-[320px] sm:px-8 sm:py-8`}
                      >
                        <div className="text-xs uppercase tracking-[0.24em] text-white/70">
                          {selectedCommunityWalk.themeCategory || '城市漫步'}
                        </div>
                        <h3 className="mt-3 text-2xl font-semibold sm:text-4xl">{selectedCommunityWalk.themeTitle}</h3>
                        <p className="mt-4 max-w-2xl text-sm leading-7 text-white/90">
                          {buildProfilePostSummary(selectedCommunityWalk)}
                        </p>
                      </div>
                    )}
                  </div>

                  <div>
                    <div className="text-xs uppercase tracking-[0.22em] text-slate-400">
                      {selectedCommunityWalk.themeCategory || '城市漫步'}
                    </div>
                    <h3 className="mt-2 text-2xl font-semibold text-slate-900 sm:text-3xl">{selectedCommunityWalk.themeTitle}</h3>
                    <div className="mt-4 flex flex-wrap gap-2 text-xs text-slate-500 sm:text-sm">
                      <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2.5 py-1 sm:px-3 sm:py-1.5">
                        <MapPin className="h-4 w-4" />
                        {selectedCommunityWalk.locationName || '未填写地点'}
                      </span>
                      <span className="rounded-full bg-slate-100 px-3 py-1.5">社区公开记录</span>
                      <span className="rounded-full bg-slate-100 px-3 py-1.5">
                        轨迹点 {selectedCommunityWalk.path?.length || 0}
                      </span>
                      <span className="rounded-full bg-slate-100 px-3 py-1.5">
                        距离 {(calculatePathDistance(selectedCommunityWalk.path || []) / 1000).toFixed(2)} km
                      </span>
                      <span className="rounded-full bg-slate-100 px-3 py-1.5">
                        Likes {selectedCommunityWalk.likeCount || 0} · Saves {selectedCommunityWalk.favoriteCount || 0} ·
                        {' '}Views {selectedCommunityWalk.viewCount || 0}
                      </span>
                      {selectedCommunityWalk.tags?.map((tag) => (
                        <span key={tag} className="rounded-full bg-amber-50 px-3 py-1.5 text-amber-700">
                          #{tag}
                        </span>
                      ))}
                    </div>
                    <div className="mt-4 flex flex-wrap gap-3">
                      <button
                        type="button"
                        onClick={(event) => void handleToggleCommunityLike(selectedCommunityWalk, event)}
                        className={`inline-flex items-center gap-2 rounded-full px-3 py-2 text-xs transition sm:px-4 sm:py-2.5 sm:text-sm ${
                          selectedCommunityWalk.liked
                            ? 'bg-rose-50 text-rose-600'
                            : 'border border-slate-200 text-slate-600 hover:bg-slate-50'
                        }`}
                      >
                        <Heart className={`h-4 w-4 ${selectedCommunityWalk.liked ? 'fill-current' : ''}`} />
                        <span>{selectedCommunityWalk.liked ? '已点赞' : '点赞'}</span>
                      </button>
                      <button
                        type="button"
                        onClick={(event) => void handleToggleCommunityFavorite(selectedCommunityWalk, event)}
                        className={`inline-flex items-center gap-2 rounded-full px-3 py-2 text-xs transition sm:px-4 sm:py-2.5 sm:text-sm ${
                          selectedCommunityWalk.favorited
                            ? 'bg-amber-50 text-amber-700'
                            : 'border border-slate-200 text-slate-600 hover:bg-slate-50'
                        }`}
                      >
                        <Bookmark className={`h-4 w-4 ${selectedCommunityWalk.favorited ? 'fill-current' : ''}`} />
                        <span>{selectedCommunityWalk.favorited ? '已收藏' : '收藏'}</span>
                      </button>
                      {selectedCommunityWalk.authorId === user?.id ? (
                        <>
                          <button
                            type="button"
                            onClick={() => openWalkEditor(selectedCommunityWalk)}
                            className="inline-flex items-center gap-2 rounded-full border border-slate-200 bg-white px-3 py-2 text-xs text-slate-600 transition hover:bg-slate-50 sm:px-4 sm:py-2.5 sm:text-sm"
                          >
                            <Pencil className="h-4 w-4" />
                            编辑帖子
                          </button>
                          <button
                            type="button"
                            onClick={() => void handleDeleteWalk(selectedCommunityWalk.id, { source: 'community' })}
                            className="inline-flex items-center gap-2 rounded-full border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-600 transition hover:bg-rose-100 sm:px-4 sm:py-2.5 sm:text-sm"
                          >
                            删除帖子
                          </button>
                        </>
                      ) : null}
                    </div>
                  </div>

                  <WalkCommentSection
                    comments={communityComments}
                    isLoading={isLoadingCommunityComments}
                    inputValue={communityCommentInput}
                    replyTarget={communityReplyTarget}
                    isSubmitting={isSubmittingCommunityComment}
                    error={communityCommentError}
                    walkAuthorId={selectedCommunityWalk.authorId}
                    walkAuthorAvatar={selectedCommunityWalk.authorAvatar}
                    currentUserId={user?.id}
                    currentUserAvatar={user?.avatar}
                    formatDate={formatProfilePostDate}
                    onInputChange={(value) => {
                      setCommunityCommentInput(value);
                      if (communityCommentError) {
                        setCommunityCommentError('');
                      }
                    }}
                    onReplyChange={setCommunityReplyTarget}
                    onSubmit={() => void handleSubmitCommunityComment()}
                    onDelete={(commentId) => void handleDeleteCommunityComment(commentId)}
                  />

                  {selectedCommunityWalk.noteText ? (
                    <div className="rounded-[28px] border border-slate-200 bg-slate-50 px-5 py-5">
                      <div className="text-xs uppercase tracking-[0.18em] text-slate-400">漫步记录</div>
                      <p className="mt-4 text-base leading-8 text-slate-700">{selectedCommunityWalk.noteText}</p>
                    </div>
                  ) : null}

                  <div className="rounded-[28px] border border-slate-200 bg-slate-50 p-4">
                    <div className="text-xs uppercase tracking-[0.18em] text-slate-400">轨迹地图</div>
                    <div className="mt-3">
                      <WalkDetailMap
                        path={selectedCommunityWalk.path || []}
                        locationLabel={selectedCommunityWalk.locationName || selectedCommunityWalk.themeTitle}
                        roomMembers={toRoomMapMembers(selectedCommunityWalk.roomMembers)}
                      />
                    </div>
                  </div>

                  <div className="grid gap-4 lg:grid-cols-[1.1fr_0.9fr]">
                    <div className="rounded-[28px] border border-slate-200 bg-slate-50 px-5 py-5">
                      <div className="text-xs uppercase tracking-[0.18em] text-slate-400">完成任务</div>
                      <div className="mt-4 space-y-3">
                        {normalizeCompletedMissionLabels(selectedCommunityWalk.completedMissions).length > 0 ? (
                          normalizeCompletedMissionLabels(selectedCommunityWalk.completedMissions).map((mission, index) => (
                            <div
                              key={`${mission}-${index}`}
                              className="rounded-2xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-800"
                            >
                              {mission}
                            </div>
                          ))
                        ) : (
                          <p className="text-sm text-slate-500">这条公开记录里还没有单独保存任务完成项。</p>
                        )}
                      </div>
                    </div>

                    <div className="rounded-[28px] border border-slate-200 bg-slate-50 px-5 py-5">
                      <div className="text-xs uppercase tracking-[0.18em] text-slate-400">路线概览</div>
                      <div className="mt-4 space-y-3 text-sm leading-7 text-slate-700">
                        <div>记录单元：{selectedCommunityWalk.recordUnit || 'event'}</div>
                        <div>轨迹点数量：{selectedCommunityWalk.path?.length || 0}</div>
                        <div>累计距离：{(calculatePathDistance(selectedCommunityWalk.path || []) / 1000).toFixed(2)} km</div>
                      </div>
                    </div>
                  </div>
                </article>
              </section>
            </main>
          ) : (
            <main className="space-y-3">
              <section className="rounded-[22px] border border-slate-200 bg-white px-4 py-4 shadow-sm sm:rounded-[32px] sm:px-8 sm:py-7">
                <div className="flex items-end justify-between gap-4">
                  <div>
                    <p className="hidden text-xs uppercase tracking-[0.24em] text-slate-400 sm:block">Community Feed</p>
                    <h2 className="text-xl font-semibold text-slate-900">社区漫步</h2>
                    <p className="mt-2 hidden max-w-2xl text-sm leading-7 text-slate-500 sm:block">
                      看看大家公开发布的漫步记录，像刷帖子一样翻一翻城市里的灵感和轨迹。
                    </p>
                  </div>
                  {isLoadingCommunity && <LoaderCircle className="h-5 w-5 animate-spin text-amber-500" />}
                </div>
                <div className="mt-3 flex flex-col gap-3">
                  <form onSubmit={handleCommunitySearchSubmit} className="flex w-full items-center gap-2">
                    <label className="flex min-w-0 flex-1 items-center gap-2 rounded-full border border-slate-200 bg-slate-50 px-3 py-2.5">
                      <Search className="h-4 w-4 text-slate-400" />
                      <input
                        value={communitySearchInput}
                        onChange={(event) => setCommunitySearchInput(event.target.value)}
                        placeholder="搜索主题、地点、作者或标签"
                        className="min-w-0 flex-1 bg-transparent text-xs text-slate-700 outline-none"
                      />
                    </label>
                    <button
                      type="submit"
                      className="rounded-full bg-slate-900 px-3 py-2.5 text-xs font-medium text-white transition hover:bg-slate-800"
                    >
                      搜索
                    </button>
                    {communitySearchKeyword ? (
                      <button
                        type="button"
                        onClick={() => void handleCommunitySearchReset()}
                        className="rounded-full border border-slate-200 px-3 py-2.5 text-xs text-slate-600 transition hover:bg-slate-50"
                      >
                        清空
                      </button>
                    ) : null}
                  </form>

                  <div className="relative self-start">
                    <button
                      type="button"
                      onClick={() => setShowCommunityFilterMenu((value) => !value)}
                      className="inline-flex items-center gap-2 rounded-full border border-slate-200 bg-white px-3 py-2.5 text-xs text-slate-600 transition hover:bg-slate-50"
                    >
                      <SlidersHorizontal className="h-4 w-4" />
                      筛选
                      <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-500">
                        {getCommunityFeedTabLabel(communityFeedTab)}
                      </span>
                    </button>

                    {showCommunityFilterMenu ? (
                      <div className="absolute right-0 top-[calc(100%+10px)] z-20 w-44 overflow-hidden rounded-2xl border border-slate-200 bg-white p-2 shadow-xl">
                        {(['recommend', 'latest', 'hot'] as CommunityFeedTab[]).map((tab) => (
                          <button
                            key={tab}
                            type="button"
                            onClick={() => {
                              setCommunityFeedTab(tab);
                              setCommunityViewMode('feed');
                              setSelectedCommunityWalk(null);
                              setShowCommunityFilterMenu(false);
                            }}
                            className={`flex w-full items-center justify-between rounded-xl px-3 py-2 text-sm transition ${
                              communityFeedTab === tab && !communitySearchKeyword
                                ? 'bg-slate-900 text-white'
                                : 'text-slate-600 hover:bg-slate-50'
                            }`}
                          >
                            <span>{getCommunityFeedTabLabel(tab)}</span>
                            {communityFeedTab === tab && !communitySearchKeyword ? <Check className="h-4 w-4" /> : null}
                          </button>
                        ))}
                      </div>
                    ) : null}
                  </div>

                  <div className="hidden flex-wrap gap-2">
                    {([
                      ['recommend', '推荐'],
                      ['latest', '最新'],
                      ['hot', '热门'],
                    ] as Array<[CommunityFeedTab, string]>).map(([tab, label]) => (
                      <button
                        key={tab}
                        type="button"
                        onClick={() => {
                          setCommunityFeedTab(tab);
                          setCommunityViewMode('feed');
                          setSelectedCommunityWalk(null);
                        }}
                        className={`rounded-full px-4 py-2 text-sm transition ${
                          communityFeedTab === tab && !communitySearchKeyword
                            ? 'bg-slate-900 text-white'
                            : 'border border-slate-200 text-slate-600 hover:bg-slate-50'
                        }`}
                      >
                        {label}
                      </button>
                    ))}
                  </div>
                </div>
                <div className="mt-3 flex flex-wrap gap-2 text-xs text-slate-500 sm:mt-4">
                  {communitySearchKeyword ? (
                    <span className="rounded-full bg-slate-100 px-3 py-1.5">搜索 “{communitySearchKeyword}”</span>
                  ) : (
                    <span className="rounded-full bg-slate-100 px-3 py-1.5">
                      当前筛选：{getCommunityFeedTabLabel(communityFeedTab)}
                    </span>
                  )}
                  <span className="rounded-full bg-slate-100 px-3 py-1.5">共 {displayedCommunityWalks.length} 条内容</span>
                </div>
                <div className="hidden mt-4 flex flex-wrap gap-2 text-xs text-slate-500">
                  {communitySearchKeyword ? (
                    <span className="rounded-full bg-slate-100 px-3 py-1.5">
                      搜索 “{communitySearchKeyword}”
                    </span>
                  ) : (
                    <span className="rounded-full bg-slate-100 px-3 py-1.5">
                      当前频道：
                      {communityFeedTab === 'recommend'
                        ? '推荐'
                        : communityFeedTab === 'latest'
                        ? '最新'
                        : '热门'}
                    </span>
                  )}
                  <span className="rounded-full bg-slate-100 px-3 py-1.5">共 {displayedCommunityWalks.length} 条内容</span>
                </div>
              </section>

              <section className="bg-transparent">
                {communityError ? (
                  <div className="rounded-2xl border border-rose-200 bg-rose-50 p-6 text-sm text-rose-600">{communityError}</div>
                ) : displayedCommunityWalks.length === 0 ? (
                  <div className="rounded-2xl border border-dashed border-slate-300 p-6 text-sm text-slate-500">
                    暂时还没有社区内容，等你来发布第一条记录。
                  </div>
                ) : (
                  <div className="grid grid-cols-2 gap-2">
                    {displayedCommunityWalks.map((walk) => (
                      <article
                        key={walk.id}
                        className="overflow-hidden rounded-[16px] border border-slate-200 bg-white shadow-sm transition hover:-translate-y-0.5 hover:shadow-md"
                      >
                        <button
                          onClick={() => void handleOpenCommunityWalk(walk.id)}
                          className="block w-full text-left"
                        >
                          {walk.photoUrl ? (
                            <img
                              src={walk.photoUrl}
                              alt={walk.themeTitle}
                              className={`${getProfilePostCoverHeightClass(walk)} w-full rounded-t-[16px] object-cover`}
                            />
                          ) : (
                            <div
                              className={`${getProfilePostGradient(walk)} ${getProfilePostCoverHeightClass(walk)} flex items-end rounded-t-[16px] px-3 py-3 text-white`}
                            >
                              <div>
                                <div className="text-xs uppercase tracking-[0.22em] text-white/70">
                                  {walk.themeCategory || '城市漫步'}
                                </div>
                                <h3 className="mt-2 text-sm font-semibold">{walk.themeTitle}</h3>
                              </div>
                            </div>
                          )}

                          <div className="space-y-2 px-2.5 py-2.5">
                            <div>
                              <div className="line-clamp-2 text-sm font-semibold leading-5 text-slate-900">{walk.themeTitle}</div>
                              <p className="mt-1 truncate text-xs text-slate-500">{buildProfilePostSummary(walk)}</p>
                            </div>

                            <div className="hidden flex-wrap gap-2 text-xs text-slate-500">
                              <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2.5 py-1">
                                <MapPin className="h-3.5 w-3.5" />
                                {walk.locationName || '未填写地点'}
                              </span>
                              <span className="rounded-full bg-slate-100 px-2.5 py-1">轨迹点 {walk.path?.length || 0}</span>
                              {walk.photoUrl ? <span className="rounded-full bg-slate-100 px-2.5 py-1">有照片</span> : null}
                              {walk.tags?.slice(0, 2).map((tag) => (
                                <span key={tag} className="rounded-full bg-amber-50 px-2.5 py-1 text-amber-700">
                                  #{tag}
                                </span>
                              ))}
                            </div>

                            <div className="flex items-center justify-between pt-1">
                              <div className="flex items-center gap-2">
                                {walk.authorAvatar ? (
                                  <img
                                    src={walk.authorAvatar}
                                    alt={walk.authorNickname || '社区漫步者'}
                                    className="h-5 w-5 rounded-full object-cover"
                                  />
                                ) : (
                                  <div className="flex h-5 w-5 items-center justify-center rounded-full bg-slate-100 text-[10px] font-medium text-slate-600">
                                    {(walk.authorNickname || '社区').slice(0, 1)}
                                  </div>
                                )}
                                <div className="max-w-[4.5rem] truncate text-xs text-slate-600">{walk.authorNickname || '社区漫步者'}</div>
                              </div>
                              <div className="text-right">
                                <div className="hidden text-xs text-slate-400">{formatProfilePostDate(walk.createdAt)}</div>
                                <div className="text-[11px] leading-none text-slate-400">
                                  赞 {walk.likeCount || 0} · 藏 {walk.favoriteCount || 0} · 看 {walk.viewCount || 0}
                                </div>
                              </div>
                            </div>

                            <div className="hidden items-center gap-2 border-t border-slate-100 pt-3">
                              <button
                                type="button"
                                onClick={(event) => void handleToggleCommunityLike(walk, event)}
                                className={`inline-flex items-center gap-2 rounded-full px-3 py-2 text-xs transition ${
                                  walk.liked ? 'bg-rose-50 text-rose-600' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                                }`}
                              >
                                <Heart className={`h-3.5 w-3.5 ${walk.liked ? 'fill-current' : ''}`} />
                                <span>{walk.liked ? '已点赞' : '点赞'}</span>
                              </button>
                              <button
                                type="button"
                                onClick={(event) => void handleToggleCommunityFavorite(walk, event)}
                                className={`inline-flex items-center gap-2 rounded-full px-3 py-2 text-xs transition ${
                                  walk.favorited ? 'bg-amber-50 text-amber-700' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                                }`}
                              >
                                <Bookmark className={`h-3.5 w-3.5 ${walk.favorited ? 'fill-current' : ''}`} />
                                <span>{walk.favorited ? '已收藏' : '收藏'}</span>
                              </button>
                            </div>
                          </div>
                        </button>
                      </article>
                    ))}
                  </div>
                )}
              </section>
            </main>
          )
        ) : (
          <>
            {profileViewMode === 'post' && selectedProfileWalk ? (
              <main className="space-y-3">
                <section className="hidden rounded-[32px] border border-slate-200 bg-white p-6 shadow-sm sm:p-8">
                  <div className="mb-6 flex items-start justify-between gap-4">
                    <div>
                      <h3 className="text-2xl font-semibold text-slate-900">{profileCollectionMeta.title}</h3>
                      <p className="mt-2 text-sm leading-7 text-slate-500">{profileCollectionMeta.description}</p>
                    </div>
                    {isLoadingProfile && <LoaderCircle className="mt-1 h-5 w-5 animate-spin text-amber-500" />}
                  </div>

                  <ProfileCollectionTabs activeTab={profileCollectionTab} onChange={setProfileCollectionTab} />

                  {profileWalkSource.length === 0 ? (
                    <div className="rounded-[28px] border border-dashed border-slate-300 bg-slate-50 px-6 py-10 text-sm text-slate-500">
                      {profileCollectionMeta.empty}
                    </div>
                  ) : (
                    <ProfileWalkCardList
                      walks={profileWalkSource}
                      onOpenWalk={(walkId) => void handleOpenProfileWalk(walkId)}
                      getCoverHeightClass={getProfilePostCoverHeightClass}
                      getGradientClass={getProfilePostGradient}
                      buildSummary={buildProfilePostSummary}
                      formatDate={formatProfilePostDate}
                      fallbackAvatarUrl={profileAvatarPreview || user?.avatar || undefined}
                      fallbackNickname={user?.nickname || '我的记录'}
                    />
                  )}
                </section>

                <section className="rounded-[24px] border border-slate-200 bg-white p-3 shadow-sm">
                  <div className="mb-4 flex flex-wrap gap-2">
                    <button
                      onClick={() => {
                        setProfileViewMode('feed');
                        setSelectedProfileWalk(null);
                        setCommunityComments([]);
                        setCommunityReplyTarget(null);
                        setCommunityCommentInput('');
                        setCommunityCommentError('');
                      }}
                      className="rounded-full border border-slate-200 px-3 py-1.5 text-xs text-slate-600 transition hover:bg-slate-50"
                    >
                      返回个人主页
                    </button>

                    {selectedProfileWalk.authorId === user?.id ? (
                      <>
                      <button
                        type="button"
                        onClick={() => openWalkEditor(selectedProfileWalk)}
                        className="inline-flex items-center gap-1.5 rounded-full border border-slate-200 bg-white px-3 py-1.5 text-xs text-slate-600 transition hover:bg-slate-50"
                      >
                        <Pencil className="h-3.5 w-3.5" />
                        编辑帖子
                      </button>
                      <button
                        type="button"
                        onClick={() => void handleDeleteWalk(selectedProfileWalk.id, { source: 'profile' })}
                        className="rounded-full border border-rose-200 bg-rose-50 px-3 py-1.5 text-xs text-rose-600 transition hover:bg-rose-100"
                      >
                        删除帖子
                      </button>
                      </>
                    ) : null}
                  </div>

                  <article className="mx-auto max-w-3xl space-y-4">
                    {selectedProfileCommunityWalk ? (
                      <ProfileCommunityEngagementCard
                        walk={selectedProfileCommunityWalk}
                        onToggleLike={(event) => void handleToggleCommunityLike(selectedProfileCommunityWalk, event)}
                        onToggleFavorite={(event) => void handleToggleCommunityFavorite(selectedProfileCommunityWalk, event)}
                      />
                    ) : null}
                    <ProfileWalkDetailBody
                      walk={selectedProfileWalk}
                      authorAvatarUrl={
                        selectedProfileWalk.authorAvatar || profileAvatarPreview || user?.avatar || 'https://placehold.co/80x80?text=U'
                      }
                      authorName={user?.nickname || '我的漫步记录'}
                      formattedDate={formatProfilePostDate(selectedProfileWalk.createdAt)}
                      coverGradientClassName={getProfilePostGradient(selectedProfileWalk)}
                      summary={buildProfilePostSummary(selectedProfileWalk)}
                      distanceKm={(calculatePathDistance(selectedProfileWalk.path || []) / 1000).toFixed(2)}
                      completedMissionLabels={normalizeCompletedMissionLabels(selectedProfileWalk.completedMissions)}
                      mapContent={
                        <WalkDetailMap
                          path={selectedProfileWalk.path || []}
                          locationLabel={selectedProfileWalk.locationName || selectedProfileWalk.themeTitle}
                          roomMembers={toRoomMapMembers(selectedProfileWalk.roomMembers)}
                        />
                      }
                    />
                    <WalkCommentSection
                      comments={communityComments}
                      isLoading={isLoadingCommunityComments}
                      inputValue={communityCommentInput}
                      replyTarget={communityReplyTarget}
                      isSubmitting={isSubmittingCommunityComment}
                      error={communityCommentError}
                      walkAuthorId={selectedProfileWalk.authorId}
                      walkAuthorAvatar={selectedProfileWalk.authorAvatar || profileAvatarPreview || user?.avatar || undefined}
                      currentUserId={user?.id}
                      currentUserAvatar={user?.avatar}
                      formatDate={formatProfilePostDate}
                      onInputChange={(value) => {
                        setCommunityCommentInput(value);
                        if (communityCommentError) {
                          setCommunityCommentError('');
                        }
                      }}
                      onReplyChange={setCommunityReplyTarget}
                      onSubmit={() => void handleSubmitCommunityComment()}
                      onDelete={(commentId) => void handleDeleteCommunityComment(commentId)}
                    />
                  </article>
                </section>
              </main>
            ) : (
              <main className="space-y-3">
                <section className="rounded-[24px] border border-slate-200 bg-white px-4 py-4 shadow-sm">
                  <div className="flex flex-col gap-3">
                    <div className="flex items-center gap-3">
                      <img
                        src={profileAvatarPreview || user?.avatar || 'https://placehold.co/120x120?text=U'}
                        alt="profile avatar"
                        className="h-16 w-16 rounded-[18px] object-cover"
                      />
                      <div className="min-w-0">
                        <p className="text-[10px] uppercase tracking-[0.22em] text-slate-400">My Citywalk Notes</p>
                        <h2 className="mt-1 truncate text-2xl font-semibold text-slate-900">{user?.nickname || '还未登录'}</h2>
                        {user?.bio?.trim() ? (
                          <p className="mt-1.5 line-clamp-2 whitespace-pre-line text-xs leading-5 text-slate-500">
                            {user.bio.trim()}
                          </p>
                        ) : null}
                      </div>
                    </div>

                    <div className="flex flex-wrap gap-2">
                      <button
                        onClick={() => setShowProfileEditor((value) => !value)}
                        className="rounded-full border border-slate-200 px-3 py-1.5 text-xs text-slate-600 transition hover:bg-slate-50"
                      >
                        {showProfileEditor ? '收起资料编辑' : '编辑主页资料'}
                      </button>
                      {user ? (
                        <>
                          <button
                            onClick={handleSignOut}
                          className="rounded-full border border-slate-200 px-3 py-1.5 text-xs text-slate-600 transition hover:bg-slate-50"
                        >
                          退出登录
                        </button>
                          <button
                            onClick={() => void handleDeleteAccount()}
                            className="rounded-full border border-rose-200 bg-rose-50 px-3 py-1.5 text-xs text-rose-600 transition hover:bg-rose-100"
                          >
                            注销账号
                          </button>
                        </>
                      ) : null}
                    </div>
                  </div>

                  <ProfileStatsGrid items={profileStats} />
                </section>

                {showProfileEditor ? (
                  <section className="rounded-[24px] border border-slate-200 bg-white p-4 shadow-sm">
                    <div className="mb-3 flex items-center justify-between">
                      <h3 className="text-base font-semibold text-slate-900">编辑个人资料</h3>
                      {isSavingProfile && <LoaderCircle className="h-5 w-5 animate-spin text-amber-500" />}
                    </div>

                    <div className="grid gap-3 lg:grid-cols-[1.1fr_0.9fr]">
                      <label className="block">
                        <span className="mb-1.5 block text-xs font-medium text-slate-700">昵称</span>
                        <input
                          value={profileNickname}
                          onChange={(event) => {
                            setProfileNickname(event.target.value);
                            setProfileMessage('');
                          }}
                          placeholder="给自己起一个更喜欢的名字"
                          className="w-full rounded-2xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm outline-none"
                        />
                      </label>

                      <div className="rounded-2xl border border-dashed border-slate-200 bg-slate-50 px-3 py-3">
                        <div className="flex items-center justify-between gap-3">
                          <div>
                            <div className="text-xs font-medium text-slate-700">头像</div>
                            <div className="mt-1 line-clamp-2 text-xs text-slate-500">
                              换一张你喜欢的照片，让这页更像自己的城市漫步主页。
                            </div>
                            {profileAvatarName ? <div className="mt-2 text-xs text-slate-400">{profileAvatarName}</div> : null}
                          </div>
                          <label className="inline-flex shrink-0 cursor-pointer items-center gap-1.5 rounded-full border border-slate-200 bg-white px-3 py-1.5 text-xs text-slate-600">
                            <ImagePlus className="h-4 w-4" />
                            选择头像
                            <input
                              type="file"
                              accept="image/*"
                              className="hidden"
                              onChange={(event) => {
                                const file = event.target.files?.[0];
                                if (!file) {
                                  return;
                                }
                                const reader = new FileReader();
                                reader.onload = () => {
                                  if (typeof reader.result === 'string') {
                                    setProfileAvatarPreview(reader.result);
                                    setProfileAvatarName(file.name);
                                    setProfileMessage('');
                                  }
                                };
                                reader.readAsDataURL(file);
                              }}
                            />
                          </label>
                        </div>
                      </div>
                    </div>

                    <label className="mt-3 block">
                      <span className="mb-1.5 block text-xs font-medium text-slate-700">个人简介</span>
                      <textarea
                        value={profileBio}
                        onChange={(event) => {
                          setProfileBio(event.target.value);
                          setProfileMessage('');
                        }}
                        maxLength={120}
                        rows={3}
                        placeholder="写一句介绍自己或你的漫步偏好"
                        className="w-full rounded-2xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm leading-6 outline-none"
                      />
                      <div className="mt-2 text-xs text-slate-400">{profileBio.trim().length}/120</div>
                    </label>

                    {profileMessage ? (
                      <div className="mt-4 rounded-2xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700">
                        {profileMessage}
                      </div>
                    ) : null}

                    <div className="mt-4 flex flex-wrap gap-2">
                      <button
                        onClick={handleSaveProfile}
                        disabled={!user || isSavingProfile}
                        className="inline-flex items-center justify-center rounded-2xl bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-60"
                      >
                        保存个人资料
                      </button>
                      <button
                        onClick={() => setShowProfileEditor(false)}
                        className="rounded-2xl border border-slate-200 px-4 py-2 text-sm text-slate-600 transition hover:bg-slate-50"
                      >
                        先不修改
                      </button>
                    </div>
                  </section>
                ) : null}

                <section className="rounded-[24px] border border-slate-200 bg-white p-3 shadow-sm">
                  <div className="mb-3 flex items-start justify-between gap-3">
                    <div>
                      <h3 className="text-lg font-semibold text-slate-900">{profileCollectionMeta.title}</h3>
                      <p className="mt-1 text-xs leading-5 text-slate-500">{profileCollectionMeta.description}</p>
                    </div>
                    {isLoadingProfile && <LoaderCircle className="mt-1 h-5 w-5 animate-spin text-amber-500" />}
                  </div>

                  <ProfileCollectionTabs activeTab={profileCollectionTab} onChange={setProfileCollectionTab} />

                  {profileWalkSource.length === 0 ? (
                    <div className="rounded-[20px] border border-dashed border-slate-300 bg-slate-50 px-4 py-6 text-sm text-slate-500">
                      {profileCollectionMeta.empty}
                    </div>
                  ) : (
                    <ProfileWalkCardList
                      walks={profileWalkSource}
                      onOpenWalk={(walkId) => void handleOpenProfileWalk(walkId)}
                      getCoverHeightClass={getProfilePostCoverHeightClass}
                      getGradientClass={getProfilePostGradient}
                      buildSummary={buildProfilePostSummary}
                      formatDate={formatProfilePostDate}
                      fallbackAvatarUrl={profileAvatarPreview || user?.avatar || undefined}
                      fallbackNickname={user?.nickname || '我的记录'}
                      showFavoriteCount
                    />
                  )}
                </section>
              </main>
            )}
          </>
        )}
      </div>
    </div>
  );
}

function InfoSelect(props: {
  label: string;
  value: string;
  options: string[];
  onChange: (value: string) => void;
}) {
  const { label, value, options, onChange } = props;

  return (
    <label className="block rounded-2xl border border-slate-200 bg-slate-50 p-4">
      <span className="mb-2 block text-xs uppercase tracking-[0.2em] text-slate-400">{label}</span>
      <select
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="w-full bg-transparent text-sm outline-none"
      >
        {options.map((option) => (
          <option key={option} value={option}>
            {option}
          </option>
        ))}
      </select>
    </label>
  );
}
