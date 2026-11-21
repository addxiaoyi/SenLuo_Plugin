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

## GP2 长握手触发链的后续复核（analysis-3）

后续将 v2 辅助程序复制为独立的 `build/InspectWatchdogJfrTrigger.java`，只导出 `RetransformClasses`、`ClassRedefinition`、`JavaErrorThrow`、`CompilationFailure`、`Compilation` 五类事件；v2 源文件及 analysis-1／2 均保留。一次与 v2 相同低内存参数的 Java 源码启动退出 0，写入全新 `build/watchdog-gp2-analysis-3/` 和独立 supervisor 日志，执行结束即释放 JVM 窗口；后续仅使用 Python 分析，没有启动服务器或修改产品代码。

此次窗口扩为 `2026-11-19T11:53:09Z`–`11:58:01Z`，覆盖完整录制的事件时间范围，以区分早期启动活动和报警邻近活动。81,596 条记录完整读至末尾，输入前后哈希仍为 `a0b51e055ddbd73542586b502e1dd5626f95a2a725291d07b4d3d13233d2376e`，状态 `SUCCEEDED`。五类事件完整计数如下：

| 事件 | 已导出数量 |
| --- | --- |
| `RetransformClasses` | 2 |
| `ClassRedefinition` | 2 |
| `JavaErrorThrow` | 498 |
| `CompilationFailure` | 44 |
| `Compilation` | 207 |

`fieldExtractionErrors=0`、`intentionallyOmittedFields=0`，但不能写成“全部无截断”：有 **1 次文本截断**，是北京时间 19:53:19.568501380、`ServerMain` 的 JNA `UnsatisfiedLinkError`／`numa` 加载消息，原始 20,446 字符，按输出上限保留 4,096 字符并明确标记；发生在握手前约 102 秒。另有 **68** 条事件的原录制栈标记为 truncated，输出未再裁掉原录制帧（`outputStackTruncated` 全为 false）。下述握手前邻近事件的 35 帧完整，两个重转换事件的 33／17 帧也都未截断。

| 新产物 | SHA-256 |
| --- | --- |
| `watchdog-gp2-analysis-3/summary.json`，4,787 字节 | `87703ad2a41d6618c2208815ebcd67a4844d24073a53e19047cad631c13a8daf` |
| `watchdog-gp2-analysis-3/events.jsonl`，6,031,586 字节 | `f9fc123c61d6190ff59d7a546e881d2547ea4974998ecc6efb05c3b7f5f8d296` |
| `InspectWatchdogJfrTrigger.java`，18,173 字节 | `a528c028c7958a945e8db57d2b730ce1b5ac9b1b10b573a7ec5181e32515623c` |
| `watchdog-gp2-analysis-3-supervisor.log`，867 字节 | `b9014c5beb15aa0a4af4583eb76f2cda6332a8be690ddb614ae25d0e097f170a` |

### 类重转换发生在更早的 CE onLoad

两条 `RetransformClasses` 的线程都是 `Server thread`（Java id 52），不是报警区域线程 id 63；每次 `classCount=1`：

| 北京时间起止 | duration | redefinitionId | 明确可见的调用路径 |
| --- | --- | --- | --- |
| 19:54:02.973721147–19:54:03.175839523 | 202.118376 ms | 2 | `InstrumentationImpl.retransformClasses0` → CE shaded ByteBuddy install → `MerchantItemMatchAgent.install:53` → `RuntimePatcher.installMerchantItemMatchHook:214` → `BukkitCraftEngine.onPluginLoad:231` |
| 19:54:03.175914836–19:54:03.254844304 | 78.929468 ms | 3 | `InstrumentationImpl.retransformClasses0` → `MerchantItemMatchAgent.install:55` → 同一 runtime patcher／onLoad 路径 |

两条 `ClassRedefinition` 均指向 **`net.minecraft.world.item.trading.ItemCost`**：19:54:03.127525923 的 modification count 为 1、redefinitionId 为 2；19:54:03.204380571 的 count 为 2、id 为 3。这证实该冻结候选中 CE 商人物品匹配钩子曾修改此类，但第二次重转换结束距长握手开始约 **58.069 秒**；不能把它们作为长握手期间正在发生重转换的证据，也不能仅据时间分离就排除早期重转换的任何后续影响。

44 条 `CompilationFailure` 中，40 条消息为 `retry without subsuming loads`，4 条为 `Jvmti state change invalidated dependencies`。后 4 条位于 **19:54:03.174868716–19:54:03.263478100**，compileId 为 16406、16318、16354、16390，与上述较早的 instrumentation 阶段邻近。未从这些记录建立它们和约 58 秒后长握手的触发关系。

### 握手前 6.3 毫秒的方法句柄查找

长握手开始前 **6.300580 ms**，北京时间 **19:55:01.317907810**，区域线程 #0 记录了一条 `NoSuchMethodError`，目标是 `DirectMethodHandle$Holder.newInvokeSpecial(Object,long,long)`。该事件 35 帧完整，相关调用链为：

`MethodHandleNatives.resolve` → `MemberName$Factory.resolveOrNull:1006` → `InvokerBytecodeGenerator.lookupPregenerated:699`／`generateCustomizedCode:708` → `DirectMethodHandle.makeAllocator:140` → `MethodHandles$Lookup.findConstructor:2838` → `InnerClassLambdaMetafactory.buildCallSite:232` → `LambdaMetafactory.metafactory:341` → `MinecraftServer.tickServer:1700` → world region tick。

该完整栈没有 CE 或 SenLuo 帧，且对应的是 JDK 方法句柄／lambda 链接活动。**不能把 JFR 的 `JavaErrorThrow` 事件名称直接当作未捕获异常或插件崩溃**。固定运行时随附源码给出了这里的容错路径：

- `MemberName.java:942–977` 的解析函数捕获 `ClassNotFoundException`／`LinkageError`，保留为未解析结果；`resolveOrNull:1004–1009` 在未解析时返回 null。
- `InvokerBytecodeGenerator.java:644–649` 通过 `resolveOrNull` 查找预生成方法；`:707–712` 在未找到预生成方法时回退生成定制字节码，而不是仅因此结束调用。
- 后续静态路径可到 `InvokerBytecodeGenerator.loadMethod:247–250` 的 hidden-class 定义，再到 `MethodHandles.java:2498–2505` 的 `JavaLangAccess.defineClass`。这解释了该类查找事件可以出现在正常回退流程中；它**尚不能证明**随后那条长原生握手正由这次 hidden-class 定义触发。

本次核对的本地固定运行时为 **Microsoft `21.0.13.0.2+1-LTS`**，release 中的源码标识为 `86770f4f323a`。随附 `lib/src.zip` 为 53,082,420 字节，SHA-256 `98694317a1a410d34ca12eda098ec99e57091554442b35a9b171d6f233c78956`。关键源码条目的 SHA-256 为：

| `src.zip` 条目 | SHA-256 |
| --- | --- |
| `java.base/java/lang/invoke/MemberName.java` | `0db2e561732422a59ca0dcecd6f9bacfaea60abd8f1d9ecfb4dd8cc986ee82c0` |
| `java.base/java/lang/invoke/InvokerBytecodeGenerator.java` | `f8b86811f7d119a5a0a75e804e5e3aee503565e56744c8a2cb0a0fe787c672b6` |
| `java.base/java/lang/invoke/MethodHandles.java` | `3cd6f1229de979b3da578acc4bc49af394dd930e411862255de977d2252b1a0f` |
| `java.instrument/sun/instrument/InstrumentationImpl.java` | `c58d5cb2d81423326755c29df3e7b7138d075ab1493504709a2667f1627005db` |

该固定 Java 源码包没有 HotSpot `.cpp` 或 `metadata.xml`，本地检索也未得到能直接对应这次 VM 握手的原生调用点。因此此次静态查证止于 Java／native 桥接边界，不借其他版本原生源码把某条可能路径写成已证实的触发链。

在 **19:55:01.324208390–19:55:10.791462236** 的长握手范围内，这五类已记录事件没有相交条目；这只是已启用事件和记录阈值下的观察，不能用来声称没有编译、类加载、异常处理或 instrumentation 活动。此次新增证据将早期 CE 商人类重转换与握手邻近的 JDK lambda 链接分开，但仍未找到长握手的目标线程、原生任务或耗时原因。根因继续为 **OPEN**；后续应针对固定 JDK 的原生握手调用链和可匹配的完整线程／事件证据继续核对。

## 固定 Microsoft HotSpot 原生源码核查

本轮仅做官方源码和既有产物的只读核查，**没有运行任何 JVM、服务器或 Gradle**。现已取得与运行时所声明源码提交匹配的原生文件，补足上一节“本地源码包不含原生代码”的限制；没有用近似 OpenJDK tag 冒充固定版本。

### 版本匹配与一手来源

官方仓库为 [microsoft/openjdk-jdk21u](https://github.com/microsoft/openjdk-jdk21u)。`git ls-remote` 对 `refs/heads/release/jdk-21.0.13.0.2_1` 返回完整提交 **`86770f4f323a4d711534e07ebef5ce2419f2ecf8`**，与固定运行时 release 文件的 `SOURCE=.:git:86770f4f323a` 前缀一致；这是 release 分支的解析结果，不把它称为已核实的 tag。后续所有文件均从[这一不可变提交](https://github.com/microsoft/openjdk-jdk21u/commit/86770f4f323a4d711534e07ebef5ce2419f2ecf8)获取。

另将该提交中的 `MemberName.java`、`InvokerBytecodeGenerator.java` 与固定 JDK 随附 `src.zip` 内容逐字节比较，两者都完全相同，SHA-256 分别为 `0db2e561732422a59ca0dcecd6f9bacfaea60abd8f1d9ecfb4dd8cc986ee82c0`、`f8b86811f7d119a5a0a75e804e5e3aee503565e56744c8a2cb0a0fe787c672b6`。这将核查绑定到运行时声明的源码版本，但没有声称已重新构建并逐字节复现本地 HotSpot 二进制。

21 个选定的一手源码文件及下载 URL、长度、SHA-256 已保存于忽略目录 `build/watchdog-native-source-audit-1/`。最终清单 `manifest-final.json` 为 8,639 字节，SHA-256 **`8dcc1ece2d7aab0615c337121724dc3576decf834d46cea402bc3e9b3920269e`**。这是有边界的选定文件核查，不是完整原生调用图扫描。

### JFR 字段能证明什么

精确版本的 [`vmThread.cpp:259–283`](https://github.com/microsoft/openjdk-jdk21u/blob/86770f4f323a4d711534e07ebef5ce2419f2ecf8/src/hotspot/share/runtime/vmThread.cpp#L259-L283) 与 [`metadata.xml:702–710`](https://github.com/microsoft/openjdk-jdk21u/blob/86770f4f323a4d711534e07ebef5ce2419f2ecf8/src/hotspot/share/jfr/metadata/metadata.xml#L702-L710) 给出以下语义：

| 字段／时长 | 同版本实现 | 对本次记录的解释边界 |
| --- | --- | --- |
| `caller` | 来自 VM operation 的 `calling_thread` | 是操作请求来源，不是被握手的慢线程或某个锁的 owner；嵌套 VM operation 可继承外层调用方 |
| `blocking` | 此事件提交路径固定设为 true；元数据定义为请求线程等待操作完成 | 可解释为同步 VM 请求，不能单凭此布尔值断言 Java monitor 争用或死锁 |
| `safepoint`／`safepointId` | 取 operation 的 `evaluate_at_safepoint()`；false 时 id 设为 0 | 本次 false／0 与非全局 safepoint 握手实现一致，不是 GC 暂停证明 |
| 事件 duration | 在 VM Thread 上，事件计时包围 `op->evaluate()` | 是执行该 VM 操作所经过的墙钟时间，包含内部处理／等待／调度；不是 CPU 时间，也不包含提交后、开始 evaluate 前的全部排队时间 |

[`VMThread::execute:531–557`](https://github.com/microsoft/openjdk-jdk21u/blob/86770f4f323a4d711534e07ebef5ce2419f2ecf8/src/hotspot/share/runtime/vmThread.cpp#L531-L557) 将普通请求的当前线程记为 calling thread，然后同步等待操作处理完成；[`wait_until_executed:353–379`](https://github.com/microsoft/openjdk-jdk21u/blob/86770f4f323a4d711534e07ebef5ce2419f2ecf8/src/hotspot/share/runtime/vmThread.cpp#L353-L379) 同时展示提交和完成等待。嵌套时继承外层调用方的逻辑在同文件 `:391–404`。因此，现有 id 63 字段将长操作关联到该区域线程的 VM 请求上下文，但不提供请求入口栈。

### `HandshakeAllThreads` 的实际执行路径

同提交 [`handshake.cpp:236–301`](https://github.com/microsoft/openjdk-jdk21u/blob/86770f4f323a4d711534e07ebef5ce2419f2ecf8/src/hotspot/share/runtime/handshake.cpp#L236-L301) 的 `VM_HandshakeAllThreads`：

- `evaluate_at_safepoint()` 明确返回 false。
- 向当时的 Java 线程列表发布同一个 handshake operation；VM Thread 尝试代替符合条件的线程执行任务，并持续处理／退让，直到操作完成。
- 事件时间也覆盖发布之后、处理循环之前的 `SystemMemoryBarrier::emit()`（使用该机制时，否则为 fence）。记录没有把发布、内存屏障、closure 工作、循环等待与 OS 调度耗时分开，不能把 9.467 秒全部算成等待某个目标线程。
- 事件名只表示这类 VM 操作，不唯一标识具体 closure。一个目标迟迟不能完成、closure 工作本身耗时，以及处理线程未获及时调度，均需要进一步运行时证据区分，不能只看总时长选择其中一种。

[`Handshake::execute:352–355`](https://github.com/microsoft/openjdk-jdk21u/blob/86770f4f323a4d711534e07ebef5ce2419f2ecf8/src/hotspot/share/runtime/handshake.cpp#L352-L355) 的**单参数、全线程**入口创建这一 VM operation，再提交给 VM Thread。接受具体目标线程的重载走另一条路径，不能把所有针对单线程的 JVMTI stack／monitor 查询都混称为本次 `HandshakeAllThreads`。

### 区域线程可能到达的同版本入口

本轮确认以下静态路径可由正在执行 Java／native 桥接的线程请求全线程去优化握手；它们是精确版本里的**候选入口，不是本次已命中的入口**：

1. **普通或 hidden class 的加载、类层次／链接依赖变化。** [`SystemDictionary::resolve_hidden_class_from_stream:790–829`](https://github.com/microsoft/openjdk-jdk21u/blob/86770f4f323a4d711534e07ebef5ce2419f2ecf8/src/hotspot/share/classfile/systemDictionary.cpp#L790-L829) 将 hidden class 加入类层次并链接；普通类定义也调用类层次更新。`InstanceKlass` 中的[链接依赖更新 `:922–933`](https://github.com/microsoft/openjdk-jdk21u/blob/86770f4f323a4d711534e07ebef5ce2419f2ecf8/src/hotspot/share/oops/instanceKlass.cpp#L922-L933)及[类层次更新 `:1247–1270`](https://github.com/microsoft/openjdk-jdk21u/blob/86770f4f323a4d711534e07ebef5ce2419f2ecf8/src/hotspot/share/oops/instanceKlass.cpp#L1247-L1270)按依赖与当时状态标记代码并提交去优化。因此 lambda／方法句柄生成的 hidden class 是一条代码上可走通的候选链，但先前 `NoSuchMethodError` 的邻近时间不足以证明此次已经沿它发起握手。
2. **CallSite target 更新后的依赖失效。** [`MHN_setCallSiteTargetNormal`／`Volatile:1205–1233`](https://github.com/microsoft/openjdk-jdk21u/blob/86770f4f323a4d711534e07ebef5ce2419f2ecf8/src/hotspot/share/prims/methodHandles.cpp#L1205-L1233)通过 `DeoptimizationScope` 处理依赖方法。这是另一个可由 Java 线程的 native 调用到达的入口，现有 JFR 没有证明本次执行了它。

两类路径可到达 [`DeoptimizationScope::deoptimize_marked:151–215`](https://github.com/microsoft/openjdk-jdk21u/blob/86770f4f323a4d711534e07ebef5ce2419f2ecf8/src/hotspot/share/runtime/deoptimization.cpp#L151-L215)。如果所需 generation 已提交，该函数会直接返回；否则可能等待其他去优化提交，或由当前线程提交。最终 [`Deoptimization::deoptimize_all_marked:1033–1053`](https://github.com/microsoft/openjdk-jdk21u/blob/86770f4f323a4d711534e07ebef5ce2419f2ecf8/src/hotspot/share/runtime/deoptimization.cpp#L1033-L1053) 在非 safepoint 场景使用名为 **`Deoptimize`** 的 closure，经单参数 `Handshake::execute` 请求全线程握手。**不能把“加载／链接一个类”简化成“必定发生一次握手”**，更不能由此直接给本次长握手定名。

同时，operation 名称确实存在其他来源：[`ObjectSynchronizer::deflate_idle_monitors:1654–1709`](https://github.com/microsoft/openjdk-jdk21u/blob/86770f4f323a4d711534e07ebef5ce2419f2ecf8/src/hotspot/share/runtime/synchronizer.cpp#L1654-L1709) 的后台 monitor 清理也发布全线程握手。因此只看 `HandshakeAllThreads` 名字不能区分 closure。另一个需要分开的路径是 [`VM_RedefineClasses::flush_dependent_code:4123–4137`](https://github.com/microsoft/openjdk-jdk21u/blob/86770f4f323a4d711534e07ebef5ce2419f2ecf8/src/hotspot/share/prims/jvmtiRedefineClasses.cpp#L4123-L4137)：它要求已经位于 safepoint；去优化函数在这种状态直接遍历线程，而非进入上面的非 safepoint 全线程握手路径。不能因较早栈中有 ByteBuddy／retransform 就把两条路径合并。

### 当前仍缺少的运行时证据

精确版本的 JFR `ExecuteVMOperation` 元数据只有 operation、safepoint、blocking、caller 和 safepointId，没有记录 handshake closure 名称、各目标线程完成进度或请求栈。因此 analysis-2 中的 9.467 秒记录仍不能确定是 `Deoptimize`、哪条具体加载／CallSite 入口，或哪个目标拖延。

同版本 `VM_HandshakeAllThreads::cause()` 会返回 closure 名称；`vmthread` debug 日志可输出该 cause，`handshake` info 日志可输出 closure、目标总数及完成耗时（`handshake.cpp:224–232`）。这是今后固定候选、资源允许时可补采的区分信息；本轮没有启动进程去补造历史记录，也没有更改现有 JVM 参数。若要确立耗时根因，仍需与该操作匹配的 closure／请求路径、慢目标线程状态以及同一时段 OS 调度／CPU 证据，不能依赖邻近错误、空白采样或静态候选链。历史根因继续 **OPEN**。


## 原始录制中的安全布尔标记快照

随后用独立、有界 helper 仅读取原始 JFR 的 `jdk.BooleanFlag` 中 `name=UseSystemMemoryBarrier`；不导出环境、系统进程、JVM 参数或其他标记值。完整读完 **81,596** 条，原始 JFR 前后仍为 `a0b51e055ddbd73542586b502e1dd5626f95a2a725291d07b4d3d13233d2376e`。500 条 BooleanFlag 中命中一条：

- UTC `2026-11-19T11:53:09.273600422Z`，即北京时间 19:53:09.273600422。
- `UseSystemMemoryBarrier=false`，`origin=Default`。
- 录制中没有 `jdk.BooleanFlagChanged` 记录；这只描述留存数据，不把未记录的变更事件当作完整观测。

派生报告 `build/watchdog-gp2-flags-1/summary.json` SHA-256 为 `11ed0a29d550c67a879ef6b7330e963a9c686c4b1e950f5315d341ccd673c1e7`，成功日志为 `build/watchdog-gp2-flags-1-supervisor-2.log`。首个 helper 在格式化 origin 字段时失败且未创建输出目录；失败源与原日志保留，修复后从原始录制重新完整读取，没有覆盖原 JFR、analysis-1/2/3 或首个失败日志。

这补充了实际运行值，不能只拿静态默认值代替。现有快照不支持该 JVM 已启用 `UseSystemMemoryBarrier` 的解释，因此不能继续把 Windows `FlushProcessWriteBuffers` 当作本次 9.467 秒操作的已证实阶段或根因。该操作仍缺 closure、目标线程和请求栈；没有因此证明去优化候选或其他握手阶段已被定位。历史 watchdog 根因继续 **OPEN**。
