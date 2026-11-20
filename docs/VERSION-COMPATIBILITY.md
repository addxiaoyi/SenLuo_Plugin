# 1.21.1–26.3 构建与验证

2026-11-17。本轮基于 `1d283d6`，未把编译成功当作全版本运行通过。

五个插件的公共源码使用 Paper 1.21.1 API 和 Java 21 编译。现代食物组件、Dialog 界面放入独立源集，使用 Paper 1.21.11 API 编译，在支持的服务器上才加载。CraftEngine API 固定为 26.9.1；实际 26.3 运行必须使用新增该版本支持的 CraftEngine 26.9.2，本轮两个端点均验证 26.9.2。

## 产物选择

`build/libs/KaleidoscopeCookeryPlugin-1.3.0.jar` 是包含十个 NMS 桥的厨房通用 JAR。`build/libs/versions/` 中提供下列专用 JAR，每个只包含对应桥及盖饭 native recipe。通用版和专用版选择一个安装。

| 文件名后缀 | Minecraft 版本 | NMS 编译依赖 | 运行 Java |
| --- | --- | --- | --- |
| mc1.21.1 | 1.21.1 | 1.21.1 | 21 |
| mc1.21.2-1.21.3 | 1.21.2、1.21.3 | 1.21.3 | 21 |
| mc1.21.4 | 1.21.4 | 1.21.4 | 21 |
| mc1.21.5 | 1.21.5 | 1.21.5 | 21 |
| mc1.21.6-1.21.8 | 1.21.6、1.21.7、1.21.8 | 1.21.8 | 21 |
| mc1.21.9-1.21.10 | 1.21.9、1.21.10 | 1.21.10 | 21 |
| mc1.21.11 | 1.21.11 | 1.21.11 | 21 |
| mc26.1-26.1.2 | 26.1、26.1.1、26.1.2 | 26.1.2 | 25 |
| mc26.2 | 26.2 | 26.2 | 25 |
| mc26.3 | 26.3 | 26.3 | 25 |

同组版本共享 NMS 源集，仅表中编译依赖已直接编译。历史 Paper 未发布的版本不能据此推断有对应 Paper/Folia 服务端。

其余四个插件没有直接 NMS 依赖，各自构建一个通用 JAR：

- `series/doll/build/libs/KaleidoscopeDoll-0.1.0-dev.jar`
- `series/tavern/build/libs/KaleidoscopeTavern-0.1.0-dev.jar`
- `series/deco/build/libs/KaleidoscopeDeco-0.1.0-dev.jar`
- `series/music/build/libs/KaleidoscopeMusic-0.1.0-dev.jar`

它们的 `plugin.yml` 最低 API 已调整为 1.21。共享兼容库随每个插件独立打包并重定位，不需要额外的系列核心插件。

## 本轮兼容修复

- 1.21.1 的食用时间、效果、容器及长按蓄力使用旧 FoodComponent；新版本使用独立 Consumable 适配。
- 厨房、酒馆兼容新旧属性名称，葡萄兼容 GameRule/GameRules，饮品使用跨版本效果注册表。
- 镰刀在支持物品冷却组的服务器保留物品冷却，旧版回退材料冷却。
- 旧版预设选择菜单可通过聊天按钮选择并使用已有铁砧输入，自定义 Dialog 只在 1.21.6+ 加载。
- 26.3 使用独立编译桥，未知版本不再因字符串前缀匹配误用 26.1/26.2 桥。

1.21.1 不具备新版 Consumable 的任意使用动画，旧版蓄力采用可食用物品动画；真实玩家显示和物品恢复仍需验证。兼容测试不能证明所有 CraftEngine 资源配置在全部版本可用。

## 内容包选择

开发分发包的 `versioned-ce-packs/` 提供三组配置；安装与服务器版本对应的一组，不要同时加载原始 `ce-packs/` 中的同名内容。每组均含厨房、玩偶、酒馆、装饰、音乐和 shared 六个 ZIP。厨房 ZIP 的 `cookery_kitchen/` 解压到 CE resources；其余 ZIP 分别解压到 `doll/`、`tavern/`、`deco/`、`music/`、`shared/`，使每个目录直接包含 `pack.yml`。

| 目录 | 配置差异 |
| --- | --- |
| `mc1.21.1` | 将 104 厨房食品/进食态和 38 酒馆饮品转为原生旧 food 组件，保留营养、饱和度、食用时间、效果概率和 PDC。40 处铁链引用使用 `minecraft:chain`；便当袋使用 paper 避免实验性 bundle。 |
| `mc1.21.2-1.21.8` | 保留 Consumable，40 处铁链引用使用 `minecraft:chain`。 |
| `mc1.21.9-26.3` | 使用新版 Consumable 和 `minecraft:iron_chain`，配置内容与 1.21.11 导出基线一致。 |

1.21.1 的 47 个自定义饮品使用吃东西的动画和声音，这是原版能力限制；效果和容器逻辑保留。复杂消费效果或共享概率的效果组无法等价转换时，导出器会报错，不会删掉效果。模型、贴图、许可和原始 PDC 按字节保留。每个 ZIP 的 `VERSION-MANIFEST.json` 记录输入哈希、修改文件、配置组和运行验收状态，厨房原有 manifest 也重新计算配置哈希。

可复现导出命令：`python tools/export_versioned_ce_pack.py --source <原始CE包.zip> --output <新文件.zip> --minecraft 1.21.1`。1.21.2 和 26.3 分别作为另外两组的代表目标。配置分组不代表该组全部版本已运行验证。

## 验证命令

```powershell
# 设置 JAVA_HOME 指向 JDK 25，并让 Gradle 能发现 JDK 21。
.\gradlew.bat test testLegacyGson shadowJar versionedJars acceptanceJar --no-daemon --no-configuration-cache
.\gradlew.bat -p series build --no-daemon --no-configuration-cache
.\.venv\Scripts\python.exe tools/build_all.py --skip-java
```

`series:common:legacyCompatTest` 仅加载真实 Paper 1.21.1 API，验证现代 Consumable 类不存在时的食物、蓄力、容器、属性、游戏规则和冷却路径。厨房产物测试检查十个桥的 NMS 描述符、Mojang 映射标记和 CraftBukkit 重定位。

本轮 Java：厨房 598 项；系列 71 项；旧 API 专用 4 项；Gson 2.10.1 专用 60 项（重跑目录测试）。Python：根工具 138 项、系列 59 项。完整结果保留在 Gradle XML 报告与开发包 manifest 中。本轮 Paper 1.21.1/26.3 已实际启动并运行五组厨房探针。Folia、原版客户端、多人保护、跨区域与卸载仍未验证。

抽奖机、酒桶、果盆、雪克杯、龙头和葡萄的 tick 现在从 CE 元数据实体重新获取当前加载的控制器，避免 Folia 重载后旧 wrapper 继续推进旧状态；卸载、无效实体、区域不归属、停用或坏档均不推进。三个专用回归覆盖旧/新控制器切换和上述拒绝路径，尚待真实 Folia 卸载验证。

隔离服准备工具支持目标版本、独立端口和 Windows 长路径，无需改变系统注册表。Paper 1.21.1 build 133 / Java 21、Paper 26.3 build 253 / Java 25 的 JAR 已按官方 SHA-256 核验；`build/acceptance/c` 和 `d` 保留 v2 对应内容包及 JAR，尚未安装便当袋改造。仅监听 127.0.0.1，端口 25601/25603，EULA 仍为 false，尚未启动。先前长路径失败目录和原始配置准备目录保留；Folia 子目录尚未提供运行 JAR。

前序便当袋检查点（尚未包含座椅与桌子）已另备 `build/acceptance/e/paper`（1.21.1，端口 25605）和 `build/acceptance/f/paper`（26.3，端口 25607）；使用 v4 内容包和本轮 JAR，安装清单位于各自父目录。均为 `eula=false`、尚未启动；同目录 Folia 尚无运行 JAR。

## 同轮玩法进展

厨房加入明确的原版 Forge 标签成员映射和运行时工具 ID 映射，612 条来源配方中 610 条已导入、2 条继续 pending。石磨 85 条来源全部转成 90 个具体输入配方；高汤锅 105 条、炒锅 225 条。未知标签继续报错；外部模组新增成员未自动纳入。

便当袋实现 24 槽、按组件合并、批量食用与果篮交换；十个桥调用各自版本原生消费。1.21.1 的 paper 底材尚未验证袋内预览和空/满显示，旧 Forge NBT 和任意第三方组件未完整移植。详见 [便当袋检查点](LUNCH-BAG.md)。

Deco 的 13 种展板装饰支持剪刀取下返还物品；郁金香按上游四种等概率奖励返还一朵。装饰消耗一件材料，取消、满包、过期家具和同步重入不提交交易。上游普通返物函数为空，本实现按装饰材料返还，属于明确补齐的行为。

MusicPlus 支持音符结束、重复键配对、延音踏板、通道停止和最多 64 个持续声部。持续声部每 4 tick 使用原版音符声音近似，不能等同原模组客户端连续合成器。

完整功能仍有缺口：厨房剩余家具/原料与配方，Doll 精确物理和自定义模型，Tavern 世界生成和复杂家具，Deco 模型/脚本差异，以及原版客户端可实现范围之外的渲染。当前交付为开发验证包。

座椅和桌子检查点：22 种座椅、11 种连接桌与四物品桌面已接入。厨房共 1,422 物品、81 方块、23 家具、4,255 显式状态、1,480 可达模型；详细交互和碰撞/水浸差异见 [座椅](SEATING.md) 与 [桌子](TABLES.md)。

储物方块增量：稻草块 fallOn 同时读取旧 Float 和新 Double 距离；主源码仍通过真实 1.21.1 API 编译。油块与稻草块的物理、火源标签和粒子尚未在本轮服务器运行。详见 [检查点](STORAGE-BLOCKS.md)。

悬挂串增量：两个方块八个显式状态、两条配方和两段采摘已接入；方块掉落规则通过 DO_TILE_DROPS/BLOCK_DROPS 双名兼容。亡灵判定使用目标版 sensitive_to_smite 标签。轴向验收补齐 x/y/z。详见 [检查点](HANGING-STRINGS.md)。

长凳增量：八状态同轴拼接、双手交互、保护检查及生命周期清理已实现；新增一条来源配方。固体碰撞、活塞移动、岩浆点燃和客户端乘坐仍有差异，见 [长凳](LONG-BENCH.md)。

八仙桌新增四格事务、整桌破坏权限与 UUID 关联清理；16 状态和一条合成导入。放置检查已接入十版 NMS；固体碰撞、回滚中途卸载、爆炸掉落与跨区恢复仍待实服验收，见 [八仙桌](EIGHT-IMMORTALS-TABLE.md)。

红灯笼新增 14 级光照、来源模型与合成；稻草人保护修正为扩展方盒与完整实体边界相交。固体碰撞/客户端仍待验证，稻草人完整内容未导入，见 [本批检查点](RED-LANTERN.md)。

下界合金菜刀锻造已接入，并补齐 CE 默认合并会覆盖的用户 custom_data；原生组件和玩家槽位行为仍待实测，见 [菜刀锻造](KITCHEN-KNIFE-SMITHING.md)。

蒸笼新增固定槽位与严格存档加载，整叠取物不丢物，叠放使用 CE 放置流程；带内容物品、分层掉落、两条合成、一次取全/拆层/副手和来源支撑已接入，见 [蒸笼交互](STEAMER-INTERACTION.md)。

## 当前本地运行要求

用户已授权 EULA；当前 g/h 测试服均仅监听 127.0.0.1，历史 a–f 保留原状。五插件总内部方块缓存为 6,045，旧的 6,000 配额不足；本轮 `block.serverside-blocks` 使用 16,000。CE 26.9.2 的首次资源缓存与工作流存在竞争，本轮关闭 `resource-pack.cache-resource-files` 后成功生成，失败日志保留。运行证据见 `research/cookery-steamer-runtime-2026-11-17.json`。

原生附魔测试使用两个固定附魔定义；1.21.1 数据包格式 48，26.3 使用 min/max_format 121。当前通用附魔示例仍是 1.21.11 格式，安装其他版本前需要对应的格式适配，版本化分发尚待接入。

本批最终 Paper 轮次为 g/7、g/8（1.21.1）和 h/2、h/3（26.3）：每端均通过五组探针、24 厨具重启恢复和 1,422 物品构建；81 厨房方块的 5,599 内部状态齐全。资源工作流最终 g/7、h/3 成功，ZIP CRC 通过，仍有模型/纹理警告，详见 [实际运行证据](research/cookery-steamer-runtime-2026-11-17.json)。两个本地服均正常关闭。

前序 Deco 模型增量：g/11、h/5 六组探针、11 家具重启恢复与资源包生成通过。旧服模型转换警告降至37，34个Deco复杂模型仍有缺口；现代端1075个生成模型逐项一致。当前交付和剩余边界见 [Deco 模型](DECO-MODELS.md) 与 [实际证据](research/deco-model-runtime-2026-11-17.json)。

最新挂鱼勾增量：最终 g/19、h/11 使用同批生产 JAR 和版本化配置，增加三格内容重启、取消掉落、拆除回收及40种外观验证。30个旧版模型转换警告仍保留。验收和已知失败轮次见 [挂鱼勾](FISHRACK.md) 与 [运行证据](research/fishrack-runtime-2026-11-17.json)。

资源路径增量：1.21.9 起吊灯改用原版 iron_chain 纹理，旧版保留 chain。两个 Deco 食物模型路径补充精确别名，详见 [资源路径](RESOURCE-PATHS.md)。
