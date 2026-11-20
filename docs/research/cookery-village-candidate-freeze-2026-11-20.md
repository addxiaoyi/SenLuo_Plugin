# 村庄回滚候选输入冻结

2026-11-20。`tools/village_publication_candidate.py` 为后续独立实服候选生成输入清单、复核清单并关联回滚收据。它不会启动 Java／服务器，不接受 EULA，不改服务器堆、watchdog、配置、世界或原输入，也不会创建一个已通过的运行结果。本批只有合成临时目录测试；没有准备或运行真实候选。

## 冻结前提与范围

使用本 checkout 的 `build/acceptance` 下全新隔离实例。实例只允许初始 `acceptance-world/datapacks`，拒绝既有世界内容和已有村庄探针收据；loopback、关闭 RCON、明确 probe 开关／UUID、CE `misc.delay-configuration-load=false` 和 `chunk-system.generation.feature=true` 都必须已经配置。

村庄 ZIP 必须真正位于初始世界 datapacks 目录，`server.properties` 的 `initial-enabled-packs` 明确包含 `vanilla,file/<村庄ZIP文件名>`，`initial-disabled-packs` 不得禁用它。目录中有包不等于已启用。包必须仍是固定来源的空 processor 占位，不能附带自然厨房模板／池；村庄清单原始字节还必须与 defining production JAR 的 `village-loot.json` 一致。

启动前，厨房 ZIP 逐文件与实际安装的 `plugins/CraftEngine/resources/cookery_kitchen` 比较字节和完整文件集合，拒绝缺失、改变或额外文件。启动后只额外允许生产插件生成的 `configuration/font/generated_item_icons.yml`；它必须是有界 YAML，且每项符合图标字体配置的固定结构。ZIP 本身不得预置该路径。ZIP 在创建解析器前限制文件和中央目录，解压／CRC 前限制成员数、单文件和总解压量。JSON 拒绝重复键、非有限常量、过深或超量对象；YAML 拒绝重复键、别名及超量 token。路径和根／祖先链接不被静默解引用。

启动前仍按原始字节冻结 `server.properties`。启动后允许服务端重写注释、行序和换行，但仍严格解析且拒绝重复键、歧义转义和续行；所有冻结时的键值必须保留，loopback、online mode、RCON/query/management、端口和 datapack 选择等约束仍逐项核对。收据阶段的不可变文件清单明确排除这项已按语义检查的属性文件，以及唯一可选的生成图标文件。

离线 `prepare` 在同级唯一 staging 目录中装配和检查全部输入，只在校验成功后原子发布实例；目录／收据写入失败会按本次创建的文件身份清理半成品，允许对同一目标路径重试。失败注入回归覆盖检查失败与收据发布失败。这改善候选准备的恢复性，不构成服务器启动或村庄发布验收。

此窄诊断实例只接受声明的 Cookery、probe、CE 三份 source plugin JAR，不给其他活动插件颁发不完整的输入证明。四份 defining JAR 必须分别声明并存在：production、probe、craftEngine、native server。若 Paper 需要 remap，source plugin 路径与 defining 路径可不同，但 remap 产物必须在冻结前已独立准备并固定；不能用旧探针的 remap 或在失败运行后回填摘要。

清单保存上述四份 defining JAR、source plugins、初始全部 datapacks、CE 资源／库／翻译、Cookery 数据目录、相关配置、server launcher、libraries／versions／remap 输入及 Java 的 bin／lib／conf／release 文件。若固定 Paperclip 路径 `cache/mojang_1.21.11.jar` 存在，也绑定该文件摘要；其他 cache 不扩张冻结范围。检查重新枚举集合，不能通过删掉清单角色、删空 Java 映射或漏记新增依赖绕过；每个文件散列也检查观察期间的长度、时间与文件身份漂移。

CE `data.db` 和两个状态分配 cache 是会被运行更新的初始输入，另存初始存在性及散列。启动前检查必须仍完全匹配；收据关联阶段不把其合法运行变化冒充不可变输入，只明确保留“初始已冻结、仍需启动前和运行证据”的边界。完整启动命令、进程环境、运行中读取顺序、服务端退出、工作流和托管资源包仍由未来外层运行器独立记录；本工具不认证整轮服务器。

## 命令

先在候选目录之外选择新的冻结目录，以下命令只生成 `candidate.json` 和 `expected.json`，目标已存在即拒绝：

```powershell
python tools/village_publication_candidate.py freeze --instance build/acceptance/<新实例> --platform Paper --run-id <新UUID> --defining-production plugins/KaleidoscopeCookeryPlugin-1.3.0.jar --defining-probe plugins/CookeryAcceptanceProbe.jar --source-probe plugins/CookeryAcceptanceProbe.jar --defining-craft-engine plugins/craftengine-26.9.1.jar --defining-server versions/1.21.11/paper-1.21.11.jar --kitchen-pack frozen-inputs/kitchen.zip --village-pack acceptance-world/datapacks/village.zip --java <已固定JDK的bin/java.exe> --output build/acceptance/<新冻结目录>
```

实际路径须按已准备候选指定，示例不是已经存在的实例或运行记录。清单及其摘要应在启动前独立保留，不能从探针结果生成。

```powershell
python tools/village_publication_candidate.py check --candidate build/acceptance/<新冻结目录>/candidate.json
python tools/village_publication_candidate.py check-receipt --candidate build/acceptance/<新冻结目录>/candidate.json --registration <本次注册收据> --report <本次最终收据>
```

`check` 是启动前检查，会拒绝新增世界、结果文件、依赖、配置和初始可变 CE 输入漂移。`check-receipt` 允许本次运行产生的世界／结果，重新核对除上述两项明确 post-run 输出外的冻结不可变输入，并单独报告 `server.properties` 语义匹配；它还要求收据在同一实例、时间不早于冻结、四份 defining JAR 的实际路径／长度／SHA 与冻结清单相同，并调用严格回滚收据检查。成功输出仍明确 `wholeServerRun=NOT_CERTIFIED_BY_THIS_CHECK`。

## 独立启动记录

`tools/village_publication_launch.py` 在候选冻结后准备独立记录，并在未来启动前复核它；两条命令都不启动进程：

```powershell
python tools/village_publication_launch.py prepare --candidate build/acceptance/<新冻结目录>/candidate.json --output build/acceptance/<新冻结目录>/launch.json
python tools/village_publication_launch.py check --candidate build/acceptance/<新冻结目录>/candidate.json --record build/acceptance/<新冻结目录>/launch.json
```

记录绑定完整候选规范 JSON 的 SHA-256，固定实际 Java 绝对路径、argv、cwd 和允许的子进程环境。沿用现有验收 `-Xms512M -Xmx2200M`、UTF-8 和 `server.jar nogui` 参数，不接受追加选项或 argfile。`JAVA_TOOL_OPTIONS`、`JDK_JAVA_OPTIONS`、`_JAVA_OPTIONS`、`CLASSPATH`、未知 Java／原生加载器覆盖变量即使为空也拒绝，诊断不输出其值。其他继承秘密值既不读取也不记录；HOME、用户及临时目录仅在已存在、绝对且无链接时保留原目录含义，JAVA_HOME／PATH 从已固定 JDK 与系统目录生成。

候选保持不可变，不能把启动摘要回写候选形成循环绑定。候选中的 `launchCommandAndEnvironment=NOT_CAPTURED_NO_SERVER_LAUNCHED` 是其冻结时的覆盖边界，后来的独立启动记录补充这一层，不改变历史记录。记录仍为 `startup=NOT_RUN`；OS、系统原生库、网络、用户注册表和整轮运行没有因此被冻结或通过。后续运行器仍须按记录实际启动，并保存进程、日志、工作流和退出证据。当前只跑合成目录合同测试，未创建真实候选或启动记录。

## 将来运行器接入

现有 `tools/acceptance/run_series_gameplay.py` 增加独立村庄模式，复用其服务端循环和跨平台实例锁。下面命令**会启动服务器**，当前用户仍选择代码完善、实服窗口稍后安排，因此本批没有执行：

```powershell
python tools/acceptance/run_series_gameplay.py --village-candidate build/acceptance/<新冻结目录>/candidate.json --village-launch build/acceptance/<新冻结目录>/launch.json --output-dir build/acceptance/<全新结果目录>
```

该模式只使用候选 UUID 和记录内的直接 Java、argv、cwd、允许环境，拒绝另加 `--java`、位置参数或系列探针系统属性；在锁内紧贴启动前复核完整冻结输入及环境，仅读取已接受的 EULA。结果目录须在实例外且原先不存在；不准备、重写或复用服务器实例。原 `instance output --java` 系列命令保持兼容。

验收同时要求本次完整冷启动回滚收据、精确食物／附魔启动 smoke、本次命令之后的资源工作流开始及完成、实际新写入托管 ZIP 的有界解压／CRC／摘要检查、正常 `stop` 应答及退出码 0。超时、异常、强制结束、提前退出、缺失或矛盾收据、晚到失败标记、输入漂移和旧资源包均不能通过。停止后再次复核完整不可变输入，并保存候选／启动记录、实际进程参数与时间、完整控制台、同一份已认证原始收据和托管 ZIP。报告读取在同一文件描述符内有界读取并检查身份／长度／时间，不将验证对象与另一轮文件内容混用，也不声称锁住外部写入者或给证据签名。

本批运行器测试使用临时合成目录和伪进程，覆盖的是程序合同。没有启动 Java、Minecraft 或实际资源工作流，也不扩展为自然生成、重启持久化、玩家／客户端验收或安全阻止首次世界载入的证明。

生产激活、完整资源屏障、启动部分失败控制、Paper/Folia 真正冷启动发布／回滚和自然五 biome 生成没有因增加此工具而完成。用户实服窗口仍待安排。
