# 压测场景矩阵

示例数据由 `generate_sample_data.py` 在 CI 内确定性生成（纯 Python，无第三方依赖），
`run_stress.sh` 把每个场景覆盖到新生成的世界后启动 Forge 服务端。
自动部分只覆盖到**服务端启动**；需要玩家进服点 GUI 的操作列在「手动验证」里，
用同一份示例数据按步骤走一遍即可。

世界种子固定为 `123456789`（`server.properties`），`level.dat` 模板来自一次真实开服，
各场景只替换 `world/region`、`world/entities`、`world/poi` 下的文件。

| 场景 | 示例数据 | 压的是哪条代码路径 | CI 自动断言 | 手动验证（进服 `/mcsaveeditor open`） |
|---|---|---|---|---|
| `empty` | 无任何 region 文件 | 空世界扫描、`listRegions` 返回空 | 启动 Done，无报错 | 不勾选范围扫描 → 聊天栏提示找到 0 个区块 |
| `normal` | 3×3 区块，当前 DataVersion，`InhabitedTime` 为 0 / 6000 / 60000 等 9 档 | 常规扫描、停留时间过滤、地形缩略图采样 | 启动 Done | ① 不过滤扫描应找到 9 个；② 勾选限制时间填 5 分钟 → 只剩停留 ≤5 分钟的；③ 框选后「升级区块数据」应成功且地形不变 |
| `old-version` | DataVersion 2860 / 3105 的区块各 1 个 + 1 个 1.13–1.17 式 `Level` 包裹区块 | `UPDATE` 的 DataFixer 升级链、`ChunkTerrainSampler` 的老格式兼容分支 | 启动 Done（服务端加载旧区块走升级路径） | 框选 3 个 →「升级区块数据」→ 完成后无报错；扫描缩略图里老格式区块应有颜色而非全黑 |
| `corrupt` | r.10.10.mca：1 个好区块 + 1 个 zlib 垃圾条目；r.11.11.mca 整文件截断；附带 `notes.txt` 非法文件名（扫描器必须忽略） | `ServerChunkScanService` 损坏容错（FIX-A：跳过而非中止全任务） | 启动 Done（损坏区远离出生点，不影响开服） | 全服扫描 → 必须**完成**并提示找到 1 个，服务端日志有且仅有一次「读取失败，扫描将跳过损坏区块」 |
| `huge` | 8×8 region、2048 个稀疏区块 | 大半径扫描吞吐、`MAX_SCANNED_ENTRIES` 上限、取消扫描 | 启动 Done，记录启动耗时 | ① 不勾选范围扫描看完成时间；② 扫描中途点「取消扫描」→ 任务立即停止；③ 半径填 512 扫全图 |
| `player-base` | 4 区块，高 `InhabitedTime`（200 万 tick），含箱子 + 刷怪笼方块实体 | 玩家保护圈（黄色不可选）、重建时的建筑/方块实体保留（FIX-C） | 启动 Done | ① 站旁边扫描 → 4 区块全黄且无法选中；② 走远后框选 →「重建地形」→ 完成后箱子/刷怪笼还在、新地形已生成 |
| `entities-poi` | region + entities（含僵尸/猪）+ poi 三套存储齐全 | `DELETE` 的三存储联动删除、`eraseIfPresent`/`verifyErased` | 启动 Done | 框选删除 → 离开 → 重进该区块：地形重生成且僵尸/猪消失（实体存储也被清掉） |
| `edge-selection` | 4×4 棋盘格 8 区块 | 重建边缘融合带、`distanceToOutside` 边界计算 | 启动 Done | 框选棋盘 → 固定种子重建 → 选区边缘与未选区之间应为平滑过渡，无悬崖切面 |

## 场景数据速查

- 当前 DataVersion：3465（1.20.1）；旧数据：2860（1.18.2 时代）、3105（1.19.3 时代）。
- 每个测试区块为单 section（Y=-4）石头打底 + 起伏草方块表层 + 平原群系，保证缩略图采样、融合、装饰读取都有东西可读。
- `corrupt` 场景的损坏文件放在 r.10 / r.11（坐标 320+，远离出生点），保证 CI 开服阶段不受影响，只在扫描路径触发。
- 本地复现：`python3 stress-test/generate_sample_data.py --out /tmp/sample-data --check`。
