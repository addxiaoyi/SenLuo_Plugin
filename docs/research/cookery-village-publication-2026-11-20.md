# 五类村庄发布事务与冷启动窗口

2026-11-20。此批继续补齐自然厨房的代码前置；生产插件仍没有调用发布器，没有把厨房接入自然生成，也没有提供在线重载屏障。

## 一次性发布与回滚

`VillagePublicationTransaction` 接受有界、带类型的读写槽位。引用按对象身份核对，缓存条目另区分不存在、存在空值及具体对象；整数按值核对。事务先执行完整预检并读取所有旧槽位，再依次写入，最后核对整个新状态。同步或并发重入在任何回调前拒绝，成功或失败都不能重试同一事务。

原生适配器的完整写集是 21 个槽位：五个模板 cache 条目、一个原 registered processor 的 list，以及五个原 registered pool 各自的 rawTemplates、展开 templates 和 maxSize。没有重新绑定 holder 或替换注册表中的原对象，fallback 保持原身份；新增池条目后重置 maxSize。第三方元素、顺序、权重及展开选择必须一致。

原生候选并非因放在 public record 中就可信。发布准备重新运行固定来源迁移并逐项比较状态、位置和 NBT；保存快照后再次检查完整候选及当前 CE 双向语义映射。命名 processor 与池候选也从当前有效注册表重新准备、验证，所有反射 accessor 在写入前打开并核对固定 ABI。

写入前抛错与写入后抛错均纳入逆序恢复；后者包含抛错的当前槽位。只恢复仍是本事务新值的槽位，已是原值则保持，未知值不会被覆盖。恢复后的全量核对还包含未尝试和 no-op 槽位。原错误、各恢复错误及未确认恢复的槽位分别保留；恢复出错始终报告 `INDETERMINATE`，不冒充成功或干净回滚。进程崩溃、失去排他窗口、任意 accessor 越界副作用不在原子性保证内。

## 排他窗口是调用前提

通用发布器要求调用方已经持有覆盖整个准备、发布和失败恢复的真实排他窗口，并持续验证活动 registry／manager 根对象及资源世代。一个布尔标记、CE lease 或 Folia global scheduler 不能单独建立这个窗口。发现窗口失效后，本次事务停止所有 accessor，包括恢复写入，并明确保留未确认状态。

固定 Paper 132／Folia 14 字节码揭示了可进一步验证的初始启动路径：`DedicatedServer.initServer` 在调用 `loadLevel` 前同步 `enablePlugins(STARTUP)`；`MinecraftServer` 构造器已建立 StructureTemplateManager 和 registry 根。Paper 的受支持 `createWorld` 要求已有原生世界，Folia 的同入口不支持，因此正常初始 STARTUP 阶段没有通过该 Bukkit API 建立的生成读者。

窗口检查必须区分真正的 CE afterEnable：同一个 `PluginTaskRegistry.executeTasks` 也用于 beforeEnable。固定 CE 26.9.1 的 `CraftEngine.onPluginEnable` 中，afterEnable 调用点为字节码 offset 264，beforeEnable 为 150；延迟配置模式在 afterEnable 前尚未完成 world manager 和资源解析，必须拒绝。主线程应比较实际 `server.getRunningThread()` 身份；两端的 `isSameThread()` 实际接受 TickThread，不能作为独占主线程证明。Folia 的 `isReady`、tick count 也不能替代真实启动阶段。

`VillageColdStartWindowNative` 提供尚无调用者的窄版本窗口实现：校验实际全局 server／CE／registry／manager／coordinator／world manager 身份，实际服务器线程、原生与 Bukkit 世界为空、启动／配置／FEATURE 条件和完整原生启动／CE 后置任务栈，再申请自己私有的真实 CE lease 并复核。它不接收可注入的“已就绪”布尔值，也不接受宽松版本 fallback。固定类资源摘要检查以有界读取完成；资源字节摘要不是任意运行期插桩后方法体的证明，未知资源或偏移直接拒绝。

窗口同步持有至准备、发布及回滚结束。错误检查保持失效，原线程仍可在 finally 释放自己的 lease；其他线程的 close 在改变关闭状态或释放前拒绝，不能在原线程仍写入时撤掉保护。辅助 ownership 的测试使用真实 CE coordinator，它自身不实现生成排他窗口。此实现仅约束固定受支持初始启动流程，任意第三方直接 NMS 建世界、重定义字节码及生命周期旁路不在保证内。

这些是固定输入的静态调用证据，不能替代目标服务器中首次启动、实际 private-final 字段写入、注册表一致性和排他窗口的运行验证。此前已完成的单元检查和旧世界不能代替新候选验收。

另一个启动失败边界已经核实：CE `PluginTaskRegistry.executeTasks` 会捕获每个任务抛出的 `Throwable` 并继续。未来 afterEnable 接线不能只从回调抛错就假定服务器停止；尤其 `INDETERMINATE` 状态必须有明确且真实生效的启动失败响应，不能在部分发布后继续载入世界。本批没有注册这样的回调。

## 未完成的端到端要求

完整自然生成仍需要启动集成、数据包在原生注册表读取前发现／启用、实际 CE FEATURE 注入和 controller 持久化、五 biome 自然选择、结构片命名 codec 与邻区块继续生成、两箱战利品、重排无关 CE 分配后的重启，以及 Paper/Folia 真实玩家与客户端验收。CE reload、原生数据包 reload、disable 和生成 reader 的统一进入／排空屏障继续 OPEN；冷启动发布代码没有缩小这些要求。
