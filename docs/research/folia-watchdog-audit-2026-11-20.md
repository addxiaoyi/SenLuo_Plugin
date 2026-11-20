# Folia watchdog 证据复核（2026-11-20）

本次仅只读核对留存日志、候选 manifest 和已有 JFR 派生数据；没有启动 Java、Gradle 或服务器，没有修改进程、堆、系统内存或 watchdog。目标仍为 Minecraft 1.21.11／Folia 14／CraftEngine 26.9.1。历史 watchdog 根因继续 **OPEN**。

## 两个不同的失败样本

### 1. CE 延迟配方加载与 ByteBuddy 类插桩

候选：`build/acceptance/net/folia-restart-2/seed`。原始控制台在 2026-11-19 13:32:32（+08:00）报告 global region **6.3533891 秒**未响应；采样线程为 `Folia Region Scheduler Thread #0`，状态 `RUNNABLE`。48 条错误行保留的调用链为：

`CE shaded ByteBuddy NameMatcher / ExecutingTransformer → ClassLoader.defineClass → FastNMSImpl.createSmithingTrimRecipe:203 → BukkitRecipeManager.delayedLoad:201 → CraftEngine.onPluginEnable → FoliaGlobalRegionScheduler`。

该采样不是 `ChunkFullTask` 栈。没有直接出现 SenLuo Java 调用帧，但单次采样缺少插件帧不能证明插件配置、加载量或其他活动对耗时没有影响，也不能证明 ByteBuddy 本身存在某个确定算法缺陷。

本次重新计算并匹配的 SHA-256：

| 证据 | SHA-256 |
| --- | --- |
| 原始 `seed/console.log` | `8eb8b2342b9d3bfe005a54bdb655c4729ec2b70dff79514d91ab92f0c2886932` |
| 原始 `seed/startup.jfr` | `3bbba57ac6c53e63e1c9cff0c6aaee927b6735b4e6e785a6d565c56f6e6b0c48` |
| `build/restart-folia-2-jfr-summary.json` | `5bf17513319d9b3657e58aba32f7204d5409abf233d62c0c1805401a9b460702` |

对应验收记录为 `docs/research/series-restart-acceptance-2026-11-19.json`，其 source base 为 `b0dfe641bf477c8056fad30908fd476910894801`；它不是本次新 JAR 的运行结果。记录中的候选 Cookery SHA 为 `9a9376bd5be327027755480bf9043739530482c4749a14c088d4971d0eedd354`、probe 为 `873d29910251aa61921a4489b054492e0c80d234e9d49dd1cc996e06e16e5dc2`。这两个哈希来自当次运行收据，本次未以已经更新的共享实例 JAR 替代验证。

已有 JFR 派生摘要仅包含 `jdk.CPULoad`、`jdk.GarbageCollection` 和 `jdk.GCHeapSummary`。13:32:20.507–13:32:39.612 有 16 个 CPU 样本，`machineTotal` 均为 1.0；JVM CPU 占比在该段明显变化。watchdog 前最后一项已记录 GC 于 13:32:26.189 开始，最长暂停约 **66 毫秒**，其后直到报警未记录另一次 GC。因此这些数据不支持把六秒延迟归因于一段六秒 stop-the-world GC。CPU 饱和仅为同期观察；缺少完整线程执行采样和同期主机进程调度证据，不能据此认定某个其他进程是根因。

该轮另有后续返花拾取导致的客户端完整库存断言失败。它与启动 watchdog 是两个独立拒绝条件，不能互相解释或覆盖。

### 2. 原生区块完成任务

候选：`build/acceptance/gp2/folia-run-1/seed`。2026-11-19 19:55:10（+08:00）报告 `acceptance-world` 区域 `[0,0]` **5.9948197 秒**未响应；采样线程同为 region scheduler、状态 `RUNNABLE`，顶部是：

`ChunkFullTask.run:98 → RegionizedTaskQueue.ChunkBasedPriorityTask.executeInternal:529 → RegionTaskQueueData.drainTasks:279 → MinecraftServer.tickServer → Folia region tick`。

日志先于 19:55:01 报告 `Done (104.126s)`；报警发生于此后约 9 秒，而首名协议玩家到 19:56:16 才加入。因此该次报警不可能是由之后才开始的协议玩家操作或其官方资源 reader 直接触发。它仍不能单凭这条栈解释为 CE 插桩、内存不足或某个插件错误归属。

本次重新计算并匹配的 SHA-256：

| 证据 | SHA-256 |
| --- | --- |
| 原始 `seed/console.log` | `8498af1511b0e8da5fc9a20c55e5ad22bd175b1cce4600ba8ef7ca31d9e5cd67` |
| 原始 `seed/startup.jfr` | `a0b51e055ddbd73542586b502e1dd5626f95a2a725291d07b4d3d13233d2376e` |
| `failed-candidate/MANIFEST.json` | `6032ae160457a9a13acbdfe77bee34891db5a12ed4e35778be853eaa2ad3120e` |

另已直接重新读取该候选 `failed-candidate/runtime/` 中的以下文件，并与 manifest 完全匹配：

| 运行文件 | SHA-256 |
| --- | --- |
| `server.jar` | `f52c408490a0225611e67907a3ca19f7e6da2c6bc899e715d5f46844e7103c39` |
| `plugins/craftengine-26.9.1.jar` | `021260c87e3546730d321f4360b1744e6e33caa1d68e98ae30dcdbbdc347ae0f` |
| `plugins/KaleidoscopeCookeryPlugin-1.3.0.jar` | `789528ba1d585553cb31cef4a3658693f9f6bd2d52c4669471f967aeefe7ec59` |
| `plugins/CookeryAcceptanceProbe.jar` | `15397d3bbc276e7ce3c05207dfca203b706322fd8ad0b458942a6921a3a4b52a` |

对应整轮记录的 source base 为 `7a676978f9c4a825275a9f905c3f860ac05a6827`。留存 JFR 有完整格式 2.1 chunk，约 291.44 秒；已提交收据只校验了 header，尚未解码其 execution samples、GC、线程状态或 CPU 原因。不能把前一个 JFR 的 CPU／GC 结论移用到这个录制。

## 目前没有得到的结论

- 未从以上样本定位到一个可直接修改、且能解释报警的 SenLuo 同步等待或错误 Folia 区域归属调用。
- 已修复的重复 YAML 解析位于另一条路径；它不能自动解释 CE 原生 recipe／ByteBuddy 或区块完成栈。
- 较早的 Windows 内存不足事件、后来新运行的内存失败、某次进程退出 -1，以及这些 watchdog 不能仅按相邻时间合并成同一原因。
- 后续干净启动只能证明对应冻结候选和那次运行未报警，不能关闭历史根因。

## 最小后续验证

1. 先在资源允许时只读解码已留存 JFR，输出到全新分析目录并保留原文件摘要。优先核对 watchdog 前后 execution／native samples、thread CPU、Java monitor／park、safepoint／GC；不存在或未记录的事件必须明确标为缺失。该步骤不需重新启动服务器。
2. 若仍无法定位，再按固定 Folia／CE／插件／资源／场景哈希做冷 JVM 启动对照；区分新 JVM、首次 remap、已有 remap、全新世界及既有世界，不把它们统称一个 cold start。保持默认 watchdog、相同 JVM 与插件配置；保留所有失败。
3. 同期记录进程级 CPU、提交内存与页面文件余量，排除“只凭机器总 CPU 样本猜测进程”的问题。不得为了构造独占环境而终止或调整其他项目。
4. 如证据定位到同步 CE 插桩／原生 recipe 注册，先对固定依赖提出最小复现，再在单独候选中验证改动；不能把 Bukkit recipe 注册任意移到异步线程，也不能用提高 watchdog 阈值当作修复。

## GP2 留存 JFR 的只读解码补充

主任务随后将 GP2 的原始 JFR 流式解码到此前不存在的 `build/watchdog-gp2-analysis-1/`；首次分析使用 Python 只读复核该目录及原始 JFR header。之后按授权修正辅助程序 v2 的换行语法残留，以一次低内存源码启动写入全新的 `build/watchdog-gp2-analysis-2/`，退出码 0；既有产物及失败编译日志均保留。该补充取代上文 GP2“仅校验 header、尚未解码”的证据状态，完成“最小后续验证”第 1 项中的首次窗口解码；没有再次启动服务器、Gradle，或改动其他项目进程、堆与 watchdog。根因仍为 **OPEN**。

v2 单次执行使用 JDK 21，`-Xmx256m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8`；独立 supervisor 日志记录命令、辅助程序哈希、开始／结束时间和退出码。JVM 执行窗口已在结束后释放，后续分析仅使用 Python 读取派生产物。

### 产物与时间范围

- 两次解码状态均为 `SUCCEEDED`，均完整读取 **81,596** 条事件。v1 保留窗口内或与窗口相交的 **1,302** 条关注事件；v2 保留 **1,337** 条，多出的 35 条为 `ExecuteVMOperation`。两次 `fieldExtractionErrors`、`textTruncations`、`intentionallyOmittedFields` 均为 0。
- 录制事件范围为 `2026-11-19T11:53:09.265031196Z`–`11:58:00.674018280Z`。分析窗口为 `11:54:58Z`–`11:55:14Z`，即北京时间 **19:54:58–19:55:14**；相交事件保留原始起止时间，没有裁剪成伪造的窗口内起点。
- 下文用 **19:55:04–19:55:10** 作为报警附近的比较区间。控制台时间仅精确到秒，不能把这一人为比较区间当作 watchdog 检测的精确起止时间。
- 原始 JFR 为单个 2.1 chunk，10,141,900 字节，header `ticksPerSecond=2600000000`，`durationNs=291440548200`。输入前后 SHA-256、长度和修改时间均一致。

| 产物 | SHA-256 |
| --- | --- |
| 原始 `seed/startup.jfr`，解码前后相同 | `a0b51e055ddbd73542586b502e1dd5626f95a2a725291d07b4d3d13233d2376e` |
| `watchdog-gp2-analysis-1/summary.json`，5,441 字节 | `410aa0fe1fb010fe7a2d534e74be7b93030b5de42ceb5ba8ab08fd10107f3e37` |
| `watchdog-gp2-analysis-1/events.jsonl`，3,420,363 字节 | `1be4bae625b275a9c28f66458d76c1cb4d54e61e3ff4775db137574ee55e00c5` |
| `watchdog-gp2-analysis-2/summary.json`，5,589 字节 | `9a4e43adb87139800db85961c50d6c08ae1a2cd154a239300a6721fa07e23aee` |
| `watchdog-gp2-analysis-2/events.jsonl`，3,448,422 字节 | `ffd7f413c1cb30270635989c59df75afe9c4f8fae484155ca4f58d3759e53d30` |
| `InspectWatchdogJfr.java` v2，18,629 字节 | `7b977d01d47d2bf7e5c7cfd17c959094adebf3c9f23bf38624e60fdb5ea1fd8c` |
| `watchdog-gp2-analysis-2-supervisor-2.log`，718 字节 | `928fd37fc60f0b9f0dda146a285e04090a1b4de07700ca1dc40125e1d1411091` |

全录制包含 `jdk.OldGarbageCollection` **22** 条和 `jdk.MetaspaceAllocationFailure` **14** 条，不能沿用另一次 restart-2 的类型分布。v2 已将 `MetaspaceAllocationFailure` 纳入关注类型；**19:54:58–19:55:14 窗口内或与窗口相交的此类事件为 0**。这将全录制中的 14 条与本窗口区分开来，但不能据窗口缺少该类型就排除所有内存／分配问题，也不能据全录制计数认定内存不足是本次原因。

### GC 周期、暂停与 Safepoint

GP2 的 `gcId=58`，`G1Old`／`G1 Evacuation Pause` 事件从北京时间 **19:55:00.017111793** 到 **19:55:10.794305990**，周期持续 **10.777194197 秒**，与报警比较区间相交。其明细记录了以下主级暂停：

| gcId | 主级事件 | 北京时间起点 | 北京时间终点 | `event.getDuration()` |
| --- | --- | --- | --- | --- |
| 58 | `Pause Remark` | 19:55:00.871788445 | 19:55:00.918911146 | 47.122701 ms |
| 58 | `Pause Cleanup` | 19:55:10.791652574 | 19:55:10.791896567 | 0.243993 ms |

这个 **10.777 秒周期不能被称为 10.777 秒 stop-the-world 暂停**。首版派生产物的 `jdk.GarbageCollection.fields.longestPause=122519024`、`sumOfPauses=123153406` 是未规范化的数值，不能当作纳秒直接解释。v2 直接检查字段的 `Timespan` 注解，再通过 JFR `getDuration(name)` 规范化，得到 `gcId=58` 的 **`longestPause=47.122701 ms`**、**`sumOfPauses=47.366694 ms`**；与主级暂停明细相符。原始 chunk 每秒 2,600,000,000 ticks 的频率也能核对该单位差异，不把原始字段写成 122.519024 ms。

窗口内另一个 OldGC 是 `gcId=62`，19:55:12.510939880–19:55:13.542982959，周期 **1.032043079 秒**；其 Remark／Cleanup 分别为 **21.082814 ms** 和 **0.247175 ms**，发生在报警之后。完整窗口共 **9** 条 `GCPhasePause`，最长为上述 **47.122701 ms**；35 条 `GCPhasePauseLevel1` 中最长为同次 Remark 内的 `Class Unloading` **27.179482 ms**，20 条 Level2 中最长为 `G1 Complete Cleaning` **24.568723 ms**。层级事件相互包含，不能把它们相加当作独立停顿。

19:55:04–19:55:10 比较区间内，已保留数据中没有相交的 `GCPhasePause`、`GCPhasePauseLevel1` 或 `GCPhasePauseLevel2` 事件。完整窗口有 **16** 条 `SafepointBegin`，其中最长为 **22.813030 ms**（19:55:00.848956167–19:55:00.871769197，`safepointId=158`），比较区间内也没有相交的 Begin。全录制包含 318 条 `SafepointBegin`，但没有 `SafepointEnd`、`SafepointStateSynchronization` 或 `SafepointCleanup*` 等完整配对事件，因此不能据 Begin 事件单独给出全部 safepoint／应用停止时间。

### 与报警重叠的长 VM 握手

v2 新展开的 `ExecuteVMOperation` 提供了首版分析中没有的直接长操作记录：**`HandshakeAllThreads`，19:55:01.324208390–19:55:10.791462236，持续 9.467253846 秒**。事件执行线程为 `VM Thread`；字段中的调用方是 **`Folia Region Scheduler Thread #0`**，Java thread id **63**、OS thread id **15008**；`blocking=true`、`safepoint=false`、`safepointId=0`。该事件没有栈帧。它覆盖了 19:55:04–19:55:10 比较区间，是这一窗口内唯一相交的 `ExecuteVMOperation`。

这条记录将范围缩小到与报警区域线程关联的长时间 VM 握手，而不是仅凭区域线程采样缺失、OldGC 周期或机器 CPU 归因。**`safepoint=false` 也说明不能把它重新标记为 9.467 秒全局 safepoint／GC 暂停**。现有字段未说明握手任务由哪条 Java／native 路径触发、在等待哪个目标线程，或为何耗时；也没有证明是 SenLuo、CE、JDK 缺陷或外部 CPU 调度所致。

窗口内共有 **35** 条 `ExecuteVMOperation`：17 条 `HandshakeAllThreads`、6 条 `ICBufferFull`、5 条 `G1CollectForAllocation`、2 条 `G1PauseRemark`、2 条 `G1PauseCleanup`，以及各 1 条 `Cleanup`、`ThreadDump`、`RendezvousGCThreads`。17 条操作标记 `safepoint=true`，18 条为 false；最长即上述握手。附近记录如下：

| 北京时间起止 | 操作 | 调用方 | duration |
| --- | --- | --- | --- |
| 19:55:01.324208390–19:55:10.791462236 | `HandshakeAllThreads` | 区域线程 #0 | 9.467253846 s |
| 19:55:10.800308866–19:55:10.958975156 | `HandshakeAllThreads` | 区域线程 #0 | 158.666290 ms |
| 19:55:10.961634709–19:55:10.961670407 | `ThreadDump` | `Folia Watchdog Thread`，Java id 62 | 0.035698 ms |

watchdog 的 `ThreadDump` 发生在长握手结束后约 170 ms；控制台中的 `ChunkFullTask.run:98` 是报警时获取的栈，不能据此重建此前整个长操作内的调用路径。下一步应核对固定 JDK 的握手／VM 操作触发链、当时完整线程及 JFR 关联记录，或在受控复现中补充能说明握手目标的证据；不能只凭操作名宣布根因关闭。

### 报警线程的采样与等待

窗口内 `Folia Region Scheduler Thread #0` 的 Java thread id 为 **63**，与控制台 watchdog 的 PID 字段一致。该线程留存 **5** 条 `ExecutionSample`、**0** 条 `NativeMethodSample`，5 条执行采样全部位于报警之后：

| 北京时间 | 采样路径摘要 |
| --- | --- |
| 19:55:11.369937170 | ASM `ClassReader.readUtf:3702` → Paper `ReflectionRemapper.processClass:51` → CE `HookHelper.getSupportedPlugins:46` → `BukkitCompatibilityManager.onDelayedEnable:338` → global scheduler |
| 19:55:11.769597740 | `JarFile.getJarEntry:472` → plugin class loading → CE `RecipeRegistry1_21_2.prepareRegistration:27` → `BukkitRecipeManager.delayedLoad:171` → global scheduler |
| 19:55:11.942052670 | `Style`／`ItemStack`／ingredient hash／equality → `InjectedShapedRecipe.of:31` → `FastNMSImpl.createShapedRecipe:163` → `BukkitRecipeManager.delayedLoad:201` |
| 19:55:11.954649568 | `ObjectLinkedOpenCustomHashSet.tryCapacity:305` → `Ingredient.ofStacks:53` → `RecipeHelper.toMinecraftVisual:47` → `InjectedShapelessRecipe.of:31` → `FastNMSImpl.createShapelessRecipe:168` → `delayedLoad:201` |
| 19:55:12.233506617 | `PalettedContainer$Data.copy:492` → `SerializableChunkData.copyOf:473` → `NewChunkHolder.saveChunk:1714`／`unloadStage2:886` → world region tick |

该线程还记录了 **38** 条 park，均在 19:55:11.382215900 之后。最长一条为 **382.227458 ms**（19:55:11.382215900–19:55:11.764443358）：`Unsafe.park` → `CompletableFuture.join:2117` → CE `AbstractPackManager.loadResourceConfigs:729` → `loadResources:422` → `CraftEngine.lambda$onPluginEnable$1:500` → global scheduler。它是可直接观察到的 CE 同步等待，但时间位于本次区块 watchdog 之后，不能倒推为此前 5.9948197 秒报警的原因。其余区域线程 park 最长为 scheduler 的 **114.909638 ms**（19:55:12.490462069–19:55:12.605371707）。

19:55:04–19:55:10 内没有该线程的 execution／native sample、park 或 monitor 事件。整个比较区间只留存 **1** 条 Java execution sample（19:55:06.686070616，`VirtualThread-unparker`），native samples **259** 条来自 console handler、Netty selector、HTTP selector 和 JFR periodic task。**采样缺失不能证明区域线程没有执行、没有等待，或证明某一种调度／停顿原因。**

完整窗口的 **17** 条 `JavaMonitorEnter` 全部发生在 19:55:10.979886810 之后，没有来自该区域线程。最长为 CE common worker 的 class loader monitor，**129.849969 ms**（19:55:11.390060177–19:55:11.519910146）；其余包括约 15–19 ms 的 CE recipe manager 注册锁。唯一相交的 `JavaMonitorWait` 属于 `Finalizer`／reference queue，横跨很长录制区间，不能视作区域线程阻塞。

### CPU 样本及未决边界

完整窗口有 **12** 条 `CPULoad`，`machineTotal` 全为 **1.0**。报警比较区间内的 6 条如下，`jvmUser + jvmSystem` 仅是本 JVM 的记录值：

| 北京时间 | JVM user + system | machineTotal |
| --- | --- | --- |
| 19:55:04.324300963 | 0.2710748384 | 1.0 |
| 19:55:05.472629247 | 0.2606360720 | 1.0 |
| 19:55:06.483441106 | 0.2514778400 | 1.0 |
| 19:55:07.714576517 | 0.2545540733 | 1.0 |
| 19:55:08.730103922 | 0.2578608700 | 1.0 |
| 19:55:09.745717600 | 0.2537121000 | 1.0 |

窗口有 13 条 `ThreadCPULoad`，区域线程唯一一条在 **19:55:03.195602323**，`user=0.0018346267`、`system=0.0`，不在上述比较区间；不能用它代替报警期间的线程 CPU 时间线。`machineTotal=1.0` 与较低的 JVM 占比是同时观察，缺少同一时段外部进程和 OS 调度明细，不能给其他进程归因，也不能由此宣布 CPU 竞争已经确立或排除。

此次解码缩小了证据边界：GP2 有一条以报警区域线程为调用方、与报警比较区间重叠的 **9.467 秒阻塞 VM 握手记录**；另有长 OldGC 周期、机器 CPU 样本饱和，以及报警后 CE 加载／注册和短时间 join。规范化 GC 暂停仍仅为几十毫秒，握手记录也尚未给出任务／目标线程及耗时原因。下一步应围绕该握手核对尚未导出的关联记录，或采用固定候选的受控复现与 OS 进程级记录；不能因为事件数量、OldGC 周期长度、CPU 相关性或缺少区域线程采样而宣布根因已修复。
