# 森罗系列本地开发包 0.1.0-dev

包含5个 JAR：原厨房开发版，以及玩偶、酒馆、装饰、音乐四个新增插件。**当前为开发验证包，仍有功能差异；服务端覆盖见版本说明，原版客户端未验收。**

五个插件公共源码使用 Java 21、Paper 1.21.1 / CraftEngine API 26.9.1 编译；现代组件单独编译。26.x 服务器需要 Java 25。十组厨房专用 JAR 和三组 CE 配置的选择见 `docs/VERSION-COMPATIBILITY.md`，完整版本运行验收尚未完成。26.3 必须使用 CraftEngine 26.9.2，建议双端统一 26.9.2。五插件合用需超过 6,045 个服务端方块状态槽位，本轮使用 `block.serverside-blocks: 16000`。

1. 停服，把 `plugins/` 中需要的 JAR 复制到服务器的 `plugins/`。每个新插件只硬依赖 CraftEngine，common 已 shade 进对应 JAR，不单独安装；Doll/Tavern 的跨玩法联动仅使用 soft-depend。
2. 存在 `versioned-ce-packs/` 时，按服务器版本选择 `mc1.21.1`、`mc1.21.2-1.21.8` 或 `mc1.21.9-26.3` 目录中的内容包。厨房 ZIP 内的 `cookery_kitchen/` 复制到 `plugins/CraftEngine/resources/`；Doll 等 ZIP 解压到 `resources/doll/` 等对应模块目录，确保目录直接有 `pack.yml`。shared ZIP 解压到 `resources/shared/`。不要同时加载原始 `ce-packs/` 中的同名定义。
3. 当前分发包含完整已导出厨房美术资源。CE 内容 ZIP 用于服务器配置，不能直接发送给客户端；由 CraftEngine 生成最终资源包。1.21.1 的自定义饮品使用原版吃东西动画，食用时间和效果仍保留。
4. 启动服务器并检查加载日志。按目标 CE 版本执行配置加载、最终资源包生成和发送；当前文档使用 `/ce reload all`，仅 `/ce reload` 默认不重建客户端包。
5. 原版客户端接收生成后的资源包，逐项执行 `docs/ACCEPTANCE.md`。

OP 获取测试物品：`/kdoll kit`、`/ktavern kit`、`/kdeco`、`/kmusic kit`。普通入口：`/kdoll` 收藏、`/ktavern` 配方、手持乐器 `/kmusic`。MIDI 放 `plugins/KaleidoscopeMusicPlugin/songs/`，用 `/kmusic play 文件.mid` 播放。

酒桶先加入4桶同种液体，再加四槽以内原料，空手潜行封盖；果盆投料后跳踩；雪克杯投三份原料后空手右键开始、再次右键停止，空鸡尾酒杯取出。酒类调酒要求熟成4级以上。

本轮新增玩偶蓄力投掷/弹跳/骑乘/幻翼，三类葡萄，龙头装瓶和红石触发，手持摇杯/倒酒，以及Deco状态切换和沙发染色。厨房便当袋支持 24 槽、一次吃到饱、后续药水和果篮交换，610/612 来源配方已导入。旧版袋内预览和客户端外观仍待验证，见 `docs/LUNCH-BAG.md`。22 种座椅与 11 种连接桌已接入，桌面可摆四件物品；说明见 `docs/SEATING.md`、`docs/TABLES.md`，固体碰撞和水浸仍有差异。稻草块、油块及四条压缩/还原配方已加入，行为差异见 `docs/STORAGE-BLOCKS.md`。辣椒串/蘑菇串新增两段采摘和条件掉落，见 `docs/HANGING-STRINGS.md`。详细操作和原版客户端替代差异见 `docs/GAMEPLAY.md`。

`docs/PORTING-MATRIX.md` 保留全系列未完成项：精确玩偶物理/自定义Bedrock、野葡萄、燃烧瓶、剩余酒馆家具、Deco复杂脚本/连接、原音乐模型/合成器等。效果、存档、领地保护及Folia边界均需实服验收。

`BUILD-MANIFEST.json` 和 `SHA256SUMS.txt` 记录构建产物、测试和包内文件哈希；`organization-checkouts.json` 记录8个官方仓库来源提交；原资源说明和许可位于各 CE 内容包的 `upstream-notices/`。

有库存/加工中的家具拒绝普通拆除；外部强制移除若经过 CE 回调，会在对应插件目录 `recovery/` 保存 NBT 内容快照，需管理员核对后人工恢复，不会自动退款。不要用热加载插件工具测试这些存档流程。

长凳已接入同轴连接和乘坐，拆除/卸载清理座位，完整差异见 `docs/LONG-BENCH.md`。八仙桌四格事务、关联拆除和 16 状态已导入，回滚/区域/碰撞限制见 `docs/EIGHT-IMMORTALS-TABLE.md`。

红灯笼新增 14 级光照、来源模型与合成；稻草人保护修正为扩展方盒与完整实体边界相交。固体碰撞/客户端仍待验证，稻草人完整内容未导入，见 `docs/RED-LANTERN.md`。

下界合金菜刀锻造已接入，并补齐 CE 默认合并会覆盖的用户 custom_data；原生组件和玩家槽位行为仍待实测，见 `docs/KITCHEN-KNIFE-SMITHING.md`。

蒸笼支持带内容物品、双层合并和取消回滚、分层掉落及两条原料合成，见 `docs/STEAMER-PORTABLE.md`。本批 JAR 必须配套安装新版 CE 配置，以免旧空蒸笼 loot 重复掉落。实服和客户端仍待验证。

蒸笼交互与双端探针增量见 `docs/STEAMER-INTERACTION.md`。资源包生成不等于客户端视觉通过，缺模型/纹理警告仍需修复。

当前模型检查点见 `docs/DECO-MODELS.md`：1.21.1/26.3 六组服务器探针通过，仍有37条旧版模型转换警告及来源资源缺口。不要将服务器构建或工作流成功当作客户端完整复刻验收。

鱼架支持三条原鱼存储，空手按后进先出取出，拆除返还内容；掉落被取消时剩余物品保留在 Deco 的 `recovery/*.nbt` 供人工处理。玩法边界和本地测试证据见 `docs/FISHRACK.md`。
