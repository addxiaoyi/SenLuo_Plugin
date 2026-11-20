# 垃圾桶来源动画（2026-11-17）

接续 [垃圾桶玩法检查点](TRASH-CAN.md)。本批替换旧手写取整轨道，使用固定 Cookery 来源 `2f4e386ce23f49a385ddf003c67fc6415c55417a` 的五条 `TrashCanAnimation` 轨道：PUT、WITHDRAW、PLAYER1、PLAYER2、ENTER。

`tools/export_trash_can_animations.py` 从固定提交读取 Java 声明，输出 `src/main/resources/trash-can-animations.json`。来源路径、提交和 SHA-256 随资源保存；`--check` 及 Python 回归测试逐字节对照。保留所有小数时间戳、部位、位置/旋转向量，以及 LINEAR/CATMULLROM 标记，不先换算为整数 tick。

Java 求值器按目标关键帧的插值方式计算，Catmull-Rom 使用原版四点多项式。PUT/WITHDRAW/ENTER 可同时叠加，同一轨道再次触发会重新开始；PLAYER1/PLAYER2 互斥。占用时按来源 `(worldTime + BlockPos.hashCode) % 61` 随机刷新待机，未占用每 5 tick 停止待机；退出、卸载和重新加载会清理状态。

来源 `eye` 和子部位 `bone3` 拆为 `show:trashcan_3`、`show:trashcan_4`。眨眼与左右眼动仅作用于子部位，眼框继续保持 4 像素抬高；此前整个眼框跟着下沉的问题已修正。七块模型几何保持原尺寸与 UV，显示部件由三组变为四组。来源渲染器的 `(x,-y,-z)` 坐标转换使 Y/Z 旋转反号，X 不反号；桶盖围绕 14.75 像素高度转动，平移按 `pivot - rotation*pivot + position` 计算，随后叠加家具朝向。

更新使用现有家具 ticker，不再为每条动作创建多个延后任务。每次姿势变化发给当前追踪者；后来进入视野的玩家收到静态模型后立即收到当前姿势，避免只看到闭盖。每 tick 求值、客户端在相邻数据之间插值仍是 20 Hz 近似，不能等同于原模组客户端按每渲染帧求值；尚无真人客户端画面对照。

测试覆盖小数关键帧、来源曲线过冲、动作叠加、独立眨眼与左右移动、旋转支点与符号、世界时间/当前追踪者、卸载后停止。实服探针额外编码五轨道的 116 个 tick 姿势，验证两端元数据 API 与四个显示模型可用；空收件人编码不证明玩家实际收到或渲染正确。

本批没有关闭垃圾桶的碰撞、水浸、精确躲藏、库存 HUD、Folia 和真人验收缺口。版本构建与运行以本批 `docs/research/trashcan-animation-runtime-2026-11-17.json` 和 `trashcan-animation-build-2026-11-17.json` 为准。
