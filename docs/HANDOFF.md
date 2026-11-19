# 森罗全系列开发交接

2026-11-18。目标平台为 Minecraft 1.21.11、Paper/Folia、CraftEngine 26.9.1。运行服用 Java 21，构建根工程同时准备 JDK 21 和 JDK 25。

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
| Cookery | 612 条来源配方均已导入。补原料获取与外部标签映射、原料链、旧厨具资源差异、旧档导入与菜单编辑、厨师 1–5 级职业交易和五类村庄厨房。继续对齐茶旗平滑摆动、墙面交互形状、厨具碰撞、水浸、茶壶支撑、动物桶 NBT、附魔获取和铁砧限制。 | 原版客户端茶旗双面纹理／墙面朝向／动画、锅铲油态和农夫套装。38 种大菜的营养和 FOOD 效果、主副手、整份取食盘，5 种盖饭的 Shift／玩家 2×2／自动合成器。竹筛雨天、玻璃类遮挡、顶部及侧面漏斗、锁定和满目标、漏斗矿车。厨具与容器在多人、保护、旧档、爆炸、热重载、卸载及 Folia 跨区下的状态和物品守恒。12 类效果逐项验证。 |
| Doll | 双格抽奖机模型、投币与动画音效。自定义 Bedrock 玩偶、专用实体物品、移动玩偶的姿态与持物变换、精确碰撞、旋转与流体物理。电脑界面使用原版背包菜单，模组界面仍有差异。 | 摆件九轴姿态拆装和重启，抽奖取消／重启／奖品回收，付费兑换与收藏。投掷、弹跳、骑乘、幻翼携带、实体卸载及保护边界。 |
| Tavern | 野葡萄自然生成、藤架连接和精确形状、复杂酒桶／管道及酒桶液面、调酒混色、燃烧瓶、招牌文字、冰桶和连接家具、剩余自动化及非玩家饮用归因。 | 果盆八档液面和六种汁液、不同步的液量恢复、跳踩与满盆守恒。酒桶熟成和倒酒、雪克杯转装和释放时序、三类葡萄成长规则、龙头红石和保护、37 项饮品效果表及自定义效果事件。 |
| Deco | 当前导入报告有 416 条聚合差异，涉及 overlay、组件和物品标签、模型分支、多交互盒、库存家具、脚本、自动连接、碰撞及座位。 | 其余状态转换、沙发 16 色和 4 形态、拆装存档、座位在状态切换后的表现，以及其他脚本家具的保护和多人边界。 |
| MusicPlus | 原乐器 Bedrock 几何、第一人称持有与演奏动作、键盘输入、自定义乐器音色与客户端合成器、持续声部。 | 手持乐器右键入口、音色和高低音、长 MIDI 和稠密音符、换曲／停止／离服清理及多人听音效果。 |

厨房的 612 条来源配方均有导出配置。包括两条保存四槽数据的便携蒸笼配方、一条保留组件的厨刀锻造，以及座具、桌具、吊绳、油块、稻草块、灯笼、垃圾桶和稻草人。逐条对应关系见厨房 ZIP 的 `cookery_kitchen/recipe-parity.json`。

逐项明细见 [厨房上游差异](COOKERY-PARITY.md)、[系列移植矩阵](../series/docs/PORTING-MATRIX.md)、[Deco 导入报告](../series/docs/deco-import.json) 和 [玩家验收表](../series/docs/ACCEPTANCE.md)。

## 2026-11-19 双人联机验证

Deco 郁金香展板现已在 Paper 132 和 Folia 14 通过双人生存协议验证。装饰扣一朵，剪取随机返一朵，满背包仍会在地面产生掉落。交互或掉落被取消时保留装饰，两人连续剪取只出一份。详见[展板验收证据](research/deco-tulip-acceptance-2026-11-19.json)。

配方来源索引支持带空状态键的 CE 配置，对同一文件串行刷新缓存，损坏的 YAML 会拒绝保存。Folia 旧录制中的竹筛和精确配方加载约 20–45 秒；修复后的最终轮分别约 1.92 和 1.83 秒。Folia 五次复跑启动用时 31.423–56.413 秒，默认 watchdog 均未报警。原先停在 CraftEngine 配方预览原料注册的 5.59 秒样本未复现；它与已修复的重复 YAML 解析位于不同调用路径，仍需观察冷启动和负载下的表现。

两端各两个普通协议客户端实测通过：下载并解码每份托管资源包的 14,203 个资源；正常和 Shift 锻造各消耗一份材料、保留厨刀组件，取消 Shift 后库存不变；合成、放置并取回四份生面团和一个空蒸笼；取消一次稻草人破坏，随后两人在同区域连续击打，只掉落一个稻草人。见[复跑步骤](../tools/acceptance/connected/README.md)和[双端证据](research/series-connected-2026-11-19.json)。

仍需用支持 OpenGL 的设备和已登录的原版客户端核对画面、声音与账号认证。第三方保护、跨区域、热重载及高密度家具压力尚未验收；满背包仍需按玩法逐项核对。五插件功能差异见上表。公开分发条件见[上游许可证据](../series/docs/UPSTREAM-LICENSES.md)。

## 2026-11-18 交付记录

厨房 612/612 条配方已有来源和产物映射。系列包的 2,759 个可达模型通过静态检查，缺失模型和纹理警告为 0。原版 1.21.11 的物品模型解码器又发现 Deco 架上展示必须写作 `on_shelf`，导入器已修正。两端重新生成的托管包各有 14,203 个资源通过原版客户端离线读取。

五插件整合服使用 `--server-blocks 8000 --early-pack-load`。插件在 CE 读取包前注册所需类型，准备工具设置 `misc.delay-configuration-load: false` 和 `resource-pack.cache-resource-files: false`。此前在 Folia 默认延迟加载模式下，全局线程等待了 77.026 秒，同轮资源缓存并发写入导致生成失败。watchdog 阈值保持默认。另一次 Folia 重启在 CE 原生有序配方注册（`Ingredient.ofStacks`）出现 5.59 秒 watchdog，启动、资源生成和保存随后完成。相同配置在独占的复测中重启耗时 74.513 秒，未再报警。配方注册耗时继续跟踪。各次启动的日志摘要、包哈希与探针结果见[双端验收记录](research/series-runtime-2026-11-18.json)。

1. 使用支持 OpenGL 的机器和已登录的原版 Java 1.21.11 客户端，连接目标服，接受服务器资源包，核对茶旗、厨房家具、Deco 架上工具、玩偶、果盆和演奏界面。本机 demo 创建窗口时报告 GLFW 65542。
2. 用两名普通玩家覆盖保护、满背包、取消、重复点击、跨区和重载。优先检查 Folia 稻草人连续击打，拿取与回收蒸笼四槽物品，普通和 Shift 锻造的扣料与返还。记录前后物品、组件和掉落数。
3. 下一阶段按上方功能差异推进，另测高密度家具与多人 MSPT、内存，完成分发许可复核后再评估正式发布。

## 2026-11-17 验收增量

- 当时工作区以 `251ad5a` 为基线。本轮新增的隔离服工具会把四个系列 ZIP 和 shared ZIP 解包到 CraftEngine 可加载的目录，并拒绝旧 JAR 名称混用、恶意 ZIP 路径和 base resource 覆盖 managed pack；还支持从 CraftEngine 26.9.1 默认配置复制 `block.serverside-blocks`。
- `build/acceptance/i254` 使用 Paper/Folia 1.21.11、Java 21、CraftEngine 26.9.1、`server-blocks=6000` 启动。两端真实加载 `cookery_kitchen`、`doll`、`tavern`、`deco`、`music` 和 `shared` 六个 CE 目录；Paper/Folia 均登记 63 个厨房方块、5,474 个厨房内部状态，茶旗 4,032 个状态，缺失/多余均为 0。状态检查器范围是注册与存档缓存，不包含方块放置或客户端证明。
- Paper/Folia 的 `ce workflow default` 均生成、验证、压缩并托管资源包。`brown_mushroom_block` 两端有自动槽位变化，但 23 个 appearance 的模型、旋转和 uvlock 语义等价；比较器明确不证明客户端显示或跨服资源包可互换。Folia 仍有约 5.998 秒 CraftEngine 延迟加载 watchdog，保持 OPEN。
- 当时 Python 门禁为根工具 104 项、系列工具 54 项，均通过；固定 Cookery catalog 与系列导入检查通过。资源包校验仍报告部分上游 Deco/旧 Cookery 模型或纹理缺失，原版客户端和玩家闭环尚未验收。

## 2026-11-18 导入增量

- 已同步远端 `1d283d6` 的安装与双端运行修复。新增 11 条合成和 3 条石磨配方，来源导入数升为 550，剩余 62 条。目标标签映射记在厨房 manifest 和配方清单的 `targetTagMappings`，上游同名标签优先。
- Deco 旧食物物品 ID 通过转发模型兼容，蛙卵果冻补齐 GUI 模型，迷之菜粒子使用现有盘纹理。Tavern 灯具按 1.21.11 使用铁链贴图。额外导出资源的自定义依赖检查当前仍记录 5 条警告，原版资源需用目标客户端核对。
- 以下为当时的导入进度，整合复测见上方 612 条交付记录。

## 2026-11-18 蘑菇配方增量

- 固定 Forge 1.20.1 标签 `forge:mushrooms` 的两个成员为原版棕色蘑菇和红色蘑菇；定义取自 Forge 47.3.0 universal JAR（SHA-256 `a3c07e6b33fe8a4b42fdb4841a37e3ffda62797366c7050c481d799d6d35831c`）内 `data/forge/tags/items/mushrooms.json`（资源 SHA-256 `5c689ad6d0c3abb7af662644c0d539f591d8faa88be36afa598f8f4e6fca29f2`）。新增明确映射并保留两个原料选项。炒锅和高汤锅各导入 4 条，配方总数升至 558/612，剩余 54 条。
- 相关炒锅/高汤锅配置现分别为 225/105 条。之后的完整厨房包、双端运行和原版资源读取结果见上方交付记录。
