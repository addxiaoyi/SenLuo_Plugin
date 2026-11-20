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

还核实了一个具体的调度限制：固定 Paper/Folia 的 Moonrise `ChunkUpgradeGenericStatusTask.run` 在生成分支把 `ChunkStep.apply` 返回的 future 接入完成回调，但加载分支会对未完成 future 警告后在字节码 offset **322 执行 join**。因此，仅在 `ChunkStep.apply` 返回一个延迟 future 不能证明非阻塞，会有阻塞加载 worker 的路径；必须覆盖实际调度入口和加载分支，不能从 CompletableFuture 返回类型推定已获得安全异步闸门。

初始启动也需要服务器在 STARTUP 插件返回后、首次 `loadLevel` 前明确检查失败结果并沿原生控制流拒绝。只改变 CE 任务为抛异常仍会被平台插件启用捕获，Bukkit shutdown 也没有这个前置控制保证。

## 尚缺的实现输入

准确公开 CE tree 中没有 `InjectedCustomChunkGenerator`／`FastNMS` 实现；其依赖为独立 `craft-engine-nms-helper:1.0.234`，已检查的 source JAR 路径返回 404，POM 无 SCM 身份。本次尚未锁定它及固定服务器调度器的完整支持源码，不能把 CE 公共文件的局部修改当作完整屏障补丁。没有据此切换到不安全 fallback 或缩小读写覆盖要求。

下一实现须先补齐这些原生来源，再以已识别的 CE 源码和明确 PREMIUM 变体核对完整补丁，验证取消／消耗顺序、所有读写切点、重入、公平性、恢复所属执行上下文、失败持久化，以及 Paper/Folia 真实重载和五 biome 自然生成。当前总目标继续进行，完整屏障仍 OPEN。
