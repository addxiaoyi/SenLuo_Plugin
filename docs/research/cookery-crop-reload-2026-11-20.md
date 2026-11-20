# 作物重载观察与掉落边界复核（2026-11-20）

目标：Minecraft 1.21.11、Paper 132／Folia 14、CraftEngine 26.9.1。

## 实服失败能证明什么

`sc3/paper-core-2` 实际发送了一次 `ce reload config`，随后立即发送只读 `reloadstate`。该快照读取到暂未注册的 `tomato_seed`，客户端因此退出。控制台在该快照之后才记录 items、recipes、blocks 的加载；授权重载收据保留 `beforeEpoch=1`、`afterEpoch=null`。因此这是**重载期间轮询失败**，不是“完成事件提前发送”的证据。此前交接中的后一推断已更正。

该失败运行保留了两名协议玩家真实下载托管包及官方 1.21.11 reader 各读取 14,419 项资源的结果，但没有完成两轮重载／工作流，不列作作物 runtime 通过。

## 固定运行依赖的生命周期

复核对象是实际运行的 CE JAR，SHA-256：

`021260c87e3546730d321f4360b1744e6e33caa1d68e98ae30dcdbbdc347ae0f`

`ReloadCommand` 的 CONFIG 分支调用 `reloadPlugin(..., true, true)`。异步阶段先 reload managers，再加载资源，随后 `runDelayTasks` 等待包括 item/block manager delayed-load 在内的 futures；平台阶段完成同步延迟任务后才调用 `callReloadEvent`。Bukkit 实现直接派发 `CraftEngineReloadEvent`。事件返回后，完成回调才清除 volatile `isReloading` 并关闭资源操作 lease。

生产重绑因此使用全局区域调度器等待 `isReloading=false`，并持有 CE `ResourceOperationCoordinator.Lease` 读取注册表和重绑形状，排除检查与绑定之间启动另一轮重载。busy lease、暂缺方块与绑定异常会在 30 秒限期内重试；新重载令牌及插件关闭使旧任务退出。无关厨具缺失仍报告诊断，不阻止三种作物恢复。

只读探针在重载期间报告 `ceReloading=true`、`canonicalReady=false`，不尝试构建暂缺的物品。正常规范物品仍使用 `InventoryUtils.createOrEmpty` 的生产 food-baking 路径并归一为一件；直接调用原始 CE item builder 会遗漏该步骤，不能作为组件守恒的规范样本。

connected runner 同时要求：恰好增加一次 epoch、`ceReloading=false`、8 个规范物品、3 种作物方块与全部 24 个原生龄形状。两轮各自还须有真实工作流、双人实际下载和官方 reader 结果。门槛不等于实服已通过。

## 成熟种子的真实边界

同一固定 CE JAR 将 `binomial_with_bonus_count` 注册到 `CropDrops`。其真实 `apply(initialCount, enchantmentLevel)` 执行 `enchantmentLevel + extra` 次独立试验，每次成功增加 1；不是 `extra * enchantmentLevel` 次。

固定上游作物 loot 使用初始数量 1、`extra=3`、`probability=0.5714286`。因此成熟作物的种子数量界限是：

| 时运等级 | 最少 | 最多 |
| --- | ---: | ---: |
| 0／空工具镰刀 | 1 | 4 |
| I | 1 | 5 |
| II | 1 | 6 |
| III | 1 | 7 |

未成熟作物只有 1 颗种子。验收器已恢复以上边界；时运 III 的 8–10 颗不是合法范围。Java 回归直接调用实际 CE 公式，以成功概率 0 和 1 覆盖各级时运的确定性两端；这不构成随机分布或实服掉落通过的证据。

## 保留的未完成项

后续 `sc6/paper-core-1` 已观察到两轮真实重载／工作流、六次官方资源读取及种植／采果组通过，但镰刀掉落的食物 PDC 与生产构造存在编码差异，整轮仍失败。根因为共享食物导出器沿用来源字典的插入序，使 `saturation` 位于末尾，而 Java `FoodDefinition` record 将其写在 `nutrition` 后；部分饱和度和概率还存在整数／浮点文本差异。由于 PDC 中存放的是 JSON **字符串**，值相同并不意味着物品组件相同。

修复导出器按生产 record 字段序和数值文本生成全部 103 项 BASE 消耗品，并加入跨 Python／Java 的精确编码 golden。旧编码仍可解码；没有批量重写玩家已有物品，也没有将接受范围改为仅比较 JSON 语义。新版启动 smoke 还直接验证实际 CE 物品与生产 BASE 物品的 `isSimilar`，以便在玩家场景前发现堆叠差异。新包和新探针仍须独立实服复验。

新构建仍需独立新编号的 Paper／Folia 两轮 core 和 `seed → verify → empty`。C14 保持 `unit_tested`。客户端 GPU／轮廓、真实保护插件、默认速度成熟与分布、非玩家破坏来源以及五种村庄厨房自然生成仍分别开放。
