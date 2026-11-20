# CE 资源共享读取门与原生入口边界

2026-11-20。`CeResourceReadGate` 是尚未接入生产作物回调的独立原语，不代表重载、作物或自然村庄已经完成。

读取门初始关闭。发布时在真实 `ResourceOperationCoordinator.Lease` 内验证捕获的资源身份；首位读者重新申请真实 lease，后续同步／嵌套读者共享该 lease。新加入者必须先增加受保护的读者计数，再执行注册表身份检查，否则上一位最后读者可能在检查尚未结束时释放 lease。最后读者先 CAS 到关闭过渡态再释放；失效停止新准入，但已有读者持有 lease 直至退出。验证异常污染后续准入，已有读者仍正常排空。

单元检查使用真实 CE coordinator，覆盖跨线程最后释放、发布期间失效、外部独占操作、重复关闭、异常排空、200 次最后关闭／加入竞争，以及阻塞加入者验证器时旧读者退出的确定性竞态。它们证明该原语的互斥范围，不能证明尚未接入的整条原生交互。

## 骨粉入口核查

固定 CraftEngine 26.9.1 的 `BlockBehavior.useOnBlock` 在 Bukkit `PlayerInteractEvent` 分发内部执行。若此处直接调用原生 `ItemStack.useOn`，骨粉生长、捕获提交及消耗会早于后续监听器的取消；因此没有采用该捷径。

Paper/Folia 1.21.11 在完整交互事件返回后才调用原生物品 fallback，实际 `ItemStack.useOn` 包含真实生长／施肥事件和物品计数处理；创造计数恢复与进度触发还位于外层 game mode。只包住 `performBonemeal` 不足以涵盖随后扣粉和外层捕获提交。固定 CE 的原生 Block 子类没有拦截 `useItemOn`／`useWithoutItem`；额外定义同名行为方法不会获得调用。当前公开 API 没有同时覆盖两手、次要使用、原始命中上下文及完整事务的晚期 wrapper。

种植入口也须从 `BlockItemBehavior.useOnBlock` 开始包围 `super`；只包 `place` 会遗漏更早的 `BlockPlaceContext` 构造和 CE 读取。没有为了增加覆盖数字而接入局部 lease。

## 可核对的输入与限制

| 输入 | SHA-256 |
| --- | --- |
| CraftEngine 26.9.1 完整插件 | `021260c87e3546730d321f4360b1744e6e33caa1d68e98ae30dcdbbdc347ae0f` |
| Paper 1.21.11 build 132 | `3a73f0cc4020a5eb45629a67629b14263331c68de45552f8505df8060fa8ed43` |
| Folia 1.21.11 build 14 | `6bfd94ffd8649cf416d247e849214667ac91759699a4c3536eef2307277c4e33` |

本地原始导出及审计保留在 `build/followups/source-crop-resource-gate/`，包括 `BONE-MEAL-ENTRY-AUDIT.md`、逐指令 JSON 与 class 文件读取器。此核查没有运行服务器。现有共享门只排斥使用同一 CE coordinator 的写入者，不提供生成 worker、原生数据包重载、CE disable 或绕过 coordinator 写入的屏障，也不保证写入公平性。全量骨粉、原生破坏、支撑丢失及生成的入口覆盖与一致 epoch 发布继续 OPEN。
