# CE 资源共享读取门与原生入口边界

2026-11-21。`CeResourceReadGate` 已接入源作物的随机生长、采收、种植回调以及范围镰刀的同步挥砍。完整原生入口和自然村庄仍待完成。

读取门初始关闭。发布时在真实 `ResourceOperationCoordinator.Lease` 内验证捕获的资源身份；首位读者重新申请真实 lease，后续同步／嵌套读者共享该 lease。新加入者必须先增加受保护的读者计数，再执行注册表身份检查，否则上一位最后读者可能在检查尚未结束时释放 lease。最后读者先 CAS 到关闭过渡态再释放；失效停止新准入，但已有读者持有 lease 直至退出。验证异常污染后续准入，已有读者仍正常排空。

单元检查使用真实 CE coordinator，覆盖跨线程最后释放、发布期间失效、外部独占操作、重复关闭、异常排空、200 次最后关闭／加入竞争，以及阻塞加入者验证器时旧读者退出的确定性竞态。它们证明该原语的互斥范围，不能证明尚未接入的整条原生交互。

## 骨粉入口核查

固定 CraftEngine 26.9.1 的 `BlockBehavior.useOnBlock` 在 Bukkit `PlayerInteractEvent` 分发内部执行。若此处直接调用原生 `ItemStack.useOn`，骨粉生长、捕获提交及消耗会早于后续监听器的取消；因此没有采用该捷径。

Paper/Folia 1.21.11 在完整交互事件返回后才调用原生物品 fallback，实际 `ItemStack.useOn` 包含真实生长／施肥事件和物品计数处理；创造计数恢复与进度触发还位于外层 game mode。只包住 `performBonemeal` 不足以涵盖随后扣粉和外层捕获提交。固定 CE 的原生 Block 子类没有拦截 `useItemOn`／`useWithoutItem`；额外定义同名行为方法不会获得调用。当前公开 API 没有同时覆盖两手、次要使用、原始命中上下文及完整事务的晚期 wrapper。

## 种植、镰刀与支撑检查

厨房包为三种种子导出 `source_crop_seed` 行为。它持 guard 调用 `super.useOnBlock`，涵盖 `BlockPlaceContext` 读取、放置和种子扣除，保留原有 CE 事件与操作规则。旧厨房包的通用 `block_item` 不受这个入口保护。镰刀从 Cookery `useOnBlock` 开始持 guard，到分类、事件、收割、耐久及冷却全部结算；嵌套的源作物采收共用同一 lease。两个入口均不涵盖 CE 分派回调前的行为查找或回调返回后的原生流程。

支撑检查的原生 `tick` 同步包含存活判断及破坏；但 CE 卸载时将方块委托换成空行为，已排队的原生 tick 可能在重载期间被消费而完全没有进入 Cookery。因此仅包住 `tick` 或在其拒绝时重排都不能保证后续检查。仍需处理重载期间的派发或重放，并对齐 Folia 所属区块和原生取消语义。

## 可核对的输入与限制

| 输入 | SHA-256 |
| --- | --- |
| CraftEngine 26.9.1 完整插件 | `021260c87e3546730d321f4360b1744e6e33caa1d68e98ae30dcdbbdc347ae0f` |
| Paper 1.21.11 build 132 | `3a73f0cc4020a5eb45629a67629b14263331c68de45552f8505df8060fa8ed43` |
| Folia 1.21.11 build 14 | `6bfd94ffd8649cf416d247e849214667ac91759699a4c3536eef2307277c4e33` |

本地原始导出及审计保留在 `build/followups/source-crop-resource-gate/`，包括 `BONE-MEAL-ENTRY-AUDIT.md`、逐指令 JSON 与 class 文件读取器。此核查没有运行服务器。当前生产接线在首轮／重载完成时以同一 CE lease 发布已安装源作物的 `BlockDefinition` 和全部龄状态对象身份，同时记录缺失的作物键；随机生长与采收在 guard 内读写状态并提交成长或掉落。共享门只排斥使用同一 CE coordinator 的写入者，不提供生成 worker、原生数据包重载、CE disable 或绕过 coordinator 写入的屏障，也不保证写入公平性。骨粉完整原生物品事务、原生破坏、支撑丢失和生成的入口覆盖与统一代际仍 OPEN。
