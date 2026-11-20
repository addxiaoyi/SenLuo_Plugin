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
