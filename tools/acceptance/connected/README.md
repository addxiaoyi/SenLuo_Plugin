# 双人协议验收（Java 1.21.11）

测试在只监听 `127.0.0.1` 的一次性离线测试服运行。两名普通生存玩家通过 1.21.11 网络协议操作，由单独构建的 `CookeryAcceptanceProbe.jar` 布置场景并从服务端记录结果。受测插件 JAR 不包含探针。

1. 按[隔离服准备说明](../../../README.md#隔离服准备)为 Paper 132 和 Folia 14 各建一份可丢弃的副本，安装 5 个插件、6 个 CE 内容包和 CraftEngine 26.9.1。选择 `--server-blocks 8000 --early-pack-load`。在已接受 EULA 的副本内生成并托管资源包（`ce workflow default`），随后停止服务器。CE 自托管地址也设成 `127.0.0.1`，保留会话令牌及玩家身份校验。
2. 仅在这两份副本内设置 `online-mode=false` 和 `enforce-secure-profile=false`。保留 `server-ip=127.0.0.1`、`level-name=acceptance-world`；关闭 RCON，两个平台使用不同端口。将 `build/acceptance/probe/CookeryAcceptanceProbe.jar` 放进各自的 `plugins/`。在各自的 `plugins/CookeryAcceptanceProbe/config.yml` 新增：

   ```yaml
   connected-smoke:
     enabled: true
   ```

3. 用 Java 21、Node.js 22 和 Python 3.11+ 在仓库根目录执行。用官方启动器安装 1.21.11，把客户端 JAR 和版本对应的 Java 库完整类路径赋给 `SENLUO_CLIENT_CLASSPATH`。以下 PowerShell 示例中的副本路径按实际目录替换。每次使用新的输出目录。

   ```powershell
   npm ci --prefix tools/acceptance/connected --ignore-scripts
   $env:SENLUO_CLIENT_CLASSPATH = '<完整类路径>'
   $java = '<Java 21 的 java.exe 路径>'
   .\.venv\Scripts\python.exe tools/acceptance/connected/run.py build/acceptance/net/folia build/acceptance/net/folia-new --java $java
   .\.venv\Scripts\python.exe tools/acceptance/connected/run.py build/acceptance/net/paper build/acceptance/net/paper-new --java $java
   ```

`result.json` 包含每位玩家的下载与解码结果，以及锻造、蒸笼、稻草人、Deco 郁金香展板和沙发核对项。展板场景检查满背包装饰、剪取掉落、交互和掉落事件被取消及两人连续剪取。沙发场景由协议玩家染色、切换形态、拆下、拾取并重新放置；探针监听器依次注入取消交易、切换热栏和另一玩家同步重入，检查失败不扣料、重复染色不扣料和样式保留。同步重入由测试回调调用控制器，其他动作经客户端协议发送。Deco JAR 和 Deco 内容包都要从同一提交构建。

玩偶场景用 `doll_0` 和微调工具打开真实四行菜单，逐项增加九轴数值，再拆下、拾取并重新放置，核对恢复的姿态、物品消耗和唯一掉落。沙发和玩偶拾取均按服务端快照的 CE 物品 ID 选择槽位，避免把同为 paper 材质的工具当成家具。此流程验证拆装物品状态，不包括重启恢复及图形渲染。

## 三种来源作物验收

三作物使用独立 `senluocrop` 探针，启用 `CookeryAcceptanceProbe/config.yml` 中的 `source-crops-smoke.enabled: true`；该场景无需启用旧 `connected-smoke`。厨房包须由当前代码导出，包含 84 个方块及三作物的原生 `minecraft:maintains_farmland` 标签。探针只在 loopback 的 `acceptance-world` 内工作，命令只允许两个固定普通生存玩家和精确动作枚举。测试服务器／JAR／资源包及先前失败候选须分别保留。

先生成并托管初始资源包、正常停服，然后对同一隔离实例依次运行独立 core 和三阶段持久化。两种输出目录均须全新；Paper 与 Folia 串行执行：

```powershell
.\.venv\Scripts\python.exe tools/acceptance/connected/run.py build/acceptance/crops/paper build/acceptance/crops/paper-core-1 --java $java --scenario sourcecrops --source-crops-core
.\.venv\Scripts\python.exe tools/acceptance/connected/run.py build/acceptance/crops/paper build/acceptance/crops/paper-restart-1 --java $java --scenario sourcecrops --restart-suite
```

core 以真实协议包驱动种植、主副手采果、骨粉、普通破坏、镰刀、拾取、来源手漂移、自然随机成长和支撑变化。干土、湿土、邻株、光照及 24 个龄状态的实际原生选择框／碰撞／支撑框均单列；每个详细状态强制核对原生农田标签。原版小麦提供骨粉取消对照，自定义作物的自然取消绑定具体株位及一次 arm 序号，不能由小麦事件代领。没有实际触发的 fertilize 回调必须保留为 SKIP／PARTIAL，不计为实服通过；真实保护提供者、客户端 GPU 与 Forge 专属回调仍另行验收。

core 严格执行两轮 `ce reload config` → `ce workflow default`，分别记录真实 reload epoch、工作流完成和每位玩家三次资源包下载。只允许两组有界资源变更窗口；窗口外变更失败。每次下载均核对 HTTP、SHA-1／SHA-256、字节数，并通过官方 headless reader，输出保留实际 ZIP 和 reader JSON。服务器、插件及运行依赖 JAR 和场景源码仍锁定。core 默认总预算 1,800 秒，reload／workflow 窗口分别为 90／240 秒。

core 正常停止后，以最终托管包哈希建立 `seed → verify → empty` 冻结基线。seed 由两位真实玩家种植六株并骨粉到成熟龄 7，水源维持土壤状态；随后生成仅创建一次的 checkpoint。verify／empty 不允许布置世界、补物、改龄或暂停成长；arrive 只加载和传送，observe 不加载块。恢复比较实际龄、原生框／标签、土壤、世界与玩家 UUID，以及两人 36 个存储槽、4 个盔甲槽、副手和光标的完整组件，实际拆下／拾取后再正常重启检查不复活、不增产。三阶段预算为 600／600／400 秒，旧 legacy／gameplay 的默认 400 秒不变。

无需 Minecraft 的合同测试共 **266 项**（legacy 96＋gameplay 111＋source-crops 59），包含实际导出场景的 fake protocol 执行与篡改负测；其中 native reader 由测试夹具模拟。合同通过不证明实服、GPU 或账号认证通过。运行 `node --test tools/acceptance/connected/test-persistence.cjs tools/acceptance/connected/test-gameplay-persistence.cjs tools/acceptance/connected/test-source-crops.cjs`。Python 运行器的完整合同另由 `tools/test_connected_acceptance.py` 检查。

## 家具三次启动持久化验收

在相同准备条件下添加 `--restart-suite`，运行器会对同一测试世界依次正常启动和停止三次。每个平台单独运行，每次使用新的输出目录：

```powershell
.\.venv\Scripts\python.exe tools/acceptance/connected/run.py build/acceptance/net/paper build/acceptance/net/paper-restart-new --java $java --restart-suite
.\.venv\Scripts\python.exe tools/acceptance/connected/run.py build/acceptance/net/folia build/acceptance/net/folia-restart-new --java $java --restart-suite
```

`seed` 先清理固定测试位置的已知残留，再跑上述完整双人流程，最后安排三件独立位置的家具。玩家把沙发染红并切为左段，逐项增加玩偶九轴，向果盆投入一份带独立 PDC 标记的发光浆果。此时写入只创建一次的 `persistence-state.json`，记录家具 UUID、位置、源物品、自定义状态和两位玩家的 UUID/库存。

`verify` 在重启后只定位已有家具，比较 UUID、源物品 PDC 和存档；由玩家拆下并拾取沙发和玩偶，分别核对唯一掉落，潜行空手从果盆取回唯一原料，再次空取不能多得。`empty` 再次重启，确认沙发和玩偶未复活、原 UUID 的果盆仍为空、两人合计物品及 PDC 守恒且地面无遗留物。后两阶段不创建家具、不注入库存，也不改写首轮快照。

每阶段保留独立的 `fixture.json`、`players.json`、`result.json`、控制台日志、资源读取结果和 JFR；顶层 `result.json` 汇总三轮。运行器锁定服务器、插件 JAR、托管资源包和验收脚本哈希，任何阶段失败、快照缺失或被改写、受测产物或场景代码变化都会停止后续阶段。`--restart-suite` 必须使用自动客户端。这个场景覆盖三种指定家具的正常服务器重启，不代表整个系列的卸载、热重载、跨区域或全部存档均已验收。

脚本保留控制台日志、服务端回复和 JFR。只有服务器启动完成且食物、附魔两项 smoke 均成功才启动客户端；超时、watchdog、服务器错误、非零退出、强制停服或客户端断言失败均返回失败，旧的成功报告不能覆盖失败。报告包含客户端与服务器退出码和结束原因；异常收尾会取消资源下载并等待 Java 解码子进程退出。客户端确认加载前先校验托管包的哈希，并使用官方客户端代码解码下载内容。此流程覆盖协议、资源格式与真实服务端交互；画面、声音、在线账号认证、第三方保护插件及跨区交互需另测。

## 木椅、文字展板与地面燃烧瓶场景

`--scenario gameplay` 选择独立的三次启动场景，必须同时使用 `--restart-suite`；不指定场景时仍为原有 `legacy` 流程。两种场景报告分别校验身份，不能互相充当成功证据。

```powershell
.\.venv\Scripts\python.exe tools/acceptance/connected/run.py build/acceptance/net/paper build/acceptance/net/paper-gameplay-new --java $java --restart-suite --scenario gameplay
.\.venv\Scripts\python.exe tools/acceptance/connected/run.py build/acceptance/net/folia build/acceptance/net/folia-gameplay-new --java $java --restart-suite --scenario gameplay
```

`seed` 仅安排橡木椅、空白文字展板、地面燃烧瓶及起始材料。三件夹具均按 CE 26.9.1 的正常放置语义预置一份完整注册来源物品；verify／empty 不补放或改写来源物品。玩家先触发一次取消的椅垫交易，确认零扣料，再装红垫；展板使用服务器实际发送的 `show_dialog` 回调 ID 与 UUID，通过 `custom_click_action` 提交含中文、反斜杠、尖括号及换行的文字和右对齐，再染红、发光、上蜡、改罂粟图案。五种材料各消耗一件。

`verify` 重启后只观察已有家具，先比较原 UUID、位置、来源物品、控制器 NBT、状态和两人逐槽库存，再由玩家 A 剪取红垫、拆取椅子及当前图案的空白展板并实际拾取，由玩家 B 空手直接取回地面燃烧瓶。期望物品来自种子材料和预先读取的注册定义，完整组件不能由实际掉落反过来自证；每步分别核对两人 36 个存储槽、光标、世界物品和生成回执。盔甲槽和副手槽不在该场景的服务端库存证据范围内。

椅子和展板的原生设置均要求三次连续命中，命中间隔超过 2000 毫秒会重置；在击打之间不做可能耗时的双人完整快照。每件家具先从当前 verify 快照读取真实 hitbox，再由只读 `gamecombat` 返回实际 hit／break 事件的累计收据，核对原 UUID、CE ID、操作者与 UUID、元数据实体和命中盒、命中次数、最终取消状态、所属区域、序号、时钟及 tick。最多三次真实网络攻击，相邻命中间隔必须处于 0–2000 毫秒（含边界）；最终 break 必须紧接最后一击且时间不倒退。原生破坏后才恢复双人完整快照，另行证明家具缺席、唯一规范掉落和指定玩家库存守恒。

`players.json.checks.gameplayRecovery` 保存命中与破坏收据，`commands.txt` 将 `gamecombat` 命令 token 映射到原始 `reply-<token>.json`。原始历史必须完整递增、只追加且旧条目不可改变；报告命中仅额外附 `intervalMillis`，其他字段及破坏条目须与原始回复逐项一致。不同启动的网络 hitbox ID 允许改变，必须使用当轮 verify 的实际值；不能用 seed 的网络 ID 代替。

回收后的库存写入单独的 `persistence-state.json.verified.json`，并绑定原始 seed 文件 SHA256，绝不覆盖 seed。`empty` 第三次启动先核对该绑定，再从 seed 与固定回收增量独立重算两人的组件账本，确认三件家具缺席、没有地面遗留或新增掉落，并在两秒后再次确认无复活或增产。后两阶段不布置家具或注入库存。

运行器保留原始插件、服务器及资源包锁，同时在 seed 完成启动、玩家连接前锁定实例内全部 JAR，包括 Paper/Folia 版本、重映射插件和运行库。首启动允许正常生成 remap；此后启动前、就绪时和停服后均检查同一依赖集合，就绪时漂移会阻止客户端启动。场景源码锁还包含实际执行的 `VanillaPack12111.java`。非 JAR 数据缓存可以正常更新。

共享步行入口在强制转向前先停止前进，转向完成后重新检查是否已经到达、目标是否移动，再决定继续行走；原有 5 秒上限不变。失败诊断记录最近阶段、位置、目标和水平距离，同时保留原错误栈。到达目标或网络掉落实体消失都不能替代服务端的唯一数量、指定拾取者及完整组件守恒证明。

固定客户端库的 `custom_click_action` 定义与原生 1.21.11 的长度前缀 NBT 不同；新场景仅局部覆盖这一包的格式，继续使用原生连接的压缩和发送链路，并记录真实编码与帧字节。Dialog 回调 ID 和 UUID 仍来自本次服务器包，不能硬编码成固定回调。

无需启动 Minecraft 的合同测试共 207 项（legacy 96＋gameplay 111），包含 21 项转向／到达重检／失败诊断回归和新增 37 项原生命中收据回归，覆盖 2000 毫秒边界、过期／篡改历史、错误操作者、取消、缺少破坏和过量命中。执行 `node --test tools/acceptance/connected/test-persistence.cjs tools/acceptance/connected/test-gameplay-persistence.cjs`。Python 运行器合同另覆盖串报、源码和依赖漂移、seed/回收检查点篡改及异常收尾。真实三阶段结果须另看各次输出，合同通过本身不代表实服通过。

## 2026-11-19 本轮有界 gameplay 结果

最终同组五插件、探针与场景脚本在 Paper 132 `build/acceptance/gp2/paper-run-6` 和 Folia 14 `build/acceptance/gp2/folia-run-5` 各完整通过 `seed → verify → empty`。六阶段 server/client 均正常退出 0、`errors=[]`，没有超时或强杀；每阶段两名协议玩家各用官方 1.21.11 代码读取 14,419 项资源。每端家具原生回收均为椅子 hit 1–3／break 4、展板 hit 5–7／break 8，最大相邻命中间隔分别为 Paper 150 毫秒、Folia 151 毫秒。原始命令与回复、两份不可变检查点、逐槽账本、运行依赖、源码和产物哈希，以及全部前序失败见[本轮有界协议证据](../../../docs/research/series-gameplay-connected-acceptance-2026-11-19.json)。

本轮只接受橡木红垫椅、指定中文右对齐的空白到罂粟展板、空地面燃烧瓶三个代表路径；盔甲／副手、全部木种／颜色／板型、投掷／火焰／红石、满载储物和异常恢复、座位／碰撞、保护、跨区、卸载／热重载、GPU／音频仍未覆盖。T05／T06／F04 广义状态保持 `unit_tested`，历史原生 9 家具 API 候选不充当新 JAR 的全套验收，历史 Folia watchdog 仍 OPEN。Paper6 期间独立 host shell 内存错误单列保留，不等同严格通过的 Minecraft suite 失败。

## 2026-11-19 前序冻结候选结果

前序候选以其当时同一套插件、探针和场景脚本，在 Paper 132 `paper-restart-12` 与 Folia 14 `folia-restart-3` 均通过三阶段验收，六次服务器和客户端退出码均为 0，无服务器错误或 watchdog。红色左段沙发、`doll_0` 九轴姿态与果盆内一份带标记的未加工发光浆果，其 UUID、状态、取回数量和 PDC 均按上述范围核对；完整哈希、资源读取及历史失败记录见[冻结候选证据](../../../docs/research/series-restart-acceptance-2026-11-19.json)。这些旧源码锁不能证明后来修改的协议场景通过。Folia 较早一轮仍出现 CE 类加载／原生配方注册 watchdog，根因保持 OPEN。

展板的满库存断言指 36 个存储槽均被占用，同色花仍可合并。进入下一夹具前，场景须从服务端确认两名玩家相对原库存合计恰好新增唯一返花，其他物品和组件不变；必要时以一次正常背包操作将一组圆石移至空副手再拾取，副手状态来自客户端网络观察。无需 Minecraft 的 96 项场景契约测试可用 `node --test tools/acceptance/connected/test-persistence.cjs` 复核。
