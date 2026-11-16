# 森罗全系列开发交接

2026-11-16。目标平台为 Minecraft 1.21.11、Paper/Folia、CraftEngine 26.9.1。运行服用 Java 21，构建根工程同时准备 JDK 21 和 JDK 25。

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
| Cookery | 612 条来源配方已导入 536 条，剩 76 条。补原料获取与外部标签映射、原料链、旧厨具资源差异、旧档导入与菜单编辑、厨师 1–5 级职业交易和五类村庄厨房。继续对齐茶旗平滑摆动、墙面交互形状、厨具碰撞、水浸、茶壶支撑、动物桶 NBT、附魔获取和铁砧限制。 | 原版客户端茶旗双面纹理／墙面朝向／动画、锅铲油态和农夫套装。38 种大菜的营养和 FOOD 效果、主副手、整份取食盘，5 种盖饭的 Shift／玩家 2×2／自动合成器。竹筛雨天、玻璃类遮挡、顶部及侧面漏斗、锁定和满目标、漏斗矿车。厨具与容器在多人、保护、旧档、爆炸、热重载、卸载及 Folia 跨区下的状态和物品守恒。12 类效果逐项验证。 |
| Doll | 双格抽奖机模型、投币与动画音效。自定义 Bedrock 玩偶、专用实体物品、移动玩偶的姿态与持物变换、精确碰撞、旋转与流体物理。电脑界面使用原版背包菜单，模组界面仍有差异。 | 摆件九轴姿态拆装和重启，抽奖取消／重启／奖品回收，付费兑换与收藏。投掷、弹跳、骑乘、幻翼携带、实体卸载及保护边界。 |
| Tavern | 野葡萄自然生成、藤架连接和精确形状、复杂酒桶／管道及酒桶液面、调酒混色、燃烧瓶、招牌文字、冰桶和连接家具、剩余自动化及非玩家饮用归因。 | 果盆八档液面和六种汁液、不同步的液量恢复、跳踩与满盆守恒。酒桶熟成和倒酒、雪克杯转装和释放时序、三类葡萄成长规则、龙头红石和保护、37 项饮品效果表及自定义效果事件。 |
| Deco | 当前导入报告有 417 条聚合差异，涉及 overlay、组件和物品标签、模型分支、多交互盒、库存家具、脚本、自动连接、碰撞及座位。郁金香展板已支持切换外观，剪下返花待实现。 | 103 条状态转换、沙发 16 色和 4 形态、拆装存档、座位在状态切换后的表现、带脚本家具的保护和多人边界。 |
| MusicPlus | 原乐器 Bedrock 几何、第一人称持有与演奏动作、键盘输入、自定义乐器音色与客户端合成器、持续声部。 | 手持乐器右键入口、音色和高低音、长 MIDI 和稠密音符、换曲／停止／离服清理及多人听音效果。 |

厨房剩余配方中，59 条有序合成、5 条无序合成、3 条石磨、炒锅及高汤锅普通／灵活各 2 条，共 8 条，另有 1 条锻造。46 条首先卡在未导入内容，8 条缺铁锭标签、8 条缺蘑菇标签、2 条需要转换 Forge 方块实体 NBT。完整来源 ID、原始配方和首个阻塞位于新导出厨房 ZIP 内的 `cookery_kitchen/recipe-parity.json`。

逐项明细见 [厨房上游差异](COOKERY-PARITY.md)、[系列移植矩阵](../series/docs/PORTING-MATRIX.md)、[Deco 导入报告](../series/docs/deco-import.json) 和 [玩家验收表](../series/docs/ACCEPTANCE.md)。

## 接续顺序

1. **跑当前提交的双端整合启动。** 用一套固定版本分别启动 Paper 和 Folia，核对行为、物品、方块、配方与最终资源包的注册日志。旧版双端启动和厨房控制器探针已有记录，本轮改动需要重新跑真实服务器。
2. **关闭两个运行差异。** Folia 的 CraftEngine tag packet 启动 watchdog 仍待定因，保留失败栈和耗时。对比 Paper/Folia 生成包中 `brown_mushroom_block` 的映射和实际模型，核对是否影响原版客户端显示。
3. **验收玩家闭环。** 原版客户端接包后验证五个插件的新旧行为。使用两名普通玩家覆盖保护、满背包、取消交互、重复点击、跨区及区块重载。重点验证茶旗、玩偶、果盆、展板和演奏在拆装或重启后的结果。
4. **继续补功能与交付。** 优先推进厨房缺失内容再导入受阻配方，继续酒馆葡萄与家具、玩偶实体、Deco 脚本与模型、MusicPlus 演奏替代。记录高密度家具和多人时的 MSPT、内存、资源包体积，完成八个上游仓库的分发许可复核后再做正式版本。
