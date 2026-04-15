import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Compass,
  History,
  ImagePlus,
  LoaderCircle,
  LogIn,
  LogOut,
  MapPin,
  Search,
  Shuffle,
  Sparkles,
  UserRound,
  Users,
} from 'lucide-react';
import {
  AppUser,
  consumeLoginCallback,
  getStoredToken,
  loadCurrentUser,
  logoutFromServer,
  loginWithEmail,
  registerWithEmail,
  redirectToWechatLogin,
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
  searchLocationContext,
} from './services/themeService';
import { createWalk, fetchMyWalks, fetchPublicWalks, fetchWalkDetail, WalkItem } from './services/walkApi';
import { fetchNearbyPois, searchLocations } from './services/mapApi';
import { uploadDataUrl } from './services/fileApi';
import { getAuthRequiredEventName } from './services/apiClient';

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

const RANDOM_CATEGORIES = ['形状漫步', '颜色漫步', '声音漫步', '街区漫步', '自然漫步', '动物漫步'];
const COMBINE_CATEGORIES = ['形状漫步', '颜色漫步', '声音漫步', '街区漫步', '自然漫步', '动物漫步'];
const DEFAULT_CENTER: [number, number] = [31.2304, 121.4737];
const DEFAULT_MAP_ZOOM = 16;
const TRACKING_MAP_ZOOM = 19;
const MIN_TRACKING_DISTANCE_METERS = 2.5;
const MAX_ACCEPTABLE_POSITION_ACCURACY_METERS = 150;
const MAX_TIMED_TRACK_POINT_INTERVAL_MS = 5000;

declare global {
  interface Window {
    AMap?: any;
    __amapLoaderPromise?: Promise<any>;
  }
}

function getAmapJsKey() {
  return import.meta.env.VITE_AMAP_JS_KEY?.trim() || '';
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

function sanitizeCardText(value: string) {
  return value.replace(/\s+/g, ' ').trim();
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
  followCurrentPosition: boolean;
  pathCoordinates: [number, number][];
  nearbyPois: MapPOI[];
  selectedPoiKey: string | null;
  onSelectMapPoint: (lat: number, lng: number) => void;
  onSelectPoi: (poi: MapPOI) => void;
}) {
  const { center, selectedLocation, currentPosition, followCurrentPosition, pathCoordinates, nearbyPois, selectedPoiKey, onSelectMapPoint, onSelectPoi } = props;
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<any>(null);
  const overlaysRef = useRef<any[]>([]);
  const infoWindowRef = useRef<any>(null);
  const onSelectMapPointRef = useRef(onSelectMapPoint);
  const [mapReadyVersion, setMapReadyVersion] = useState(0);
  const isSameCoordinate =
    selectedLocation &&
    currentPosition &&
    Math.abs(selectedLocation.lat - currentPosition.lat) < 0.000001 &&
    Math.abs(selectedLocation.lng - currentPosition.lng) < 0.000001;

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

    mapRef.current.setZoomAndCenter(followCurrentPosition ? TRACKING_MAP_ZOOM : DEFAULT_MAP_ZOOM, [center[1], center[0]]);
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

    if (currentPosition) {
      overlays.push(
        new AMap.Marker({
          position: [currentPosition.lng, currentPosition.lat],
          anchor: 'center',
          offset: new AMap.Pixel(0, 0),
          content: createMarkerContent('#f97316', 20, currentPosition.name),
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
      });

    if (pathCoordinates.length > 1) {
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

    if (overlays.length > 0) {
      map.add(overlays);
    }

    overlaysRef.current = overlays;
  }, [currentPosition, isSameCoordinate, mapReadyVersion, nearbyPois, onSelectPoi, pathCoordinates, selectedLocation, selectedPoiKey]);

  return <div ref={containerRef} className="h-full w-full" />;
}

function normalizeCompletedMissionLabels(completedMissions?: WalkItem['completedMissions']) {
  if (!Array.isArray(completedMissions)) {
    return [];
  }

  return completedMissions
    .map((mission) => sanitizeCardText(mission?.mission || ''))
    .filter((mission) => mission.length > 0);
}

function WalkDetailMap(props: {
  path: PathPoint[];
  locationLabel: string;
}) {
  const { path, locationLabel } = props;
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<any>(null);
  const overlaysRef = useRef<any[]>([]);
  const [nearbyPois, setNearbyPois] = useState<MapPOI[]>([]);

  useEffect(() => {
    if (path.length === 0) {
      setNearbyPois([]);
      return;
    }

    const lastPoint = path[path.length - 1];
    fetchNearbyPois(lastPoint.lat, lastPoint.lng)
      .then((pois) => setNearbyPois(pois.slice(0, 8)))
      .catch((error) => {
        console.error('Fetch detail nearby POIs error:', error);
        setNearbyPois([]);
      });
  }, [path]);

  useEffect(() => {
    if (!containerRef.current || mapRef.current || path.length === 0) {
      return;
    }

    let isDisposed = false;

    loadAmapJsApi()
      .then((AMap) => {
        if (isDisposed || !containerRef.current || mapRef.current) {
          return;
        }

        const lastPoint = path[path.length - 1];
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
  }, [path]);

  useEffect(() => {
    const map = mapRef.current;
    const AMap = window.AMap;
    if (!map || !AMap || path.length === 0) {
      return;
    }

    if (overlaysRef.current.length > 0) {
      map.remove(overlaysRef.current);
      overlaysRef.current = [];
    }

    const overlays: any[] = [];
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
  }, [locationLabel, nearbyPois, path]);

  if (path.length === 0) {
    return (
      <div className="flex h-72 items-center justify-center rounded-[24px] border border-dashed border-slate-300 bg-white text-sm text-slate-500">
        这条记录里还没有可展示的轨迹。
      </div>
    );
  }

  return <div ref={containerRef} className="h-72 w-full overflow-hidden rounded-[24px] border border-slate-200" />;
}

export default function App() {
  const [user, setUser] = useState<AppUser | null>(null);
  const [showEmailLogin, setShowEmailLogin] = useState(false);
  const [emailLoginMode, setEmailLoginMode] = useState<'login' | 'register'>('login');
  const [emailInput, setEmailInput] = useState('');
  const [passwordInput, setPasswordInput] = useState('');
  const [emailCodeInput, setEmailCodeInput] = useState('');
  const [authError, setAuthError] = useState('');
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
  const [history, setHistory] = useState<WalkTheme[]>([]);
  const [myWalks, setMyWalks] = useState<WalkItem[]>([]);
  const [selectedProfileWalk, setSelectedProfileWalk] = useState<WalkItem | null>(null);
  const [communityWalks, setCommunityWalks] = useState<WalkItem[]>([]);
  const [isLoadingCommunity, setIsLoadingCommunity] = useState(false);
  const [isLoadingProfile, setIsLoadingProfile] = useState(false);
  const [isSavingProfile, setIsSavingProfile] = useState(false);
  const [profileNickname, setProfileNickname] = useState('');
  const [profileAvatarPreview, setProfileAvatarPreview] = useState<string | null>(null);
  const [profileAvatarName, setProfileAvatarName] = useState('');
  const [profileMessage, setProfileMessage] = useState('');
  const [isGenerating, setIsGenerating] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
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
  const [selectedPoiKey, setSelectedPoiKey] = useState<string | null>(null);
  const [noteText, setNoteText] = useState('');
  const [isPublic, setIsPublic] = useState(true);
  const [path, setPath] = useState<PathPoint[]>([]);
  const [isTracking, setIsTracking] = useState(false);
  const [livePosition, setLivePosition] = useState<SearchLocation | null>(null);
  const searchTimeoutRef = useRef<number | null>(null);
  const hasAutoLocatedRef = useRef(false);

  useEffect(() => {
    const callbackPayload = consumeLoginCallback();
    const token = callbackPayload?.token || getStoredToken();
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
    if (activeTab !== 'community') {
      return;
    }

    setIsLoadingCommunity(true);
    fetchPublicWalks(1, 50)
      .then(setCommunityWalks)
      .catch((error) => {
        console.error('Error fetching community walks:', error);
      })
      .finally(() => {
        setIsLoadingCommunity(false);
      });
  }, [activeTab]);

  useEffect(() => {
    setProfileNickname(user?.nickname || '');
    setProfileAvatarPreview(user?.avatar || null);
    setProfileAvatarName('');
  }, [user]);

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
    () => selectedLocation?.name || searchLocation || '当前位置',
    [searchLocation, selectedLocation],
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
    return DEFAULT_CENTER;
  }, [currentPosition, isTracking, path, selectedLocation]);

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

    if (tailDistance < 0.8) {
      return pathCoordinates;
    }

    return [...pathCoordinates, currentCoordinate];
  }, [currentPosition, isTracking, pathCoordinates]);
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
      })
      .slice(0, 10);
  }, [communityReferencePoint, communityWalks]);

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
      setSelectedProfileWalk(null);
      return;
    }
    try {
      const data = await fetchMyWalks(1, 10);
      setMyWalks(data);
      setHistory(data.map((item) => toThemeFromWalk(item)));
      setSelectedProfileWalk((prev) => {
        if (prev) {
          const matched = data.find((item) => item.id === prev.id);
          return matched ?? data[0] ?? null;
        }
        return data[0] ?? null;
      });
      if (data.length === 0) {
        return;
      }
    } catch (error) {
      console.error('Fetch recent walks error:', error);
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
    const context = await getLocationContext(gcjPosition.lat, gcjPosition.lng);

    setLocationContext(context);
    setSelectedLocation({
      name: '当前位置',
      lat: gcjPosition.lat,
      lng: gcjPosition.lng,
    });
    setSearchLocation('当前位置');
    setSearchResults([]);

    return {
      locationName: '当前位置',
      locationContextText: context,
    };
  };

  const resolveCurrentContext = async (): Promise<{ locationName: string; locationContextText: string }> => {
    if (selectedLocation) {
      return {
        locationName: selectedLocation.name,
        locationContextText: locationContext,
      };
    }

    if (searchLocation.trim()) {
      return {
        locationName: searchLocation.trim(),
        locationContextText: locationContext,
      };
    }

    try {
      return await resolveBrowserLocation();
    } catch (error) {
      console.error('Get current geolocation error:', error);
    }

    return {
      locationName: '当前位置',
      locationContextText: locationContext,
    };
  };

  const handleSearchLocation = (query: string) => {
    setSearchLocation(query);
    setSelectedLocation(null);

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
    setIsGenerating(true);
    try {
      const context = await getLocationContext(lat, lng);
      const locationName = `地图选点 (${lat.toFixed(4)}, ${lng.toFixed(4)})`;
      setSelectedLocation({ name: locationName, lat, lng });
      setSelectedPoiKey(null);
      setSearchLocation(locationName);
      setSearchResults([]);
      setLocationContext(context);
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

  const handleGenerateRandomTheme = async () => {
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

  const handleCombineThemes = async () => {
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

  const handleWechatLogin = async () => {
    try {
      await redirectToWechatLogin();
    } catch (error) {
      console.error('Auth error:', error);
      alert('登录失败，请稍后重试。');
    }
  };

  const handleSignIn = () => {
    setAuthError('');
    setEmailLoginMode('login');
    setShowEmailLogin(true);
  };


  const getEmailSendErrorMessage = (error: unknown) => {
    const message = error instanceof Error ? error.message : '';
    if (message.includes('code_send_too_frequent')) {
      return '验证码刚发过，请等 60 秒后再试。';
    }
    if (message.includes('email_not_supported')) {
      return '目前只支持 QQ 邮箱注册。';
    }
    if (message.includes('email_send_failed')) {
      return '验证码邮件发送失败，请稍后重试。';
    }
    return '验证码发送失败，请稍后重试。';
  };

  const getEmailAuthErrorMessage = (mode: 'login' | 'register', error: unknown) => {
    const message = error instanceof Error ? error.message : '';
    if (mode === 'register') {
      if (message.includes('code_invalid')) {
        return '验证码无效或已过期，请重新获取。';
      }
      if (message.includes('email_already_registered')) {
        return '这个 QQ 邮箱已经注册过了，直接登录就可以。';
      }
      if (message.includes('email_not_supported')) {
        return '目前只支持 QQ 邮箱注册。';
      }
      if (message.includes('password_too_short')) {
        return '密码至少需要 6 位。';
      }
      return '注册失败，请检查验证码、邮箱和密码。';
    }

    if (message.includes('email_not_registered')) {
      return '这个邮箱还没有注册，请先注册账号。';
    }
    if (message.includes('invalid_password')) {
      return '密码不正确，请重新输入。';
    }
    if (message.includes('email_not_supported')) {
      return '目前只支持 QQ 邮箱登录。';
    }
    return '登录失败，请检查邮箱和密码。';
  };

  const handleEmailAuthSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const trimmedEmail = emailInput.trim().toLowerCase();
    if (!trimmedEmail || !trimmedEmail.endsWith('@qq.com')) {
      setAuthError('请使用 QQ 邮箱登录（例如：name@qq.com）。');
      return;
    }
    if (!passwordInput || passwordInput.length < 6) {
      setAuthError('密码至少 6 位。');
      return;
    }

    setIsAuthLoading(true);
    setAuthError('');
    try {
      if (emailLoginMode === 'register') {
        if (!emailCodeInput.trim()) {
          setAuthError('请输入邮箱验证码。');
          setIsAuthLoading(false);
          return;
        }
        await registerWithEmail(trimmedEmail, passwordInput, emailCodeInput.trim());
      } else {
        await loginWithEmail(trimmedEmail, passwordInput);
      }
      const profile = await loadCurrentUser();
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
    try {
      await sendEmailCode(trimmedEmail);
      setSendCodeCooldown(60);
    } catch (error) {
      console.error('Send email code error:', error);
      setAuthError(getEmailSendErrorMessage(error));
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
      setUser(null);
      setMyWalks([]);
      setSelectedProfileWalk(null);
      setProfileMessage('');
    }
  };

  const handleOpenProfileWalk = async (walkId: number) => {
    setIsLoadingProfile(true);
    try {
      const detail = await fetchWalkDetail(walkId);
      setSelectedProfileWalk(detail);
    } catch (error) {
      console.error('Fetch walk detail error:', error);
    } finally {
      setIsLoadingProfile(false);
    }
  };

  const handleSaveProfile = async () => {
    if (!user) {
      alert('请先登录后再编辑个人资料。');
      return;
    }

    const nextNickname = profileNickname.trim();
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

      await createWalk({
        themeTitle: currentTheme.title,
        themeCategory: currentTheme.category,
        locationName: currentLocationName,
        recordUnit,
        isPublic,
        noteText,
        path,
        completedMissions: [],
        photoUrl,
      });
      const card = await generateWalkRecordCardWithAi({
        theme: currentTheme,
        locationName: currentLocationName,
        locationContext,
        noteText,
        photoUrl: cardPhotoSource || photoUrl,
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

        {showEmailLogin && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 px-4 py-6">
            <div className="w-full max-w-md rounded-3xl bg-white p-6 shadow-xl">
              <div className="mb-4 flex items-center justify-between">
                <div>
                  <h2 className="text-xl font-semibold text-slate-900">QQ 邮箱登录</h2>
                  <p className="text-sm text-slate-500">
                    {emailLoginMode === 'register' ? '创建账号并绑定邮箱' : '使用邮箱 + 密码登录'}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => setShowEmailLogin(false)}
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
                {emailLoginMode === 'register' ? (
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
                {authError ? <div className="text-sm text-rose-500">{authError}</div> : null}
                <button
                  type="submit"
                  disabled={isAuthLoading}
                  className="flex w-full items-center justify-center rounded-2xl bg-slate-900 py-3 text-sm font-medium text-white disabled:opacity-60"
                >
                  {isAuthLoading ? '处理中...' : emailLoginMode === 'register' ? '创建账号' : '登录'}
                </button>
              </form>

              <div className="mt-4 flex items-center justify-between text-sm">
                <span className="text-slate-500">
                  {emailLoginMode === 'register' ? '已有账号？' : '没有账号？'}
                </span>
                <button
                  type="button"
                  onClick={() => {
                    setEmailLoginMode(emailLoginMode === 'register' ? 'login' : 'register');
                    setAuthError('');
                  }}
                  className="rounded-full border border-slate-200 px-3 py-1 text-slate-600"
                >
                  {emailLoginMode === 'register' ? '去登录' : '去注册'}
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
                        onClick={() => setWalkMode('pure')}
                        className={`rounded-full px-4 py-2 text-sm ${walkMode === 'pure' ? 'bg-white shadow text-slate-900' : 'text-slate-500'}`}
                      >
                        纯净模式
                      </button>
                      <button
                        onClick={() => setWalkMode('advanced')}
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
                    <div className="rounded-full bg-slate-100 px-3 py-1">POI {nearbyPois.length}</div>
                  </div>
                </div>

                <div className="h-[360px]">
                  <AmapScene
                    center={mapCenter}
                    selectedLocation={selectedLocation}
                    currentPosition={visibleCurrentPosition}
                    followCurrentPosition={isTracking}
                    pathCoordinates={visiblePathCoordinates}
                    nearbyPois={nearbyPois}
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
                  {nearbyPois.length === 0 ? (
                    <p className="mt-2 text-sm text-slate-500">选择地点后，这里会显示附近推荐点位。</p>
                  ) : (
                    <div className="mt-3 grid gap-3 md:grid-cols-2">
                      {nearbyPois.map((poi, index) => (
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
                    <div key={`${mission}-${index}`} className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm">
                      {index + 1}. {mission}
                    </div>
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
                          className={`rounded-full px-4 py-2 text-sm ${selected ? 'bg-slate-900 text-white' : 'border border-slate-200 bg-white'}`}
                        >
                          {category}
                        </button>
                      );
                    })}
                  </div>
                </div>

                <div className="mt-6 flex flex-wrap gap-3">
                  <button
                    onClick={handleCombineThemes}
                    className="inline-flex items-center gap-2 rounded-full bg-emerald-500 px-4 py-2 text-sm font-medium text-white"
                  >
                    组合生成主题
                  </button>
                  <button
                    onClick={handleGenerateAiTheme}
                    className="inline-flex items-center gap-2 rounded-full bg-amber-500 px-4 py-2 text-sm font-medium text-white"
                  >
                    <Sparkles className="h-4 w-4" />
                    AI 生成
                  </button>
                  <button
                    onClick={handleGenerateRandomTheme}
                    className="inline-flex items-center gap-2 rounded-full bg-slate-900 px-4 py-2 text-sm font-medium text-white"
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
              </div>
            </section>

            <aside className="space-y-6">
              <div className="rounded-[32px] border border-slate-200 bg-white p-5 shadow-sm">
                <div className="mb-4 flex items-center justify-between">
                  <h3 className="text-lg font-semibold">保存本次漫步</h3>
                  <History className="h-5 w-5 text-slate-400" />
                </div>

                <textarea
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
          <main className="rounded-[32px] border border-slate-200 bg-white p-6 shadow-sm">
            <div className="mb-6 flex items-center justify-between">
              <div>
                <h2 className="text-2xl font-semibold">社区漫步</h2>
                <p className="text-sm text-slate-500">查看大家公开发布的漫步记录</p>
              </div>
              {isLoadingCommunity && <LoaderCircle className="h-5 w-5 animate-spin text-amber-500" />}
            </div>

            <div className="grid gap-4 md:grid-cols-2">
              {visibleCommunityWalks.length === 0 ? (
                <div className="rounded-2xl border border-dashed border-slate-300 p-6 text-sm text-slate-500">
                  暂时还没有社区内容，等你来发布第一条记录。
                </div>
              ) : (
                visibleCommunityWalks.map((walk) => (
                  <article key={walk.id} className="rounded-[28px] border border-slate-200 bg-slate-50 p-5">
                    <div className="text-xs uppercase tracking-[0.2em] text-slate-400">{walk.themeCategory || '城市'}</div>
                    <h3 className="mt-2 text-lg font-semibold">{walk.themeTitle}</h3>
                    <p className="mt-1 text-sm text-slate-500">{walk.locationName || '未填写地点'}</p>
                    {walk.noteText && <p className="mt-4 text-sm leading-7 text-slate-700">{walk.noteText}</p>}
                    {walk.photoUrl && (
                      <img src={walk.photoUrl} alt={walk.themeTitle} className="mt-4 h-48 w-full rounded-2xl object-cover" />
                    )}
                  </article>
                ))
              )}
            </div>
          </main>
        ) : (
          <main className="grid gap-6 lg:grid-cols-[0.88fr_1.12fr]">
            <section className="space-y-6">
              <div className="rounded-[32px] border border-slate-200 bg-white p-6 shadow-sm">
                <div className="flex items-center gap-4">
                  <img
                    src={profileAvatarPreview || user?.avatar || 'https://placehold.co/120x120?text=U'}
                    alt="profile avatar"
                    className="h-24 w-24 rounded-[28px] object-cover"
                  />
                  <div>
                    <p className="text-xs uppercase tracking-[0.24em] text-slate-400">Profile</p>
                    <h2 className="mt-2 text-2xl font-semibold text-slate-900">{user?.nickname || '还未登录'}</h2>
                    <p className="mt-2 text-sm leading-6 text-slate-500">在这里查看更完整的生成记录，也可以更新你的头像和昵称。</p>
                  </div>
                </div>

                <div className="mt-6 grid gap-3 sm:grid-cols-3">
                  <div className="rounded-2xl bg-slate-50 px-4 py-4">
                    <div className="text-xs uppercase tracking-[0.18em] text-slate-400">记录总数</div>
                    <div className="mt-2 text-2xl font-semibold text-slate-900">{myWalks.length}</div>
                  </div>
                  <div className="rounded-2xl bg-slate-50 px-4 py-4">
                    <div className="text-xs uppercase tracking-[0.18em] text-slate-400">公开记录</div>
                    <div className="mt-2 text-2xl font-semibold text-slate-900">
                      {myWalks.filter((item) => item.isPublic).length}
                    </div>
                  </div>
                  <div className="rounded-2xl bg-slate-50 px-4 py-4">
                    <div className="text-xs uppercase tracking-[0.18em] text-slate-400">带照片</div>
                    <div className="mt-2 text-2xl font-semibold text-slate-900">
                      {myWalks.filter((item) => Boolean(item.photoUrl)).length}
                    </div>
                  </div>
                </div>
              </div>

              <div className="rounded-[32px] border border-slate-200 bg-white p-6 shadow-sm">
                <div className="mb-4 flex items-center justify-between">
                  <h3 className="text-lg font-semibold">编辑个人资料</h3>
                  {isSavingProfile && <LoaderCircle className="h-5 w-5 animate-spin text-amber-500" />}
                </div>

                <div className="space-y-4">
                  <label className="block">
                    <span className="mb-2 block text-sm font-medium text-slate-700">昵称</span>
                    <input
                      value={profileNickname}
                      onChange={(event) => setProfileNickname(event.target.value)}
                      placeholder="给自己起一个名字"
                      className="w-full rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm outline-none"
                    />
                  </label>

                  <div className="rounded-2xl border border-dashed border-slate-200 bg-slate-50 px-4 py-4">
                    <div className="flex items-center justify-between gap-4">
                      <div>
                        <div className="text-sm font-medium text-slate-700">头像</div>
                        <div className="mt-1 text-xs text-slate-500">选择一张你喜欢的图片作为个人主页头像。</div>
                      </div>
                      <label className="inline-flex cursor-pointer items-center gap-2 rounded-full border border-slate-200 bg-white px-3 py-2 text-sm text-slate-600">
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

                  {profileMessage ? (
                    <div className="rounded-2xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700">
                      {profileMessage}
                    </div>
                  ) : null}

                  <button
                    onClick={handleSaveProfile}
                    disabled={!user || isSavingProfile}
                    className="inline-flex w-full items-center justify-center rounded-2xl bg-slate-900 px-4 py-3 text-sm font-medium text-white disabled:opacity-60"
                  >
                    保存个人资料
                  </button>
                </div>
              </div>
            </section>

            <section className="space-y-6">
              <div className="rounded-[32px] border border-slate-200 bg-white p-6 shadow-sm">
                <div className="mb-4 flex items-center justify-between">
                  <div>
                    <h3 className="text-lg font-semibold">我的详细记录</h3>
                    <p className="text-sm text-slate-500">点击左侧记录卡片，可以查看更完整的地点、任务和照片内容。</p>
                  </div>
                  {isLoadingProfile && <LoaderCircle className="h-5 w-5 animate-spin text-amber-500" />}
                </div>

                <div className="grid gap-6 lg:grid-cols-[0.88fr_1.12fr]">
                  <div className="space-y-3">
                    {myWalks.length === 0 ? (
                      <div className="rounded-2xl border border-dashed border-slate-300 p-6 text-sm text-slate-500">
                        还没有保存过漫步记录，先去探索页生成一条吧。
                      </div>
                    ) : (
                      myWalks.map((walk) => (
                        <button
                          key={walk.id}
                          onClick={() => void handleOpenProfileWalk(walk.id)}
                          className={`block w-full rounded-[24px] border px-4 py-4 text-left transition ${
                            selectedProfileWalk?.id === walk.id
                              ? 'border-amber-300 bg-amber-50 shadow-sm'
                              : 'border-slate-200 bg-slate-50 hover:bg-slate-100'
                          }`}
                        >
                          <div className="text-xs uppercase tracking-[0.2em] text-slate-400">{walk.themeCategory || '城市'}</div>
                          <div className="mt-2 text-base font-semibold text-slate-900">{walk.themeTitle}</div>
                          <div className="mt-1 text-sm text-slate-500">{walk.locationName || '未填写地点'}</div>
                          <div className="mt-3 flex flex-wrap gap-2 text-xs text-slate-500">
                            <span className="rounded-full bg-white px-2 py-1">轨迹点 {walk.path?.length || 0}</span>
                            <span className="rounded-full bg-white px-2 py-1">{walk.photoUrl ? '有照片' : '无照片'}</span>
                          </div>
                        </button>
                      ))
                    )}
                  </div>

                  <div className="rounded-[28px] border border-slate-200 bg-slate-50 p-5">
                    {selectedProfileWalk ? (
                      <div>
                        <div className="text-xs uppercase tracking-[0.22em] text-slate-400">
                          {selectedProfileWalk.themeCategory || '城市漫步'}
                        </div>
                        <h3 className="mt-2 text-2xl font-semibold text-slate-900">{selectedProfileWalk.themeTitle}</h3>
                        <div className="mt-3 flex flex-wrap gap-2 text-sm text-slate-500">
                          <span className="inline-flex items-center gap-1 rounded-full bg-white px-3 py-1">
                            <MapPin className="h-4 w-4" />
                            {selectedProfileWalk.locationName || '未填写地点'}
                          </span>
                          <span className="rounded-full bg-white px-3 py-1">
                            {selectedProfileWalk.isPublic ? '已公开发布' : '仅自己可见'}
                          </span>
                          <span className="rounded-full bg-white px-3 py-1">
                            轨迹点 {selectedProfileWalk.path?.length || 0}
                          </span>
                        </div>

                        {selectedProfileWalk.noteText ? (
                          <div className="mt-5 rounded-2xl bg-white px-4 py-4">
                            <div className="text-xs uppercase tracking-[0.18em] text-slate-400">我的记录</div>
                            <p className="mt-3 text-sm leading-7 text-slate-700">{selectedProfileWalk.noteText}</p>
                          </div>
                        ) : null}

                        {selectedProfileWalk.photoUrl ? (
                          <div className="mt-5 overflow-hidden rounded-[24px] border border-slate-200 bg-white">
                            <img
                              src={selectedProfileWalk.photoUrl}
                              alt={selectedProfileWalk.themeTitle}
                              className="h-72 w-full object-cover"
                            />
                          </div>
                        ) : null}

                        <div className="mt-5 rounded-[24px] bg-white p-4">
                          <div className="text-xs uppercase tracking-[0.18em] text-slate-400">轨迹地图</div>
                          <div className="mt-3">
                            <WalkDetailMap
                              path={selectedProfileWalk.path || []}
                              locationLabel={selectedProfileWalk.locationName || selectedProfileWalk.themeTitle}
                            />
                          </div>
                        </div>

                        <div className="mt-5 grid gap-4 sm:grid-cols-2">
                          <div className="rounded-2xl bg-white px-4 py-4">
                            <div className="text-xs uppercase tracking-[0.18em] text-slate-400">完成任务</div>
                            <div className="mt-3 space-y-2">
                              {normalizeCompletedMissionLabels(selectedProfileWalk.completedMissions).length > 0 ? (
                                normalizeCompletedMissionLabels(selectedProfileWalk.completedMissions).map((mission, index) => (
                                  <div key={`${mission}-${index}`} className="rounded-xl bg-slate-50 px-3 py-2 text-sm text-slate-700">
                                    {mission}
                                  </div>
                                ))
                              ) : (
                                <p className="text-sm text-slate-500">这条记录里还没有单独保存任务完成项。</p>
                              )}
                            </div>
                          </div>
                          <div className="rounded-2xl bg-white px-4 py-4">
                            <div className="text-xs uppercase tracking-[0.18em] text-slate-400">路线概览</div>
                            <div className="mt-3 space-y-2 text-sm text-slate-700">
                              <div>记录单元：{selectedProfileWalk.recordUnit || 'event'}</div>
                              <div>轨迹点数量：{selectedProfileWalk.path?.length || 0}</div>
                              <div>累计距离：{(calculatePathDistance(selectedProfileWalk.path || []) / 1000).toFixed(2)} km</div>
                            </div>
                          </div>
                        </div>
                      </div>
                    ) : (
                      <div className="flex min-h-[360px] items-center justify-center rounded-[24px] border border-dashed border-slate-300 bg-white text-sm text-slate-500">
                        从左侧选一条记录，就能在这里看到更完整的内容。
                      </div>
                    )}
                  </div>
                </div>
              </div>
            </section>
          </main>
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
