# 源作物支撑检查恢复

2026-11-21。目标为 Paper/Folia 1.21.11、CraftEngine 26.9.1，来源基线 `f95c66f`。

## 问题与行为

CE 重载期间会临时解除原生方块行为委托；此时被消费的支撑 tick 不会进入 Cookery。番茄、辣椒和生菜可能因此错过支撑失效检查。仅在 Cookery 回调中延后重试不能覆盖完全没有派发的回调。

`SourceCropBehavior.tick` 现在在资源读门内复核定义身份、所属区域、已加载区块及原生当前位置状态，再调用 CE 原有支撑 tick。形状更新和独立存活查询也持读门；读门不可用时存活查询保守返回可存活，tick 与形状更新记录区块地址供后续复查。

## 恢复流程

- `SourceCropSupportRecovery` 注册区块加载／卸载及世界卸载事件。每次作物资源代际成功发布后，收集当前已加载区块的地址；全局任务不读取方块或调色板。
- 全局每 tick 最多分派四个区域任务，失效队列条目也计入该预算。区域任务先检查世界身份、所有权和区块已加载，再申请真实 CE 读门；资源忙碌或 CE 区块尚未就绪时重新入队。
- 在已加载 CE 区段中，用调色板筛掉没有三种源作物的区段；每个区域任务最多扫描 256 个位置。调色板仅提供候选位置，补排前重新读取原生当前状态和对应作物行为，避免旧索引对已替换方块执行检查。
- 对候选补排延迟一个 tick 的原生支撑检查，保留原生去重、破坏事件及掉落流程。恢复任务本身不直接移除方块或创建掉落。
- 区块卸载、世界替换及插件停用使任务失效。每次新资源发布从首个区段重新扫描；身份不匹配的旧 continuation 不能移除新任务。扫描不加载区块、不持有跨调度回调的资源 lease。
- 调度或扫描异常记录区块身份和错误；不会将异常任务当成实服验收成功。

## 验证

本地根 Python 329 项、系列 Python 78 项全部通过，`git diff --check` 通过。Java 回归覆盖资源关闭／忙碌时不读 CE 区块、读门排斥写入、256 位置与四任务预算、负区块和负高度坐标、区域所有权、已卸载区块、世界替换、CE 区块延迟就绪、旧任务失效、代际重新扫描、空区段、异常释放、停用、当前原生方块已替换，以及成功发布后才开始恢复。

本地 Gradle distribution 下载因网络不可达中止；Java 构建与回归由仓库 GitHub Actions 执行，结果关联本提交。Paper/Folia 实际重载、支撑丢失、破坏取消和唯一掉落仍需实服验证。骨粉完整原生事务、回调前的 CE 派发、原生生成和绕过协调器的写入仍保留为后续工作。

## 固定 API 来源

- [CE 支撑行为](https://github.com/Xiao-MoMi/craft-engine/blob/2b9e6f59a0bf4dac7f71958c0ea96c14b8e44985/bukkit/src/main/java/net/momirealms/craftengine/bukkit/block/behavior/AbstractCanSurviveBlockBehavior.java)
- [CE 区块与区段](https://github.com/Xiao-MoMi/craft-engine/tree/2b9e6f59a0bf4dac7f71958c0ea96c14b8e44985/core/src/main/java/net/momirealms/craftengine/core/world/chunk)
- [CE 当前原生方块查询](https://github.com/Xiao-MoMi/craft-engine/blob/2b9e6f59a0bf4dac7f71958c0ea96c14b8e44985/bukkit/src/main/java/net/momirealms/craftengine/bukkit/api/CraftEngineBlocks.java)
