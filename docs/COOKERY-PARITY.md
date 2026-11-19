# Cookery 官方玩法对照与厨房移植进度

更新日期：2026-11-19。第 2、3、5 节保留接手时差异与设计背景；当前状态以本节和第 6 节为准。完整未来计划和换设备步骤见 [README](../README.md#继续开发)。代码、自动化运行、玩家和客户端验收分别记录。

## 当前检查点

- 构建目标为 Paper/Folia 1.21.11、Java 21、CraftEngine 26.9.1；固定官方提交不变。
- `cookery-parity.json` 严格导入 148 foodProperties、94 food、8 tea、12 effect、2 enchantment，未知结构拒绝发布。快照 SHA-256 为 `d7b5e10c9a6d51f458804d4454ec8eac174f24ce22bb9eda40eea4c50146ba25`。
- 食物/茶饮/品质创建快照、PDC、便当袋与消费入口已接入；Paper/Folia 各 103 种食物构造和序列化验证零失败。原版客户端接包和实际进食流程未测。
- `CookeryEffects` 已实现 12 种效果的基础生命周期和事件入口；取消事件、坏 PDC、退出/重登、清除/tick 期间监听器重入新增效果有单元回归。效果表现、保护插件和真实实体场景仍逐项待测。
- 快刀、清扫已接入现有行为。原生附魔数据包见 `examples/cookery-enchantments`，精确运行证据见 [VALIDATION.md](VALIDATION.md)。附魔书获取、铁砧和客户端展示尚未验收。
- 厨房验收包现有 81 方块、1,432 物品（含 672 个茶旗显示物品）、4,255 个显式外观组合和 25 件家具，1,490 个模型通过依赖检查、18 个音效别名。茶旗已接入 CraftEngine 方块控制器，保存颜色和六种原版图案并在拆除时恢复带状态物品，支持地面/墙面安装、四向放置和三段波形状态。新增 38 种大菜对应 49 格、1,024 状态，原 24 厨具/418 状态仍分别验证。当前运行证据见 [VALIDATION.md](VALIDATION.md)，用法见 [厨房验收包](../examples/kitchen-pack/README.md)。
- 11 种取物盘已修正为官方整份语义：默认满盘、补一件增一份、混合浆果双产且禁补、取空保留盘、下次空盘交互及破坏只结算余份与容器；新 schema 2 保存物品快照并兼容旧份数/品质/容器。203 Java、86 Python 通过，Paper/Folia 各三轮验证满盘取空、空盘重启、补份、实际掉落和移除后无重复；从未操作的盘在 CE 先清空注册表后关服保存的错误已修复并复验。具体范围与失败历史见 [取物盘验收摘要](research/cookery-plate-acceptance-2026-11-15.json)。这不代替玩家/客户端或 38 种直接食用大菜。
- 2026-11-15 竹筛检查点：179 Java、81 Python 通过；Paper/Folia 各三轮真实 CE 加工、底部漏斗取消/放行和重启通过。28 条竹筛配方均验证注册/产物构造；实际加工覆盖湿海绵、海带、黏土、泥巴及海绵反向润湿。7 条营火配置类型键和 Paper 二次映射导致的滴水检测故障已修复；20 条炉具配方验证原生类型、时间、经验与产物。完整范围、失败历史和产物哈希见 [竹筛验收摘要](research/cookery-bamboo-acceptance.json)。
- 612 条来源配方均有原始 JSON、固定提交、路径、SHA-256 和状态。612 条来源已全部导入。配置包含 116 条普通合成、20 条炉具、5 条盖饭和 1 条锻造；炒锅/高汤锅现分别导入 225/105 条。
- 石磨 85 条官方来源已完整展开为 90 条具体原料配方。固定原版目标对砂砾、红沙、沙配置显式 Forge 标签映射，并对铁锭、金锭、铜锭、铁粒和钻石补齐映射。`targetTagMappings` 随包记录，上游同名标签优先。新增加工沿用每圈和存档逻辑，新增配方玩家验收待完成。
- 石磨底座、横杆、竖杆和磨石的 12 个 cube 从固定 Java 模型转换，父级 pivot 或旋转变化拒绝导入。保留原插件动画锚点、六倍滚动和磨石高度 1.4，官方抬升动画/1.3125 高度和实体碰撞尚有差异；资源包视觉未验收。Folia 重启实测暴露 CE ticker 仍指向旧家具实例，现通过公开家具映射转发到当前控制器，不增加重复调度任务；完整失败与复验记录见 VALIDATION。
- 炒锅新增 `pot_recipes`：195 精准、24 具体物品 ID 去重配方；精准 → 种类 → 旧余弦回退。按原料数量建不可变索引，与高汤锅共用重叠标签增广匹配；固定官方产量、沿用旧品质。新批次冻结官方翻炒次数，最后一次 24 tick 动画结束后出锅；`source_time` 仅保留来源时间，不增加官方独立时间门槛。已有翻炒但无快照的旧档仍走旧余弦与厨具默认次数。菜单九格/备选项、记录本 `pot` 类型和家具成品查询已接入。
- 炒锅单元覆盖断点动画、配方表变化后续炒、部分盛出存档、旧批次与损坏计划。盛出取消、空成品、回调期间份数/容器变化不继续扣容器；发物品前先扣锅中份数并标脏。CE 解析器显式等待物品注册与原料白名单重置，首轮实服加载失败及修复回归均保留。
- 高汤锅新增 `stockpot_recipes`：75 精准多重集、26 物品种类去重；匹配顺序为精准 → 种类 → 旧余弦。按汤底/原料数量建立索引，重叠备选原料使用增广匹配。保留既有品质档位和缩放，官方配方产出数量固定；重复骨头仍只产 1 份骨汤。计划 v2 保存模型和颜色并读回 v1；无计划的旧炖煮档仍按旧余弦逻辑补齐。菜单只读查看全部九格和备选原料，记录本及家具成品查询已接入，导入配方的菜单编辑待补。
- 高汤锅烹饪/成品/米饭汤面来自固定官方贴图；新批次成品高度为 `0.065 + 0.315 × 剩余份数 / 初始份数`，旧配方保留原液面规则。泡泡颜色用原版 dust 替代，奶液与字体仍是客户端替代。8 茶杯、64 切割帧、6 茶壶字形、9 汤底保留；真实画面尚未验收。
- 双端 132 加工计划、33 合成注册/输出通过；中途重启完成 4 蒸食、8 份酥油茶、3 小肉块＋1 骨头、3 份米饭和 1 份骨汤。取走 1 份米饭和整锅骨汤后再次重启，保留 2 份米饭、液面偏移 -0.105，空锅无重复产出。最终 JAR cycle 5 两端通过。输入来自 API/存档，见 [高汤锅验收摘要](research/cookery-stockpot-acceptance.json)，不能当作玩家操作证据。
- 转换 3 个 Forge `separate_transforms` 模型为原版 `display_context` 选择器，保留 GUI/手持差异；提取壶身、壶盖和杯垫，补齐 12 个煮汤/烧茶音效别名。未知 loader/状态结构、缺模型/贴图或哈希不符均失败。
- 38 种直接食用大菜（注册表 37＋冷切火腿片 1）已独立接入；34 单格、3 双格、1 九格共用每组 UUID/主状态。每口营养先按品质取整，饱和度按官方 modifier 公式，效果概率/等级保留；旧物品 `FoodDefinition.scaled` 不变。Bukkit 饥饿事件可取消，真实 FOOD 原因效果桥有 API/单元证据。未吃过只回整菜，吃过只返容器/额外物品，吃空后下一次交互移除。319 Java、102 Python 及最终双端各三轮 API 探针通过，见 [大菜验收摘要](research/cookery-placed-meals-acceptance-2026-11-15.json)。[原差异清单](research/cookery-food-bite-gap-2026-11-15.json) 保留为接入前历史。
- 5 条盖饭 native recipe 已独立接入：额外格允许、全网格最高品质、熟米饭返碗和原版余料均通过 Paper/Folia 服务端探针。Shift/自动合成器、玩家 UI、客户端配方书和外部可选米饭依赖仍未验收，见 [盖饭验收摘要](research/cookery-rice-bowls-acceptance-2026-11-16.json)。
- 2026-11-16 附属物品推进：农夫四件装备、花草帽、镰刀、厨铲和茶旗进入 CE 包；镰刀连接范围收割行为，茶旗支持染色、图案、存档和带状态掉落，`kitchen_shovel_no_oil` 与 `kitchen_shovel_has_oil` 两个 CE ID/模型均已注册。包级结果 535 items、63 blocks、547 models、536 imported/76 pending；原版客户端动态茶旗渲染、玩家实际锅铲状态切换、装备槽效果及 Forge 铁标签配方仍待验收。详情见[附属物品审计](research/cookery-auxiliary-items-2026-11-16.json)和[双端启动摘要](research/cookery-shovel-runtime-2026-11-16.json)。
- 大菜原版模型状态已转换，九格冷切火腿由官方 Java 22 cube 生成 9 阶段、仅中心显示；source quality 模型完全相同才折叠。细绊线碰撞/选框、服务端粒子频率与自定义烹饪粒子仍是替代；比较器 `(3-bites)*5` 限制在原版 0–15。放置拒绝未归属 Folia 区域；跨区后续清理仅单元验证。真人营养/效果、主副手、保护插件/多人、客户端画面、独立卸载/热重载/爆炸仍未验收。
- 外部模组原料获取、精确碰撞、茶壶支撑、动物桶 NBT、原版客户端、多人/保护与玩家容器守恒仍待完成。Folia 默认延迟加载的等待栈与当前配置的复测结果见[双端验收记录](research/series-runtime-2026-11-18.json)；茶旗动态客户端渲染、农夫套装实机效果、厨师交易和五类村庄厨房仍待验收或补齐。

## 1. 对照基线

- 插件：`addxiaoyi/SenLuo_Plugin`（原 `KaleidoscopeCookery_Plugin`），`main`，`7a2f8427cbbdbd08d6ceef2e4b80ddc8536d1f23`，版本 `1.3.0`。
- 官方模组：`KaleidoscopeMods/KaleidoscopeCookery`，已实时核对默认分支为 `main`，HEAD 为 `2f4e386ce23f49a385ddf003c67fc6415c55417a`，提交日期 `2026-09-26`。
- 该提交的标签：`snapshot-2026-09-26-13-50-42`、`snapshot-2026-09-26-14-06-32`。这是默认分支及指向标签核对，不将其等同于正式发布版本查询。
- 官方 `gradle.properties` 声明 `1.6.0-forge+mc1.20.1`。参考 checkout 为 `../.references/KaleidoscopeCookery`，保留原仓库与干净工作树。
- 当前运行验收固定 Paper/Folia 1.21.11、Java 21、CE 26.9.1；既有 NMS 源集继续构建，不将 Forge 1.20.1 当成部署目标。

下文 `U/` 表示官方 checkout 的 `src/main/java/com/github/ysbbbbbb/kaleidoscopecookery/`；`P/` 表示本仓库 `src/main/java/net/kaleidoscope/cookery/`。所有行号以以上提交为准。

## 2. 已确认的优先差距

### 2.1 完整资源定义未随插件仓库提供

`git ls-files Kaleidoscope` 仅列出 5 个文件：包元数据、成就文本、菜单提示 YAML、提示 PNG 和 SVG 源文件。物品、厨具、作物、全部配方、模型和绝大多数贴图不在当前 checkout 内。`.gitignore` 也明确排除了这些内容。

因此，现有 Java 行为不等于一套可以从空目录装出的完整玩法；也不能仅从 Java 判断某个原版药水效果是否已在用户现有 CE 配置中近似实现。应先建立可追踪的物品 ID、配方、模型、音效、翻译与行为绑定清单，再补齐资源。官方命名空间为 `kaleidoscope_cookery`，插件主要命名空间为 `kaleidoscopecookery`，迁移不能直接复制引用字符串。

证据：本仓库 `.gitignore` 第 32 行起，`README.md` 第 89 行；官方 `init/ModItems.java`、`init/ModBlocks.java`、`init/registry/FoodBiteRegistry.java` 及 `src/generated/resources/data/kaleidoscope_cookery/`。

### 2.2 竹筛整套玩法缺失

官方已有竹筛，插件的行为注册、控制器、配方类型和菜单中均无竹筛实现。官方具备：

- 四个独立槽，每槽允许同种物品按最大堆叠数堆叠。
- 顶面四象限命中选槽；空手取一个，潜行空手取整槽；阻止把竹筛自身塞入。
- 每 19 tick 错峰处理；雨淋或有效滴水石锥润湿，非雨天且露天或上方仅透明无色玻璃、无色玻璃板、其他竹筛时晾晒；润湿优先。
- `NONE`、`DRYING_COMPLETED`、`WETTING_COMPLETED` 完成标记，防止同方向结果无限连续加工；环境转向后允许反向配方。
- 上方与侧面进料，下方只抽出已完成产物；支持漏斗及其他标准容器自动化。
- 保存物品、进度、完成状态；拆除时掉落全部库存。
- 当前官方生成资源有 28 条竹筛配方：晾晒 5 条、润湿 23 条，包含茶叶、海带、海绵、腐肉/皮革、泥土/泥巴/黏土、铜块及 16 色混凝土。

证据：`U/block/kitchen/BambooTrayBlock.java:54-75,79-97,126-137`；`U/blockentity/kitchen/BambooTrayBlockEntity.java:38-116,118-138,142-202,210-255`；官方 `src/generated/resources/data/kaleidoscope_cookery/recipes/bamboo_tray/`。插件现有类型清单为 `P/recipe/ApplianceType.java:4-11`。

### 2.3 12 个自定义效果：基础系统已实现，表现待验收

官方注册了 `flatulence`、`tundra_strider`、`warmth`、`satiated_shield`、`vigor`、`sulfur`、`mustard`、`preservation`、`hinder`、`projectile_dodge`、`instant_smelting`、`vitality`。现已新增 `P/effect/`、`CookeryEffectChangeEvent` 和消费入口；下列官方规则仍是实际玩家验收的对照标准，基础系统通过单元测试不代表每项效果完全一致。

- 胀气：按下潜行键向上增加 `0.75` 速度，另发云粒子与音效；记录效果起点用于爬升成就，结束清理。官方依赖客户端按键监听和专用 C2S 消息；插件需改用原版可观测输入及服务端速度同步。现有 4 项氮气成就明确标为未支持。
- 冻原行者：特定地表速度补偿，并能行走在细雪上，涉及官方客户端/服务端 Mixin；插件需分别验证服务器碰撞与原版客户端预测，不能承诺纯资源包自动实现。
- 温暖：每秒检查 5×3×5 热源范围，存在热源治疗 1 点；下界无热源时每秒 25% 概率治疗 0.5 点。
- 活力 `vigor`：疾跑时每 tick 清零疲劳。
- 硫磺：每 5 tick 清除周边 8×16×8 范围幻翼对持有效果实体的目标。
- 芥末：苦力怕新增回避携带此效果实体的 AI，范围 6、行走/奔跑速度 1/1.2。
- 保鲜：进食后移除该食物可能施加的有害效果，不是清空全部已有负面效果。
- 阻碍：造成伤害时给目标 100 tick 的缓慢 II。
- 弹射物闪避：跳过弹射物命中，尝试近距离随机传送，每次消耗 200 tick 效果时间。
- 即时冶炼：仅矿石掉落，按效果等级限制此次转换数量，依原熔炉配方转换并保留其他掉落。
- 生机 `vitality`：击杀成年可繁殖生物产生幼体；成年僵尸死亡时通常生幼体，5% 为幼年村民。
- 饱腹代偿：食物值门槛、伤害减免、疲劳消耗、饥饿禁用、弱点伤害倍率等来自 `GeneralConfig`，不是原版抗性效果。

证据：`U/init/ModEffects.java:13-24`；`U/effect/`；`U/event/effect/`；`U/event/EntityJoinWorldEvent.java:23-24`；`U/mixin/LivingEntityMixin.java:22-28`；`U/mixin/PowderSnowBlockMixin.java:14-18`；`U/config/GeneralConfig.java:50-76`；本仓库 `README.md:99`。

移植时应建立独立效果注册表与实体状态，而不是占用一个原版药水类型代表多个效果。玩家死亡、喝奶、退出重进、跨世界、重复进食、不同等级覆盖、取消事件与 Folia 实体线程都需明确语义。状态图标可用资源包字体与 UI 呈现；原版效果栏注册能力须另行验证。

### 2.4 自定义附魔：行为已接入

官方快刀最高 II，每次切割推进 `1 << level`（1/2/4）；官方清扫最高 III，使镰刀半径为 `2 + level`，对应 5/7/9/11 格方形，单次耐久消耗 1/2/4/8。

`CookeryEnchantments` 只读取 CE 活跃附魔列表，忽略书本 stored enchantments 和 lore；等级分别裁剪到 0–2、0–3。砧板从实际选中刀具（保留副手优先）读取等级，`cut(int)` 推进 1/2/4，旧 `cut()` 继续单步。原始阶段 1 表示零刀，最后一刀仍直接产出，跨过末阶段也只构造一次产物。

镰刀以旧配置半径加清扫等级，保留最大半径 8；耐久基数乘 1/2/4/8。默认配置与官方一致。空挥、创造、关闭损耗、冷却和逐格保护规则保留；新增未加载区块、其他 Folia 区域和世界高度边界检查。负耐久及会溢出的配置明确拒绝。

Java 回归覆盖等级 0–3、越界、无附魔、错误附魔、重复附魔、配置基数、切割次数、旧档续切、输出缺失、一次性产出及跨区块保护。原生注册通过服务器数据包实现，无客户端模组；获取方式和剑类基材限制仍与完整 CE 工具配置一起待验收。

证据：`U/enchantment/QuickKnifeEnchantment.java:8-14`、`U/enchantment/SweepEnchantment.java:8-14`、`U/blockentity/kitchen/ChoppingBoardBlockEntity.java:99-120`、`U/item/SickleItem.java:74-97`；`P/block/behavior/ChoppingBoardBehavior.java:116-131`、`P/item/behavior/SickleRangeHarvestBehavior.java:44-55,89-107`。

保留的砧板交互差异：官方切满后下一次点击才取出，且只在未切第一刀前允许直接取回原料；插件放料从阶段 1 开始，最后一次切割直接产出，`takeBack()` 保留原插件收回规则。这是用户确认的兼容边界，不修改旧档语义。

### 2.5 厨师村民、厨房结构、茶旗与装备效果

- 官方新增厨师职业、工作站 POI 与 1–5 级交易；插件未见职业/交易注册实现。证据：`U/init/ModVillager.java`、`U/init/ModPoi.java`、`U/event/ModTradesEvent.java:27-137`。
- 官方将厨房加入平原、雪地、热带草原、沙漠、针叶林村庄结构池，各权重 4；插件无世界生成实现，也未携带这五个 NBT。证据：`U/event/AddVillageStructuresEvent.java:27-41` 与官方 `src/main/resources/data/kaleidoscope_cookery/structures/village/houses/`。
- 茶旗已通过 `TeaBannerController` 保存颜色和图案物品，支持六种原版图案、剪刀取下、带状态掉落和重新放置恢复。CE 方块状态按 16 种颜色与 7 种图案生成 112 个模型变体，染色和图案交互会同步状态。旗杆摆动与墙面朝向仍待客户端验收。上游证据：`U/blockentity/decoration/TeaBannerBlockEntity.java:17-54`、`U/item/TeaBannerItem.java:20-32`。
- 官方全套农夫装备在水中每 20 tick 刷新 25 tick 海豚恩惠；插件已有对应规则，支持草帽及花草帽，按完整 CE ID 和装备槽校验，在玩家实体 scheduler 中刷新。药效等级 0、ambient/particles 为 true、icon 为 false，保留 Bukkit 药效事件的取消，离水或卸甲后自然过期，不主动清除其他来源效果。[农夫套装回归](../src/test/java/net/kaleidoscope/cookery/effect/FarmerOutfitTest.java)覆盖这些参数、装备匹配与退出／死亡／关闭生命周期；真实游泳、第三方效果共存和客户端仍待验收。上游证据：`U/event/ArmorEffectEvent.java:16-31`。

村民外观与自定义职业名称、世界生成时机、既有区块和新生成区块的差异，都需要单独验收，不能用放置几个静态 NPC 作为完整复刻。

## 3. 已有厨具的重要差异

### 炒锅不是同一状态机

官方倒油后有 1200 tick 准备窗口；窗口到期且有料会自动开炒。选中配方后使用其 `time` 倒计时，同时要求完成规定翻炒次数；倒计时结束时次数不足变迷之炒菜。完成后的盛出窗口为 800 tick，再经历 400 tick 焦化。

插件当前首次有效翻炒冻结计划，翻炒次数达到目标并经过 24 tick 动画窗口即完成，没有独立烹饪时间门槛和准备超时自动开炒；默认盛出窗口为 200 tick，焦化为 400 tick。CE 行为可覆盖插件默认值，因此 200 tick 是代码缺省值，不能直接当成用户运行配置。

证据：`U/blockentity/kitchen/PotBlockEntity.java:53-55,92-129,175-218,245-248,294-350`；`P/block/entity/PotController.java:123-171,228-280`；`P/block/behavior/PotBehavior.java:75-81`。

本轮不修改炒锅。若要求原版规则，后续增加明确的烹饪规则模式、时长字段、旧存档迁移和 UI 解释，保留现有 1.3.0 配方语义，不把次数字段悄悄变为秒数。

### 沙威玛不应擅自添加热源条件

官方 `U/block/kitchen/ShawarmaSpitBlock.java:99-100` 与插件 `P/block/entity/ShawarmaSpitController.java:124-128` 都由红石通电驱动，无单独外部热源判定。“炭火慢烤”是 README 玩法描述，不能据此判定热源缺失。

实际差异在配方与库存：官方直接读取原版营火配方、一次放同种原料，插件按多层多槽自定义精准配方加工。是否增加原版营火配方回退，应保留自定义配方优先级。证据：`U/blockentity/kitchen/ShawarmaSpitBlockEntity.java:41-54` 与 `P/block/entity/ShawarmaSpitController.java:63-64,245-246`。

## 4. 食物效果数据能否自动迁移

可以数据驱动，但不能只解析 `ModFoods`。

1. 普通食物属性及效果声明在 `U/init/ModFoods.java`；实际物品 ID 到属性常量绑定在 `U/init/ModItems.java:122-201` 等注册项。
2. 可摆盘食物由 `U/init/registry/FoodBiteRegistry.java` 绑定，区分整份物品 `itemFood` 和每口方块 `blockFood`，还带可食次数与额外掉落，不能合并成一张只有物品 ID 的表。
3. 茶类由 `U/init/registry/TeacupRegistry.java:33-68` 单独声明，包括茶品、效果类型、持续 tick、等级与概率；与 `ModFoods` 不同。
4. 食用品质会改变饥饿值、饱和度和效果时长。证据：`U/block/food/FoodBiteBlock.java:118-142`、`U/item/quality/QualityUtils.java:29-55`、`U/item/FoodWithEffectsItem.java:56-64`。
5. `FoodWithEffectsItem` 的 tooltip 缓存只收录概率为 1 的效果，不能从该缓存或 lore 反推全部食物效果；应解析原属性列表。

建议导入清单保留 `sourceCommit`、原 ID、CE ID、食用形态、每口/整份属性、概率、基础持续 tick、等级、品质缩放方式与容器。生成器只做已识别结构转换；未识别语法显式报错，避免静默漏食物。效果行为与食物映射分离，先建立精确清单再逐个实现效果。

## 5. 竹筛最小完整闭环设计

这是建议优先实现的独立新玩法：不改变已有厨具状态机，也不依赖先恢复全部食物。原版海带、海绵、腐肉、泥土等配方可组成独立可运行验收包。竹筛本体外观仍需经过 CE 配置与资源加载验证；使用原版临时模型时只称功能样例，不称外观还原完成。

### 模块边界

- `BambooTrayBehavior`：继承现有 Bukkit 行为体系，实现 `EntityBlock` 和 CE `WorldlyContainerHolder`；处理放置/叠放 `stand` 状态、顶面四象限选槽、主手去重、取放、领地交互保护、创建控制器和自动化容器查询。
- `BambooTrayController`：唯一权威库存为四槽；保存各槽进度、完成方向及当前加工输入身份；CE tick 中每 19 tick 错峰判环境、匹配配方并原子替换结果；无环境时暂停；拆除掉库存、卸载仅保存。所有手动与漏斗变动走同一库存变更入口。
- `BambooTrayElement`：以已有 `ItemDisplaySet` 显示四槽物品；只在内容变化时构建元数据，跟随 CE 玩家追踪显示/隐藏，不承担加工推进。
- `BambooTrayContainer`：继承 CE `BukkitWorldlyStorageContainer` 或实现其 `BukkitContainer`/`WorldlyContainer` 契约；上/侧仅允许未完成槽进料，底部仅允许完成槽出料；同物品组件才能堆叠；所有库存变更标脏并更新显示。
- `BambooTrayRecipe` / `BambooTrayRecipes`：独立不可变配方记录与原子快照，字段为 ID、输入 matcher、输出、方向 `drying/wetting`、正整数 tick。支持输入标签，保留注册顺序优先级。
- `BambooTrayRecipeParser`：按 CE 加载阶段解析独立 `bamboo_tray_recipes` 节；引用物品必须存在，重复 ID/未知方向/非正时长/非法数量报错；失败保留上一代有效快照，不能半代发布。
- `BambooTrayEnvironment`：集中管理雨淋、玻璃遮棚、叠放竹筛、有效水滴石锥来源判断。滴水石锥必须检查尖端、上方水源和距离，不能把任意悬空滴水石锥当水源。

### 漏斗接口已核实存在

已对本机实际用于构建的 **CraftEngine 26.9.1 发布 JAR** 执行 `javap`，确认下列 API 和调用链存在：

```text
core.block.behavior.WorldlyContainerHolder#getContainer(Object, Object[])
core.world.WorldlyContainer#getSlotsForFace(Direction)
core.world.WorldlyContainer#canPlaceItemThroughFace(int, Item, Direction)
core.world.WorldlyContainer#canTakeItemThroughFace(int, Item, Direction)
core.plugin.Platform#createContainer(Container) -> Object
bukkit.world.inventory.BukkitWorldlyStorageContainer
bukkit.world.WorldlyContainerHolder implements InventoryHolder
```

`BlockGenerator$GetContainerInterceptor` 将 NMS 容器查询委托给行为的 `getContainer`。现有 CE `SimpleStorageBlockEntityController` 用 `platform().createContainer(container)` 创建 NMS 适配，再经 `CraftInventoryProxy.newInstance` 提供 Bukkit Inventory；`SimpleStorageBlockBehavior#getContainer` 返回其底层 NMS 容器。其容器的 `setChanged()` 调用 `CEWorld.blockEntityChanged(pos)`。

因此可沿用 CE 已有跨版本容器桥，不必先为 9 个 NMS 模块各写竹筛适配。以上是设计阶段的编译接口与字节码证据；当前下方漏斗、取消和重启的运行范围以 [VALIDATION.md](VALIDATION.md) 为准。漏斗矿车、完整进料场景、Folia 区域边界或 CE 26.10 运行效果仍未验证。

### 状态与存档规则

- 固定四槽，保留真实 ItemStack 组件和数据版本；存档不能只保存物品 ID。
- 槽位清空、换成不同物品或对已完成槽重新放置时重置进度；同类未完成堆叠允许累加且不重置进度，对齐官方 `insertIntoSlot`。
- 当前方向已完成则不再加工；反向配方匹配后才清除原完成标记；没有匹配配方时清零进度，按官方规则保留完成标记。
- 产量为 `min(产物最大堆叠数, 每个输入产量 × 输入数量)`。官方在超过堆叠上限时截断；若选择保护溢出物品，应作为明确的插件差异记录，不能无声改变。
- 每 19 tick 增加 19 进度，因此实际完成时刻向下一个检测步取整；配置时长不能伪装成每 tick 精确处理。
- 官方竹筛每次处理重新查配方；若沿用插件已有冻结批次原则，需要显式定义重载边界。建议先对齐官方竹筛规则，不把其他厨具的 CookingPlan 语义未经讨论套入。
- NBT 用稳定字符串/显式数值保存完成方向，并携带 schema version，不把未来可重排 enum 的 ordinal 当新持久化协议。
- 物品移出后必须马上标脏，拆除掉落与库存清空保持单一事务，避免重载/卸载复制物品。

### 现有菜单与编辑系统接入面

不能只给 `ApplianceType` 加一项：现有菜单会把非模糊类型导向精准配方。完整菜单需要同步：

1. 新厨具入口、图标、翻译；列表区分晾晒与润湿；详情明确环境与时长。
2. 新建/编辑/删除的 draft、校验、来源索引、原文件安全写回与重复保存保护。
3. 配方加载开始/结束阶段、失败恢复、菜单快照、API 查询；保持已有配方快照语义。
4. 配置示例和 28 条默认配方，官方模型/贴图引用迁移和包 metadata；更新 `.gitignore` 白名单确保交付资源真的入库。

相关入口：`P/ui/RecipeMenus.java`、`P/ui/RecipeListMenu.java:43-44`、`P/recipe/FoodRecipeManager.java`、`P/recipe/FoodRecipeRegistry.java`、`P/recipe/edit/RecipeEditService.java`、`P/api/ui/RecipeMenuStyle.java`。

预计范围为 6–8 个新玩法/配置类、约 8–12 处既有注册和菜单集成，加资源及验收；这是一个独立功能批次，不适合在调查阶段留下几块互不连通的半成品。

### 完成标准

- 空服安装插件、CE 与专用样例包后，竹筛可获取、放置、叠放、破坏，四槽显示正确。
- 手动单取/整取、同类堆叠、满槽、不同组件、创造模式、主副手和非顶面交互通过。
- 晴天、下雨、无色玻璃/玻璃板遮棚、不透明遮挡、叠放、有效/无效滴水石锥、无配方与环境反转通过。
- 28 条官方配方的输入/输出/时长核对；同方向完成不循环，反方向可再加工。
- 上/侧漏斗进料、下漏斗只取完成品、通电锁定、目标已满、物品移动事件取消、漏斗矿车如支持时均实测。
- 断线、区块卸载、服务器重启、CE 重载、部分取出后重载、破坏/爆炸与保护取消无复制/丢失。
- 兼容范围至少覆盖既有 Java/NMS 构建；目标 Paper/Folia 与 CE 版本需各自运行验收，不能用构建成功代替。

## 6. 本轮边界与后续顺序

2026-11-04 最初实现已新增竹筛行为、控制器、容器、环境判定、展示、独立配方快照、菜单编辑与 28 条配方示例；当时为 29 Java/15 Python。当前测试与运行结果见本页检查点及 [VALIDATION.md](VALIDATION.md)。现有厨具规则未替换，全系列复刻尚未完成。

竹筛显示已按官方数量每槽生成 1–5 份，使用固定位置种子的偏移、层高、旋转与 FIXED 物品变换；缓存未变化槽的物品快照和显示数据包，不新增真实实体 tick。客户端画面仍未验收。自制独立示例模型使用原版竹材贴图和细绊线外观状态，不能视为官方美术/碰撞复刻；完整厨房包已有官方竹筛模型。复杂模板实例同时生成其他内容时，菜单保守拒绝单独自动删除。

存档加载对字段类型、槽位/重复槽、数组长度、进度/完成值及解码数量先完整校验，再替换库存；错误数据不再被 NBT 默认值当成空槽。仍接受旧档缺省字段和较短数组，未更改存档 schema 或旧厨具语义。

2026-11-15 当前交接点：完整厨房 536/612 条来源导入、76 pending，535 物品/63 方块/1,442 状态/1 石磨家具。双端各三轮通过；竹筛晴天、玻璃顶、实心遮挡、叠筛、有源/无源滴水、底漏斗取消及样例重启已有运行证据。茶旗服务端状态和带状态掉落已补入。仍须补玩家/客户端、茶旗动态渲染、降雨/玻璃板/遮光玻璃、顶部/侧面漏斗与满目标、完整物品组件、保护/多人、独立区块卸载、CE 热重载和 Folia 跨区。接着推进剩余配方、原料获取、效果、套装、职业/结构与摆盘玩家闭环。历史 Folia 启动 watchdog 继续 OPEN。


2026-11-17 交接增量：已从 `251ad5a` 重建并运行 Paper/Folia 五插件整合服。修复 CE 系列 ZIP 未解包、JAR 命名识别、旧资源覆盖和 Tavern 1.21.11 原料名称；使用 `--server-blocks 6000` 完整注册厨房 63 方块/5,474 内部状态（4,130 个显式外观组合不等于内部状态数，茶旗 anchor_type 包含 ceiling）。根 Python 104 项、系列 Python 54 项通过。两端包加载、生成和状态注册已有证据，但 Folia watchdog 和缺模型/纹理警告仍 OPEN，客户端/玩家流程未验收。详见 [当前交接](HANDOFF.md) 与 [本轮证据](research/series-runtime-2026-11-17.json)。
