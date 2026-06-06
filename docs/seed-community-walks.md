# 社区测试数据种子脚本

如果你现在最缺的是“先把社区流和 RAG 跑起来的数据量”，推荐直接用这个脚本。

它会批量写入：
- `users`
- `walk_records`
- `walk_record_tags`

生成的数据会尽量贴近你当前项目结构：
- 中文标题和正文
- 标签
- 封面图和图片列表
- 路线点
- 任务列表
- 分散的发布时间

## 用法

先预览，不写库：

```bash
npm run seed:community -- --city guangzhou --count 20 --dry-run
```

确认没问题后，正式写库：

```bash
npm run seed:community -- --city guangzhou --count 80
```

混合多个城市一起生成：

```bash
npm run seed:community -- --city mixed --count 120
```

## 参数

```bash
--city guangzhou
--city shanghai
--city chengdu
--city mixed
--count 80
--dry-run
--generation-source seed-community
```

`mixed` 会把广州、上海、成都混在一起生成。

## 建议流程

1. 先跑 `30` 到 `80` 条测试帖子
2. 检查前端社区流和详情页展示
3. 再触发一次社区 RAG 入库
4. 最后看 agent / 检索效果
