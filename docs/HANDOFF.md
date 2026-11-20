# 森罗全系列开发交接

2026-11-17。构建目标为 Minecraft 1.21.1–26.3、Paper/Folia、CraftEngine 26.9.2 运行（编译 API 26.9.1）。1.21.x 运行服用 Java 21，26.x 用 Java 25；构建同时准备 JDK 21 和 JDK 25。各组实际编译依赖、资源包和验收边界见 [版本兼容说明](VERSION-COMPATIBILITY.md)。

## 当前多版本检查点

最新增量为 [稻草人受击](SCARECROW-HITS.md)：按实体共享五tick受击窗口、首次正弦晃动、创造即时无掉落，以及最终事件取消/装备掉落设置。g/31–32、h/20–21各十组报告通过，包含未登录原生玩家夹具的真实CE事件/移除API验收；没有真人网络点击/客户端证据。g/29探针缺少luck导致失败，g/30修正后通过，记录保留。最终证据 `docs/research/scarecrow-hit-runtime-2026-11-17.json`。

前序增量为 [稻草人](SCARECROW.md)：双手工具/头颅交换、游戏tick冷却、完整装备持久化和取消掉落回收；13块来源几何、独立倾斜帽和合成配方。厨房1,432物品、81方块、25家具，612/612配方配置导入。g/27–28、h/18–19各九组报告通过，资源生成/正常退出及三件装备重启/回收通过；h每轮两条Windows OSHI错误保留。原始证据 `docs/research/scarecrow-runtime-2026-11-17.json`。完整实体行为与客户端仍待实现/验收。

前序增量为 [垃圾桶来源动画](TRASH-CAN-ANIMATION.md)：五轨道15通道51关键帧，四部件独立瞳孔，来源旋转/支点、世界时间和追踪姿势。最终g/25–26、h/16–17各八组报告通过，116姿势编码、库存重启和回收通过；实际客户端与完整躲藏仍未验。运行证据 `docs/research/trashcan-animation-runtime-2026-11-17.json`。g/23–24为最后一行坐标哈希修正前的通过记录，不作为最终JAR证据。

前序增量为 [垃圾桶](TRASH-CAN.md)：修正潜行取回、创造扣物和组件合并，新增红石筛选、严格存档和取消掉落回收，导入来源七块模型几何/配方。g/21–22、h/14–15 各八组报告通过、资源包生成成功并正常退出；26.3 每轮两条 Windows OSHI 错误仍保留。运行证据 `docs/research/trashcan-runtime-2026-11-17.json`。前序 [资源路径](RESOURCE-PATHS.md) 修复继续保留。

前序新增 [挂鱼勾](FISHRACK.md)：完整三格物品、后进先出、创造模式语义、拆除返还与取消回收。40种组合模型、160家具变体，Deco物品定义1115、家具785、变体2423。正式运行摘要 `docs/research/fishrack-runtime-2026-11-17.json`；完整复刻、客户端、Folia和中间版本仍未完成。

本工作区基于 `1d283d6`，修改尚未提交/推送，没有使用子代理。五个插件、十组厨房专用 JAR 已构建；三组 CE 配置覆盖旧食物组件和铁链名称差异。Java 638＋80＋4，另有旧 Gson 60 项，Python 147＋75，通过。分发脚本支持 `--versioned-cookery --versioned-ce-packs build/distribution/versioned-ce-packs-scarecrow-hit-v1`，包内带逐文件哈希和版本说明。

前序 v2 Paper 1.21.1/26.3 隔离服为 `build/acceptance/c/paper`、`build/acceptance/d/paper`，端口 25601/25603，仅本机监听；使用 v2 检查点的目标 CE 包和 JAR，尚未包含便当袋改造。这些历史目录保持 `eula=false`、没有启动；用户现已授权，实际验收使用下述 g/h。两个目录旁的 `INSTALL-MANIFEST.json` 可核对所有来源。原始准备目录 a/b 和长路径失败目录保留。Folia 同目录仅准备文件，没有运行 JAR。

本轮接入带内容蒸笼物品、双层合并与取消回滚、分层掉落和两条合成，见 [带内容蒸笼](STEAMER-PORTABLE.md)。前序接入下界合金菜刀锻造，保留用户 custom_data 并保持目标菜刀身份，见 [菜刀锻造](KITCHEN-KNIFE-SMITHING.md)。前序加入红灯笼和一条配方，修正稻草人耕地保护的方盒与实体边界，见 [本批检查点](RED-LANTERN.md)。稻草人本批已导入，完整实体行为仍未完成。前序加入八仙桌 16 状态、四格事务与关联拆除，见 [八仙桌检查点](EIGHT-IMMORTALS-TABLE.md)。前序加入长凳八状态、同轴拼接、乘坐与清理和一条配方，见 [长凳检查点](LONG-BENCH.md)。前序加入辣椒串/蘑菇串和两条配方，采摘/塌落与亡灵伤害见 [悬挂串检查点](HANGING-STRINGS.md)。前序加入稻草块、油块及四条压缩/还原配方，新增行为与残余差异见 [储物方块检查点](STORAGE-BLOCKS.md)。前序 11 种桌子（四件物品、连接、64 种桌布模型，见 [桌子检查点](TABLES.md)）、11 种椅子、11 种凳子与 16 色坐垫，资源、交互和持久化检查通过；固体碰撞、水浸及客户端乘坐位置仍待对齐，见 [座椅检查点](SEATING.md)。本轮便当袋实现与限制见 [便当袋检查点](LUNCH-BAG.md)：24 槽、按组件合并、批量消费原生食物、取消与重入保护及果篮转移。厨房 612/612 配方已导入。本批交付 `build/distribution/SenLuo-1.21.1-26.3-dev-20261117-scarecrow-hit-v1.zip`；前序 portable 包保留。

前序本轮新增：抽奖机/酒馆计时器解析当前控制器，避免旧 wrapper 在重载后推进旧存档；旧版 CE 食物保持营养、时长和效果概率；导出器遇到不能等价转换的复杂效果即报错。继续运行验收时不要把版本组或编译成功当作所有中间版本已经验证。

较早便当袋检查点（尚未包含座椅更新）已另备 `build/acceptance/e/paper`（1.21.1，端口 25605）和 `build/acceptance/f/paper`（26.3，端口 25607）；使用 v4 内容包和本轮 JAR，安装清单位于各自父目录。均为 `eula=false`、尚未启动；同目录 Folia 尚无运行 JAR。

## 在新设备恢复

1. 克隆仓库并切到 `main`。已有工作区先检查本地改动，再用 `git pull --ff-only` 同步。
2. 按 [新设备构建](../README.md#新设备构建) 恢复仓库同级 `.references/` 下的五个固定源码 checkout，并安装两组 Python 依赖。上游地址与提交见 [来源锁定](research/organization-checkouts.json)。
3. 从仓库根目录运行厨房和系列导入检查，然后跑 Python、Java 测试和 JAR 构建。Windows 可在 PowerShell 使用以下命令。

```powershell
git clone --branch main https://github.com/addxiaoyi/SenLuo_Plugin.git
Set-Location SenLuo_Plugin
# 先按 README 恢复固定源码、.venv 和 JDK
.\.venv\Scripts\python.exe tools\export_cookery_catalog.py --source ..\.references\KaleidoscopeCookery --check
.\.venv\Scripts\python.exe series\tools\import_sources.py --check
.\.venv\Scripts\python.exe tools\build_all.py
```

4. 重新导出 [厨房内容包](../examples/kitchen-pack/README.md) 和 [四个独立系列内容包](../series/README.md#构建与验证)。按 [隔离服准备](../README.md#隔离服准备) 安装 JAR、内容包、CraftEngine 运行 JAR 和默认资源。生成包、原版客户端、Paper/Folia 运行 JAR 和测试世界需在新设备准备。

## 功能差异

| 插件 | 仍需实现或对齐 | 已有代码、需要实机验证 |
| --- | --- | --- |
| Cookery | 612 条来源配方已导入 610 条，剩 2 条。补原料获取、原料链、旧厨具资源差异、旧档导入与菜单编辑、厨师 1–5 级职业交易和五类村庄厨房。继续对齐茶旗平滑摆动、墙面交互形状、厨具碰撞、水浸、茶壶支撑、动物桶 NBT、附魔获取和铁砧限制。 | 原版客户端茶旗双面纹理／墙面朝向／动画、锅铲油态和农夫套装。38 种大菜的营养和 FOOD 效果、主副手、整份取食盘，5 种盖饭的 Shift／玩家 2×2／自动合成器。竹筛雨天、玻璃类遮挡、顶部及侧面漏斗、锁定和满目标、漏斗矿车。厨具与容器在多人、保护、旧档、爆炸、热重载、卸载及 Folia 跨区下的状态和物品守恒。12 类效果逐项验证。 |
| Doll | 双格抽奖机模型、投币与动画音效。自定义 Bedrock 玩偶、专用实体物品、移动玩偶的姿态与持物变换、精确碰撞、旋转与流体物理。电脑界面使用原版背包菜单，模组界面仍有差异。 | 摆件九轴姿态拆装和重启，抽奖取消／重启／奖品回收，付费兑换与收藏。投掷、弹跳、骑乘、幻翼携带、实体卸载及保护边界。 |
| Tavern | 野葡萄自然生成、藤架连接和精确形状、复杂酒桶／管道及酒桶液面、调酒混色、燃烧瓶、招牌文字、冰桶和连接家具、剩余自动化及非玩家饮用归因。 | 果盆八档液面和六种汁液、不同步的液量恢复、跳踩与满盆守恒。酒桶熟成和倒酒、雪克杯转装和释放时序、三类葡萄成长规则、龙头红石和保护、37 项饮品效果表及自定义效果事件。 |
| Deco | 当前导入报告有 188 条聚合差异，涉及 overlay、组件和物品标签、模型分支、多交互盒、库存家具、脚本、自动连接、碰撞及座位。13 种展板剪下返物及交易回归已实现。 | 状态转换、沙发 16 色和 4 形态、拆装存档、座位在状态切换后的表现、带脚本家具的保护和多人边界。 |
| MusicPlus | 原乐器 Bedrock 几何、第一人称持有与演奏动作、键盘输入、自定义乐器音色与客户端合成器。持续声部已有每 4 tick 原版声音近似。 | 手持乐器右键入口、音色和高低音、长 MIDI 和稠密音符、换曲／停止／离服清理及多人听音效果。 |

石磨、高汤锅和炒锅的已支持来源全部接入；612 条来源配方配置均已导入；这不证明玩法/自动化/获取链全部完成。完整来源 ID、原始配方和首个阻塞位于厨房 ZIP 内的 `cookery_kitchen/recipe-parity.json`。

逐项明细见 [厨房上游差异](COOKERY-PARITY.md)、[系列移植矩阵](../series/docs/PORTING-MATRIX.md)、[Deco 导入报告](../series/docs/deco-import.json) 和 [玩家验收表](../series/docs/ACCEPTANCE.md)。

## 接续顺序

当前增量见 [蒸笼交互与双端运行记录](STEAMER-INTERACTION.md)：一次填入/取全、空层拆除、副手、非热源支撑、来源朝向/底座和取消落地保护已接入。Gson 2.10.1 与 CE 空键 YAML 读取已修复；不要重复开发这些部分。前序带内容蒸笼 ZIP 及哈希保留为历史检查点。

用户已明确同意 EULA，授权本地 1.21.1/26.3 测试，记录在 `research/local-eula-authorization-2026-11-17.json`，无需再次询问。当前 g/h 分别使用 Paper 1.21.1-133/26.3-253、Java 21/25、CraftEngine 26.9.2，端口 25609/25611，仅 127.0.0.1。五组厨房探针和资源包生成已有双端通过轮次，重启与最终证据见版本说明。Folia 和真人仍未验收。g 的失败测试世界与所有失败轮次保留。

下一步继续解决 1.21.1 剩余 30 项旧物品模型转换警告（27 Deco、3 Cookery）、两端缺模型/纹理及 26.3 CE 默认棕榈树配置；补版本化附魔数据包交付。再继续真实玩家与 Folia、蒸笼世界顶端/水浸/碰撞/掉落语义，以及稻草人完整内容和垃圾桶碰撞/躲藏/精确动画。CE 26.9.2 的资源缓存初始化与首次工作流发生竞争，当前采用 `cache-resource-files: false` 后双端可生成；保留失败栈并继续确认其上游修复。

1. **跑当前提交的双端整合启动。** 用一套固定版本分别启动 Paper 和 Folia，核对行为、物品、方块、配方与最终资源包的注册日志。旧版双端启动和厨房控制器探针已有记录，本轮改动需要重新跑真实服务器。
2. **关闭两个运行差异。** Folia 的 CraftEngine tag packet 启动 watchdog 仍待定因，保留失败栈和耗时。对比 Paper/Folia 生成包中 `brown_mushroom_block` 的映射和实际模型，核对是否影响原版客户端显示。
3. **验收玩家闭环。** 原版客户端接包后验证五个插件的新旧行为。使用两名普通玩家覆盖保护、满背包、取消交互、重复点击、跨区及区块重载。重点验证茶旗、玩偶、果盆、展板和演奏在拆装或重启后的结果。
4. **继续补功能与交付。** 优先推进厨房缺失内容再导入受阻配方，继续酒馆葡萄与家具、玩偶实体、Deco 脚本与模型、MusicPlus 演奏替代。记录高密度家具和多人时的 MSPT、内存、资源包体积，完成八个上游仓库的分发许可复核后再做正式版本。

## 2026-11-17 验收增量

- 当前工作区以 `251ad5a` 为基线。本轮新增的隔离服工具会把四个系列 ZIP 和 shared ZIP 解包到 CraftEngine 可加载的目录，并拒绝旧 JAR 名称混用、恶意 ZIP 路径和 base resource 覆盖 managed pack；还支持从 CraftEngine 26.9.1 默认配置复制 `block.serverside-blocks`。
- `build/acceptance/i254` 使用 Paper/Folia 1.21.11、Java 21、CraftEngine 26.9.1、`server-blocks=6000` 启动。两端真实加载 `cookery_kitchen`、`doll`、`tavern`、`deco`、`music` 和 `shared` 六个 CE 目录；Paper/Folia 均登记 63 个厨房方块、5,474 个厨房内部状态，茶旗 4,032 个状态，缺失/多余均为 0。状态检查器范围是注册与存档缓存，不包含方块放置或客户端证明。
- Paper/Folia 的 `ce workflow default` 均生成、验证、压缩并托管资源包。`brown_mushroom_block` 两端有自动槽位变化，但 23 个 appearance 的模型、旋转和 uvlock 语义等价；比较器明确不证明客户端显示或跨服资源包可互换。Folia 仍有约 5.998 秒 CraftEngine 延迟加载 watchdog，保持 OPEN。
- 当前 Python 门禁为根工具 104 项、系列工具 54 项，均通过；固定 Cookery catalog 与系列导入检查通过。资源包校验仍报告部分上游 Deco/旧 Cookery 模型或纹理缺失，原版客户端和玩家闭环尚未验收。

本批最终分发 `build/distribution/SenLuo-1.21.1-26.3-dev-20261117-steamer-runtime-v3.zip`：54381041 字节，SHA-256 `88b6d9e6aa87fe99716d370f11a0bccea776186b6a11aa06fd2ed89add051b8c`。68 个 manifest 文件、11 个厨房 JAR 公共类一致性、18 个 CE 包逐文件哈希及 ZIP CRC 均通过。Java 共 733 次测试执行（含旧 Gson 60 项复跑），Python 197 项通过；实际测试 JAR 哈希与分发一致。见 [构建核验](research/cookery-steamer-interaction-build-2026-11-17.json)。所有本地 Java 测试服已退出，25609/25611 不再监听。
