import crypto from 'node:crypto';
import process from 'node:process';

import dotenv from 'dotenv';
import mysql from 'mysql2/promise';

dotenv.config({ path: '.env.local', quiet: true });
dotenv.config({ path: '.env', quiet: true });

const DEFAULT_COUNT = 60;
const DEFAULT_CITY = 'mixed';
const DEFAULT_GENERATION_SOURCE = 'seed-community';
const DEFAULT_WALK_MODE = 'web';

const CITY_SCENES = {
  guangzhou: {
    cityName: '广州',
    vibeColor: '#b45309',
    authors: ['阿榕', '南方慢慢走', '骑楼散步局', '珠江晚风', '岭南取景器'],
    places: [
      {
        area: '荔湾',
        locationName: '永庆坊',
        category: '老城漫步',
        tags: ['广州散步', '骑楼', '老街拍照', '周末去哪儿'],
        points: [
          ['永庆坊南门', 23.1193, 113.2387],
          ['粤剧艺术博物馆', 23.1182, 113.238],
          ['恩宁路骑楼段', 23.1198, 113.2364],
        ],
      },
      {
        area: '海珠',
        locationName: '海珠国家湿地公园北区',
        category: '自然疗愈',
        tags: ['城市绿洲', '湿地', '发呆路线', '自然散步'],
        points: [
          ['湿地北门', 23.0773, 113.3272],
          ['花洲古渡牌坊', 23.0758, 113.3313],
          ['水杉栈道', 23.0741, 113.3337],
        ],
      },
      {
        area: '越秀',
        locationName: '东山口',
        category: '街区散步',
        tags: ['东山口', '小洋楼', '咖啡店', '拍照路线'],
        points: [
          ['培正路口', 23.1247, 113.2891],
          ['庙前西街', 23.1261, 113.2898],
          ['署前路口袋公园', 23.1272, 113.2875],
        ],
      },
      {
        area: '天河',
        locationName: '珠江新城花城广场',
        category: '夜景漫步',
        tags: ['珠江新城', '夜景', '城市天际线', '傍晚散步'],
        points: [
          ['花城广场北区', 23.1184, 113.3278],
          ['广州图书馆外侧', 23.1164, 113.3255],
          ['海心沙西侧视角', 23.1148, 113.3287],
        ],
      },
    ],
  },
  shanghai: {
    cityName: '上海',
    vibeColor: '#9a3412',
    authors: ['梧桐散步册', '海派慢游', '街角留白', '黄浦晚风', '弄堂观察员'],
    places: [
      {
        area: '徐汇',
        locationName: '武康路',
        category: '街区散步',
        tags: ['武康路', '梧桐区', '老洋房', '拍照散步'],
        points: [
          ['武康大楼', 31.2033, 121.4375],
          ['安福路口', 31.2023, 121.436],
          ['复兴西路转角', 31.2011, 121.4339],
        ],
      },
      {
        area: '黄浦',
        locationName: '外滩',
        category: '夜景漫步',
        tags: ['外滩', '黄浦江', '夜景', '城市散步'],
        points: [
          ['外滩观景平台', 31.2406, 121.4905],
          ['和平饭店门前', 31.2397, 121.4902],
          ['金陵东路渡口附近', 31.2358, 121.4942],
        ],
      },
      {
        area: '静安',
        locationName: '愚园路',
        category: '社区散步',
        tags: ['愚园路', '街角小店', '慢生活', '社区漫步'],
        points: [
          ['江苏路地铁站口', 31.2202, 121.4256],
          ['愚园公共市集', 31.2192, 121.4238],
          ['哥伦比亚公园', 31.2179, 121.4218],
        ],
      },
    ],
  },
  chengdu: {
    cityName: '成都',
    vibeColor: '#0f766e',
    authors: ['松弛感指南', '成都散步研究所', '巷子慢游', '玉林路晚风'],
    places: [
      {
        area: '武侯',
        locationName: '玉林西路',
        category: '夜生活漫步',
        tags: ['玉林路', '小酒馆', '夜晚散步', '成都生活'],
        points: [
          ['玉林西路口', 30.6366, 104.0462],
          ['芳草街转角', 30.6358, 104.0448],
          ['小酒馆附近', 30.6349, 104.0437],
        ],
      },
      {
        area: '青羊',
        locationName: '宽窄巷子周边街区',
        category: '老城漫步',
        tags: ['宽窄巷子', '老成都', '街巷', '周末散步'],
        points: [
          ['宽巷子入口', 30.6672, 104.0504],
          ['窄巷子中段', 30.6664, 104.0488],
          ['井巷子', 30.6654, 104.0471],
        ],
      },
      {
        area: '锦江',
        locationName: '望平街',
        category: '河边散步',
        tags: ['望平街', '夜游', '沿河步道', '成都citywalk'],
        points: [
          ['望平街东口', 30.6598, 104.0912],
          ['府河边步道', 30.6605, 104.0892],
          ['香香巷口', 30.6612, 104.0874],
        ],
      },
    ],
  },
};

const TITLE_PATTERNS = [
  '{locationName}半日散步实录：{mood}',
  '在{locationName}慢慢走，发现{cityName}最舒服的一面',
  '{cityName}周末路线：从{locationName}开始的轻松漫步',
  '{locationName}真的太适合{moment}了',
  '不赶景点，只在{locationName}过一个松弛下午',
];

const MOODS = ['很出片', '很安静', '很松弛', '很适合发呆', '很适合和朋友慢慢走'];
const MOMENTS = ['傍晚散步', '一个人走走', '拍照取景', '约会散步', '周末放空'];
const NOTE_OPENERS = [
  '这条线很适合不想赶景点的时候慢慢走。',
  '整个节奏比较松，适合边走边拍，遇到喜欢的地方就停一下。',
  '如果你最近想找一条不用费脑子的城市散步线，这条我会推荐。',
  '我更喜欢这种不需要硬打卡的路线，走起来舒服很多。',
];
const NOTE_OBSERVATIONS = [
  '路上能明显感觉到街区层次很丰富，拐个弯就会有新的画面。',
  '沿线店铺和街景都比较友好，随手拍都容易有细节。',
  '整条线的噪音感不算强，适合聊天，也适合自己一个人走。',
  '中间有几段树荫和转角视野特别好，傍晚光线会更舒服。',
];
const NOTE_CLOSERS = [
  '建议穿舒服一点的鞋，真的会想多走几步。',
  '如果时间够，最好把节奏放慢一点，这条线适合细看。',
  '阴天和傍晚都适合走，拍照也会更稳。',
  '我会愿意二刷，尤其适合带第一次来这座城的朋友。',
];

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    printHelp();
    return;
  }

  const cityKeys = resolveCityKeys(options.city);
  const posts = buildSeedPosts(cityKeys, options.count, options.generationSource);

  if (options.dryRun) {
    printPreview(cityKeys, posts, options);
    return;
  }

  const db = await mysql.createConnection(parseMysqlConfig());

  try {
    await db.beginTransaction();
    const userCache = new Map();

    for (const post of posts) {
      const userId = await ensureSeedUser(db, post.author, userCache);
      const walkId = await insertWalk(db, userId, post, options.generationSource);
      await insertTags(db, walkId, post.tags);
    }

    await db.commit();
    printSummary(cityKeys, posts, options);
  } catch (error) {
    await db.rollback();
    throw error;
  } finally {
    await db.end();
  }
}

function parseArgs(argv) {
  const options = {
    city: DEFAULT_CITY,
    count: DEFAULT_COUNT,
    dryRun: false,
    help: false,
    generationSource: DEFAULT_GENERATION_SOURCE,
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--help' || arg === '-h') {
      options.help = true;
      continue;
    }
    if (arg === '--city') {
      options.city = String(argv[index + 1] || DEFAULT_CITY).toLowerCase();
      index += 1;
      continue;
    }
    if (arg === '--count') {
      const count = Number(argv[index + 1] || DEFAULT_COUNT);
      options.count = Number.isFinite(count) && count > 0 ? Math.floor(count) : DEFAULT_COUNT;
      index += 1;
      continue;
    }
    if (arg === '--dry-run') {
      options.dryRun = true;
      continue;
    }
    if (arg === '--generation-source') {
      options.generationSource = String(argv[index + 1] || DEFAULT_GENERATION_SOURCE);
      index += 1;
    }
  }

  return options;
}

function printHelp() {
  console.log(`
Usage:
  npm run seed:community -- --city guangzhou --count 80

Options:
  --city <name>              guangzhou / shanghai / chengdu / mixed
  --count <number>           number of posts to generate, default 60
  --dry-run                  preview only, do not write to MySQL
  --generation-source <v>    walk_records.generation_source, default seed-community
  --help                     show help
`);
}

function parseMysqlConfig() {
  const rawUrl = process.env.MYSQL_URL;
  const user = process.env.MYSQL_USERNAME;
  const password = process.env.MYSQL_PASSWORD || '';

  if (!rawUrl || !user) {
    throw new Error('Missing MYSQL_URL or MYSQL_USERNAME in .env.local/.env');
  }

  const normalized = rawUrl.startsWith('jdbc:') ? rawUrl.slice(5) : rawUrl;
  const parsed = new URL(normalized);

  return {
    host: parsed.hostname,
    port: Number(parsed.port || 3306),
    user,
    password,
    database: parsed.pathname.replace(/^\//u, ''),
    charset: 'utf8mb4',
  };
}

function resolveCityKeys(city) {
  if (city === 'mixed') {
    return Object.keys(CITY_SCENES);
  }
  if (!CITY_SCENES[city]) {
    throw new Error(`Unsupported city: ${city}`);
  }
  return [city];
}

function buildSeedPosts(cityKeys, count, generationSource) {
  const posts = [];

  for (let index = 0; index < count; index += 1) {
    const cityKey = cityKeys[index % cityKeys.length];
    const scene = CITY_SCENES[cityKey];
    const place = scene.places[index % scene.places.length];
    const authorName = scene.authors[index % scene.authors.length];
    const mood = MOODS[index % MOODS.length];
    const moment = MOMENTS[index % MOMENTS.length];
    const titleTemplate = TITLE_PATTERNS[index % TITLE_PATTERNS.length];
    const createdAt = buildCreatedAt(index);

    const title = titleTemplate
      .replace('{locationName}', place.locationName)
      .replace('{cityName}', scene.cityName)
      .replace('{mood}', mood)
      .replace('{moment}', moment);

    const missionsCompleted = [
      `在${place.locationName}记录一处最喜欢的街角`,
      `沿着${place.points[1][0]}附近慢慢走 10 分钟`,
      `拍下一张最能代表${scene.cityName}气质的画面`,
    ];

    const noteText = [
      NOTE_OPENERS[index % NOTE_OPENERS.length],
      `这次我是从${place.points[0][0]}开始，经过${place.points[1][0]}，最后在${place.points[2][0]}收尾。`,
      NOTE_OBSERVATIONS[index % NOTE_OBSERVATIONS.length],
      `适合的人群：想在${scene.cityName}找一条${place.category}路线、拍点城市细节，顺便喝杯咖啡的人。`,
      NOTE_CLOSERS[index % NOTE_CLOSERS.length],
    ].join('\n\n');

    const coverImage = `https://picsum.photos/seed/${cityKey}-${index}/1200/900`;
    const photoList = [
      coverImage,
      `https://picsum.photos/seed/${cityKey}-${index}-b/1200/900`,
      `https://picsum.photos/seed/${cityKey}-${index}-c/1200/900`,
    ];

    posts.push({
      cityKey,
      cityName: scene.cityName,
      author: {
        name: authorName,
        openid: buildSeedOpenId(cityKey, authorName),
        avatarUrl: `https://api.dicebear.com/9.x/shapes/svg?seed=${encodeURIComponent(`${cityKey}-${authorName}`)}`,
      },
      title,
      locationName: place.locationName,
      locationContext: `${scene.cityName}${place.area}`,
      themeSnapshot: {
        title,
        description: `一条围绕${place.locationName}展开的${place.category}路线，适合${moment}。`,
        category: place.category,
        missions: missionsCompleted,
        tags: place.tags,
        vibeColor: scene.vibeColor,
        provider: generationSource,
        coverImageUrl: coverImage,
      },
      routePoints: buildRoutePoints(place.points, createdAt),
      missionsCompleted,
      missionReviews: {},
      photoList,
      coverImage,
      noteText,
      tags: place.tags,
      createdAt,
      updatedAt: createdAt,
    });
  }

  return posts;
}

function buildCreatedAt(index) {
  const now = Date.now();
  const offsetHours = index * 7 + (index % 3) * 5;
  return new Date(now - offsetHours * 60 * 60 * 1000);
}

function buildRoutePoints(points, createdAt) {
  const baseTime = createdAt.getTime();
  return points.map(([name, lat, lng], index) => ({
    name,
    lat: Number((lat + index * 0.0002).toFixed(6)),
    lng: Number((lng + index * 0.0002).toFixed(6)),
    timestamp: baseTime + index * 12 * 60 * 1000,
  }));
}

function buildSeedOpenId(cityKey, authorName) {
  return `seed_${cityKey}_${crypto.createHash('md5').update(authorName).digest('hex').slice(0, 12)}`;
}

async function ensureSeedUser(db, author, cache) {
  if (cache.has(author.openid)) {
    return cache.get(author.openid);
  }

  const [rows] = await db.query('SELECT id FROM users WHERE openid = ? LIMIT 1', [author.openid]);
  if (Array.isArray(rows) && rows.length > 0) {
    cache.set(author.openid, rows[0].id);
    return rows[0].id;
  }

  const [result] = await db.query(
    `INSERT INTO users (openid, nickname, avatar_url, bio, role, status, source, created_at, updated_at, last_login_at)
     VALUES (?, ?, ?, ?, 'user', 'active', 'web', ?, ?, ?)`,
    [
      author.openid,
      author.name,
      author.avatarUrl,
      '用于生成社区流和 RAG 测试数据的种子作者',
      new Date(),
      new Date(),
      new Date(),
    ]
  );

  cache.set(author.openid, result.insertId);
  return result.insertId;
}

async function insertWalk(db, userId, post, generationSource) {
  const [result] = await db.query(
    `INSERT INTO walk_records (
       user_id, theme_title, theme_snapshot, location_name, location_context,
       route_points, missions_completed, mission_reviews, photo_list, cover_image,
       note_text, is_public, walk_mode, generation_source, status, created_at, updated_at
     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, 'active', ?, ?)`,
    [
      userId,
      post.title,
      JSON.stringify(post.themeSnapshot),
      post.locationName,
      post.locationContext,
      JSON.stringify(post.routePoints),
      JSON.stringify(post.missionsCompleted),
      JSON.stringify(post.missionReviews),
      JSON.stringify(post.photoList),
      post.coverImage,
      post.noteText,
      DEFAULT_WALK_MODE,
      generationSource,
      post.createdAt,
      post.updatedAt,
    ]
  );

  return result.insertId;
}

async function insertTags(db, walkId, tags) {
  for (const tag of tags.slice(0, 8)) {
    await db.query(
      `INSERT INTO walk_record_tags (walk_id, tag_name, created_at)
       VALUES (?, ?, ?)
       ON DUPLICATE KEY UPDATE tag_name = VALUES(tag_name)`,
      [walkId, tag.slice(0, 64), new Date()]
    );
  }
}

function printPreview(cityKeys, posts, options) {
  printSummary(cityKeys, posts, options);
  console.log('');
  console.log('Preview:');
  posts.slice(0, 3).forEach((post, index) => {
    console.log(`${index + 1}. ${post.title}`);
    console.log(`   ${post.locationContext} | ${post.tags.join(' / ')}`);
  });
}

function printSummary(cityKeys, posts, options) {
  console.log(`Cities: ${cityKeys.join(', ')}`);
  console.log(`Generated: ${posts.length}`);
  console.log(`Mode: ${options.dryRun ? 'dry-run' : 'write-db'}`);
  console.log(`Generation source: ${options.generationSource}`);
}

main().catch((error) => {
  console.error(`seed failed: ${error instanceof Error ? error.message : String(error)}`);
  process.exitCode = 1;
});
