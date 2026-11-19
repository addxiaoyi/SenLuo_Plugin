# 森罗系列本地开发包 0.1.0-dev

开发包包含厨房、玩偶、酒馆、装饰和音乐五个 JAR。已在 Paper 132 和 Folia 14 验证服务器启动和资源包生成。

新增插件使用 Java 21，编译目标 Paper 1.21.11 / CraftEngine API 26.9.1；其他组合尚未验证。先在测试服安装。

1. 停服，把 `plugins/` 中需要的 JAR 复制到服务器的 `plugins/`。每个新插件只硬依赖 CraftEngine，common 已 shade 进对应 JAR，不单独安装；Doll/Tavern 的跨玩法联动仅使用 soft-depend。
2. 把厨房 ZIP 中的 `cookery_kitchen/` 解压到 `plugins/CraftEngine/resources/`。其余 ZIP 分别解压到该目录下的 `doll/`、`tavern/`、`deco/`、`music/` 和 `shared/`。每个目录直接包含 `pack.yml`，其中 `shared/` 提供合并后的图集。
3. 五插件整合安装在 CE `config.yml` 中设置 `block.serverside-blocks: 8000`、`misc.delay-configuration-load: false`、`resource-pack.cache-resource-files: false`。隔离服准备工具可通过 `--server-blocks 8000 --early-pack-load` 生成这组设置。
4. 启动服务器并检查加载日志。首次安装或更新后在控制台执行 `ce workflow default`，确认最终资源包生成、校验、压缩和托管成功。
5. 原版客户端接收生成后的资源包，逐项执行 `docs/ACCEPTANCE.md`。

OP 获取测试物品：`/kdoll kit`、`/ktavern kit`、`/kdeco`、`/kmusic kit`。普通入口：`/kdoll` 收藏、`/ktavern` 配方、手持乐器 `/kmusic`。MIDI 放 `plugins/KaleidoscopeMusicPlugin/songs/`，用 `/kmusic play 文件.mid` 播放。

酒桶先加入4桶同种液体，再加四槽以内原料，空手潜行封盖；果盆投料后跳踩；雪克杯投三份原料后空手右键开始、再次右键停止，空鸡尾酒杯取出。酒类调酒要求熟成4级以上。

本轮新增玩偶蓄力投掷/弹跳/骑乘/幻翼，三类葡萄，龙头装瓶和红石触发，手持摇杯/倒酒，以及Deco状态切换和沙发染色。详细操作和原版客户端替代差异见 `docs/GAMEPLAY.md`。

`docs/PORTING-MATRIX.md` 保留全系列未完成项：精确玩偶物理/自定义Bedrock、野葡萄、燃烧瓶、剩余酒馆家具、Deco复杂脚本/连接、原音乐模型/合成器等。效果、存档、领地保护及Folia边界均需实服验收。

`BUILD-MANIFEST.json` 和 `SHA256SUMS.txt` 记录构建产物、测试和包内文件哈希；`organization-checkouts.json` 记录8个官方仓库来源提交；原资源说明和许可位于各 CE 内容包的 `upstream-notices/`。

本包用于本地开发和验收。对外发布前按 [上游许可证据](UPSTREAM-LICENSES.md) 复核并解决素材授权。

有库存/加工中的家具拒绝普通拆除；外部强制移除若经过 CE 回调，会在对应插件目录 `recovery/` 保存 NBT 内容快照，需管理员核对后人工恢复，不会自动退款。不要用热加载插件工具测试这些存档流程。
