# CE 26.9.1 源码身份与完整资源屏障边界

2026-11-20。本批只读核对官方 Maven 源码和第一方 Git 历史，没有替换依赖、构建 CE、启动 JVM／服务器，也没有发送上游消息或 PR。

官方 26.9.1 core／Bukkit sources JAR 与公开提交 [`2b9e6f59a0bf4dac7f71958c0ea96c14b8e44985`](https://github.com/Xiao-MoMi/craft-engine/commit/2b9e6f59a0bf4dac7f71958c0ea96c14b8e44985) 共核对 **2,033 个 Java 文件**，仅规范化 CRCRLF／CRLF 换行后 **2,032 个 Git blob 完全相同**。唯一例外 `VersionHelper.java` 是公开 Git 的 `PREMIUM=false` 与发布源码的 `PREMIUM=true`；只替换这一条声明后其他字节全部相同。该差异单独保留，不能把公开源码说成完整二进制的可复现构建证明。

| 官方源码包 | 字节数 | SHA-256 |
| --- | ---: | --- |
| [core 26.9.1](https://repo.momirealms.net/releases/net/momirealms/craft-engine-core/26.9.1/craft-engine-core-26.9.1-sources.jar) | 1,484,006 | `680db25e3c6bde661e02b353e50988afefdbbc6b750ccee534eb84831b712d80` |
| [Bukkit 26.9.1](https://repo.momirealms.net/releases/net/momirealms/craft-engine-bukkit/26.9.1/craft-engine-bukkit-26.9.1-sources.jar) | 878,941 | `f53afe676bb5f14a8cd35380c1b741e69f67416bbb943cffd82d0a32a68076e6` |

相近的 `26.9` tag 只匹配 1,974 项，已拒绝作为准确基线。原始下载、完整逐文件对照、Git tree、差异源码和可离线复算脚本位于本地 `build/followups/ce-upstream-resource-barrier-6/`；已提交的[摘要记录](craftengine-resource-barrier-source-2026-11-20.json)保存输入身份和所审阅七份源码的摘要。

## 已锁定的协作边界

`CraftEngine.reloadPlugin` 在资源修改前申请独占 coordinator；`onPluginDisable` 则直接禁用 manager，没有同一读者排空机制。`BukkitWorldManager.handleChunkGenerate` 仅检查 stage 和 disabled，不能保证覆盖 CE 语义为空再重绑的窗口。`ItemEventListener` 的 CE 回调仍位于 Bukkit 交互事件分发内部，提前执行原生 fallback 会改变后续监听器取消的时序。这些源码结论与此前固定运行 JAR 字节码相符。

完整方案需要 CE 与原生服务器共同参与：写入先停止新准入并异步排空；原生玩家操作在最早 CE 读取之前进入，并包围完整事件、晚期原生物品处理、消耗／创造恢复和最终记账；普通破坏、支撑丢失、形状／邻居与种植上下文也须涵盖；生成任务在获得会被写入方依赖的执行所有权之前准入。CE reload、disable、原生数据包 reload 和 cache 清空都必须参与。受保护回调中的重入写入不能等待排空自身。

此前本地 `ce-upstream-resource-barrier-6/` 字节码审阅已证明加载路径会在 offset **322 执行 join**，当时没有证明生成路径不会阻塞。后续 `native-scheduler-source-7/` 将固定源码与实际分支目标对照，补足结论：**生成和加载两个非空正常分支都会到达同一个 join322**。生成路径 `apply113 → whenComplete122 → goto177 → goto274`，加载路径 `apply172 → 177 → goto274`；共同的 `isDone276` 与 warn-once 条件只决定是否跳过警告到320，随后都是 `join322`。源码 `ChunkUpgradeGenericStatusTask.java` 的生成调用在97行、加载调用在110行、共同 join 在140行。因此，给 `ChunkStep.apply` 返回未完成 future 会占住任一分支的 worker；完成回调和 CompletableFuture 返回类型都不能充当非阻塞证明。上述历史结论只增补覆盖，没有将旧的局部检查改记为完整通过。

固定 Paper `1.21.11-132-c5eb079` 对应第一方提交 [`c5eb0790f199da6c38d0a650e1e5cd5415b28185`](https://github.com/PaperMC/Paper/commit/c5eb0790f199da6c38d0a650e1e5cd5415b28185)；固定 Folia `1.21.11-14-529aabc` 对应 [`529aabc13852021f6081f8a6069959a449c33785`](https://github.com/PaperMC/Folia/commit/529aabc13852021f6081f8a6069959a449c33785)，它的 `gradle.properties` 将 Paper 基底锁在 `79c77f5d7d409b692b67f75e03e5c138a9283fad`。两个 Paper tree 的 Moonrise feature patch 均为 Git blob `d1782ae71933a48dac3ec9bce54f3b4087220c23`，有效 `ChunkUpgradeGenericStatusTask` 源文件均为 `78fd90e0aa4d84ab13de8c101c5d31596e9ba1f7`。该 patch 只链接 Tuinity/Moonrise，没有声明其上游提交，故这里只用实际包含的 patch/source blob 固定快照。完整扫描两 Paper 与 Folia 的49个去重 server feature patch（5,994,005字节）并核对 Git blob，确认没有后续补丁改变这一个 run 实现。

任务所有权也已锁定：`ChunkTaskScheduler.schedule` 在调度 area lock 内 `createTask`，随后 `NewChunkHolder.setGenerationTask` 记录任务并增加全部邻区 usage；外层释放 ticket／schedule lock 后才调用任务的 `schedule()`。完成回调再于调度锁内减少邻区 usage、推进状态及依赖，解锁后调度后继。故 `schedule()` 或 `run()` 处的闸门均晚于区块／邻区所有权；只延迟入队也不满足写入方排空要求。新增准入必须在任务构造／`setGenerationTask` 前做不等待的决定；拒绝时还要保存需求、处理取消／卸载／停机，并在开放后回到正确 world／region 上下文重新取得锁和调度。现有源码没有一个直接可调用、已覆盖这些恢复状态的资源闸门，不能在调度锁内等待写入结束。

本地 `build/followups/native-scheduler-source-7/READY.md`、`feature-patch-inventory.json`、`run-bytecode-branch-map.json` 和 `source-and-bytecode-proof.json` 保留固定 JAR 身份、第一方 commit/tree、逐补丁来源、顺序应用 Folia region／profiler 后的源码 blob 与实际方法字节码。`verify_native_source.py` 可离线重算这四个调度类的源码和方法证据，无需 JVM；对应 SHA-256 已记入配对 JSON。这是静态来源及控制流复核，不是服务器验收。

初始启动也需要服务器在 STARTUP 插件返回后、首次 `loadLevel` 前明确检查失败结果并沿原生控制流拒绝。两个固定 Paper tree 的 `DedicatedServer.java.patch` 均为 `c8cd84714454c505dac5fb349b16ea376b39d9c6`；实际 Paper/Folia 字节码均为 `loadPlugins940 → enablePlugins950 → loadLevel1201`，中间没有运行／停机状态或资源失败结果的检查，Folia 的后续补丁没有改变这段启动流程。只改变 CE 任务为抛异常仍会被平台插件启用捕获，Bukkit shutdown 也没有这个前置控制保证。

## 已找到独立原生 helper 源码

此前准确公开 CE tree 中没有 `InjectedCustomChunkGenerator`／`FastNMS` 实现；对独立 `craft-engine-nms-helper:1.0.234` 检查的 source JAR 路径返回404，POM 无 SCM 身份，猜测的 `Xiao-MoMi/craft-engine-nms` 也返回404。这些原始路线和结果继续保留在本地 `ce-upstream-resource-barrier-6/`，不改写为成功。后续从作者公开仓库清单与第一方 GitHub 搜索找到正确的独立仓库 [`Xiao-MoMi/craft-engine-nms-helper`](https://github.com/Xiao-MoMi/craft-engine-nms-helper)。版本提交 [`3c4fe899726dccf6f9405902c278792e26638f18`](https://github.com/Xiao-MoMi/craft-engine-nms-helper/commit/3c4fe899726dccf6f9405902c278792e26638f18) 明确将 `projectVersion` 从1.0.233改为 **1.0.234**，对应 tree 为 `4b83d64e63429dc0f6fd1e7d7164ffc51ca27697`；没有用较新的 main 或相近版本代替。

官方 [`craft-engine-nms-helper-mojmap:1.0.234`](https://repo.momirealms.net/releases/net/momirealms/craft-engine-nms-helper-mojmap/1.0.234/craft-engine-nms-helper-mojmap-1.0.234.jar) 为1,012,708字节，SHA-256 `2b6fe20cbafa6fe4bae5e71da6fd5214eeab76e1bae537019465d0161890933c`，与其 Gradle module 元数据一致。其 **408个 class 与固定 `craftengine-26.9.1.jar` 内同路径 class 全部逐字节相同**，包括三个目标类。普通 helper 制品是混合重混淆产物，仅160／408相同，不能混作这个二进制对应。两个官方发布目录及 module 只有 binary shadow variant，没有列出 source 制品。

已下载并核对版本 tree 中的三个准确源码 blob：`FastNMS.java` 为 `720837de646a8b4e70cb09b723f68249dfa8f076`，`v1_21_11/FastNMSImpl.java` 为 `078dc2283597cb78f43637c5b561d498c1e9d806`，`v1_21_11/worldgen/InjectedCustomChunkGenerator.java` 为 `b38c6efc9fefaacbe0160cde291ea28a94840b55`。Gradle 发布坐标、版本声明、SourceFile／LineNumberTable／方法引用与这些源码对应；这证明明确的第一方版本关联和目标静态调用对应，**不等于已复现二进制构建**。没有 source JAR、签名构建证明或本地重编译来证明 Maven 输出由哪一个 Git commit 编译产生。

这个来源补足还确认了需要覆盖的实际读者：`FastNMSImpl.createInjectedChunkGenerator` 的139–140行创建 wrapper；生成器 `getFeatures` 的52–60行直接读取 `hasCustomFeatures`、`lastReloadFeatureTime`，调用 `fetchFeatures` 并更新本地缓存。三参数 `applyBiomeDecoration` 的182–208行先取得 CE stage 回调，再执行原生 target 装饰，然后独立读取 CE features、筛选 biome／feature 并调用原生 `PlacedFeature.place`，最后才执行先前的 Runnable。仅保护 `handleChunkGenerate` 或返回的 Runnable 覆盖不了这段直接读资源与放置；四参数 overload 的218–222行还会直接委托 target。`fillFromNoise` 的109–115行同样先取得 stage 回调，随后调用 target future，再 `thenApply` 执行 Runnable；其他 CARVER／SURFACE／STRUCTURE wrapper 也把原生 target 包在 stage 回调取得与最终执行之间。以上调用链已对照固定 class 的 BCI 和源码行表，不能从回调位置推定完整 native lease。

新证据在本地 `build/followups/ce-native-helper-source-8/`，包括 `REVIEW.md`、官方两份制品及 module、第一方版本 commit／tree、逐 class 对照、三个源码和静态调用／行表。`verify_helper_provenance.py` 可离线重算九份 source／build 文件的 Git blob、两份制品摘要及408项完全一致的 mojmap 对照，无需 JVM；配对 JSON 保存对应摘要。旧目录缺失来源的历史结论保留，新目录记录本次补齐。

## 尚缺的实现与验收

固定服务器调度类、启动切点和独立 CE helper 的公开来源现已补齐到上述证据范围；新增非阻塞准入／恢复协议、完整原生读写覆盖、重入写入与启动失败控制仍未实现，不能把源码取证记作完整屏障补丁。下一实现须以这些固定来源和明确 PREMIUM 变体核对完整修改，验证取消／消耗顺序、所有读写切点、重入、公平性、恢复所属执行上下文、失败持久化，以及 Paper/Folia 真实重载和五 biome 自然生成。当前总目标继续进行，完整屏障仍 OPEN；本批未启动 JVM／服务器，未发送上游消息或 PR。
