# 森罗系列插件移植工作区

更新于 2026-11-19。四个系列插件可分别构建 JAR 和 CE 内容 ZIP，已与 Cookery 一起在 Paper/Folia 1.21.11 上加载并生成托管资源包。前序冻结候选的有界原生 API 回归与每端 14,419 项官方离线解码见[历史玩法回归证据](../docs/research/series-gameplay-regression-2026-11-19.json)，不能沿用为后续新 JAR 的未测流程证据。当前 `gameplay` 协议场景只覆盖橡木红垫椅、罂粟文字展板和空地面燃烧瓶三个代表样例；最终同组五插件、探针及场景已在 Paper 132 `gp2/paper-run-6` 和 Folia 14 `gp2/folia-run-5` 各通过三阶段正常重启，每阶段两名协议玩家各读取 14,419 项资源，见[本轮有界协议证据](../docs/research/series-gameplay-connected-acceptance-2026-11-19.json)与[协议检查点](docs/ACCEPTANCE.md#木椅文字展板与地面燃烧瓶协议检查点2026-11-19)。当前为 0.1.0-dev 开发版，T05／T06／F04 广义状态仍为 `unit_tested`；完整真人玩法与画面仍待验收，历史 watchdog 根因仍 OPEN。[开发交接](../docs/HANDOFF.md)

厨房主工程继续位于仓库根目录；这里的 `common / doll / tavern / deco / music` 是独立 Gradle 多模块工程，不替换厨房已有实现。

## 官方源码

组织的 8 个仓库全部位于 `../../.references/`：KaleidoscopeCookery、KaleidoscopeDoll、KaleidoscopeTavern、KaleidoscopeDeco、KaleidoscopeDemo、KaleidoscopeWeb、MusicPlus、midi。

固定提交和仓库地址见 [organization-checkouts.json](../docs/research/organization-checkouts.json)。Demo、Web、midi 是示例、官网和 MIDI 支撑代码，不单独包装成虚假的玩法插件。导入工具使用固定提交的 Git blobs，拒绝修改过的参考源码。

## 已落地的范围

| 模块 | 已实现的服务端逻辑 | 当前资源定义 |
| --- | --- | --- |
| Doll | 抽奖、礼盒、643 玩家玩偶兑换、收藏、九轴显示微调与拆装恢复、蓄力投掷、落地弹跳、玩偶骑乘和幻翼携带事件 | 729 物品 / 725 家具 / 3 工作台配方 |
| Tavern | 果盆压榨和八档液面、24 酒桶配方、熟成、12 调酒配方、37 饮品效果表、三种葡萄、龙头装瓶、摇杯和倒酒；燃烧瓶及四种储物家具的燃烧瓶链、14 款 Dialog 文字展板 | 238 物品（含 48 个内部液面物品）/ 58 家具 / 114 工作台配方 |
| Deco | 主题图鉴、家具及部分座位、104 条状态转换、沙发染色与形态、拆装状态保存、郁金香展板装饰与剪取；12 木种椅子及 16 色椅垫、12 木种静态厨娘凳 | 1,070 物品 / 809 家具 / 68 沙发样式 / 24 石切配方 |
| Music | 右键打开 25 键菜单、本地 MIDI 多轨/速度/音色解析、原版音符播放、停止与离线清理 | 1 个使用上游吉他纹理的乐器 |

这些数量是资源和数据条目数，**不是功能完成率**。逐项差异见 [PORTING-MATRIX.md](docs/PORTING-MATRIX.md)，运行验收见 [ACCEPTANCE.md](docs/ACCEPTANCE.md)。厨房进度继续看 [原实施表](../docs/IMPLEMENTATION-ROADMAP.md)。

## 构建与验证

新四模块的编译基线为 **Paper 1.21.11、Java 21、CraftEngine API 26.9.1**；`plugin.yml` 最低 API 明确为 1.21.11。其他 MC/CE 组合尚未验证，不能沿用厨房九个 NMS 适配的编译结论。

在仓库根目录运行：

```powershell
python -m venv series/.venv
.\series\.venv\Scripts\python.exe -m pip install -r tools/requirements.txt -r series/tools/requirements.txt
.\series\.venv\Scripts\python.exe series/tools/import_sources.py --check
.\series\.venv\Scripts\python.exe -m unittest discover -s series/tools -p 'test_*.py' -v
.\gradlew.bat -p series build --console=plain
.\gradlew.bat build --console=plain
```

首次生成 CE 内容包。模块、官方仓库和转换器由 [`tools/module-specs.json`](tools/module-specs.json) 统一声明；`--module-archives-dir` 会生成四个独立模块包和一个可选的共享图集包；`--archive` 仍保留总包兼容旧流程：

```powershell
.\series\.venv\Scripts\python.exe series/tools/import_sources.py --module-archives-dir series/build/distribution/ce-packs-0.1.0-dev --archive series/build/distribution/KaleidoscopeSeries-CE-Packs-0.1.0-dev-gameplay.zip
.\series\.venv\Scripts\python.exe series/tools/validate_packs.py series/build/distribution/KaleidoscopeSeries-CE-Packs-0.1.0-dev-gameplay.zip
.\series\.venv\Scripts\python.exe tools/export_cookery_kitchen_pack.py --source ../.references/KaleidoscopeCookery --output build/distribution/kitchen-612.zip
.\series\.venv\Scripts\python.exe series/tools/package_dev.py --module-packs-dir series/build/distribution/ce-packs-0.1.0-dev --cookery-pack build/distribution/kitchen-612.zip --output series/build/distribution/KaleidoscopeSeries-0.1.0-dev-gameplay.zip
```

生成工具拒绝覆盖已有输出；重新生成时选择新路径，或确认后整理自己生成的旧文件。`--assets <新目录>` 可输出展开后的 CE 包。`build/packs/`、`packs-v2/`、`packs-v3/` 是开发期间的旧输出，不能与最终归档混装。

四个 JAR 位于各模块 `build/libs/`，名称为 `KaleidoscopeDoll-0.1.0-dev.jar`、`KaleidoscopeTavern-0.1.0-dev.jar`、`KaleidoscopeDeco-0.1.0-dev.jar`、`KaleidoscopeMusic-0.1.0-dev.jar`；独立 CE 包位于 `build/distribution/ce-packs-0.1.0-dev/`，文件名分别为 `doll-ce-pack-0.1.0-dev.zip`、`tavern-ce-pack-0.1.0-dev.zip`、`deco-ce-pack-0.1.0-dev.zip`、`music-ce-pack-0.1.0-dev.zip`，另有按需安装的 `shared-ce-pack-0.1.0-dev.zip`。厨房使用根工程生成的 `cookery-ce-pack-1.3.0.zip`。本地整包位于 `build/distribution/KaleidoscopeSeries-0.1.0-dev-gameplay.zip`，会把 5 个独立插件的 JAR 和 CE 包原样收入 `plugins/` 与 `ce-packs/`。旧 `0.1.0-dev.zip` 保留，不能代表本轮成果。

## 测试服安装

1. 在测试服准备 Java 21、Paper 1.21.11 和对应 CraftEngine，停服后把需要的独立 JAR 放到 `plugins/`。`common` 不单独安装；新增或更换插件 JAR 使用正常停启，不使用热加载工具。
2. 将对应的独立 CE ZIP 展开到 `plugins/CraftEngine/resources/<module>/`，保证模块目录下直接有 `pack.yml`。`shared-ce-pack` 只在该模块需要原版图集时安装；不同时加载两套同名定义。
3. 上述带 `--cookery-pack` 的开发整包包含完整厨房 CE 包，竹筛已合入[完整厨房生成器](../examples/kitchen-pack/README.md)。五插件的原生整合加载、部分协议流程与资源离线解码已有分层证据，完整玩家玩法与画面仍待验收；同名厨房／食物定义及竹筛独立示例不能重复安装。
4. 启动并检查未知物品、行为、配方、模型与 NBT 加载错误。按照目标 CE 版本执行资源生成和发送；当前官方命令为 `/ce reload all`，仅 `/ce reload` 默认只刷新配置，不代表客户端资源包已更新。
5. 用未安装模组的原版客户端接收服务器资源包，再执行验收表。MIDI 文件放在 `plugins/KaleidoscopeMusicPlugin/songs/`。

这里提供的是 **CraftEngine 内容包**，不是直接发给客户端的最终 vanilla resource-pack ZIP；最终客户端包须由 CE 生成并通过服务器的资源包托管流程发送。

## 操作

- `/kdoll` 查看收藏；OP 用 `/kdoll kit` 获取机器、电脑、微调工具。机器投入铁锭/金锭/钻石，电脑每只玩家玩偶消耗一块原版羊毛。手持微调工具右键玩偶打开宽/高/深、三轴偏移、三轴旋转菜单。
- 手持玩偶潜行右键蓄力，松开投掷；手持玩偶右键实体让玩偶骑乘目标。直接攻击移动玩偶回收，背包满时保留实体。原版盔甲架承载模型和物理，和模组自定义实体仍有差异。
- `/ktavern` 查看酒桶配方；OP 用 `/ktavern kit` 获取酒桶、果盆、雪克杯及空容器。酒桶加入 4 桶同种液体，再投最多四槽原料，空手潜行开关盖；发酵后用配方指定空瓶取酒。
- 果盆投料后站入跳踩，每次按配方增加果汁；达到 1000mB 后用桶取出。空手潜行可取回未使用原料。
- 雪克杯每次投入一份原料，酒类要求熟成至少 4 级。容器在投料时返还，倒入的液体不再以整瓶返还。满三份后空手右键开始，第二次右键停止；19–68 tick 迷之酒、69–88 tick 特调、89–98 tick 匹配配方、99 tick 起迷之酒，111 tick 自动结束。用空鸡尾酒杯领取结果。中途重启/卸载会停止摇动，保留原料。
- 用一个空雪克杯潜行右键已装料的雪克杯转装；手持右键持续摇动，松开结束；对放置的空鸡尾酒杯倒酒。手持同类空杯领取成品，放置杯留在原处。雪克杯原料及成品随物品保存。
- `/ktavern kit` 另含藤架、葡萄藤和龙头。葡萄种植、封蜡、剪刀采收、红石龙头及各来源的摆放规则见 [GAMEPLAY.md](docs/GAMEPLAY.md)。
- 饮品使用原版饮用动作。特调按原料熟成等级和药水收集效果，同类时长相加后乘 1.2，等级与概率取最大值。
- `/kdeco` 浏览分类及领取状态工具；领取需要 `kaleidoscopedeco.give`，默认 OP。沙发用染料换色、水桶清色、状态工具切换四种形态；其余交互按已导入规则执行，尚未移植的脚本会明确提示。
- 手持乐器对空气右键或用 `/kmusic` 打开演奏菜单。用 `/kmusic play example.mid` 播放本地文件，`/kmusic stop` 停止。OP 用 `/kmusic kit` 获取乐器。

## 存档与恢复

有原料、酒液、待领取奖品的家具会拒绝普通玩家拆除。异常 NBT 会阻止操作，并原样保存；不会把读不出的库存覆盖为空。

如果其他插件或管理员强制移除家具，执行到 CE `preRemove` 时会将控制器数据写到该插件目录的 `recovery/*.nbt` 并刷盘。这是管理员核对用的内容快照，不自动发放物品；未执行该回调的外部直接删除、服务器崩溃和文件系统故障不在此保证内。

燃烧瓶储物家具写盘失败时，在日志保留已编码的完整 NBT；NBT 无法编码时逐槽优先保留物品原始字节，再尝试带 DataVersion 的 Paper 物品 JSON。组件级的最终降级明确标为 `BEST_EFFORT_NOT_GUARANTEED_COMPLETE`，字段读取失败另标 `recovery_incomplete`，不可当成完整物品。一个坏槽不会截断后续槽位；损坏 NBT 连 SNBT 也不能渲染时，以 `BEST_EFFORT_INCOMPLETE_SNBT` 保留独立的家具 UUID、世界、家具 ID 和坐标，不重复渲染坏 Tag。CE 已进入不可逆销毁后，不靠抛异常假装保留家具，基础家具掉落仍独立结算；所有应急记录均须先核对实际世界和库存，再由管理员决定是否人工恢复，绝不自动发放或重复回放。

自定义饮品效果在玩家 PDC 保存，退出暂停剩余时间，死亡和牛奶清除。Folia 范围效果只读取当前线程拥有的已加载区块；跨区域边缘的目标可能跳过，尚未作实服压力验证。

原资源的 README/LICENSE 保留在各包的 `upstream-notices/` 中。本次 Git 交接保存源码、导入工具、目录和证据；生成资源、JAR、测试服及缓存保留为本机输出，不作为发布/部署成果。
