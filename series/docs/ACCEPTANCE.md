# 测试服验收表

更新于 2026-11-19。当前证据包括局部服务端与协议客户端验证；全系列尚未标记为 `accepted`，下方未勾选的玩法仍未验收。不能用编译、单元测试或探针成功代替整个玩法闭环。全系列计划与换设备步骤见 [根 README](../../README.md#继续开发)。

## 燃烧瓶、酒馆展板与木椅单元检查点（2026-11-19）

本轮将 T05、T06、F04 记录为 `unit_tested`，分层结果见[玩法回归证据](../../docs/research/series-gameplay-regression-2026-11-19.json)。以下实现已有单元／静态合同，另列本轮有界原生探针结果；局部放置与进程内 NBT 往返不升级为整个玩法通过，也不将前序协议玩家或三个家具样例的正常重启证据套用到新 JAR。

- T05：[燃烧瓶投掷](../tavern/src/main/java/net/kaleidoscope/tavern/Molotov.java)使用永久 `minecraft:consumable` 组件与原生雪球，覆盖主副手、蓄力、放手、同区域火焰及保护；四种储物家具只接入燃烧瓶链，未完成其余瓶装物交互。飞行实体不跨卸载或重启保存。[投掷回归](../tavern/src/test/java/net/kaleidoscope/tavern/MolotovTest.java)、[物理回归](../tavern/src/test/java/net/kaleidoscope/tavern/MolotovPhysicsTest.java)、[储物回归](../tavern/src/test/java/net/kaleidoscope/tavern/MolotovControllerTest.java)与[配置合同](../tools/test_tavern_furniture.py)均有各自范围，不能视为真实投掷或保护插件验收。
- T06：[14 款酒馆文字展板](../tavern/src/main/java/net/kaleidoscope/tavern/SandwichBoardController.java)支持原生 Dialog、文字染色、发光／墨囊、蜡、花纹和对齐；最多保存 320 个 UTF-16 单元原文，最多显示 8 个 CE 文字行。普通拆下仅返当前花纹的空白展板，原文字不随掉落携带。[控制器](../tavern/src/test/java/net/kaleidoscope/tavern/SandwichBoardControllerTest.java)、[状态](../tavern/src/test/java/net/kaleidoscope/tavern/SandwichBoardStateTest.java)与[变换合同](../tools/test_sandwich_transform.py)测试不替代原版 Dialog 输入、实际字体和 GPU 画面，变换依据见[研究记录](../../docs/research/board-render-research.md)。这不是已有协议验收的 Deco 郁金香展板。
- F04：[木椅控制器](../deco/src/main/java/net/kaleidoscope/deco/WoodenSeatController.java)与[导入器](../tools/deco_seating.py)覆盖 12 木种椅子的空垫／16 色椅垫、12 木种静态厨娘凳及 24 条石切配方。厨娘凳按固定源模型，没有坐下或椅垫事件。[控制器测试](../deco/src/test/java/net/kaleidoscope/deco/WoodenSeatControllerTest.java)、[家族测试](../deco/src/test/java/net/kaleidoscope/deco/WoodenSeatsTest.java)及[来源测试](../tools/test_deco_seating.py)仅建立对应单元与导出证据。

本轮最终构建通过 **912 项 Java 测试**：Cookery 582、common 34、Doll 43、Tavern 175、Deco 64、MusicPlus 14，失败、错误和跳过均为 0。**257 项 Python**（根工具 179＋系列 78）和 **75 项 Node 场景契约测试**通过；厨房与系列两个来源导入 `--check` 也通过。上述数字属于构建、单元与静态／场景契约层；本轮原生运行范围另列于下。

同一组本轮五插件 JAR 在 Paper 132 的 `paper-run-final-2`（70.301 秒）与 Folia 14 的 `folia-run-final`（154.193 秒）各通过原生 CE 回归：每端核对 43 个定义、431 个变体与 235 个物品；实际放置并移除 9 件代表家具（2 款文字展板、1 把木椅、1 个厨娘凳、1 个地面燃烧瓶及 4 种储物家具），完成 9 次二进制 NBT 进程内往返，并生成、清理各 19 个真实储物显示实体。探针使用本次运行 UUID、所属区域调度、真实控制器及 CE 显示工厂；运行器要求资源工作流完成标记、ZIP CRC 和正常停服，两端退出码均为 0、`errors=[]`、没有强杀。这是服务端 API 放置及进程内保存／恢复，不是玩家交互或服务器重启验收。

两端最终托管包另经官方原版 1.21.11 客户端代码离线解码，每包均读取 **14,419 个资源**：1,023 个 blockstate、4,276 个物品定义、6,292 个模型、50 个其他二进制资源、161 个其他 JSON 和 2,617 张 PNG；20 个命名空间，活动 overlay 为 `ce_overlay_75-97`。这不证明账号登录、GPU 字体／画面、碰撞或音频正确。

静态报告的缺失引用与额外资源警告为 0，不等于运行日志无警告。两端各保留 **4 条上游模型越界 UV 提示**：`doll_271` 的 3 个面和 `doll_722` 的 1 个面；CE 提示可能影响 26.1 及以上客户端烘焙，当前目标仍为 1.21.11。这与先前已经解决的 5 条缺模型／纹理引用警告不同；固定上游 UV 没有为清空日志而被修改，未来客户端兼容与实际显示仍待验证。

首次 `paper-run-1` 与第二候选 `paper-run-final` 的真实失败、当时产物哈希与日志均保留在玩法回归证据中。最终修复依照 CE 原生时序：同几何变体切换不重复检查自身占位，展板在 `spawn` consumer 内的 `onLoad` 尚无有效元数据实体时延后刷新，再由插入后的 `onPlace` 恢复。成功结果没有覆盖失败历史，也不关闭历史 Folia watchdog 根因。

两处新增数据安全修复均不得扩大解释为运行通过。地面燃烧瓶先持久化唯一的完整来源物品意向，再调用 CE 原生销毁；同步移除回调结束后只安全写回原槽，原槽被占用则重新规划库存。移除／交付异常，以及原家具已移除后的交付取消、满包或离线，保留人工核对记录，必须先确认原家具和实际库存再恢复一次，绝不自动重放。木椅的玩家取消仅在 `FurnitureBreakEvent` 阶段阻止移除；真实 CE `destroy` 即使 `preRemove` 抛异常仍会销毁，因此已进入销毁后的未结算椅垫转存完整人工恢复记录，基础椅掉落独立结算，不声称原家具仍被保留。木椅单元夹具实际调用 CE `BukkitFurniture.destroy` 核对其正常与异常收尾。

上述新功能的真实玩家、正常重启、独立卸载／热重载、第三方保护、Folia 跨区及原版客户端渲染均为 OPEN，具体未勾选项见下方酒馆和 Deco 清单。全系列 `accepted=[]`，E06 仍 `blocked`；历史启动 watchdog 根因没有关闭。

Chef 边界单列于[固定源码与原版客户端研究](../../docs/research/cookery-chef-vanilla-boundary-2026-11-19.md)：仅靠数据包与资源包无法给原版 1.21.11 注册独立 Chef 职业；替换既有职业或静态 NPC 不等于完整 Chef。五类村庄厨房结构（NBT、processor、loot 和生成池）是另一项尚未实现的工程，不因职业硬边界而视为已处理。酒馆旧清单中的“冰桶”尚未在固定来源识别对应注册／类，规格待源识别，不声明已有实现。

## 本轮原生回归复现

入口为 [SeriesGameplaySmoke 原生探针](../../src/acceptance/java/net/kaleidoscope/cookery/acceptance/SeriesGameplaySmoke.java)与 [run_series_gameplay.py 运行器](../../tools/acceptance/run_series_gameplay.py)。先按[隔离服准备](../../README.md#隔离服准备)安装本轮构建的五插件、五玩法内容包和共享包，使用 Java 21、Paper 132／Folia 14、CE 26.9.1、`--server-blocks 8000 --early-pack-load`。`--base-resources` 只能来自单独整理的五个 CE 基础目录：`default_assets`、`default_feature_populator`、`default_templates`、`internal`、`legacy_armor`；不要把包含旧 `doll/tavern/deco/music/shared` 的整份运行资源目录当作基础资源覆盖新内容包。

将 `acceptanceJar` 产出的 `build/acceptance/probe/CookeryAcceptanceProbe.jar` 安装到各实例 `plugins/`，在 `plugins/CookeryAcceptanceProbe/config.yml` 显式设置 `series-gameplay-smoke.enabled: true`。首次启动前还须把仓库 [`examples/cookery-enchantments`](../../examples/cookery-enchantments) 数据包完整安装至各实例 `acceptance-world/datapacks/cookery_enchantments/`，不能关闭附魔探针来掩盖缺包。只使用操作者已经明确接受 EULA 的隔离实例；运行器核对现有 `eula=true`，不会自动接受，也不替代该决定。实例必须在 `build/acceptance/` 内，绑定 `127.0.0.1`、`level-name=acceptance-world` 且 `enable-rcon=false`。

以下为已准备实例的 PowerShell 复跑示例，`JAVA_HOME` 须指向 JDK 21；每次使用新的输出目录保留原有结果，两端顺序运行：

```powershell
$java21 = Join-Path $env:JAVA_HOME 'bin\java.exe'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
.\.venv\Scripts\python.exe tools\acceptance\run_series_gameplay.py build\acceptance\gameplay\paper "build\acceptance\gameplay\paper-rerun-$stamp" --java $java21
.\.venv\Scripts\python.exe tools\acceptance\run_series_gameplay.py build\acceptance\gameplay\folia "build\acceptance\gameplay\folia-rerun-$stamp" --java $java21
```

每次运行以新 UUID 关联当前探针和当前生成包，等待完整工作流标记并核对 ZIP CRC；最多等待 300 秒，再为正常 `stop` 留出 60 秒，不承诺固定完成时间。最终结果以各新目录的 `result.json`、`series-gameplay-smoke.json` 和 `console.log` 为准；进程内 NBT 往返不能当作跨服务器重启保存验证。

## 当前单元与场景契约回归（2026-11-19）

前序礼盒与效果检查点当时通过 **745 项 Java、223 项 Python、75 项 Node 场景契约测试**。Node 契约仅使用内置模块，不启动 Minecraft 服务端或协议客户端，可运行 `node --test tools/acceptance/connected/test-persistence.cjs` 复核。该检查点新增 50 项 Java 回归，分层结果及构建哈希见[礼盒与效果回归证据](../../docs/research/series-effects-gift-regression-2026-11-19.json)；当时没有启动实服，下面的双端运行记录保留历史提交范围。

- [礼盒 17 项](../doll/src/test/java/net/kaleidoscope/doll/GiftBoxesTest.java)覆盖错误类型 PDC 原样保留、主手快照及提交前状态复核。关键切槽、禁用、取消与同步重入用真实 `InventoryTrade` 和模拟 Bukkit 事件，两项额外测试单独隔离 `beforeCommit` 的组件／数量检查；不声称已完成真实玩家开盒。
- [血腥玛丽 18 项](../tavern/src/test/java/net/kaleidoscope/tavern/BloodyMaryAttributionTest.java)以模拟 `DamageSource` 验证当前玩家／投射物来源、历史 kill credit 拒绝、先前取消死亡、可取消／改额治疗及回调后守卫；保持同区域限制，不替代实服投射物或跨区验收。
- [农夫套装 15 项](../../src/test/java/net/kaleidoscope/cookery/effect/FarmerOutfitTest.java)覆盖解码后装备 ID、槽位、药效参数和玩家实体任务生命周期。复用现有 `InventoryUtils.wrap` 的 mock，不验证实际 CE 物品解码、真人游泳或 GPU 显示；拒绝药效申请后不强制覆盖，离水／卸甲不主动删除其他来源效果。

- 缺少自身存档键的新家具保持默认空状态；已存在但类型错误或内容损坏的键冻结操作并保留原始数据。[抽奖机回归](../doll/src/test/java/net/kaleidoscope/doll/LotteryRestoreTest.java)与[酒馆回归](../tavern/src/test/java/net/kaleidoscope/tavern/TavernPersistenceTest.java)分别覆盖这些分支。
- [沙发](../deco/src/test/java/net/kaleidoscope/deco/DecoControllerTest.java)与[玩偶九轴](../doll/src/test/java/net/kaleidoscope/doll/DollItemRestoreTest.java)通过 CE `FurniturePersistentData` 字节往返后，在没有物品放置回调的情况下恢复控制器状态、显示及未来掉落物。雪克杯晚读取物品 PDC 后也重新同步源物品；编码失败或坏 PDC 在改变内容前拒绝并保留原物品，见酒馆回归。
- 家具 ticker 通过元数据实体和 UUID 找到当前有效包装，再按控制器类型及同类型出现序号分派；不退回旧控制器。覆盖行为槽位重排、同类型多实例、移除其中一项、卸载和实体 ID 复用，见[调度回归](../common/src/test/java/net/kaleidoscope/series/FurnitureTickersTest.java)。
- 场景契约覆盖重启前后精确状态与库存校验、沙发及玩偶真实掉落位置跟踪、拾取 PDC 和唯一数量、果盆取回后重新选空手、重复返回拒绝；模拟通过不作为真实服务器重启通过的依据。
- 掉落追踪使用实时网络实体位置；实体被移除或同 ID 已对应其他对象时，只停止追逐旧位置并释放前进控制，不能据此认定拾取成功。随后仍必须从服务器读取所有真实参与者库存，核对实际归属、合计唯一一件及原始 PDC。契约覆盖同行玩家途中拾取、实体消失却无人持有、ID 复用、拾取后 PDC 损坏；行走期间只检查网络实体，不逐帧发送服务器库存查询。
- 单次家具操作发送前读取 `furnitureInteractions` 基线，动作只发送一次，随后有界、只读轮询；必须同时观察 `FurnitureInteractEvent` 的 MONITOR 回执计数递增和预期状态／数量。对有效目标的取消和空操作也需要本次回执，每轮检查状态、材料与掉落不变量，不能以未处理动作的旧快照通过。契约负例覆盖缺少回执、回执与状态分步抵达、永不提交及非法扣料。
- 展板并发收割先捕获两名玩家的基线，再各向同一旧命中框发送一次请求，分别记录 `attemptedPlayers` 与实际收到事件回执的 `acknowledged`。CE 第一剪切换展板状态时可能更换命中框，第二个旧 ID 请求可因目标失效而没有事件；仅在至少一人回执递增、双方确认空展板与唯一一朵掉落，并有一致的新命中框 ID 与旧 ID 不同的证据时，才允许缺少第二人回执。随后 B 对新 ID 的空剪仍必须收到独立回执且不增产，为其先前旧 ID 请求提供同连接的顺序确认。没有记录唯一返花掉落前，双方存储槽库存必须完全不变；记录后仅允许双方合计该花增加 0 或 1，且新增物品的类型与组件必须精确等于记录的原掉落，其他槽位、数量与组件均不变。跨玩家或多槽重复、错误花／PDC、无掉落记录前提前增加均拒绝；零回执、未换 ID 却缺回执或多份掉落也拒绝，不重发旧目标请求，也不声称两人均进入控制器。
- 展板收尾在原唯一掉落与 B 对新命中框空剪验证后、`clearboard` 和沙发 setup 前完成。36 个存储槽已占用仍允许返花自然合并到原同类堆叠；若尚未持有该花，才将 A 热栏第 3 格的 64 圆石正常移入空副手，腾出一格，不额外丢物。动作仅发送一次，服务器存储槽／光标快照显式容许圆石 64 仍在光标上的合法过渡，但该状态不能完成准备或开始取花。待光标清空且存储槽腾空后，还须由客户端网络库存观察确认副手圆石 64；探针不读取副手，不能据此声称服务端已验证副手及其全部组件。最终由服务器双人存储槽差分确认原返花恰好增加 1，再进入下一场景；网络掉落实体消失仍只用于停止行走。
- 玩偶九轴每次仅点击一个轴，先核对服务器完整递进姿态，再等待新的菜单窗口 ID，才继续下一轴。姿态未提交或菜单未刷新时均有界失败，不重发点击；契约测试与下方真实双端重启证据分别记录。

这些回归不代替对应玩法的实服、原版客户端、第三方保护或 Folia 跨区域验收。

## 三个家具样例的正常重启检查点（2026-11-19）

提交 `01778f8` 的历史候选在 Paper 132 的 `paper-restart-12` 与 Folia 14 的 `folia-restart-3` 各完成 `seed → verify → empty` 三阶段正常启动，共六次通过。两端使用相同场景代码与当时五插件＋探针六份构建 JAR；各端三阶段的运行资源哈希稳定，服务端／客户端退出码均为 0，最终六次日志无服务器错误或 watchdog。每端两名离线生存协议玩家在每阶段各读取 14,203 个资源；这是官方客户端代码的无图形解码，不是 GPU 显示、听音或账号认证验收。完整产物、场景、日志、检查点和失败历史哈希见[本轮三阶段重启证据](../../docs/research/series-restart-acceptance-2026-11-19.json)。

- `seed` 由玩家操作保留红色左段沙发、`doll_0` 固定九轴姿态（缩放 1.1／偏移 0.05／旋转 15°，各三轴）及果盆中单个带 marker、尚未加工的发光浆果（液量 0）。
- `verify` 在正常重启后先只读核对原家具身份、位置、保存状态与原物品 PDC，不重建样例或借物品放置回调恢复；随后玩家实际拆取沙发和玩偶。双方服务器存储槽合计各只有一件对应物品，实际归属和 PDC 与检查点一致。果盆原料恰取回 1，重新选空手后再次取回为 0。
- `empty` 再正常启动同一世界，核对已拆的沙发／玩偶未复活、果盆仍空、双方持有量保持精确一致，并继续观察 2 秒无重复产出。

这只关闭以上三个样例的正常重启、回收及空状态再启动检查，不关闭其他沙发颜色／形态、其他玩偶或姿态、其他果盆原料／加工状态／液量，也不代替酒桶、龙头、雪克杯、抽奖机完整玩家流程。独立区块卸载、热重载、爆炸、第三方保护、Folia 跨区域、密度／性能及客户端显示继续 OPEN。五模块仍为 `runtime-tested`，全系列 `accepted=[]`，E06 上游发布许可证仍 `blocked`。

历史启动 watchdog 的根因仍 OPEN：Folia2 真实记录全局区域卡顿 **6.3533891 秒**，栈位于 CE shaded ByteBuddy 插桩、`FastNMSImpl.createSmithingTrimRecipe` 与延迟配方加载链。该轮同时因展板返花进入后续沙发库存而失败；原错误栈和断言均保留，不能以退出码 0 或最终六次干净启动抹去，也不能据后者宣称 CE watchdog 根因已修复。早期不同探针／场景哈希的成功记录仅作历史，不作为最终候选通过证据。

## 可靠性修复历史检查点（2026-11-19）

当时通过 668 项 Java、214 项 Python 测试。该检查点修复家具交易取消、热栏切换与同步重入下的提交顺序，抽奖掉落实体校验、玩偶回收和收藏通知重入，九轴姿态在 CE 晚读物品 PDC 后的显示刷新，MIDI 精确计时与播放任务清理，以及联网验收运行器的失败判定和子进程收尾。历史修复范围与分层证据见[可靠性修复记录](../../docs/research/series-polish-acceptance-2026-11-19.json)。

## 双人协议验收（2026-11-19）

该历史检查点在 Paper 132/Folia 14 各两名普通协议玩家下通过沙发取消／换槽／同步重入及红色左段拆装重放，`doll_0` 经真实菜单增加全部九轴后拆装重放保持数值，两类家具各只有一件掉落。原锻造、蒸笼、稻草人和展板一并复跑；每位客户端读取 14,203 个资源，两端均正常停服且无服务器错误或 watchdog。首次失败与修复后产物／日志哈希见[当时双端证据](../../docs/research/series-polish-acceptance-2026-11-19.json)。该历史检查点不覆盖服务器重启，三个样例的当前重启证据单列于上方；GPU 显示和账号认证仍未验收。

Paper/Folia 的联网普通玩家流程已通过以下范围：资源包下载和无图形界面的官方解码、厨刀普通和 Shift 锻造及取消、蒸笼四槽守恒、保护取消后两人连续击打稻草人。见[两端实测证据](../../docs/research/series-connected-2026-11-19.json)。已登录原版客户端的画面、听音，和其他玩家玩法仍需逐项验收。

Deco 郁金香展板的装饰与剪取返花也已在 Paper 132/Folia 14 完成双人生存协议验证，覆盖满背包、交互取消、掉落取消与两人连续剪取，见[展板验收证据](../../docs/research/deco-tulip-acceptance-2026-11-19.json)。

## 盖饭 native 合成历史检查点（2026-11-16）

- 319 Java、102 Python 通过；Paper/Folia 各三轮 native recipe 探针通过并正常停服。5 条固定来源 `rice_bowl` 配方覆盖 125 组品质、额外格/重复/错配拒绝、15 次连续合成、熟米饭返碗和所有原生余料。
- 两端各保存 15 个盖饭和 15 个碗，重启核对结果组件哈希；清空箱子后无重复产出。验证使用服务端 recipe registry 和 `Bukkit.craftItemResult`，没有创建玩家或自动合成器。
- Shift 合成、2×2 玩家界面、crafter、客户端配方书、外部可选米饭和真人背包仍未验收。详细边界与失败历史见 [盖饭摘要](../../docs/research/cookery-rice-bowls-acceptance-2026-11-16.json)。

## 38 种直接食用大菜历史检查点（2026-11-15）

- 319 Java/102 Python 通过；最终 v5 Paper/Folia 各三轮 PASS 并正常停服。大菜 38 种/49 格/1,024 状态验证覆盖阻挡放置、首口保存、品质/组 UUID 重启、剩余 93 口、耗尽保留容器、整菜与额外掉落及子格破坏，第三轮 49 格无复活重复。取消/过期回调/坏档均拒绝且不改状态。
- 当时原 24 厨具、取物盘、竹筛和加工回归继续通过；该检查点最终包为 527 物品/62 方块/1,442 状态，532/612 来源导入，80 pending。1,718 源包文件与额外图标配置单列核对，539 可达模型/全包 1,260 loader 扫描通过。当前厨房已导入 612/612 条来源配方，导入完成不代表逐条玩家合成与原料链验收。
- 消费探针是控制器提交 API，无真人：玩家营养、效果及 FOOD 事件归因仅有单元/API 签名证据；碰撞/粒子、客户端接包/画面、保护多人、独立卸载/热重载/爆炸、跨区仍待验收。详见 [最终摘要](../../docs/research/cookery-placed-meals-acceptance-2026-11-15.json) 与 [验证记录](../../docs/VALIDATION.md)。

## 11 种整份取物盘（2026-11-15，前序）

- 203 Java、86 Python 通过；`plate-runtime-20261115-v2` Paper/Folia 各三轮 PASS、正常停服并释放 25583/25584。每端满盘取出 48 份/52 食物且保留 11 空盘，重启后 10 单盘补料只增 1，浆果双产且拒绝单种补料；两批空盘/部分盘移除分别恰好 11 碗，部分盘余食物合计 40，无满盘重复掉落。
- 第三轮移除位置不复活、不重复产出；原厨房、石磨和竹筛回归保留。首次 v1 未操作盘在关服时查已清空的 CE 注册表，导致整区块保存失败；现提前构造物品快照，旧首轮错误 PASS 已作废，实际失败与复验均归档。
- 运行路径是控制器 API、真实 CE 方块移除和物品实体/事件核对，不是玩家操作。旧 schema 1 迁移、取消放置与 callback 重入/异常有单元回归，旧真档、保护插件、多人、客户端、跨区及独立卸载仍未验收。
- 这 11 种取物盘不同于 38 种直接食用大菜（注册表 37＋冷切火腿片 1）。取物盘检查点早于大菜接入，当时后者对应 CE 方块为 0；后续接入与探针结果见上方大菜历史检查点。完整结果见 [取物盘摘要](../../docs/research/cookery-plate-acceptance-2026-11-15.json)、[验证记录](../../docs/VALIDATION.md) 和作为接入前快照保留的 [大菜差异清单](../../docs/research/cookery-food-bite-gap-2026-11-15.json)。

## 竹筛检查点（2026-11-15，前序）

- 179 Java、81 Python 通过；Paper/Folia 1.21.11 + CE 26.9.1 各三轮通过并正常停服。运行器端口 25583/25584 已释放。
- 四个竹筛中途存档与重启续作、露天/玻璃/实心顶/叠筛、有源/无源滴水通过；样例产出为海绵 2、干海带 3、泥巴 2、黏土 1。取 1 海绵后剩余 1 个反向润湿；底漏斗取消至少两次后放行，恰好抽出 1 个湿海绵；第三轮空槽不重复产出。
- 28 竹筛配方验证注册及产物构造，未逐一实际加工。原 103 消费品、5 附魔、219 加工/219 炒锅、65 原版配方及真实牛驱动石磨回归均通过；20 炉具配方额外核对类型、时长和经验。
- 六轮 JAR 与资源哈希、源包文件、351 厨房可达模型及全包 1,251 模型 loader 检查通过；ItemIcons 生成的额外字体配置单列。首次营火类型、Paper 二次映射及石磨探针时序失败保留，详见 [竹筛验收摘要](../../docs/research/cookery-bamboo-acceptance.json) 和 [验证记录](../../docs/VALIDATION.md)。
- 玩家/客户端、降雨、完整自动化、保护/多人、完整组件、独立卸载、破坏/爆炸和跨 Folia 区域仍未验收；本轮不升级整个厨房或全系列为 `accepted`。历史 CE 冷启动 watchdog 继续 OPEN。

## 独立服准备入口（2026-11-08）

验收层级和五个模块的当前状态见 [`acceptance-matrix.json`](acceptance-matrix.json)。完成系列 JAR 与独立 CE ZIP 构建后，可用下面的命令生成 Paper/Folia 两个隔离目录、安装清单和 SHA-256；命令只复制文件，不启动服务器，也不会自动接受 EULA：

```bash
PYTHONPATH=series/tools python series/tools/check_acceptance_matrix.py
PYTHONPATH=series/tools python series/tools/prepare_independent_servers.py \
  --root build/acceptance/series-independent \
  --artifacts series \
  --packs series/build/distribution/ce-packs-<run> \
  --craft-engine build/acceptance/runtime/craft-engine-paper-plugin-26.9.1.jar \
  --base-resources build/acceptance/runtime/craftengine-resources
```

如果操作者已经明确接受 EULA，再显式添加 `--accept-eula`；如果要运行服，另外提供 `--paper-jar` 和 `--folia-jar`。生成的 `INSTALL-MANIFEST.json` 中 `startup/resourcePack/vanillaClient` 初始均为 `NOT_RUN`，必须用实际日志、客户端和重启记录逐项更新，不能从安装成功推导运行通过。目录已存在或不在 `build/acceptance` 下时工具会拒绝操作。

## 石磨检查点（2026-11-06，历史）

- 132 Java、71 Python 通过；85 石磨来源中 82 条展开为 87 配方，全厨房 462/612 imported、150 pending。3 条石磨缺外部 gravel/sand 标签单列，未知结构/父级模型变换拒绝导入。
- 271 物品、12 方块、180 状态、1 石磨家具；4 个显示部件、12 个源 cube、18 音效别名。保留插件动画锚点、旧圈数和已有计划；官方抬升、精确碰撞及客户端画面未对齐。
- 取料/完成转移闸门、失败输出回滚、异常与取消后持久化阻塞已实现；14 项石磨单元回归通过，不替代玩家背包和保护插件实测。
- 首轮 `millstone-v1` Paper 三轮通过，Folia 第二轮真实续磨失败。只读诊断确认 CE ticker 仍引用旧家具；石磨 Folia ticker 现通过公开映射定位当前控制器，保留单任务调度。失败日志和先红后绿测试均保留。
- 修复版 `millstone-v2` Paper/Folia 各三轮 PASS 并正常停服；实际牛驱动、重启续磨恰好 10 骨粉、空槽重启无重复通过。双端各 1,709 安装文件和 manifest 一致，生成 1,251 模型无 Forge loader。JAR 与资源哈希见 `docs/research/cookery-millstone-acceptance.json` 和根目录 `docs/VALIDATION.md`。
- Folia 历史启动 watchdog 继续 OPEN；玩家、客户端接包、保护/多人、跨区域与独立区块卸载仍 NOT_TESTED。其他模块不因此提高完成度。

## 炒锅检查点（2026-11-06，前序证据）

- 118 Java、64 Python 通过；612 来源中 380 导入、232 pending。219 炒锅＝195 精准＋24 具体物品去重，保留旧余弦、品质、旧批次默认翻炒阈值。官方时间只作来源数据，完成仍遵循原插件翻炒动画。
- `pot-v1` 首轮 Paper 因解析器先于 CE 物品加载失败；依赖修复和回归后 `pot-v2` Paper cycle 1–4 全通过，Folia 1/3/4/5 通过。最终 JAR 由 Paper 4/Folia 5 验证，旧产物保留。
- 两锅炒一次后停服，重启续炒完成 3 烤土豆/1 拔丝土豆，分别取一份后再次重启为 2/0；空锅无重复，油消耗/容器属性及记录本序列化通过。原 132 加工＋33 合成、103 食物、5 附魔回归继续通过。
- 盛出取消/空产物/同步回调改余量的单元回归先失败后通过；控制器防重入与每份标脏已接入，不能代替真实玩家背包和保护插件验收。
- 264 物品/12 方块/180 状态；1,705 安装文件和 manifest 一致，生成包各 1,247 模型无 Forge loader；重复导出 ZIP 相同。包 SHA-256 `f59fd9beeb7abd95ad34cd6c7d9411c1f7660977409b875c1d6c97835a690091`；详见 `docs/research/cookery-pot-acceptance.json`。
- Folia cycle 2 再次触发启动 watchdog 5.2812568 秒，`CraftEngine.runDelayTasks().join()` 采样，未进入玩法探针；仍 `PARTIAL_FOLIA_STARTUP_WATCHDOG_OPEN`。之前冷启动失败同样保留，未豁免 ERROR。
- 实际原版客户端、多人、保护、跨区域、独立区块卸载、容器扣除/余物仍 NOT_TESTED；其他系列不因此升级完成度。

## 高汤锅检查点（2026-11-05，前序证据）

- 105 Java、61 Python 全通过，包 263 物品/12 方块/180 状态；161/612 来源配方已导出为 165 配置，451 pending。
- 新增 75 精准多重集与 26 种类去重配方，旧余弦回退及 v1 存档兼容；官方烹饪/成品/米饭汤面、颜色和份数高度随批次保存。菜单可只读查看九格/备选原料，说明独立于物品 lore；记录本序列化通过。
- `build/acceptance/stockpot-v1`：Paper cycle 1–5、Folia cycle 2–5 通过。最终 JAR 为 cycle 5 哈希；双端 132 加工计划/33 合成构造、两锅中途存档续煮、取后剩余 2/0 份、米饭液面 -0.105、空锅防重复通过。
- Folia 冷启动 cycle 1 仍 FAIL：global region watchdog 5.7309607 秒，CEWorld/BukkitWorldManager.delayedInit 采样，根因未确定。后续通过不覆盖失败。
- 1,705 个安装文件与最终 ZIP manifest 一致；重新导出逐字节一致；两端生成 1,247 模型无 Forge loader。证据 `docs/research/cookery-stockpot-acceptance.json`，最终包 SHA-256 `8a5d15b1dbbeea64bdcff015c574a7823ff41e21315e9ccb0ad2f567d8862e33`。
- 仍非实际玩家/原版客户端验收：保护、多人、事件取消/重入、物品余物和动物桶 NBT 等待补。其余系列状态不因此升级。

## 厨房配方前序检查点（历史证据）

- 94 项 Java、58 项 Python 测试通过；固定来源 612 配方全部可追踪，47 条导出为 51 个插件配方，565 条继续 pending。
- 252 物品、12 方块、180 状态，两端安装资源 1,702 文件哈希一致；8 茶杯去底板、64 切割阶段、6 字形和 9 汤底映射已加载。牛奶往返茶壶保留旧存档和品质规则。
- `build/acceptance/recipes-v2`：Paper cycle 1/2/3、Folia cycle 2/3 通过。真实控制器 tick 验证中途存档重启后 4 蒸食、8 份酥油茶、3 小肉块＋1 骨头单次掉落，再次重启成品保留；31 加工计划和 20 合成配方注册/输出核验通过。
- Folia cycle 1 仍因 5.4575362 秒 global watchdog 判 FAIL，采样在 CE 字体偏移缓存类加载；后续成功不关闭冷启动缺陷。摘要 `docs/research/cookery-recipes-acceptance.json`，完整命令/限制见根目录 `docs/VALIDATION.md`。
- API/存档注入不是玩家操作；原版接包、合成网格/容器余物、多人守恒、保护取消和 Folia 区域边界仍未验收。汤面只补现有控制器基础显示，每配方纹理/官方动态高度待补。

## 前序厨房自动验证与边界（保留历史证据）

- 基线：Paper `1.21.11-132-c5eb079`、Folia `1.21.11-14-529aabc`、Java 21、CraftEngine `26.9.1`。
- 固定源码：`2f4e386ce23f49a385ddf003c67fc6415c55417a`，导入 JSON SHA-256 `d7b5e10c9a6d51f458804d4454ec8eac174f24ce22bb9eda40eea4c50146ba25`。
- 两套既有本地服分别监听 `127.0.0.1:25581/25582`；食物探针均 `checkedConsumables: 103`、`failures: []`。
- 新附魔验收使用独立副本 `25583/25584`，自动完成启动、序列化、重启读取、CE 资源生成和正常停服。当前轮次及 JAR/包哈希见根目录 `docs/VALIDATION.md` 和对应 `build/acceptance/.../acceptance.json`，不使用历史 manifest 的 `NOT_RUN` 判断当前运行状态。
- 本轮 Paper 两次、Folia 后续两次启动均完成 103 个食物和 5 个附魔等级探针；重启后附魔物品重读 5/5。Folia 首次 CE 冷启动触发 5.4128759 秒 global region watchdog，已保留失败日志，问题仍 OPEN。临时副本均正常停服。
- 84 项 Java 测试通过，包括 9 项效果生命周期回归和 31 项本轮附魔/砧板/镰刀测试；这是单元测试证据。
- 已修复效果清除/tick 期间监听器新增状态被旧快照覆盖、`spend()` 绕过可取消事件等问题。测试不能替代真实牛奶、死亡、换世界、保护插件和区域边界操作。
- **厨具资源推进**：新验收包补齐上述 11 个定义及油壶，共 12 方块、180 状态、165 物品；Paper/Folia 最新探针全部构造/序列化通过，12 方块跨重启保留，搪瓷盆 7 份油和油壶 87 份油读回正确。原版玩家放置、多方块物品行为和交互仍未测。
- `build/acceptance/kitchen-v1` 两服均已正常停服，安装资源 1,618 文件哈希一致，生成包无 Forge loader。Folia 首次冷启动再次触发 5.1684424 秒 watchdog，严格判失败，后续重启通过不能关闭该问题。详见 `docs/research/cookery-kitchen-acceptance.json`。
- **剩余资源差异**：共享细绊线碰撞、茶壶支撑模型近似、水浸、完整配方、茶杯/高汤液面和摆盘尚未闭环；完整工具/附魔书/铁砧仍需对齐。
- [ ] 原版玩家接收资源包，进食/喝茶/喝奶并核对概率、计时和容器守恒。
- [ ] 主副手菜刀 0/I/II、进度保存、末刀产出与保护取消；镰刀 0/I/II/III 的范围、耐久、冷却和逐格保护。
- [ ] 两玩家同时操作同一厨具、区块卸载、坏 PDC、Folia 区域边界和完整资源表现。

## 启动与资源

- [ ] 新四个插件启动无异常，所有自定义家具行为注册成功，无 Unknown behavior/item/recipe。
- [ ] CE 加载五个内容目录，生成客户端包无模型/纹理/组件错误，原版客户端可接收并进服。
- [ ] `/kdoll kit`、`/ktavern kit`、`/kdeco`、`/kmusic kit` 图标可见、物品可放置，地图区块卸载重载后不消失。
- [ ] 微调后只显示一只玩偶，换区块/重进服/重启后姿态保存；微调碰撞差异已确认。
- [ ] 厨房旧存档、旧命名空间、原有模型与新增内容同时正常。

## 物品守恒与保护

- [ ] 普通玩家不能使用 OP 领取权限；领地内外投料、兑换、取物、微调权限正确。
- [ ] 满背包、部分空位、元数据不同的材料、主副手、连点、Shift点击、拖拽、创造菜单不能复制物品。
- [ ] 付款不足/返还容器放不下/奖励缺失时不扣材料；一次点击只结算一次。
- [ ] 抽奖中重启、奖品实体生成取消、满奖池洗牌后保存恢复均不吞付费奖品。
- [ ] 库存家具普通拆除被取消；强制移除触发 recovery 快照，快照能被管理员读回，未自动重复退款。
- [ ] 测试副本损坏 NBT 后操作被阻止；保存未覆盖原始坏数据。

## 酒馆

- [ ] 燃烧瓶主副手蓄力／放手、取消／中断／换槽、生成或命中被拒绝时物品与组件正确；永久 consumable 组件不临时污染其他物品，同区域火焰遵守真实保护与伤害事件。
- [ ] 瓶架、斜酒架、圆酒架和地窖柜逐一验证燃烧瓶放入／取回、红石触发、取消及两人操作的唯一扣料；该验收不扩展为其他瓶装物交互。飞行雪球卸载、移除、正常停启后不复活、不重复结算。
- [ ] 地面燃烧瓶回收时，原生销毁回调切热栏／改原槽／改其他库存不被覆盖；原槽占用时重新规划交付；已移除后的交付取消、满包、离线、禁用与异常保留唯一完整来源物品的人工核对记录，原生移除结果不确定也必须先人工核对。成功交付后不重复发放；残留或交付不确定记录必须核对世界与库存，不能自动退款或重放。
- [ ] 14 款文字展板原生 Dialog 编辑、原文 320 UTF-16 单元边界、Unicode 和按字面显示的标签正确；最多 8 行显示且保留完整原文，原版字体下左／中／右对齐、倾角和行距可读。
- [ ] 文字展板染色、发光／墨囊、花纹与封蜡只扣一份有效主手材料；同状态、创造、取消、同步重入和过期 Dialog 不误耗。编辑者身份、距离、状态版本、保护与 Folia 所有权变化时拒绝旧回调。
- [ ] 文字展板保存并正常重启后文字、颜色、花纹、蜡与对齐一致；普通拆下恰好一件当前花纹空白展板，重放不恢复旧文字，空状态再启动不复活。独立卸载／热重载和第三方保护另行记录。
- [ ] 果盆0.5格以上跳踩、不同果汁、满盆、取回原料和桶取汁均符合规则。
- [ ] 酒桶四槽计数、4桶液体、97 tick熟成和升档边界、失败醋产量、瓶装品质正确。
- [ ] 三份调酒原料、品质4门槛、容器返还和19/69/89/99/111 tick边界正确。
- [ ] 调酒中换人、区块卸载、重启、中断、输出缺失、空杯/满包取酒不丢失或重复产出。
- [ ] 放置雪克杯装料→潜行转装→持续右键→松手→倒入放置空杯→空杯交换取酒，全程只产出一杯。
- [ ] 手持摇动期间换槽/换手/丢弃/背包拖拽被阻止；死亡、传送、退出、111 tick超时以及停服重启无残留使用状态或多次结算。
- [ ] 装料雪克杯放置→清空→拆除→重新放置不恢复旧原料；改名/附魔等元数据保留；坏PDC和超限编码不会先扣料。
- [ ] 普通/冰/金葡萄的土壤、温度、年龄、骨粉、3个葡萄和额外青提掉落、未成熟剪刀提示、潜行移藤、封蜡和除蜡符合文档。
- [ ] randomTickSpeed=0停止自动成长；跨区块、同/不同所有者藤架扩散、离线保护限制、下方方块阻挡、重启存档符合预期；种植中的藤架拒绝普通拆除。
- [ ] 龙头朝向后方来源、正下方空瓶，30 tick完成；重复开启、关闭、满瓶、资源缺失、卸载/重启中断均不双扣或双产。
- [ ] 酒桶扣一份酒；蜂巢扣一级蜂蜜；水为原版WATER药水，蜂蜜瓶/龙息为原版物品；西瓜/水/龙头方块来源不被消耗。
- [ ] 红石上升沿一次提取；操作者离线、无权限、Folia跨区域时拒绝；水/岩浆炼药锅填充可被CauldronLevelChangeEvent取消，岩浆复制默认关闭。
- [ ] 原版饮用、37效果表、特调合并、饮用取消、牛奶、死亡、退出/重登正确；12自定义效果逐项对照 PORTING-MATRIX.md。
- [ ] 醇热不绕过领地/破坏事件；尖啸不绕过伤害保护；取消掉落不卸下生物装备。

## 玩偶和 Deco

- [ ] 玩偶5 tick起可投，蓄力35 tick达到速度上限；松手、受保护区域、生成取消、满包回收不会复制或吞物品。
- [ ] 投出实体和剩余物品保留原元数据，无临时使用组件污染；死亡掉落、退出、传送和正常停启后可继续操作。
- [ ] 弹跳衰减、实体击退、攻击回收、骑乘层数和防取装备正常；骑乘目标的领地放置/交互限制生效。
- [ ] 将测试配置的幻翼概率设为1确认携带生成，恢复默认0.03；卸载重载不重复生成，脱离后寿命按配置累计；原版盔甲架显示/碰撞差异已确认。
- [ ] Deco状态工具、潜行/物品/标签条件及状态循环正确；需额外脚本的事件提示未实现并保留状态。
- [ ] 沙发16色/清色和四种形态正确；同色不扣染料，创造不扣，水桶不消耗；拆装/重启保存外观和源物品状态。
- [ ] Deco无匹配规则时原生座位仍能使用；切换形态后座位/碰撞无错位；坏存档冻结且保留原记录。
- [ ] 12 木种椅子逐一验证空垫／16 色垫、主手定点扣一、同色无操作；更早的同类物品堆不代扣，创造不消耗／返料。剪刀交互的退款掉落取消时保留原垫和材料；拆家具已进入原生销毁时按下一项处理。
- [ ] 玩家 `FurnitureBreakEvent` 取消仍保留木椅；强制移除或已进入 CE `destroy` 后遇未结算椅垫、坏档／忙态、退款掉落取消或改写，完整状态只存人工恢复记录，基础椅原生掉落另计。原家具不宣称保留；同一控制器不重复返垫，记录不自动恢复，落盘失败须有完整紧急状态和严重日志供核对。
- [ ] 木椅保护取消、两人操作、换槽／同步重入、坏存档、拆下／重放与正常重启保持状态和物品守恒；模型、坐姿、碰撞及不同朝向由原版客户端核对，独立卸载／热重载／跨区另行记录。
- [ ] 12 木种厨娘凳保持源静态模型，不出现来源没有的坐下或椅垫事件；椅子与厨娘凳合计 24 条石切配方逐条由真实玩家验证材料标签、单次产量和客户端显示。

## 音乐和并发

- [ ] 普通玩家演奏权限、重复播放/停止、换掉乐器、离服、重载后无残留任务。
- [ ] 合法多轨 MIDI、速度变化、SMPTE、密集音符、损坏文件、超4MiB文件和越界路径均有预期结果。
- [ ] Folia区域边界运行果盆、效果、GUI和音乐没有线程所有权异常；两个玩家同时操作同一机器无双付/双领。
- [ ] 按目标在线人数和家具密度记录 MSPT、内存、数据包数量和资源包体积；未测得数据前不承诺性能。
