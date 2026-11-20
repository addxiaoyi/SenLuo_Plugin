# 五类村庄厨房：1.21.11 原生接入字节码核查

核查日期：2026-11-20（Asia/Shanghai）。本记录只核查本地固定 JAR 与现有源码，未运行 Java、javap、Gradle 或服务器，也未实现自然生成。通过 Python 读取 class 文件的常量池、访问标记、方法描述符与 Code 指令；下面的调用链是静态证据，不能替代 Paper/Folia 运行验收。前序语义和风险见[边界研究](cookery-chef-vanilla-boundary-2026-11-19.md)。

## 1. 本地输入身份

Paper 文件位于 `../SenLuo_Plugin-continue/build/acceptance/v612/paper/versions/1.21.11/paper-1.21.11.jar`，完整 CE 插件位于同实例的 `plugins/craftengine-26.9.1.jar`。API 文件取自本机 Gradle 缓存；以下均为 SHA-256。

| 输入 | 字节数 | SHA-256 |
| --- | ---: | --- |
| Paper 1.21.11 build 132 | 29,560,892 | `3a73f0cc4020a5eb45629a67629b14263331c68de45552f8505df8060fa8ed43` |
| 完整 CraftEngine 26.9.1 插件 | 10,981,948 | `021260c87e3546730d321f4360b1744e6e33caa1d68e98ae30dcdbbdc347ae0f` |
| `craft-engine-bukkit-26.9.1.jar` | 2,224,587 | `011afd3b1cfcaf5fc4b4af4757843e5479e1be4a9763c1a97bde04dcb1c0795d` |
| `craft-engine-core-26.9.1.jar` | 5,947,954 | `3db3473986aefe52936aa807a0b5858355775fa16927eba96b701e84431a0d2b` |

Paper MANIFEST 为 `Implementation-Version: 1.21.11-132-c5eb079`、`Build-Number: 132`；`version.json` 为 world version 4671、protocol 774、Java 21、data pack 94.1。这里没有据文件名推断版本，也未验证 Folia 二进制的同名成员完全一致。

## 2. 真实 CE state 的原生生成与 controller 路径

以下类均来自完整 CE 插件；NMS 类名针对 `bukkit.nms.v1_21_11`。

1. `worldgen.InjectedCustomChunkGenerator.applyBiomeDecoration(WorldGenLevel, ChunkAccess, StructureManager): void` 在委托目标 generator 的同名方法**之前**调用 `BukkitWorldManager.handleChunkGenerate(BukkitWorld, ChunkPos, Object, ChunkGenerationStage.FEATURE): Runnable`，委托完成后执行返回的 Runnable。另有带 `boolean` 的四参数重载仅直接委托，不能混同两条路径；真实服务器是否进入三参数入口仍须验证。
2. `handleChunkGenerate(...)` 先检查 stage enabled 和 manager disabled，读取 CEChunk、设置 ChunkAccess，在 section 数组同步块中对尚未注入的 sections 调用 `WorldStorageInjector.inject(...)`。返回的 Runnable 在 CEChunk dirty 时调用 `WorldDataStorage.writeChunkAt(...)`，成功后清 dirty。
3. `chunk.InjectedLevelChunkSection.setBlockState(int, int, int, BlockState, boolean): BlockState` 先调用原生 super，再在 `isActive` 时调用 `WorldStorageInjector.compareAndUpdateBlockState(int, int, int, Object newState, Object oldState, InjectedStorage): void`。`InjectedPalettedContainer.getAndSet/getAndSetUnchecked` 也有该回调；其普通 `set` 只有委托，不能把所有存储写入口都视为等价。
4. `compareAndUpdateBlockState` 识别 `DelegatingBlockState`，取得 `blockState(): ImmutableBlockState`，写 CESection、标记 CEChunk unsaved；需要 block entity 时构造 `new BlockEntity(BlockPos, ImmutableBlockState)` 并 `CEChunk.addBlockEntity(...)`。
5. CE `BlockEntity` 构造器通过 `state.behavior()` 的 `EntityBlock.createBlockEntityController(this)` 建 controller。当前 Cookery `StoveBehavior` 返回真实 `StoveController`，其构造器没有要求已加载世界。
6. `CEChunk.setBlockEntity` 设置 world、valid 和位置索引。`addBlockEntity` 对已 activated chunk 立即执行 controller `onLoad`、ticker 和 renderer；`activateAllBlockEntities(): void` 为尚未激活的实体设置 valid、建立 ticker/renderer、调用 `onLoad` 后将 chunk 标为 activated。

**推论及边界：**在正确注入且 active 的原生生成 section 中，模板直接放入 `ImmutableBlockState.customBlockState().minecraftState()`，可沿现有 CE 路径建立语义存储和 stove controller；不必先放显示替身、再等玩家可见后修补。此处没有证明具体厨房在实际 pipeline 中满足全部前提。必须同时观察原生 state、CESection、CEChunk、Bukkit CE 查询和 controller，不能只凭模型或上述方法存在判定通过。生成回调不得改用 Bukkit `getBlockAt`、跨区直接放置或同步加载邻区块。

## 3. 冷启动的可用次序与严格门槛

完整插件的 `paper-plugin.yml` 声明 `load: STARTUP`。`CraftEngine.onPluginEnable()` 在 `Config.delayConfigurationLoad()==false` 时依次执行：

1. `worldManager.delayedInit()`（Code offset 131–145）；该方法处理已有世界并注册 WorldInit/WorldLoad 等监听。
2. preEnable task registry；随后 reload managers、load packs/resources、run delayed tasks。
3. afterEnable task registry（offset 260–264）；最后安排 platform delayed enable。

`WorldInitEvent → ensureStorageWorld(World) → installStorageWorld(...)` 在首次 storage world 安装且有 Paper patch 时调用 `injectWorldGeneration(...)`；后者在目标 WorldGenContext 中安装 `InjectedCustomChunkGenerator`。因此由 Cookery `onLoad` 注册的独立 CE afterEnable 初始化任务，存在在首个世界生成前准备模板和有效池的可行路径，且 CE 已安排后续世界注入。不能让此早期任务依赖 Cookery 直到 `onEnable` 才赋值的静态实例，或直接把全部 UI/玩家初始化迁到 STARTUP。

最小实现必须在任何发布前核对：精确支持的 Paper/Folia 版本、`delayConfigurationLoad=false`、CE world manager initialized、`worlds.generation.feature=true`、所有所需块及属性/行为可解析、启动 registry/manager 已存在且没有生成 reader。延迟配置模式的 world manager 初始化在后续 delayed-enable 分支；没有真实生成屏障时必须拒绝激活厨房，而非排到一个 tick 后再补。此门槛的正确性仍要以冷启动运行记录证明。

## 4. 迁移与运行模板：保留稳定 key，不烘焙分配 ID

Paper 132 的可用签名/成员：

```text
StructureTemplateManager.readStructure(InputStream): StructureTemplate
StructureTemplateManager.readStructure(CompoundTag): StructureTemplate
public final StructureTemplateManager.structureRepository: Map
public final StructureTemplate.palettes: List<Palette>
Palette.blocks(): List<StructureBlockInfo>
public StructureBlockInfo(BlockPos, BlockState, CompoundTag)
```

`readStructure(CompoundTag)` 的实际 Paper 路径为 `NbtUtils.getDataVersion(tag, 500)`，随后 `MCDataConverter.convertTag(MCTypeRegistry.STRUCTURE, tag, sourceVersion, Version.getCurrentVersion())`，再 `StructureTemplate.load(blockLookup, migratedTag)`。这是本 JAR 的调用，不能用修改 DataVersion 整数代替迁移；源 3465 的旧 grass、chest/campfire/jigsaw 数据仍须逐项核对。

可实施流程是先校验原始哈希和有界 NBT，将每个源自定义状态/位置/属性保存到 sidecar，用内部合法状态使 native parser 不丢未知 mod palette，再运行目标迁移与加载；在**尚未发布和查询**的模板中，将相应 BlockInfo 替换为当前 CE 语义 ID 解析的实际 server state，保留迁移后的原版 NBT。该内部表示不进入世界，不能将它作为可见后处理方案。未知状态或侧车不完整必须拒绝；不用 `visualBlockState`，不保存 CE 数值 registry ID。

新加载模板的 Palette.blocks 是 `buildInfoList` 产生的 ArrayList；Palette 构造器保存该 list 并建立空查询 cache。发布前可用 ListIterator 替换 BlockInfo；还应按 native `addToLists/buildInfoList` 规则重新校验 full/nonfull/block-entity 分组与 y/x/z 排序。对已查询模板原地改 blocks 会留下 block/jigsaw cache，不能照做。最后按稳定 `Identifier` 将完整模板作为 Optional 存入 manager cache，或经等价公开 StructureManager 注册入口发布。

## 5. 有效池、processor 与存档编码

`StructureTemplatePool` 有 public 构造器 `(Holder fallback, List rawPairs)`、`getTemplates(): List`、`getFallback(): Holder`。内部成员为 private final `rawTemplates: List`、private final `templates: ObjectArrayList`、mutable `maxSize: int`；`getRandomTemplate/getShuffledTemplates` 使用展开的 templates，而编码/getTemplates 使用 rawTemplates。`maxSize` 的未计算初值为 Integer.MIN_VALUE。

应读取五个**实际已解析** houses 池，完整保留其他数据包/插件的元素对象、权重及 fallback，追加一个 weight 4 的 `StructurePoolElement.legacy(String stableLocation, Holder namedProcessor).apply(Projection.RIGID)`。先用原生构造器在内存准备 canonical 合并对象；全部 accessor 和五池预检通过后，才在可证明没有生成 reader 的阶段一致发布三份内部状态，并验证幂等和冲突。`getTemplates()` 不承诺可变；只改一个列表不正确。只反射 `Holder.Reference.bindValue` 也不正确，会遗漏 registry 的反向关系。私有 final 字段访问策略必须实际在目标 JDK/Paper/Folia 验证，不能以本次只读核查当作已验证写入。

稳定 processor key 须在原生 registry 初次加载时已存在；静态空壳不能在未绑定时放行生成。`StructureProcessorList(List): public`、`list(): List`，但内部 `list` 是 private final；可由窄版本 accessor 保留原 registered list 对象身份，绑定一个原生 `RuleProcessor(List)`。两条顺序规则可用 `RandomBlockStateMatchTest(BlockState, float)`、`AlwaysTrueTest.INSTANCE` 与 `ProcessorRule(RuleTest, RuleTest, BlockState)`，精确匹配当前成熟 tomato state，依次 0.33 输出成熟 chili/lettuce。保持一个 processor 的 first-match 随机流，不把两个结果改成等概率或拆成重新播种的两个 processor。

`SinglePoolElement.encodeTemplate` 只接受 `Either.left(Identifier)`；`Either.right(StructureTemplate)` 返回编码错误。已保存但尚未全部放置的 `PoolElementStructurePiece` 必须只引用稳定模板 location 和命名 processor holder，不能内联 transient CE 分配。应专测结构 start 已存档、邻 chunk 未生成就停机，重排其他 CE 内容分配后继续生成。

## 6. 未解决的在线 reload／生成屏障

`StructureTemplateManager.onResourceManagerReload(ResourceManager): void` 明确替换 resource manager 并清空整个 structureRepository。一次性 cache 注册无法保证数据包 reload 后模板仍可用。

CE `ResourceOperationCoordinator.acquire(): Lease` 用 AtomicReference.compareAndSet 从 null 独占申请，忙时抛 BusyException；`Lease.close()` 释放。它不是共享读锁，不会暂停生成 worker，也不能假设多条并发生成可同时持有。它能保护一次短事务不跨越 CE reload，但不能自行解决模板/processor/池的一致发布。完整本地插件未发现可取消的 CE pre-reload 事件；`CraftEngineReloadEvent` 不能充当前置屏障。Folia global scheduler 也不拥有所有 region 或暂停区块生成。

尚须设计并证明：在旧 CE state/registry/cache 失效前阻止新生成进入、排空现有生成 reader，完整重建后再发布一个一致 epoch；原生数据包 reload 和 CE reload 都要覆盖。仅完成事件后延时轮询/重绑不消除失效窗口。未有该保证前，不得声明支持在线热更新自然生成；“要求停机更新”也须有真实可执行的边界，不能只加一行文档后仍允许不安全更新。

## 7. 剩余证明与当前状态

下一实现应先分开验证：窄 accessor/失败回滚；完整迁移和所有状态/旋转；命名 location/processor 的原生 piece codec 往返；第三方池条目及权重不变；启动无 reader 次序；CE FEATURE 注入及真实 controller/存档链；再进入五 biome 的 Paper/Folia 自然新村庄、跨 chunk/region、旧区块不重刷、卸载/重启和两箱 loot 验收。手放 template/jigsaw 仅是夹具，不能替代自然选择证据。GPU/音频与 Chef 职业边界另验。

**当前：研究记录已完成；自然生成、私有 accessor 写入和在线重载屏障均未在本次实现或验收。**
