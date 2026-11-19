# 本地开发验证记录

更新于 2026-11-19（Asia/Shanghai）。完整计划和换设备步骤见 [根 README](../README.md#继续开发)。11 月 7 日构建/依赖失败、离线校验和历史运行日志见 [交接证据](handoff/2026-11-07/README.md)；后续检查点按日期保留。双人协议验收见下方；原版客户端画面仍待实机验证。

目标 Paper/Folia 1.21.11、Java 21、CraftEngine 26.9.1。历史构建/运行记录不因 Git 交接升级成全系列完成；未发布、部署或操作生产服。

## 燃烧瓶、文字展板与木椅回归（2026-11-19）

当前冻结构建通过 **923 项 Java、282 项 Python、207 项 Node 场景契约测试**，厨房与系列来源导入 `--check` 均通过。本轮新增燃烧瓶及四种储物家具的燃烧瓶链、14 款文字展板、12 木种椅子的空垫／16 色椅垫、12 木种静态厨娘凳和 24 条石切配方。下方原生 API 回归属于前序冻结候选，其源码、产物和日志哈希保留在[玩法回归证据](research/series-gameplay-regression-2026-11-19.json)，不能替代新候选的协议或重启结果。

前序冻结五插件 JAR 候选在 Paper 132 和 Folia 14 各验证 43 个定义、431 个变体、235 个物品，完成 9 件代表家具的放置、二进制 NBT 进程内往返与移除，以及 19 个真实储物显示的生成和清理。两端正常停服、退出码 0、`errors=[]`；该候选的最终托管资源包各有 14,419 项通过官方 1.21.11 代码离线读取，不能自动作为后续新 JAR 的验证结果。静态引用检查警告为 0，但运行日志每端仍保留 4 条上游 Doll 模型越界 UV 兼容提示，不能声称无任何警告。

这层原生探针未驱动玩家，也未执行服务器重启，不能覆盖 Dialog 操作、投掷、红石、座位、保护或 GPU／音频验收。首次及第二候选失败均保留，历史 Folia watchdog 根因继续 OPEN；全系列 `accepted=[]`，许可证 E06 仍 `blocked`。精确范围和复现步骤见[玩家验收表](../series/docs/ACCEPTANCE.md#本轮原生回归复现)，下面保留旧提交的历史检查点。

## 木椅、文字展板与地面燃烧瓶协议检查点（2026-11-19）

独立 `--restart-suite --scenario gameplay` 场景以两名普通生存协议玩家检查一把橡木红垫椅、一块空白到罂粟花纹文字展板及一个空地面燃烧瓶。最终相同五插件、探针与场景脚本在 Paper 132 的 `gp2/paper-run-6` 和 Folia 14 的 `gp2/folia-run-5` 各完整通过三阶段；六次服务器／客户端均正常退出 0、`errors=[]`，每阶段每位玩家各以官方代码读取 14,419 项资源。完整产物、源码、依赖、检查点、原生事件及历史失败哈希见[本轮有界协议证据](research/series-gameplay-connected-acceptance-2026-11-19.json)。

范围为取消与实际装垫、服务器真实 Dialog 中文／反斜杠／尖括号／换行输入及右对齐、染色／发光／封蜡／换花纹，正常重启后保留原 UUID、完整来源物品和状态，再由指定玩家剪回红垫、实际拾取基础椅及空白罂粟板、空手直接取回地面燃烧瓶。第三次启动核对独立 SHA 绑定检查点、每人 36 个存储槽与光标的组件账本、无家具和遗留物，两秒后再查无复活或增产；不观察盔甲和副手。完整方法见[协议复现](../series/docs/ACCEPTANCE.md#木椅文字展板与地面燃烧瓶的三阶段协议复现)。

这层代表样例不覆盖全部木种／颜色／板型、投掷／火焰／红石、四种储物家具满载、异常人工恢复、座位碰撞、保护、跨区、卸载／热重载或 GPU／音频，T05／T06／F04 仍为广义 `unit_tested`。本轮 11 项 Molotov 异常归档合同计入 Tavern 186 项 Java 单元测试，不是满载实服验证。Paper 的 URL、Dialog 编码、夹具来源物品、旧击打节奏失败与 Folia 的 watchdog、公钥 TLS EOF、异常退出及移动超时均保留；异常退出原因没有证实，后续成功不覆盖失败或关闭 watchdog。Paper6 期间的独立 PowerShell／分页文件与 .NET 内存观察单列保留，不等同已通过的实服 suite 失败。

## 前序双人协议验收（2026-11-19）

Paper 132 和 Folia 14 各两名普通生存玩家在隔离服完成资源包下载及官方解码、厨刀锻造取消／普通／Shift 扣料、四槽蒸笼回收和稻草人保护取消及两人连续击打。每位玩家解码 14,203 个资源；两端都正常保存和停服，无 watchdog 或 ERROR 日志。方法见[协议验收工具](../tools/acceptance/connected/README.md)，结果及 JFR 哈希见[双端证据](research/series-connected-2026-11-19.json)。两者均为本机离线协议客户端，不代表已认证客户端的画面或声音验收。

## 612 条厨房配方与五插件整合（2026-11-18）

厨房包含 1,432 件物品、81 个方块、25 件家具。612 条来源配方均有目标映射。根工程 565 项与系列工程 60 项 Java 测试通过，两个 Python 套件分别通过 139 和 58 项。完整输入、启动日志摘要、各轮产物哈希和玩家验收范围见[双端验收记录](research/series-runtime-2026-11-18.json)。

服务端用 `--server-blocks 8000 --early-pack-load` 准备。Folia 默认配置曾在 `AbstractPackManager.loadResourceConfigs` 等待 77.026 秒，同轮 CE 异步初始化与资源生成都在写入同一缓存。调整加载时机并关闭该资源缓存后，五个插件在 CE 读包前注册所需类型。另一次 Folia 重启在原生有序配方的 `Ingredient.ofStacks` 注册路径记录了 5.59 秒全局线程 watchdog，注册随后完成。五轮均正常停服，另一次独占的 Folia 重启未再报警。原来的 tag packet 栈也保留在历史记录中。

原版客户端的离线检查用 Java 21、1.21.11 官方客户端 JAR 和对应运行库，读取每个平台 `plugins/CraftEngine/cache/hosted/default/resource_pack.zip`。两份托管包各通过 14,203 个资源检查。客户端选中了 `ce_overlay_75-97`。原版解码器解析了每包的 6,292 个模型、4,060 个物品定义和 1,023 个方块状态，另有 2,617 张 PNG 成功解码。所有有效资源均通过客户端读取，并逐项与 ZIP 内对应内容比较。可用官方启动器准备好该版本和依赖，将启动时的类路径（含客户端 JAR）赋给 `CLIENT_CP` 后复验。

```powershell
$instances = 'build/acceptance/series-five-runtime'
$hosted = 'plugins/CraftEngine/cache/hosted/default/resource_pack.zip'
java '-Djava.awt.headless=true' -cp "$env:CLIENT_CP" tools/acceptance/VanillaPack12111.java build/client-read.json "$instances/paper/$hosted" "$instances/folia/$hosted"
```

本机 demo 启动到创建窗口时报告 `GLFW error 65542`。已登录客户端的接包、渲染和普通玩家操作仍待验收。

## 导入与资源转换（2026-11-18，前序检查点）

- 已同步远端 `1d283d6`，保持五个固定上游提交。八组 Forge 基础材料标签映射到明确的原版目标，已有来源标签优先；映射写入厨房 manifest 和 `recipe-parity.json` 的 `targetTagMappings`。
- 新增 11 条合成与 3 条石磨来源配方：550/612 imported、62 pending；石磨 85 条来源完整展开为 90 条具体输入。厨房仍为 1,206 物品、63 方块和 1,218 个通过引用检查的模型。
- Deco 新增两个旧物品模型转发和蛙卵果冻 GUI 模型，修正盘粒子；Tavern 铁链贴图转换为 1.21.11 的 `minecraft:block/iron_chain`。声明的 CE 资源依赖无错误，额外随包导出的自定义资源当时有 5 条 `asset_warnings`，当前结果见[资源检查报告](../series/docs/pack-validation.json)。
- Python 根工具 105 项、系列工具 57 项通过；固定源码目录及生成导入报告检查通过。本轮新增资源与配方的 Paper/Folia 加载、原版客户端和玩家验收待执行；双端运行基线见 [2026-11-17 记录](research/series-runtime-2026-11-17.json)。

## Forge 蘑菇标签配方（2026-11-18，前序检查点）

- Forge 47.3.0 universal JAR（SHA-256 `a3c07e6b33fe8a4b42fdb4841a37e3ffda62797366c7050c481d799d6d35831c`）中 `data/forge/tags/items/mushrooms.json`（资源 SHA-256 `5c689ad6d0c3abb7af662644c0d539f591d8faa88be36afa598f8f4e6fca29f2`）将 `forge:mushrooms` 明确展开为 `minecraft:brown_mushroom` 和 `minecraft:red_mushroom`。Cookery 配方报告保存该目标映射，来源若以后提供同名标签仍优先采用来源定义。
- 8 条炒锅/高汤锅配方（鱼香肉丝、春卷、鸡肉蘑菇炖汤、野蘑菇兔肉汤的两种配方形式）全部导入，分别为 225 条炒锅、105 条高汤锅；总量 558/612 imported、54 pending。
- Windows Python 全量测试当前为 106 根工具、57 系列工具；本轮额外修正路径规范化测试在 Windows 临时目录的大小写/短路径差异。根工程 `test shadowJar acceptanceJar` 和系列 `build` 均通过，使用本地 Gradle 9.4.0 与 Java 21/25。厨房包来源检查通过。Paper/Folia 加载、客户端资源包显示和玩家操作尚未复验。

## 盖饭原生合成（2026-11-16，历史）

- 固定源码的 5 条 `rice_bowl` 已导出为 CraftEngine 自定义配方；厨房包当前 532/612 imported、80 pending，配方配置 73 条，其中 5 条盖饭、221 条炒锅、20 条炉具和其余原版/合成入口。可选 Farmers Delight 米饭缺席会写入 optionalTagAbsences，不伪装成已安装依赖。
- 盖饭 native bridge 覆盖九个保留 NMS ABI。它绕过 CraftEngine shapeless companion 对额外格的数量门槛，匹配恰好一格菜和一格熟米饭，质量扫描整个工作矩阵；原生结果槽负责消耗每个非空格，余料 delegate 保留原版余料并将米饭槽替换为一个碗。额外格不是免费保留输入。
- `test shadowJar acceptanceJar` 通过 319 Java 测试零失败/错误/跳过；Python 全量 102 项通过。九个 native 构造/assemble/remaining ABI、规则 17 项、Recipe 9 项均已验证；1.21.11 运行目标的跨版本编译产物与实际 Paper/Folia 运行由下方探针核对。
- `rice-runtime-20261116-v2` Paper/Folia 各三轮通过并正常停服。每端核对 125 组品质组合、5 组额外最高品质、15 组额外格消耗、10 组重复角色拒绝、5 组错配拒绝、15 次连续 native 合成；箱子保存 15 份盖饭和 15 个碗，重启后物品组件哈希不变，清空后第三轮无重复产出。探针使用 `Bukkit.craftItemResult` 和实际服务器 recipe registry，无玩家。
- Shift 合成、玩家工作台 2×2 界面、自动合成器、客户端配方书、外部可选米饭依赖仍未验收。容器余料和重复合成服务端守恒已证；真实玩家交互仍保持 NOT_TESTED。摘要见 [盖饭验收 JSON](research/cookery-rice-bowls-acceptance-2026-11-16.json) 和 [证据包](research/cookery-rice-bowls-evidence-2026-11-16.zip)。
- 盖饭最终插件 SHA-256 为 `a20400b80662c60f70cff47a845f4cf7bf9fe6c9dd0d884e752c9d535b26772f`，探针为 `123235f689faaee408bbbe4e1c0bcda1e951886d7fb011544e8790882557d2feb`，盖饭包为 `a52e9f1efc669eb05c591c14bb0266ddf302b74fd5fe7a8c23930f359a3e9299`。九版 native ABI 全编译，实际运行目标为 Paper/Folia 1.21.11。

## 38 种直接食用大菜（2026-11-15，当前）

- 当前源码目标与固定 Cookery 提交不变。完整厨房包 527 物品、62 方块、1,442 状态、539 可达模型；38 种大菜为 34 单格/3 双格/1 九格，共 49 占用格、1,024 状态。冷切火腿片补齐后自动导入其合成，来源为 532 imported / 80 pending，原版/合成配方 73 条（含 5 盖饭）。
- `test shadowJar acceptanceJar` 最终通过 319 Java，零失败/错误/跳过；Python 全量 102 通过。包含每口营养/效果、严格存档/布局、控制器提交与重入、玩家消费事件、12 项放置事务和 11 项整组破坏保护回归。取消后逃逸 commit 的三项真实失败及回滚覆盖无关方块的两项真实失败均先复现后修复；编译/API 重载和 RegistryAccess 夹具问题另行保留。
- `meal-runtime-20261115-v5` Paper 132/Folia 14 各三轮全部通过且正常停服。每轮验证 38 菜/1,024 状态的控制器和存档；首轮 38 次阻挡放置不改其他格、取消食用不扣口、过期回调拒绝、坏存档拒绝，各菜吃一口后保存品质/UUID/朝向。次轮恢复全部 38 菜，完成剩余 93 口，耗尽后 49 格仍在；主动移除只掉规定容器/额外物品。再次放置未吃过的 38 菜，从 4 种多格菜的非主格破坏，全组移除、整菜各掉 1。第三轮全部 49 格无复活或重复掉落。
- 探针消费使用无玩家的事务提交回调，**不证明真人饥饿、效果、FOOD 归因或背包交互**。原厨房 24 厨具/418 状态、527 物品构造、103 消费品、5 附魔、219 加工/219 炒锅、66 原版配方、石磨、11 种取物盘和竹筛回归同轮通过。
- 多格放置逐格检查权限、可替换性及区域所有权，按本次创建的控制器身份回滚；回调后复核完整组与手物品，最后才扣 1。提交后的音效/游戏事件异常保留世界和已扣数量。整组破坏逐格发保护检查，CE 自动转换的事件不再手工重复；掉落许可只对当前 tick 的实际移除生效，后续取消不永久污染新移除。这些事件分支有单元验证，真人/第三方保护插件仍需实测。
- 六轮最终插件、探针、CE、服务端 JAR 和源包 manifest 哈希均核对一致。两端 1,718 源包文件逐项一致，额外 `ItemIcons` 生成 261 图标配置单列；539 厨房可达模型与贴图通过，全包 1,260 模型无专用 loader。端口 `25583/25584` 已释放。最终插件 SHA-256 `c787d5e4999041f2d85e8f5128e67aeca9ae20c3eab0e56ed49af04ca480528f`，厨房 ZIP `2d441b6a5db22c46fcb4a9595203fee8118a10809eff341af114896e3fe00731`。

证据见 [机器摘要](research/cookery-placed-meals-acceptance-2026-11-15.json) 与 [完整日志/测试/来源证据包](research/cookery-placed-meals-evidence-2026-11-15.zip)。v1 仅探针相对路径回退两次产生空父目录而失败，修为绝对路径；v2 双端通过的是事件加固前的 JAR，最终结论以 v5 为准。历史日志全部保留。v3 通过后仅补说明注释导致构建字节码行号/哈希变化，v4 使用当前产物复验时 Folia 第三轮启动出现 5.1581751 秒 watchdog（CE BukkitProjectileManager.delayedInit / EntityLookup.getAll / ClassLoader.defineClass）；各玩法报告虽通过，整轮仍严格记 FAIL。v5 同产物完整复验通过，不改变阈值或豁免错误，启动问题仍 OPEN。

仍有差异：大菜碰撞/选框用细绊线替代，服务端粒子近似客户端动画，比较器对源负数输出夹到 0–15；放置拒绝当前线程不拥有的跨区位置。真人消费/主副手/效果桥、保护插件/多人、客户端显示、独立卸载、CE 热重载、爆炸及跨区域清理未实服验收。全系列目标仍未完成，历史 CE 冷启动 watchdog 继续 OPEN。

## 取物盘语义与物品守恒（2026-11-15，前序）

本轮修正 11 种 `PlateRegistry` 整份取物盘，并不实现 38 种直接食用大菜（注册表 37 种＋冷切火腿片 1 种）。完整包仍为 342 物品、24 方块、418 状态、522/612 来源导入；新增 manifest `plates` 逐项保存最大份数、有序产物和容器，缺任何盘方块状态直接导出失败。90 条剩余配方已按固定源码逐项审计，见 [待实现清单](research/cookery-pending-recipes-2026-11-15.json)。

- 新放整盘按官方最大份数初始化；单一食物盘补回一件只增一份。浆果每份同时给甜浆果和发光浆果，不允许用其中一种补盘。最后一份取完保留空盘，下一次空盘操作才移除；手持其他物品时向上掉出一份。摆放朝向和取放音效沿用官方语义。
- CE 基础 loot 不再掉满盘物品，控制器只结算剩余各项食物及一次容器。取份先扣数量并保存状态再交付，转移期间拒绝同步重入；交付回调中破坏也不会重复发出已取份。回调已经部分交付后抛异常不能安全回滚，因此保留扣除并解除短期锁，不承诺任意第三方物品转移可撤回。
- 新存档 schema 2 保存份数、每份物品 NBT 及容器 NBT；完整验证再替换状态，损坏字段不再静默清空。schema 1 迁移保留旧剩余数量、原物品组件、品质和保存的容器；旧浆果单产存档不会凭空补出第二种食物。新盘遵循官方默认物品规则；创造补料免扣仍沿用原插件规则。
- `test shadowJar acceptanceJar` 通过，203 Java 测试零失败/错误/跳过，其中 24 项盘控制器回归；Python 全量 86 项通过。旧实现“新盘为空/坏档吞清”2 项真红；导出默认空盘和重复整盘 loot 的回归真红；未操作盘关服保存回归先失败后通过。测试中的 NBT 物品编解码边界使用 mock，不能冒充旧真实存档迁移验收。

首次实服 `plate-runtime-20261115-v1` 失败被保留：Paper 第一轮关服出现 `Failed to unload chunk 0,0`，从未操作的盘尝试在 CE 清空物品注册表后才构造食物，导致整个区块序列化中止；第二轮缺失 24 厨具及原配方控制器。旧运行器漏判了该 WARN，因此第一轮 `smoke-1.json` 的正常关服结论作废。修复将物品/容器快照提前到构造时，保存不再查注册表；验收器增加卸载/保存警告判失败，失败日志与现场哈希保留。

修复后的 `plate-runtime-20261115-v2` 通过 Paper 132/Folia 14 各三轮并正常停服。每端第一轮 11 盘共 48 份、52 食物，取空无提前掉碗，保存 11 空盘；第二轮 11 空盘重载、10 种单盘补一件增一份、浆果两种原料均拒补，空盘移除与重新放盘取一份后破坏各产 11 碗，后者剩余食物合计 40；同步重入检查首轮 48 次、次轮 21 次。第三轮 11 个位置仍为空，无方块/控制器复活或重复掉落。真实 CE 移除同时核对成功物品生成事件与地面实体数量，未通过反射修改份数。

每轮原 24 厨具/418 状态/342 物品、103 消费品、5 附魔、219 加工/219 炒锅、65 原版配方、默认 40 秒牛驱动石磨和竹筛加工/漏斗/重启回归继续通过；未操作盘所在厨房区块在两次重启后完整恢复。两端 1,709 源包文件逐项匹配 manifest，另单列现有 `ItemIcons` 生成的 260 图标配置；349 厨房入口递归 351 模型依赖通过，全部 1,251 模型无专用 loader。六轮安装插件、探针和 CE 哈希一致，端口 `25583/25584` 已释放。

完整结果见 [机器可读验收摘要](research/cookery-plate-acceptance-2026-11-15.json) 和 [失败/复验/测试证据包](research/cookery-plate-evidence-2026-11-15.zip)。插件 SHA-256 为 `1ee5a52580b119176737ce39e4b9bfb210044cc13e585575929003bad5b36bde`，厨房 ZIP 为 `913c8ecd85d89b0614cbd44246e458c4015261d5b00eae8577d0e2756551418d`。来源审计和后续直接食用大菜路径也保存在证据包中。

本轮实际玩家主副手、背包、保护插件、多人、旧 schema 1 真档迁移、客户端画面、独立卸载、CE 热重载、爆炸和 Folia 跨区域仍需验收。盘控制器的补料创造免扣是保留的插件差异；其他系列功能与历史 CE 冷启动 watchdog 不因本轮提升完成度。

## 竹筛存档、显示和实服加工（2026-11-15，前序）

- 已从 GitHub 快进到 `7157bc5e739f501946f1fc062a4310ed16881948`，固定 Cookery 源码仍为 `2f4e386ce23f49a385ddf003c67fc6415c55417a` 且 tracked clean；严格目录导入检查通过。本轮代码改动仍未提交/推送。
- `test shadowJar acceptanceJar` 通过，179 Java 测试无失败/错误/跳过；其中 10 项检查最终 JAR 的映射标记和九版 NMS 字节码。Python 全量 81 项、加强后的验收器 13 项通过。损坏 NBT 的真实旧实现回归为 10 项中 7 失败；显示旧实现 2 项均失败；打包回归 10 项中 8 失败，修复后全部通过。夹具类加载失败与业务回归分别保留。
- 竹筛按整批校验再加载库存，拒绝错误 NBT 类型、非法/重复槽、过长数组、非法进度/完成值及空/超量物品；兼容旧缺省字段和短数组。显示按官方每槽 1–5 份、固定种子偏移/层高/旋转，缓存未变化槽数据包，客户端画面尚未验收。
- `bamboo-runtime-20261115-v3` 中 Paper 132/Folia 14 各三轮通过并正常停服。首轮验证露天晾晒、实心顶暂停、玻璃顶/叠筛晾晒、无源滴水暂停和有源滴水润湿，保存四个未完成竹筛；次轮逐槽恢复材质/数量/进度/完成码，实际获得海绵 2、干海带 3、泥巴 2、黏土 1，保留无配方石头 3。控制器取 1 海绵后，剩余 1 个反向润湿；真实底漏斗取消至少 2 次仍保留成品，放行后只抽取 1 个。第三轮读回空槽和漏斗 1 个湿海绵，等待后不重复产出。
- 每轮同时通过 103 消费品、5 附魔、342 物品/24 方块/418 状态、219 加工计划、219 炒锅计划、65 原版配方注册/产物构造；其中 20 炉具配方核对原生类型/时长/经验。石磨默认 40 秒牛驱动、续磨 10 骨粉和空槽重启保持通过。28 竹筛是注册与构造覆盖，真实计时样例仅为上述物品；没有逐 tick 精确时长或全部 28 配方的运行验收。
- 两端每轮使用相同插件、探针和 CE JAR，安装的 1,709 个源包文件逐一匹配 manifest；额外一份 `generated_item_icons.yml` 来自现有 `ItemIcons` 启动生成器，260 图标及哈希单列。实际生成包的 349 个厨房模型入口递归检查 351 模型/贴图，全包 1,251 模型无 `loader`。端口 `127.0.0.1:25583/25584` 已释放。

首次失败没有删除：v1 的 7 条营火配方误用 `campfire-cooking`，修为 CE 的 `campfire_cooking`；原 Mojang JAR 缺少映射声明，被 Paper 再映射成错误 `FluidState` 签名，现由 `mojang` manifest 和打包时 CraftBukkit 前缀转换修复。v2 石磨在检查前已完成，区域存档实际保存恰好 10 骨粉；探针改为首轮等其它报告、重启优先检查，没有改变生产时长或削弱断言。CE 的 tick 崩溃和配置拒载即使是 WARN，也会由验收器判失败。

完整哈希和六轮报告见 [机器可读摘要](research/cookery-bamboo-acceptance.json)；首次失败、复验日志、测试 XML、探针源码及文件 SHA-256 见 [可携带证据包](research/cookery-bamboo-evidence-2026-11-15.zip)。JAR SHA-256 为 `b5c41817d0141324e72603578da837187aebde796370fcfc4a5bf4fff3628bb6`；完整厨房 ZIP 为 `670b1053b4a0252d5e2c97b0825f6e5ba54631960b1cafe354d486b2a7347881`。

尚未验收：玩家/客户端、精确碰撞、降雨切换、玻璃板/遮光玻璃、上/侧漏斗进料、满目标及漏斗矿车、完整物品组件、保护/多人、独立卸载、CE 热重载、破坏/爆炸和 Folia 跨区域。空槽清零断言针对序列化状态。90 条来源配方、厨房附属功能及其他系列缺口仍待完成；历史 CE 冷启动 watchdog 保持 OPEN。

## 食物摆盘与每口状态（2026-11-07，历史实现存在语义错误）

下面保留当时实现和运行记录，不代表官方行为正确。2026-11-15 固定源码复查确认：这 11 种是整份取物盘，旧代码误用直接食用大菜的每口机制，存在空盘初始化、补料增产、混合浆果少产、最后一份提前移除及整盘重复掉落问题；旧探针只覆盖加载/状态，未验证这些交互。当前修复和新增专用探针见最新检查点。38 种直接食用大菜仍未接入 CE 方块。

- 新增 `PlateBehavior` / `PlateController`，接入官方 11 种盘/拼盘方块。主手放入允许的整份食物后建立 `PlateServing`，空手逐口取出；同一来源食物可逐口补盘，最后一口掉落官方额外容器并移除空盘。
- `PlateServing` 保存来源物品 ID、品质、最大口数、已消耗口数和额外掉落；控制器同时保存源物品 NBT，旧世界没有摆盘标签时按空盘加载，坏的可选摆盘数据不会阻断旧存档启动。每口成品通过 `BakedFood.ofBite` 写入独立营养、饱和度、效果和消费组件。
- 厨房导出保留官方 `facing`/`servings` 模型状态，11 个盘/拼盘新增 236 个状态；完整包 manifest 为 342 物品、24 方块、418 状态、351 个检查模型。Python 厨房测试 72 项通过，Java `test`、`shadowJar`、`acceptanceJar` 均通过。
- `build/acceptance/kitchen-plate-runtime-20261107/acceptance.json` 记录 Paper/Folia 各 3 轮：每端 24 方块、418 状态、342 物品，cycle 1/2/3 分别保存进行中、重启恢复完成、空状态无重复产出；所有 kitchen failures 为 0。Paper 资源包 SHA-256 为 `7e09fb507d239de9b69658236548efc4c9334f0b699e87d11ceec6ab1b6c2107`，Folia 为 `83f1634c3de0ccb707ae145b9539d51c9cd05bebf58241d6b1d01d7c02cbaebe`。
- 本轮厨房包 `build/acceptance/cookery-plate-runtime-20261107.zip` SHA-256 为 `b3bcc801ecc9a56990c8b6e6e2c77ef5b8808a84cd85731eff5bd3ba55b20d08`；插件 JAR SHA-256 为 `ae4eaeed4e6010a8e886f68450f74150ab7e9808fc734901836e1677a9570990`。验收使用服务器 API 放置方块，未模拟真实玩家点击、保护插件、多人竞争、碰撞精度或原版客户端显示。

## 竹筛整包与 Folia 冷启动（2026-11-07，历史）

- 完整厨房包生成成功：272 物品、13 方块、28 条竹筛配方、281 个模型检查通过。Python 厨房测试 71 项通过。
- Paper 1.21.11 build 132 与 CraftEngine 26.9.1 成功启动并加载厨房包。Folia 1.21.11 build 14 在完成默认资源包初始化后成功加载 53 个方块和 28 条竹筛配方，插件方块注册自检无 `craftengine.registry.blocks.missing`。
- Folia 冷启动期间发现并修复三类可复现配置问题。竹筛 appearance 使用 `auto_state`，竹筛 base/stand 使用独立状态映射 ID，基础食品包与完整厨房包不再同时安装。修复后最终日志没有厨房配置 issue、模型绑定冲突或方块注册错误。
- 最终厨房包 `build/acceptance/cookery-handoff-folia-final-20261107.zip` 的 SHA-256 为 `07f2655adeafa2569f87e6f7a06a2dfe05e539e6d30922b774ac4a7613e6c443`。运行插件 `build/libs/KaleidoscopeCookeryPlugin-1.3.0.jar` 的 SHA-256 为 `30ca4b4ff5316cc9d31c248ec7be0a3de664471f53820495d82360613f034fa5`。
- 已证明范围是服务器启动、CraftEngine 配置加载、方块注册和竹筛配方加载。真实玩家、原版客户端资源包、漏斗取消、天气场景、多人竞争和摆盘家具流程仍未完成。
- 更新后的 `tools/run_enchantment_acceptance.py` 同时识别 CraftEngine 中文和英文资源包完成日志。Paper 厨房隔离 smoke cycle 1/2 均正常停服，`build/acceptance/kitchen-final-20261107-h/paper/smoke-1.json` 与 `smoke-2.json` 各记录 13 方块、182 状态、272 物品、0 failures、`stoppedCleanly: true`。Folia 同一运行器的 recipe smoke 仍需单独收尾，不能由 Paper 证据代替。
- 最终双端 acceptance 已通过，证据入口为 `build/acceptance/kitchen-final-20261107-l/acceptance.json`。Paper/Folia 各 3 轮均正常停服，13 方块、182 状态、272 物品和 28 条竹筛配方全部通过；219 配方计划、34 合成计划、219 炒锅计划、石磨重载和资源包生成均无失败。cycle 1 保存进行中状态，cycle 2 恢复完成状态，cycle 3 验证空状态无重复产出；Paper 资源包 SHA-256 `542439b986d02d4e48fbc50767bc544567f0ded5f669cfd647356cc12d809c80`，Folia `588f99ad92e3075bf8c95f0fb39c4e1661d7fd5ed1e41fd445b33e6af280fa8c`。
- 12 种厨房效果均有 handler 注册完整性测试，覆盖 ID 集合、反向解析和 handler 数量一致性。该测试证明注册覆盖，不替代每个效果的等级、概率、事件和玩家流程验收。

上述三轮记录中，竹筛覆盖配置/配方注册及空方块加载；进行中、完成和空状态的生命周期针对原有厨具，不能当作竹筛真实加工或漏斗通过证据。

## 石磨配方、守恒与真实动物驱动（2026-11-06，历史）

- `gradlew test shadowJar acceptanceJar --console=plain` exit 0，132 项 Java 测试无失败/错误/跳过；Python 全量 71 项通过。证据 `build/acceptance/millstone-build-v2.log`、`millstone-java-final.json`、`millstone-python-all-v4.log`。strict catalog、资源引用、分发 JAR 隔离和 `git diff --check` 通过。
- 85 条官方石磨来源中 82 条展开为 87 条具体输入配置。3 条分别缺 `forge:gravel`、`forge:sand/red`、`forge:sand/colorless`，没有擅自删掉标签备选项。全厨房 612 来源现为 462 imported / 150 pending；438 加工配置＋33 合成，271 物品、12 方块、180 状态、1 家具、279 个可达模型、18 音效别名。
- 新石磨配方复用 `accurate_foods`、`cook: MILLSTONE`、`rotations: 1` 与官方产量。旧配方默认四圈、已冻结计划和旧档圈数保留；品质继续走原厨房规则。精准配方探针按 `cook` 分类，原 4 蒸笼断言仍保留。
- 14 项石磨控制器回归覆盖取料/完成回调重入、回调移除家具、无效输入与角度、空或非法事件产物、第二个掉落取消、速度设置异常、完成异常/取消持久化阻塞、配方变化和坏计划。`millstone-controller-red-v5.log` 真实复现 4 项行为失败，`green-v6` 与最终全量通过；纯测试环境的 Material/Registry 初始化问题没有当作业务失败。
- 取料先清槽再发物品，转移中拒绝再次取/放；完成事件前先保存阻塞标记，失败时保留原料且不逐 tick 重试。实体先加入回滚集合，再设置速度；后续失败撤回已创建实体。撤回失败显式抛出并保持阻塞，不承诺任意第三方已主动搬走的物品仍能事务恢复。补箱恢复为完成、取料后和原空转周期，不每 tick 扫描驴箱。
- `cookery_millstone.py` 从固定 Java 模型提取 12 个 cube 并保持面 UV；父级 pivot/旋转的未知变化拒绝导入。模型回归包含实际失败的四种父级变化；新测试初稿的上表面 UV 期望按源 `-Y` 转目标 `+Y` 修正后通过。长杆用 fixed scale 恢复尺寸；插件六倍滚动、竖杆固定抬升、磨石 1.4 高度和 interaction 碰撞继续列为差异，客户端仍 NOT_TESTED。

首次实服 `millstone-v1`：Paper 三轮通过；Folia 首轮通过，第二轮未完成，保留 `folia/boot-2.log`。三个独立诊断副本复现：角度冻结在 78.29998，传送已完成、牛有效且拥有区域，CE 当前家具与 ticker 引用的家具却不同（`sameTickingFurniture: false`）。旧家具仍有效，CE 没有替换 ticker。源码和运行 JAR 的 `javap` 均核对；`millstone-diagnostic-v3` 记录引用身份证据。

修复限定为石磨 Folia ticker：经 CE 公开 `getLoadedFurnitureByMetaEntity` 查到当前控制器再 tick，不读取/改写 CE 私有状态、不增加第二套定时器。只读私有字段诊断仅存在独立探针 JAR。`millstone-folia-ticker-red.log` 先复现新控制器未被调用，最终全量通过。修复版独立双端三轮证据入口为 `build/acceptance/millstone-v2/acceptance.json`，验收摘要为 [cookery-millstone-acceptance.json](research/cookery-millstone-acceptance.json)。

`millstone-v2` 双端均完成三轮并正常停服。中途存档保留两根骨头、牛、家具及冻结计划；第二轮真实 tick 续磨只触发一次完成事件并掉落恰好 10 骨粉；第三轮空槽无新事件或产物。原厨房、219 炒锅、219 其他加工计划、33 合成、103 食物及 5 附魔回归同时通过。

| 平台 | cycle 1 / 2 / 3 | 安装文件与 manifest | 生成资源模型 | 最终 JAR |
| --- | --- | --- | --- | --- |
| Paper | PASS / PASS / PASS | 1,709 全部一致 | 1,251，无 Forge loader | 三轮哈希一致 |
| Folia | PASS / PASS / PASS | 1,709 全部一致 | 1,251，无 Forge loader | 三轮哈希一致 |

| 本轮产物 | SHA-256 |
| --- | --- |
| `build/acceptance/cookery-millstone-v2.zip`（2,270,578 字节） | `02ad7037bfcb603d3fa26910f80ec7271f26d405269275cdba82055f85099bf1` |
| `build/libs/KaleidoscopeCookeryPlugin-1.3.0.jar`（1,362,289 字节） | `e061bc28cf878cb915b21faf16657127cf62462091842788b399bab23f77a469` |
| `build/acceptance/probe/CookeryAcceptanceProbe.jar`（61,227 字节） | `ce6348e0f1460b358202fcec1711aa4e0f5b5e957a93ef3ba12abeee941ea83c` |

v1/v2 石磨资源 ZIP 逐字节一致；v2 增加的严格导入检查没有改变已知模型。验收不会扩大到真实玩家/原版客户端、保护插件、多人同家具、跨 Folia 区域或独立区块卸载。前序 CE 启动 watchdog 继续 OPEN，本次石磨 ticker 修复不作为该启动问题的修复证据。全系列和剩余 150 条厨房来源配方仍未完成。

## 炒锅精准/种类配方与存档（前序证据）

- `gradlew test shadowJar acceptanceJar --console=plain` exit 0；118 Java 测试，0 失败/错误/跳过，日志 `build/acceptance/pot-build-final.log`。Python 全量 64 项通过，日志 `pot-python.log`。strict catalog check、`git diff --check`、分发 JAR 排除 CE/Paper/JUnit/Mockito/探针类检查通过。
- 官方 225 条炒锅配方导入 195 精准＋24 种类。6 条 pending：4 条缺 `forge:mushrooms`、2 条引用未映射 `farmersdelight:cooked_rice`，没有删除备选项。整个厨房 612 来源中 380 导入、232 pending；最终 351 加工＋33 合成配置，264 物品、12 方块、180 状态、273 可达模型检查。
- 运行时按原料数量建不可变索引，精准 → 具体物品去重 → 旧余弦。与高汤锅共用增广匹配和品质偏差计算；产出数量固定。新增 `pot_recipes` 原子发布、白名单、容器映射和数量统计。导入菜单九格及原料备选说明只读，原 CE 名称/lore 保留；记录本和家具查询识别 `pot` 类型。
- 官方 `stir_fry_count` 固定到新批次，最后一次 24 tick 动画结束后出锅；`source_time` 保留来源时间但不参与完成门槛。无快照且已翻炒的旧批次仍使用旧余弦与厨具默认次数。断点动画/配方表变更、旧批次、坏计划/保留原料、部分取出保存有单元回归。
- 盛出先获取控制器防重入标记，再发可取消事件；取消、空成品、份数/容器变化均不扣容器。每份先扣锅中数量并标脏，再给物品；同步物品回调改变余量后终止后续盛出。不会将这组 mock 回归宣称为保护插件或多人实测。
- 首轮 `pot-v1/paper/boot-1.log` 捕获 CE 物品未注册时炒锅解析已运行，129 条失败导致整代配方回退。`PotRecipeParser` 改为等待 `POT_FOOD_RAW`（传递依赖 ITEM），新目录 `pot-v2` 干净启动通过。`pot-load-order-red.log` 为修复前失败，最终测试通过。导入回归 `pot-import-red.log`（219 != 0）→ `pot-import-green.log`；无效盛出回归 `pot-serving-red-verified-v2.log`；同步回调重复盛出回归 `pot-reentrant-red.log`（实际扣取次数过多）→ 最终全量通过。此前 Mockito/NMS 初始化失败日志单列，不充当业务失败证明。

| 验证 | 真实结果 | 边界 |
| --- | --- | --- |
| Paper | `pot-v2` cycle 1/2/3/4 通过，全部正常停服 | 最终 JAR 为 cycle 4 |
| Folia | cycle 1/3/4/5 通过，正常停服 | cycle 2 启动 watchdog 5.2812568 秒，仍 FAIL/OPEN；最终 JAR 为 cycle 5 |
| 炒锅 | 每轮 195 精准＋24 种类的匹配、官方参数、固定产物构造、计划序列化；2 个记录本序列化通过 | 原料/翻炒由区域内 API 驱动，无玩家操作 |
| 重启与盛出 | 两锅炒一次后停服；读回 1/3 次进度后续炒完成 3 烤土豆、1 拔丝土豆。分别取一份，再次重启余量 2/0、原料显示随份数回收、空锅无重复、油被消耗 | 原版客户端动画、玩家碗扣除、保护/多人/跨区域仍未测 |
| 原功能回归 | 132 原加工计划、33 合成注册/输出、103 消费品、5 附魔、12 方块/180 状态/264 物品；蒸笼/茶壶/砧板/高汤锅中途重启继续通过 | 合成网格/余物、客户端接包/画面未测 |
| 资源与产物 | 两端 1,705 文件与 ZIP manifest 一致；生成包各 1,247 模型无 Forge loader；重复导出 ZIP 逐字节一致 | 服务端生成资源不代表客户端已渲染 |

Folia cycle 2 在 `CraftEngine.runDelayTasks` 的 `CompletableFuture.join()` 采样，未到炒锅探针。首次启动通过并不能关闭这个问题；前序冷启动的 CEWorld/字体缓存采样也保留，未改 watchdog 阈值或豁免 ERROR。整体继续 `PARTIAL_FOLIA_STARTUP_WATCHDOG_OPEN`。

摘要 [cookery-pot-acceptance.json](research/cookery-pot-acceptance.json)，完整证据 `build/acceptance/pot-v2/acceptance.json`，日志 `pot-runtime-v2.log`、`pot-folia-resume.log`、`pot-final-runtime.log`。旧 JAR 保存在各实例 `artifact-checkpoints`。新副本均停服；没有重启既有实例或生产服。

| 当前产物 | SHA-256 |
| --- | --- |
| `build/acceptance/cookery-pot-v1.zip`（2,264,500 字节） | `f59fd9beeb7abd95ad34cd6c7d9411c1f7660977409b875c1d6c97835a690091` |
| `build/libs/KaleidoscopeCookeryPlugin-1.3.0.jar`（1,360,593 字节） | `5c686adf0392e5459d7ee625a2888c2340ac2c0e98db4bed2d55b63b7aaf1733` |
| `build/acceptance/probe/CookeryAcceptanceProbe.jar`（48,214 字节） | `86321b81fcf521be7bf76ecbbd7e51353a36dff1c4438c90f7b5f222f04df514` |

全系列仍未完成：232 条厨房配方、原料获取链、农夫套装、茶旗、厨师/交易/村庄结构、摆盘，以及 Doll/Tavern/Deco/MusicPlus 剩余服务端和资源功能，参见系列矩阵。

## 高汤锅精准/种类配方与存档（前序证据）

- `gradlew test shadowJar acceptanceJar --console=plain` exit 0，105 项 Java 零失败/错误/跳过；全部 9 个 NMS 适配编译任务通过。`python -X utf8 -m unittest discover -s tools -p 'test_*.py'` exit 0，61 项通过。日志 `build/acceptance/stockpot-build-final-v2.log`、`stockpot-python-final.log`。strict catalog check 与 `git diff --check` 通过。
- 配方导入回归先失败（101 != 0），通过后记录 `stockpot-import-{red,green}.log`；汤底返回物品缺失时会清空旧锅的回归先失败，修复后通过，记录 `stockpot-controller-red-verified.log`、`stockpot-unit-green.log`。最初 Mockito 缺 NMS proxy 的环境失败另存 `stockpot-controller-red.log`，不当作业务失败证据。
- 105 条官方高汤锅来源中导入 75 精准、26 去重种类配方；另 4 条缺 `forge:mushrooms` 明确 pending。全包 612 来源中 161 已导出、451 pending；最终 132 加工配置＋33 合成配置。未知字段、标签、循环、NBT、汤底、输出数量失败，只有明确列出的外部依赖允许 pending。
- 选择器按汤底/原料数量建不可变索引，精准优先、种类其次、旧余弦最后。备选原料用增广匹配，不能用贪心误吃重叠标签。品质沿用既有档位/缩放；官方产出数量不随重复原料扩大。无计划旧炖煮档依旧走余弦补齐；plan v2 可读 v1，模型/颜色及结果随批次持久化。
- 新高汤锅汤面 `default_cooking/default_finished/rice_finished` 引用官方贴图，显示实体高度按 `0.065 + 0.315 × remaining / maximum`，旧配方液面规则保持。原版 dust 替代官方有色泡泡。九格菜单只读、备选项/时间放独立说明按钮；原物品 lore 保留。配方记录本序列化和查询通过，玩家自动投料/家具画面未测。

| 验证 | 本轮真实结果 | 边界 |
| --- | --- | --- |
| Paper | `stockpot-v1` cycle 1–5 通过，正常停服 | 最终 JAR 是 cycle 5；前轮 JAR 在 artifact-checkpoints 保留 |
| Folia | cycle 2–5 通过，正常停服 | cycle 1 冷启动 watchdog 5.7309607 秒，继续 FAIL/OPEN |
| 高汤锅 | 75 精准/26 种类的匹配、时长、成品/模型构造、计划序列化；2 锅保存后续煮，米饭 3 份/骨汤 1 份 | 由 API 放置、盖盖和投料，无玩家点击 |
| 盛出/重启 | 取 1 米饭与整份骨汤后重启，剩余 2/0 份，液面 -0.105；空锅再取为空；碗载体与米饭成品模型保留 | 不证明玩家手中碗扣除、事件取消/重入、多人与保护 |
| 原加工与资源 | 132 加工计划、33 合成输出、103 消费品、5 附魔、12 方块/180 状态/263 物品通过；1,705 安装文件哈希匹配，1,247 生成模型无 Forge loader | 原版客户端接包/画面、合成网格/余物仍 NOT_TESTED |

Folia 本次采样在 `CEWorld.<init>` → `BukkitWorldManager.delayedInit`；此前字体偏移缓存/`runDelayTasks.join` 的失败证据仍保留，不能证明根因相同或已修复。整体继续 `PARTIAL_FOLIA_COLD_START_WATCHDOG_OPEN`，没有延长 watchdog 或豁免 ERROR。

当前摘要：[cookery-stockpot-acceptance.json](research/cookery-stockpot-acceptance.json)，完整实例 `build/acceptance/stockpot-v1/acceptance.json`；主测试服 `25581/25582` 未重启，副本 `25583/25584` 均已停服。最终包 `cookery-stockpot-v2.zip` 仅 manifest 差异说明相对 v1 改动，所有资源逐字节相同；另一次导出与 v2 完全一致。

| 当前产物 | SHA-256 |
| --- | --- |
| `build/acceptance/cookery-stockpot-v2.zip`（2,259,358 字节） | `8a5d15b1dbbeea64bdcff015c574a7823ff41e21315e9ccb0ad2f567d8862e33` |
| `build/libs/KaleidoscopeCookeryPlugin-1.3.0.jar`（1,345,410 字节） | `98208e978790ed694c6289390ed8df53f7b0a3c975c9a297fc01ce63c77e74f2` |
| `build/acceptance/probe/CookeryAcceptanceProbe.jar`（36,986 字节） | `4bb28c106ebadda9ef78bac0f94ad49a7268caa0a70f799a73ebdde4e8194fa5` |

生产 JAR 不含 CE/Paper/JUnit/Mockito/acceptance 类。两端 cycle 5 安装 JAR 与上述产物哈希一致。剩余 451 配方、实际原版客户端、获取链、套装/茶旗/村民/结构/摆盘和其他系列目标均未完成，未提交/推送/发布/部署生产服。

## 厨房配方与加工重启（前序证据）

- `gradlew test shadowJar acceptanceJar --console=plain` exit 0；扩展回归后 `gradlew test` exit 0，94 项零失败/跳过。Python 全部 58 项通过。日志：`build/acceptance/recipes-build.log`、`recipes-java-tests.log`、`recipes-python-final.log`。
- 奶桶业务回归先失败 3 项，再实现倒入/取回和守恒；详见 `teapot-milk-red-verified.log`、`teapot-milk-green.log`。新增错误手持物、返回物品缺失、创意模式、加工/成品拒绝装桶回归，水/岩浆原行为继续通过。
- `cookery_recipes.py` 从固定 Git blob 读取两个资源命名空间，共 612 配方、16 类型。47 条已导出为 11 茶壶（花茶展开 5 原料）、4 蒸笼、16 砧板、20 合成配方；其余 565 条保留具体 pending 原因。未知结构失败；Forge NBT 不会静默丢弃，回归 `recipes-nbt-red.log` 先失败，全量测试再通过。
- 首轮 Paper 实测发现源 `minecraft:chain` 不存在于目标注册表；`javap org.bukkit.Material` 确认目标是 `IRON_CHAIN`。显式别名修复后回归通过，首轮失败日志保留于 `recipes-v1/paper/boot-1.log`，先红后绿见 `recipes-chain-{red,green}.log`。

| 验证 | 当前证据 | 边界 |
| --- | --- | --- |
| Paper | `recipes-v2` cycle 1/2/3 均通过并正常停服 | API/存档注入原料，未模拟玩家 |
| Folia | cycle 2/3 通过并正常停服；恢复 3 组中途批次 | cycle 1 冷启动 watchdog 5.4575362 秒仍判 FAIL |
| 注册与产物 | 两端 31 加工计划匹配、时长、序列化、构造成品/副产；20 原版合成配方注册/输出 | 合成网格、容器余物、玩家拿取未验证 |
| 加工存档 | 蒸笼 4 原料运行 100 tick 后暂停；茶壶移走热源；砧板切 1 刀。重启续作产出 4 蒸食、8 份酥油茶、3 小肉块＋1 骨头；额外切割无产出；再次重启成品仍在 | 跨区域/多人/保护/区块卸载独立验证仍缺 |
| 资源 | 252 物品、12 方块、180 状态；两端安装的 1,702 文件哈希一致；生成 ZIP 1,244 模型无 Forge loader，茶壶字体存在 | 8 茶杯、6 HUD 字形、64 切割帧及基础汤面未由原版客户端目视验收 |

本次 Folia watchdog 采样在 CE `OffsetFont.createOffset` → `registerShiftTags` → `delayedLoad` 中的 Caffeine 类加载；前轮采样在 `CompletableFutures.allOf(...).join()`。尚未证明共同根因或修复，保留全部日志，不延长阈值或忽略 ERROR。整体状态仍 `PARTIAL_FOLIA_COLD_START_WATCHDOG_OPEN`。

可追踪摘要：[cookery-recipes-acceptance.json](research/cookery-recipes-acceptance.json)，详细证据 `build/acceptance/recipes-v2/acceptance.json`。新包 `build/acceptance/cookery-recipes-v2.zip` SHA-256 `6e5af0077d5dcfd92c0de4e5350e9a875bf16e908ff34454769cf1baaa1c5494`，重新导出逐字节一致；生产 JAR `475e4eaca71d4e27cc615cf2678bf596547d72b4c90d2cef283608509e659b37`，未包含 CE/Paper/测试/探针类。旧运行证据和系列发布包保留。

仍未闭环：565 条厨房配方（含外部 Forge 标签、原料生长/获取、精准/模糊匹配冲突）、每配方汤面/动态高度、原版客户端、玩家交互、多人/保护、套装/茶旗/职业/结构/摆盘。

## 厨具资源检查点（上一检查点，保留历史证据）

- `tools/export_cookery_kitchen_pack.py` 从固定提交生成食品＋厨具验收包：165 物品、12 方块、180 状态、174 个可达模型引用检查、12 个音效别名。原先缺失的 11 个厨具定义均已加载；油壶作为第 12 个方块。
- `python -m unittest discover -s tools -p 'test_*.py'`：45 项通过，exit 0，日志 `build/acceptance/kitchen-python.log`。搪瓷盆中档油面错误映射到低档的回归先失败后通过，见 `kitchen-import-red.log` / `kitchen-import-green.log`，未削弱断言。
- 包安装路径回归先暴露 `cookery_kitchen/../default_assets/...` 可越出目标包目录的问题（`kitchen-path-red.log`）；现同时拒绝 `..`、反斜杠和目标包外路径，失败前不移动原食品目录。修复后全套 45 项通过。
- `gradlew test shadowJar` exit 0；生产类未改，84 项 Java 测试为 Gradle 缓存命中，日志 `kitchen-java.log`。`gradlew acceptanceJar` 实际编译扩展后的探针并成功，日志 `kitchen-probe-build.log`。
- 新副本在 `build/acceptance/kitchen-v1/{paper,folia}`，端口仍为隔离副本端口 `25583/25584`；原先 `25581/25582` 实例未重启。所有本轮副本已正常停服。

| 验收 | 实际结果 | 限制 |
| --- | --- | --- |
| Paper | 冷启动与重启通过；最新 cycle 3：165 物品构造/序列化、12 方块、180 状态/控制器、12 方块重启读回，均零失败 | 放置走服务端 API，未模拟玩家 |
| Folia | cycle 2/3 重启通过；最新 cycle 4 同样 165/12/180、重启读回 12，均零失败 | 首次冷启动 watchdog 5.1684424 秒，严格验收判 FAIL，仍 OPEN |
| 内容持久化 | 搪瓷盆打开状态与 7 份油、油壶 87 份油跨重启保留；同时检查沙威玛上半方块保留 | 不等于烹饪流程或多人守恒验收 |
| 食物与附魔 | 继续通过 103 食物、5 附魔等级及 5 个附魔物品重读 | 获取/铁砧、实际消费仍未测 |
| CE 资源 | 生成/引用验证/压缩成功；两服各 1,618 个安装文件 SHA-256 与包 manifest 一致；生成 ZIP 的 1,168 个模型 JSON 无 Forge loader | 原版客户端接包/显示为 NOT_TESTED |

Folia 失败堆栈落在 CE 26.9.1 `CraftEngine.runDelayTasks:282` 的 `CompletableFutures.allOf(delayedLoadTasks).join()`；这里只确认等待位置，尚未确定哪一个异步延迟加载任务耗时。保留 `folia/boot-1.log`，没有延长 watchdog 或过滤该错误。后续通过不覆盖冷启动失败。

证据摘要：[cookery-kitchen-acceptance.json](research/cookery-kitchen-acceptance.json)。逐轮日志、探针、资源哈希和整体 `PARTIAL_FOLIA_COLD_START_WATCHDOG_OPEN` 在 `build/acceptance/kitchen-v1/acceptance.json`；旧探针与扩展探针哈希按 cycle 分别记录。启动脚本启用 `--kitchen-pack` 后不再允许缺失厨具错误，停服出现 ERROR 也判失败。

本轮产物：

- `build/acceptance/cookery-kitchen-v2.zip`：2,099,878 字节，SHA-256 `08d9391b75e755caf81fe6592dcd12e9c933554ebd2d882e5193d83cf8ac9e94`。
- `build/acceptance/probe/CookeryAcceptanceProbe.jar`：19,632 字节，SHA-256 `1c1ec949ff285b0bf94a24481ae867512b210a251f787b1fc719a33fa09da543`，仅用于隔离验收。
- 主插件 JAR 和目录快照哈希保持下文基线；已有系列开发 ZIP 未覆盖。

仍有明确差异：非炉灶厨具用共享透明细绊线状态加显示实体，不能宣称具有官方实体碰撞；茶壶支架和吊链暂取高汤锅支撑模型，须继续转换官方 Java 模型；`steamer` 的 TOP 兼容态为半笼平移；未启用水浸。茶杯/汤面、完整配方、食物摆盘、保护、多人与原版客户端均未完成。复现方法见 [厨房验收包](../examples/kitchen-pack/README.md)。

## 上一轮附魔基线（保留历史证据）

- `.\gradlew.bat test shadowJar acceptanceJar --console=plain`：exit 0，包含主源码、全部 9 个 NMS 适配、Shadow JAR 和独立验收探针。日志 `build/acceptance/enchantments-build.log`。
- Java：84 项通过，0 失败、0 错误、0 跳过；XML 位于 `build/test-results/test/`。新增附魔读取 10 项、砧板 12 项、镰刀 9 项；效果重入等 9 项保留。
- `python -m unittest discover -s tools -p 'test_*.py'`：30 项通过，exit 0，日志 `build/acceptance/enchantments-python.log`。
- `python tools/export_cookery_catalog.py --source ../.references/KaleidoscopeCookery --check`：exit 0，固定提交及 SHA-256 一致；148 foodProperties、94 food、8 tea、12 effect、2 enchantment。
- 新附魔数据包 JSON、物品标签引用对照实际 Mojang 1.21.11 server JAR 通过。CE 运行资源生成见下文；没有客户端渲染证据。
- JAR 检查：含 `CookeryEnchantments`，未打入 CE、Paper、JUnit、Mockito、未重定位 bStats 或 acceptance 类。验收探针独立打包。
- `git diff --check` 通过，官方参考 checkout 无改动。

镰刀先运行行为回归，9 项中 7 项失败，确认旧实现没有附魔范围和区块归属检查；实现后全部通过。保留 `build/acceptance/sickle-regression-red.log` 和 `sickle-regression-green.log`。测试依赖最初缺类及无服务器 Registry 的问题在测试环境修正，没有删减业务断言。

砧板回归覆盖 0/I/II/越界等级、奇偶切割次数、旧无参入口、存档重读后换刀、末刀一次性产出、产物不可用时保留原料和整数边界。镰刀检查逐格保护、未加载区块和其他 Folia 区域；这是模拟世界单元测试，尚非玩家区域边界验收。

## 上一轮隔离服务器证据

`build/acceptance/paper` 和 `folia` 的既有服保持在 `127.0.0.1:25581/25582`。本轮副本使用 `build/acceptance/enchantments-v4/{paper,folia}` 的 `25583/25584`，自行正常停服，不影响已有实例。

| 范围 | 已确认结果 | 边界 |
| --- | --- | --- |
| Paper 1.21.11-132-c5eb079 + CE 26.9.1 | 冷启动/重启各 103 种食物和 5 个原生附魔等级验证零失败，重启读回 5/5 | 仍有 11 个缺失厨具定义报错 |
| Folia 1.21.11-14-529aabc + CE 26.9.1 | 后续两次重启均食物 103/103、附魔 5/5、旧物品重读 5/5，探针零失败且正常停服 | 首次 CE 初始化触发 global region 5.4128759 秒 watchdog，冷启动问题保持 OPEN |
| CE 资源包 | `ce reload pack` 执行生成、引用验证、压缩和本机托管；每轮资源 SHA-256 写入 smoke 记录 | 仅默认 CE + 食物包 + 图标，不包含缺失厨具；客户端 `NOT_TESTED` |
| 原版玩家 | `playerFlow: NOT_TESTED`、`clientResourcePack: NOT_TESTED` | 未宣称完成实际切菜、收割、接包或多人操作 |

可追踪摘要：[cookery-enchantment-acceptance.json](research/cookery-enchantment-acceptance.json)，记录构建、服务器、数据包、CE 源资源与运行日志哈希。原始证据入口 `build/acceptance/enchantments-v4/acceptance.json`，逐轮 `paper/boot-*.log`、`folia/boot-*.log` 与 `smoke-*.json`。首次 Paper 标签错误、脚本命令错误和 ANSI 日志识别超时保留在 `enchantments`、`enchantments-v2`、`enchantments-v3`，不作为通过记录。Folia 首次 watchdog 保留在 `enchantments-v4/folia/boot-1.log`，不静默排除。

复现命令（需要已有 `tools/prepare_test_servers.py` 的运行依赖及食品测试资源；EULA 已由用户在本会话明确接受）：

```powershell
python -X utf8 tools/run_enchantment_acceptance.py --accept-eula --root build/acceptance/enchantments-next
```

脚本只创建全新隔离目录，拒绝覆盖旧证据；两次启动间检查物品二进制重读，处理带颜色的控制台日志，任何新增 ERROR 会失败。完整例外清单仅为上述 11 个缺失厨具定义，仍写入结果。Folia 冷启动耗时异常需要继续定位，后续成功不能抹去该失败。

## 厨房附属物品双端启动（2026-11-16）

- 运行准备：`series/tools/prepare_independent_servers.py` 在全新 `build/acceptance/shovel-v2/{paper,folia}` 创建隔离实例，EULA 已由服主接受，端口 `25591/25592`。固定 Java 21、Paper 1.21.11-132、Folia 1.21.11-14、CraftEngine 26.9.1；输入 Cookery 包 SHA-256 `0618e04492e7b7d7f0d8317934a6aaaf8b9c5bd66a055a1c79ead015a011b011`。
- Paper 启动 PASS（49.321 秒），Folia 启动 PASS（43.493 秒）；两端加载五个插件、CraftEngine 与 `cookery_kitchen`，Cookery 方块注册自检 11/11。通过控制台 `ce workflow default` 运行生成、格式校验、压缩和托管，Paper/Folia 均 PASS，之后都通过 `stop` 正常保存并退出。服务器日志在各自的 `logs/latest.log`。
- 两端生成 ZIP 分别为 SHA-256 `8b2e995efec368c3c39275cb709932a1f54f8700a80e6f640c1ef60f3de1b27c`（2,507,044 bytes）和 `d874cd0edd76421fe60c4213e2ffb9d8ac5843e7d2597ed374c6de3b956ce38a`（2,507,037 bytes）。两包均有 3,020 entries；唯一内容差异是 CraftEngine 默认资源 `assets/minecraft/blockstates/brown_mushroom_block.json`。锅铲无油/油态 item definitions、两套模型及油态贴图均在包中，之前的 item-model 文件冲突警告已消失。
- Folia 冷启动有 5.0093117 秒 global-region watchdog 记录，栈位于 CraftEngine `TagUtils.createUpdateTagsPacket` / `BukkitNetworkManager.resendTags`；服务器随后启动并完成资源包 workflow。该 watchdog 根因仍 OPEN，不能因后续成功关闭历史告警。
- `python -m unittest discover -s series/tools -p 'test_*.py' -v` 共 33 项通过。原版客户端没有连接；锅铲实际涂油/倒油和客户端显示尚未验收。详见[双端验收摘要](research/cookery-shovel-runtime-2026-11-16.json)。

## 本地产物（历史附魔检查点）

`build/libs/KaleidoscopeCookeryPlugin-1.3.0.jar`

- 大小：1,325,139 字节。
- SHA-256：`d9b036bbacbea71459feff5ed6ac1d1b2088e3458d48b69279fedf0d43d5e405`。
- 仍沿用原项目 `1.3.0` 文件名，是本次本地开发构建，不能与原版发布产物混同。
- 上一轮附魔探针保留在 `build/acceptance/enchantments-v4/paper/plugins/CookeryAcceptanceProbe.jar`：10,106 字节，SHA-256 `5c41ad1df491fecafee9ee9c4d100fca4c69a60db9ae8a9f5b522317bb7ea421`。当前构建探针已扩展为本轮厨房版本。
- 原生附魔注册样例位于 `examples/cookery-enchantments`；不是完整附魔书获取/铁砧系统。

## 构建修复

原失效 `sparrow-reflection-proxy-scanner:1.1` 无源码引用，已移除。CE 基线维持 26.9.1。

本机 Windows 原生编码 GBK，JDK 21 启动器按原生编码读取 classpath 参数文件；Gradle 默认 UTF-8 导致中文 checkout 下测试类无法加载。Gradle daemon 与 Javadoc JVM 明确使用 `file.encoding=COMPAT` 后，参数文件读写编码一致；Java 编译、资源处理、文档输出和测试内容继续显式使用 UTF-8。保留既有 Gradle 弃用和 Java deprecated/unchecked 提示。

行为测试需要 CE API 未发布的 relocated 运行类，因此增加同版本官方 Modrinth artifact、Netty、Authlib 和已有目标 Spigot NMS 作为 testRuntimeOnly；不进入分发 JAR。

## 历史未验证项与当前边界

本节原始记录中的竹筛漏斗、堆叠显示和完整厨房包状态已由上方新日期检查点逐项替代。实际玩家、保护插件、多人同家具、世界切换、精确碰撞、完整组件守恒、独立区块卸载和跨区域仍待验收。附魔获取/铁砧、农夫套装、茶旗染色图案、厨师交易/五类村庄结构、各系列剩余功能保留在实施表中。没有生产部署。

独立示例模型和精确碰撞仍有差异；每槽数量堆叠已接入并有单元测试，原版客户端画面尚未验收。完整森罗系列中的自定义效果、玩偶、酒馆等工作保留在 [实施表](IMPLEMENTATION-ROADMAP.md)，不得标记为已复刻。

2026-11-04 历史基线：竹筛 `build` 含 Javadoc 通过，29 Java / 15 Python；当时 JAR SHA-256 `3971624172afb5020885f3d6891a8ec443c8e2f8960a4487de06ed843db52ab3`，日志 `build-bamboo-tray.log`。该旧产物不代表本轮附魔代码。


2026-11-17 交接增量：已从 `251ad5a` 重建并运行 Paper/Folia 五插件整合服。修复 CE 系列 ZIP 未解包、JAR 命名识别、旧资源覆盖和 Tavern 1.21.11 原料名称；使用 `--server-blocks 6000` 完整注册厨房 63 方块/5,474 内部状态（4,130 个显式外观组合不等于内部状态数，茶旗 anchor_type 包含 ceiling）。根 Python 104 项、系列 Python 54 项通过。两端包加载、生成和状态注册已有证据，但 Folia watchdog 和缺模型/纹理警告仍 OPEN，客户端/玩家流程未验收。详见 [当前交接](HANDOFF.md) 与 [本轮证据](research/series-runtime-2026-11-17.json)。
