# 村庄冷启动发布／回滚验收入口

2026-11-20。此入口仅存在于独立 `CookeryAcceptanceProbe.jar`，默认关闭。生产插件没有启动调用，也没有启用自然厨房；本批没有启动服务器。

## 入口与检查范围

验收插件在 `onLoad` 读取显式配置并注册 CE after-enable 任务。固定 Paper/Folia 会先完成全部插件的 onLoad，再启用 STARTUP 插件；验收插件自身默认 POSTWORLD 不会使注册过晚。真正回调仍须通过生产 `VillageColdStartWindow` 的固定版本、实际线程、原生／Bukkit 世界为空、根身份、CE 真实 lease 和精确启动调用点检查。

回调迁移五份固定来源模板、准备当前有效池及 processor，调用生产发布器。完整原生验证通过后，再执行独立只读观察，核对实际已发布槽位并抛出本次独有的主动回滚异常。只有原始 cause 身份相同、事务 `ROLLED_BACK`、恢复错误和未确认槽位均为空，原生深度恢复验证和独立的 21 槽位／完整模板 cache 复核也全部通过，才可能生成成功收据。

最终观察回调还在同一真实排他窗口、五模板已临时发布时调用 `VillageStructureCodec`。它对每个 biome 的四种原生 rotation 使用实际 `StructurePoolElement.CODEC` 和具名 processor／template，再由原生 `PoolElementStructurePiece.createTag`、有界 NBT 写读和真实恢复构造器完成 20 组往返；逐项核对完整 Tag、位置、边界、rotation、ground delta 与空 junctions。NBT 限制 64 KiB、深度 32，不将二进制字段顺序当作规范编码。另对侧车全部 41 个 CE 方块位置执行 3 mirror × 4 rotation 共 492 个变换，核对当前 CE 语义、完整属性和原状态未变。检查不放置世界，也不调用会填充模板查询 cache 的接口。

这部分新增离线测试只检查真实编译 ABI、原生产物调用边界和原生 placement 的 processor→mirror→rotation 顺序。完整 codec 需要 Minecraft 原生注册表 bootstrap，因此 **20／492 是未来探针的必需覆盖数，当前没有真实运行通过记录**；不是持久化重启、邻区生成、自然选择或实际 CE 世界存储证明。任何检查失败都会进入原生产事务回滚路径，不能达到成功 sentinel。

21 个槽位不等于 21 次实际写入：初始池的 maxSize 常已是 Integer.MIN_VALUE，它们应是 no-op。探针要求五个模板、processor 与十份池列表至少 16 次真实改变，另逐项记录最多五个 size cache 的改变／no-op；不会为凑数预先修改缓存。

生产事务增加了调用方最终只读校验入口，以及回滚后的原始深度一致性检查。后者在同一窗口内检查模板、processor、有效池和当前 CE 身份；即使字段引用已经恢复，深层内容或恢复验证失败也报告 `INDETERMINATE`。未恢复状态不会因“已尝试回滚”而计为成功。

## 独立收据与身份

每次使用新 UUID。onLoad 创建不可变 `NOT_RUN` 注册收据；最终回调创建另一份结果。报告以 CREATE_NEW 临时文件写入并 force，再通过创建硬链接原子公布新路径，目标已存在即失败，不覆盖历史。onEnable 缺少完成回调或重复调用会留下独立失败标记；原注册记录永远不会被升级为通过。

探针绑定生产、探针、CE 与实际原生服务器四份 defining JAR 的长度和 SHA-256，并在回调前及恢复后复核输入没有漂移。检查器要求它们与**启动前独立冻结**的候选清单一致。若平台 remap 改变 defining JAR，源插件 JAR 的摘要不能自动当作加载后摘要；失败候选不能事后改清单补算通过。

探针 manifest 现显式声明 `paperweight-mappings-namespace: mojang`，与其实际 Mojang 原生引用一致。固定 Paper 132／Folia 14 的 remapper 字节码在该标记下返回原 JAR 并跳过 Spigot remap；这是静态分支核查，还须由未来收据验证实际 defining 路径及摘要，不能预记实服已通过。

收据还包含五份模板的固定来源 commit／哈希／稳定 location、原生 DataVersion 4671，实际原生世界数量、21 槽位类型／对象身份／列表内容，及真实 lease 在窗口内排斥写入、关闭后允许重新申请的观察。离线检查器对注册与最终收据、失败标记和完整状态逐项复核。它只验收 `NATIVE_COLD_START_PUBLICATION_ROLLBACK_ONLY`，不接受自然生成、玩家或客户端通过声明。

本探针没有认证完整 CE 配置和来源包身份，报告明确 `packIdentity=NOT_CERTIFIED_BY_THIS_PROBE`。正式运行还须在外层候选清单独立冻结并核对厨房包、村庄空 processor 包、配置、依赖、JVM 和运行日志；单独收据不能替代整轮启动／正常退出和资源工作流检查。

## 将来隔离候选的配置与检查

须先准备全新隔离实例、冻结全部输入并显式预装／启用村庄数据包，使 `kaleidoscopecookery:crop_replace` 在原生 worldgen 注册表读取前存在。保持该候选默认 watchdog 和服务器堆，不复用失败世界。然后在验收插件配置加入：

```yaml
village-publication-smoke:
  enabled: true
  run-id: <新的规范 UUID>
```

离线期望清单为 `village-publication-expected-v1`：含同一 runId、`platform`（Paper 或 Folia），以及 `artifacts` 下 production、probe、craftEngine、server 四项的 bytes／sha256。该清单必须在本次启动前冻结，不能从结果收据回填。

```powershell
python tools/village_publication_report.py --expected <冻结的期望清单> --registration <本次注册收据> --report <本次最终收据>
```

这条命令只读文件，不会启动或停止服务器。当前尚未安排本探针运行；用户选择先完善代码、实服窗口稍后安排。

## 启动失败控制仍未解决

固定字节码已经核实 CE `PluginTaskRegistry` 捕获 Throwable 后继续。Bukkit.shutdown／native halt 只请求退出或执行停止流程，`DedicatedServer.initServer` 在 STARTUP 返回后仍无条件进入 `loadLevel`；等待自身线程退出会死锁。直接调用 DedicatedServer.onServerExit 则会走 System.exit，不是所采用的方案。WorldInit／WorldLoad／ServerLoad 也不是可取消的前置生成屏障。

因此，未知部分写入或 `INDETERMINATE` 没有被包装成“已安全阻止首次世界加载”。隔离诊断候选一旦遇到此状态必须整轮失败并保留原实例，报告明确 `UNPROVEN_IF_UNEXPECTED_PARTIAL_STATE`。生产正式接线及在线 CE／数据包 reload、disable／生成 reader 的完整屏障继续 OPEN。
