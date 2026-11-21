# 来源镰刀的原生分类、提交边界与工具损耗

本记录对应 2026-11-20 新增代码，Java 编译与测试结果由集成验证另记，Paper／Folia 实服尚未验证。旧 sc8 使用旧 `iron_hoe` 材料和旧导出包，不能作为本次新 `iron_sword` 材料、组件、分类或 observer 的验收证据。首版按整个 Ageable 白名单统一套用 EMPTY 的本地草稿已废弃，保留在 `build/followups/vanilla-sickle/` 供审阅历史追溯。

## 固定来源和真实类别

固定 `SickleItem.java` 对候选先查 `sickle_harvest_blacklist`，再按真实 `CropBlock` 和 `BushBlock` 分流。成熟 CropBlock 使用 `playerDestroy(..., ItemStack.EMPTY)`，随后 `getStateForAge(0)`；其他 BushBlock 交由 `ServerPlayer.gameMode.destroyBlock`，不手工复位。可可既不是 CropBlock 也不是 BushBlock，来源不处理它。

已用 Mojang 官方 1.20.1 client JAR 和 official mappings 核对来源基类：client SHA-256 `56b71336d2b4fdffd197f56595b0da93e32a946f78f382a299b8f4b92758bb0f`，mappings SHA-256 `67bfc33ef0c7103f002d6ac134f8eddd3d67e2ce45d266e83816d1a2e2c9b01d`，下载按该版本官方元数据 SHA-1 和大小校验。固定 Forge 47.3.0 sources JAR 的一次请求返回 HTTP403，没有把未取得的 Forge 补丁当作已检查。对应 Paper 132／Folia 14 的 1.21.11 保留运行 JAR 也进行了 class-file 元数据和方法指令检查，全程使用 Python 读取器，没有运行 Java。原始记录保留在 `build/followups/vanilla-sickle/classification-v2/`。

| 方块 | 来源 1.20.1 | 目标 1.21.11 | 新来源模式行为 |
| --- | --- | --- | --- |
| 小麦、胡萝卜、马铃薯、甜菜 | CropBlock 或其子类 | CropBlock 或其子类 | 原生 `isMaxAge`，EMPTY 战利品和原生 age0 状态 |
| 火把花作物 | TorchflowerCropBlock → CropBlock | 同左 | 原生最大龄为 2，但自身 AGE_1 仅 0、1；龄 1 不算成熟，不落入 Vegetation 分支 |
| 已长成的火把花 | FlowerBlock → BushBlock | FlowerBlock → VegetationBlock | 原生普通破坏 |
| 地狱疣、甜浆果 | BushBlock 子类 | VegetationBlock 子类 | 任意龄原生普通破坏，不重置龄 |
| 瓶子草作物 | DoublePlantBlock → BushBlock | DoublePlantBlock → VegetationBlock | 原生普通破坏及上下半株处理 |
| 可可 | HorizontalDirectionalBlock | 同左 | 不处理 |

目标有一个具体的 `BushBlock`，它不是来源的通用植物基类；来源 BushBlock 对应目标 VegetationBlock。不能仅凭 Bukkit Ageable 或目标类名中出现 Bush 判断。四种固定黑名单为西瓜茎、南瓜茎和两种附着茎，新模式始终排除，并与用户 `blacklist`、目标原生同名 tag 和 Cookery `block_tags` 同名 tag 的附加限制取并集。

## 配置与版本兼容

`vanilla-policy` 支持 `legacy-ageable` 与 `source-classified`，缺省仍为旧的 `legacy-ageable`。既有手写 `crops`／`bushes` 列表保留原来的选择；新来源导出器仅为来源镰刀显式启用 `source-classified`。它不再落入旧 Ageable 分支的 MAIN_HAND 战利品路径。

新模式可单独设置 `vanilla-allowlist`。同时填写旧 `crops`／`bushes` 会报清晰配置错误，要求选择旧模式或迁移到新 allowlist；旧模式携带新 allowlist 也报错。未知策略、缺失 companion 或不支持版本均拒绝，不静默回退。

新 companion 只实现目标 Paper／Folia 1.21.11，独立于现有九组 `NmsBridge`；没有给旧桥增加抽象方法。插件 enable 时只有该版本注册通用 observer，不在旧八版强制加载 R7。选择旧模式不加载 native companion。

导出物的材料和组件同时由来源证据对齐：新版为 iron_sword、2000 耐久、附魔值 5、燧石修复、攻击伤害加成 1 和来源 float 扩宽后的速度 `-2.4000000953674316`，挖掘损耗 2、攻击损耗 1、允许创造模式破坏。剑的效率成员按固定 1.20.1 tag 展开 87 项，只映射 `grass → short_grass`，不引用成员已改变的目标同名 tag。该组件证据和导出测试由对应静态来源快照记录；这不证明新版材质的实服画面或完整玩法。

## Crop 提交顺序与部分副作用

来源先调用原生 playerDestroy，统计与饥饿回调在战利品计算前，loot 的位置条件还能看到原成熟方块。不能先改龄再算 loot，也不能算完后发现手变了，就把已经发出的统计／饥饿称为“无变化”。

R7 companion 检查已核实的公开 Paper／Folia 八参 `playerDestroy`，使用 `false,false` 只保留其原生统计／饥饿，然后以原始 native state、原世界和 EMPTY 计算完整 native ItemStack 副本。类型若覆盖该统计方法则拒绝，避免套用未知重写。每块重入锁和权限、原始交互手完整组件／数量／槽位、原始 native state 身份在最后无副作用门槛前均核对。

**统计调用开始即 COMMIT。** 统计／饥饿或 loot 回调使原块／来源失效时，返回有类型的 `PARTIAL`，保留实际已经发生的统计／饥饿，不覆盖替换块，也不发布旧源产物。稳定路径提交原生 age0，再发布完整冻结产物并运行原生 after-break；loot 看到的仍是统计后的原成熟世界，但物品生成回调看到 reset，和来源的发布时点有明确差异。任意外部插件回调或崩溃下的原子性未获保证。

`PARTIAL` 有明确日志，并结束剩余扫描；已经提交的动作按原目标每挥规则处理耐久。原生 RuntimeException 或 LinkageError 保留为失败原因，处理完每挥成本后重新抛出，不伪装成绿色成功。precommit 无效参数仍直接抛出，不被吞成成功。

Crop 路径保留现有 `InteractGuard`、`SickleHarvestEvent` 以及 logging-only `BlockBreakEvent` 契约，后者取消仍不充当第二套保护系统。Vegetation 路径直接调用真正 `gameMode.destroyBlock`，由它产生一次原生 BlockBreakEvent、取消／drop 判断、创造模式和当前主手工具语义；不重复发事件，不强制 EMPTY，不手工清理瓶子草半株。

## 原生工具损耗回执

普通 Vegetation 破坏可能合法损耗主手工具，尤其主手本身就是镰刀。只用整挥初始完整组件快照会把自己的原生耐久变化误认为外部替换，使后续正常植物提前停止。

真实 R7 普通物品指令链为 `ItemStack.mineBlock → Item.mineBlock → TOOL.damagePerBlock → hurtAndBreak`，非零硬度才使用 TOOL 损耗。剪刀另有 `ShearsItem.mineBlock` 覆盖：非 FIRE 方块即使零硬度也可损耗，且不进入 Item 的同名实现。当前按实际物品的反射声明类与完整方法签名捕获这两个已核实实现；未知 override 在原生破坏前拒绝。原生 Unbreaking 处理后构造 `PlayerItemDamageEvent`，完整事件派发返回后读取最终 `cancelled/getDamage` 再 applyDamage；最终 damage 可被合法插件调高、取消或调为负数，不能把 TOOL 输入基数冒充最终上界。玩家的该分支跳过非玩家 EntityDamageItemEvent。

enable 时注册的只读 observer 在原生完整 `destroyBlock` 调用的 ThreadLocal 会话中保留真实 PlayerItemDamageEvent 和单工具断裂 PlayerItemBreakEvent 引用，调用返回才读取最终值。归属同时要求同一 actor、事件 `asCraftMirror` 内的原始 NMS handle，以及捕获实现的真实声明类／完整签名调用栈。asCraftMirror 和 Bukkit 事件本身保存同一底层对象，并非脱离原物品的副本；不能用两个 Bukkit wrapper 的 `==` 代替 handle 身份。

receipt 另行核对选中槽位、原始 native stack 身份、完整非 Damage 组件、数量、原损耗、最终事件值及断裂边界。旧完整单位副本只按已证实损耗更新 Damage；不把回调后的整手重新采纳为可信基线。零事件的 Unbreaking／不可损物品保持原状态；取消的最终损耗为 0，即使之后 listener 写负 damage。合法负数按原生 setDamageValue 的 clamp 复原预期。单件断裂须有它自己的真实 break event、只减少一件并保持原 broken stack 的组件边界。

主手镰刀正常磨损可继续多株；它断裂则结束原始 use，不对空旧物品再次补伤。副手镰刀不因正常主手工具磨损／断裂而重新定义自身来源。重复／重入损耗事件、未知物品替换、组件漂移、无有效 observer、observer 中途失效均拒绝认证，必要时报告已经发生原生副作用的 PARTIAL。Bukkit disable 自动卸注册会使会话失效，但本方案不构成 CE 或插件 disable 的全局屏障。

原生 `destroyBlock=false` 且完整观察未发现归属本操作的 damage／break 时，在槽位及组件认证前直接判定未提交。这样，取消破坏的外部监听器切换主手热栏不会把副手镰刀误记为已提交并扣额外耐久。若确实收到归属损耗／断裂、观察器失效、归属判断异常或原生调用抛错，仍保留 PARTIAL／异常，不把所有 false 都当成无副作用。

## 尚保留的目标策略与验收范围

固定上游忽略循环返回值，总是在循环后播放挥动、扣耐久、加 cooldown 并返回成功；目标当前只在至少一项成功／已提交时才按挥计费。无候选、权限拒绝或取消时仍保留目标的 PASS／零额外耐久策略，本批没有暗中扩大为取消也扣耐久，不能声称整把 Sickle 完全等价。

单元测试涵盖分类调度、明确配置、逐块重入、precommit 漂移、统计／loot／reset／发布顺序和 PARTIAL、真实可变事件引用、最终损耗、主副手正常多株及断裂。native class 继承和方法已静态检查；测试不替代真实资源包、普通玩家取消／损耗插件、完整 ItemStack/PDC 账本、物理拾取、Folia 区域和正常重启验收。先前 sc8 及其所有失败现场继续保留。

三来源作物的 CE 资源读事务仍是另一份设计：骨粉完整原生调用、普通挖掘／支撑销毁、并发 reload、数据包 reload 和 disable 旁路没有因此关闭。
