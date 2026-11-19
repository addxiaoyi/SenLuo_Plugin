# Cookery 厨师职业与原版客户端边界

核查日期：2026-11-19（Asia/Shanghai）。本记录为固定版本的只读源码、API 和字节码调查；没有启动新服务、执行 Gradle、修改职业注册表或进行玩家验收。它不能作为厨师职业或村庄厨房已实现的证据。

## 结论

在本项目固定的 Minecraft/Paper/Folia 1.21.11 和无客户端模组条件下，不能仅靠数据包与资源包增加一个可由客户端原生识别的 `kaleidoscope_cookery:chef` 村民职业。职业及 POI 属于内置注册表，Paper 公开可写注册表未开放这两类，原版的数据包加载和网络同步清单也不包含它们。村民职业元数据按注册表数值 ID 编码，客户端不存在的 ID 会解码失败。

这不等于五种厨房结构不能移植。结构模板、模板池和 processor 是另一条世界生成链，可以移植；当前仍缺转换、运行接线和完整验收。两项必须分别报告，不能以职业限制解释未完成的结构工作，也不能以结构已导入推断职业已实现。

## 固定输入与哈希

官方 Cookery 上游固定提交为 `2f4e386ce23f49a385ddf003c67fc6415c55417a`，其 `gradle.properties` 声明 `1.6.0-forge+mc1.20.1`。本调查用 `git show <commit>:<path>` 读取该提交，不以可变工作树内容作为基线。

以下为本次实际读取文件的 SHA-256；API 的 Maven `SNAPSHOT` 名称本身不构成版本锁定。

| 文件／坐标 | SHA-256 |
| --- | --- |
| `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT` 本地 JAR | `c577b181c11a8674310e56c92a91e31c010b7f04c9bd10b91c3be18374401070` |
| `org.spigotmc:spigot:1.21.11-R0.1-SNAPSHOT:remapped-mojang` 本地 JAR | `9fa9a05a48349f1bafb8aa37540b5a0503ec941abf26205db30e6d8cf256985a` |
| 官方 `mojang_client_1.21.11.jar` | `1473c9489ac50fda3c435049a76a70d61a10b8610db27f5ba9d8756b686cd3bd` |
| 该客户端 `client-mappings.txt` | `517799a8485e107e932dc1bd27c002b2d0b9207eb2396b685bcfe6c3321a9fbd` |
| Paper 1.21.11 build 132 发行 JAR | `5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba` |
| Folia 1.21.11 build 14 发行 JAR | `f52c408490a0225611e67907a3ca19f7e6da2c6bc899e715d5f46844e7103c39` |

Paper/Folia 发行 JAR 在此只核对哈希，没有声称逐一反汇编其全部实现。服务器侧字节码证据来自以上 Mojang-remapped Spigot JAR；Paper 特有 API 证据来自以上 Paper API JAR；原版客户端边界另由官方客户端独立核对。

## 职业注册与协议证据

使用 JDK 21 的 `javap -p` 检查签名，`javap -c -p` 检查方法体。以下为可复核的类和入口。

| 输入 | 类／方法 | 实际观察 |
| --- | --- | --- |
| Paper API | `io.papermc.paper.registry.event.RegistryEvents` | 有 enchantment、damage type、狼／猫等变体、dialog 等 provider；没有 villager profession、POI 或 template pool 的可写 provider。不能由 `RegistryKey` 或 `VillagerProfessionKeys` 存在推断可注册。 |
| Paper API | `org.bukkit.entity.Villager$Profession` | 已是 `OldEnum`、`Keyed`、`Translatable` 接口，而非 Java enum；这只说明取值抽象，不提供注册自定义职业的 API。 |
| 服务器 NMS | `net.minecraft.core.registries.BuiltInRegistries` 静态初始化 | `VILLAGER_PROFESSION` 是 `DefaultedRegistry`，以 `none` 为默认值；`POINT_OF_INTEREST_TYPE` 是内置 `Registry`。 |
| 服务器 NMS | `net.minecraft.resources.RegistryDataLoader` 静态初始化 | `WORLDGEN_REGISTRIES` 与 `SYNCHRONIZED_REGISTRIES` 均没有职业或 POI。前者包含 `TEMPLATE_POOL`、`PROCESSOR_LIST`。 |
| 服务器 NMS | `net.minecraft.core.RegistrySynchronization` 静态初始化、`packRegistries` | 网络可同步集合来自 `RegistryDataLoader.SYNCHRONIZED_REGISTRIES`，不会自动同步服务端追加的任意内置 registry。 |
| 服务器与客户端 | `VillagerData` 静态初始化 | `STREAM_CODEC` 对 type 和 profession 调用 `ByteBufCodecs.holderRegistry`，再编码 level。不是按自定义职业字符串传输完整定义。 |
| 原版客户端 | `ByteBufCodecs$29.decode` → `Registry.asHolderIdMap` → `IdMap.byIdOrThrow` | 先读取 VarInt ID，再从客户端的 holder ID map 查值。未知 ID 没有默认职业回退；holder 查询为空后抛 `IllegalArgumentException`。 |
| 原版客户端 | `VillagerProfessionLayer.getIdentifier`、`submit` | 依据已解码的 profession holder key 查 `textures/entity/<villager-path>/profession/<key>.png` 等资源。新增 PNG 只增加资源，不能创建该 holder。 |

官方客户端混淆名已用对应 mappings 核实：

| Mojang 名称 | 本客户端混淆名 |
| --- | --- |
| `VillagerData` | `dcy` |
| `RegistryDataLoader` | `amp` |
| `ByteBufCodecs` | `aam` |
| 映射名称 `ByteBufCodecs$29` | `aam$22` |
| `Registry`／其 holder ID map 匿名实现 | `jq`／`jq$1` |
| `IdMap` | `ji` |
| `VillagerProfessionLayer` | `ibv` |

特别注意 `DefaultedMappedRegistry.byId` 对普通值的默认回退不能用于推翻以上结论：`holderRegistry` 使用的是 `Registry.asHolderIdMap()`，该实现调用 `registry.get(int).orElse(null)`，随后由 `byIdOrThrow` 检查。已独立检查官方客户端的 `jq$1` 和 `aam$22` 方法体。

复核命令示意（变量使用操作者本机实际路径）：

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath $paperApiJar, $nmsJar, $vanillaClientJar, $clientMappings
& $javap -classpath $paperApiJar -p io.papermc.paper.registry.event.RegistryEvents
& $javap -classpath $nmsJar -c -p net.minecraft.core.registries.BuiltInRegistries
& $javap -classpath $nmsJar -c -p net.minecraft.resources.RegistryDataLoader
& $javap -classpath $nmsJar -c -p net.minecraft.core.RegistrySynchronization
& $javap -classpath $nmsJar -c -p net.minecraft.world.entity.npc.villager.VillagerData
& $javap -classpath $vanillaClientJar -c -p dcy amp 'aam$22' 'jq$1' ji ibv
```

这些命令只读类文件，不运行 Minecraft 主程序。没有通过运行探针实际发送一个非法职业 ID；解码失败结论来自上述明确的字节码调用链。

## 固定上游的完整职业范围

上游 Java 路径以下均相对于 `src/main/java/com/github/ysbbbbbb/kaleidoscopecookery/`：

- `init/ModVillager.java`：`chef` 的 held/acquirable job site 都仅匹配 `ModPoi.STOVE`，requested items 和 secondary POI 为空，工作声是原版 butcher 的工作声。
- `init/ModPoi.java`：stove、pot、stockpot、chopping board 各自有 POI，匹配对应方块所有状态；`PoiType` ticket 与 valid range 均为 1。厨师不能把所有厨具都当工作站。
- `event/ModTradesEvent.java`：1–5 级 listing 数量分别为 6、7、4、14、1，共 32 个；其中 12 个出售带配方记录的物品。价格、最大次数、村民经验、价格倍率及随机附魔都要保留。原版每级从 listing 抽取至多两项，不能把全部 listing 一次性当成村民当前交易。
- `item/RecipeItem.java`：记录含 input、output、type、flexRecipe；实际右键会检查锅状态、清空输入和所需原料后执行投料。普通纸／仅 lore 名称不是功能菜谱。
- `loot/RecipeRandomlyFunction.java`：村庄箱子另有随机菜谱函数，从食物记录和配方表选择记录；不等同于厨师的 12 个固定菜谱交易。
- `mixin/VillagerMixin.java`：村民拾取番茄、辣椒、生菜及其种子的补充规则是额外移植范围。
- `data/kaleidoscope_cookery/loot_tables/gameplay/hero_of_the_village/chef_gift.json`：英雄赠礼从 5 种盖饭中抽取。只注册 trade listing 不会自动补齐礼物映射。

服务器 NMS 的 `Villager.updateTrades(ServerLevel)` 从 `VillagerTrades.TRADES`／实验交易映射取当前职业和等级，调用 `addOffersFromItemListings(..., 2)`；`GiveGiftToHero.GIFTS` 是独立职业映射。真实实现还须处理就职、失业、工作站竞争、工作时间补货、需求与折扣、经验升级、序列化、僵尸化／治愈与 Folia 实体线程。

现有 `tools/export_cookery_catalog.py` 对职业只输出 `record(name, 'profession')`，对结构只保留路径；catalog 的 `pending` 不是已注册行为。现有 `RecipeUtils.tryAutoFill` 和 `setRecipeItem` 是可复用的接入点，但前者当前按找到的原料逐件投料，不应未经审查就当作上游“原料齐全才执行”的事务语义。

## 替代路线的实际偏差

| 路线 | 可以做到 | 不能据此宣称 |
| --- | --- | --- |
| 数据包＋资源包 | 注册原版支持的动态数据、提供结构及纹理 | 注册并同步 Chef／自定义 POI；PNG 不会扩展职业 registry。 |
| 只用 Farmer／Butcher 并替换交易 | 原版职业实体展示自定义交易 | 独立 Chef 身份、stove 专属 POI 与完整 AI 语义；改同一原版职业纹理还会影响普通该职业村民。 |
| NMS 真正增加服务端 Chef／POI，再将发给 vanilla 的 profession 映射为原版 ID | 理论上保留服务端身份，并让协议避免未知 ID | 客户端识别独立 Chef；客户端仍按映射后的职业渲染。每个版本、存档、标签、AI、交易和包过滤都需实现及验收，目前未实现。 |
| 自定义展示实体／静态 NPC＋商人界面 | 自定义外观、可点击交易入口 | 与原版村民完整生命周期、职业竞争和交互完全相同。 |
| 客户端模组或配套自定义协议支持 | 可以扩展客户端职业注册与渲染 | 继续满足“无客户端模组”的原版客户端条件。 |

如果以后接受某一种路线，应先明示相应兼容边界，再按那条路线实现和验收；本记录不替用户选择缩水替代。

## 五种村庄厨房是独立可实现的链

上游 `event/AddVillageStructuresEvent.java` 在服务器启动前为五个 `minecraft:village/<biome>/houses` 池各追加一个权重 4 的 `legacy single`、`RIGID` 模板，processor key 为 `kaleidoscope_cookery:crop_replace`。这是自然 jigsaw 池接入，不是任意地点手动放置模板。

已只读解压并解析固定提交的五个 NBT，均为 `DataVersion=3465`，无实体；每份均有一个 stove、两个带 loot table 的 chest、一个 jigsaw 和一个 campfire。下表只列自定义块：

| 模板 | 尺寸 | 自定义方块 |
| --- | --- | --- |
| plains kitchen | 9×10×10 | stove×1；成熟 tomato crop×6 |
| snowy kitchen | 8×10×7 | stove×1；chili ristra×9，须保留 sheared/is_head 状态 |
| savanna kitchen | 8×9×11 | stove×1；成熟 tomato crop×12 |
| desert kitchen | 7×8×8 | stove×1 |
| taiga kitchen | 9×10×11 | stove×1；成熟 tomato crop×9 |

NBT 来源为 `src/main/resources/data/kaleidoscope_cookery/structures/village/houses/<biome>_kitchen.nbt`。雪地和沙漠模板中 jigsaw 的 pool 字段原本引用 plains streets，不能未经说明按 biome 自动改写。旧版 `minecraft:grass` 等原版状态也须经目标版本 data fixer／明确迁移，而非因未知 ID 静默变为空气。

`worldgen/processor_list/crop_replace.json` 顺序尝试两个独立概率 0.33 的规则：tomato → chili，然后仍匹配 tomato 时 → lettuce。对应比例为 chili 0.33、lettuce 0.67×0.33、保留 tomato 0.67×0.67；不能误写成三个结果等概率。

两个箱子分别用 `chest/village_chest` 和 `chest/village_hide_chest`，必须保留原版随机抽取、权重、数量和只生成一次的语义，产物必须是完整 CE 物品；普通箱子中的 `recipe_randomly` 也要接入功能菜谱。

### 最小完整实施顺序

1. 将五份固定 NBT、processor 和两份箱子 loot 转为可追踪 manifest，保留来源路径、哈希、状态、朝向、jigsaw 与 loot NBT，未知状态拒绝导入。仅导出这些资源仍不算运行完成。
2. CE 完成当前包注册后，将自定义块转换为实际 `ImmutableBlockState`／server state，构造运行模板及原生可序列化 processor。检查炉灶 controller、作物和辣椒串状态；不要硬编码易随包变化的 CE server-block 数值 ID。
3. 对当前已解析的五个 houses 池幂等追加权重 4，保留其他包已有条目并使池的展开列表、权重原列表与最大尺寸缓存一致。原版数据包对同一路径 JSON 采用覆盖，不能将复制五份 vanilla JSON 称为“自动合并”。
4. 安排正确初始化时机，使目标新生成区块使用模板；安全处理世界初始化、CE reload、数据包 reload、停用与重复加载。Paper 公开 API 只有 `StructureManager` 模板操作和 `AsyncStructureGenerateEvent` transformer，没有 template-pool 写入 provider；池增量接入需要单独的版本适配，不应仓促反射写入。
5. 接入箱子 native loot／生成事件桥，严格只转换本项目标记，保留别的插件取消与修改；功能菜谱使用真实配方记录，库存和重启后不得重复生成。
6. Paper/Folia 分别验证五 biome 的实际新村庄自然生成、旋转／镜像、块实体初始化、箱子内容和随机 processor，存档重启后继续生成／使用；另验证旧区块不被重刷、重复 reload 不累加池权重、其他数据包条目仍在。客户端画面另验。

### 已核实的接入能力与尚未验证项

- Paper `LifecycleEvents.DATAPACK_DISCOVERY` 的 owner 是 `BootstrapContext`；`DatapackRegistrar.discoverPack` 支持路径／URI、自动启用和位置。现插件仍使用 legacy `plugin.yml`，没有 bootstrapper，不能直接在 `onEnable` 使用该启动前事件。
- NMS `StructureTemplatePool` 同时持有 `rawTemplates`、展开 `templates` 和 `maxSize`；有 `getTemplates`，没有公开的安全 append API。仅改一个列表会留下另一表示不一致。
- CE 26.9.1 的 `BukkitWorldManager.handleChunkGenerate` 和 `WorldStorageInjector.compareAndUpdateBlockState` 有区块生成存储及自定义状态更新路径；后者能创建 `BlockEntity` 并加入 CEChunk。该能力支持进一步实现，但本次没有用运行探针证明厨房生成时序与 controller 已正确接线。
- 以上结构链没有职业 registry 的客户端限制；其剩余工作是工程接入与运行／客户端验收，不能据此列为原版客户端技术上不可能。

本次仅新增此研究记录；没有将 profession／structure 的 catalog 状态改为 completed，也没有将上述拟实施路径包装成已通过的测试。
