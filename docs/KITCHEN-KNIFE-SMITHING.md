# 下界合金菜刀锻造开发检查点

2026-11-17，固定 Cookery 来源 `2f4e386ce23f49a385ddf003c67fc6415c55417a`。来源配方使用下界合金升级锻造模板、钻石菜刀和下界合金锭，产出一把下界合金菜刀。源文件 SHA256 为 `1331b90a12144f5f6b2799a2c5575587bdc0e421894432019d2c7d6afe73bf5c`。导出器严格检查这条来源配方与全部角色，避免静默套用错误的升级关系。

接入 CraftEngine 26.9.1 的原生 smithing_transform 配方，开启 merge_components。CE 的组件合并先复制原菜刀并变换底材，再应用目标物品的组件；其中目标 custom_data 会覆盖玩家和第三方插件写入的整段数据。新增 KitchenKnifeSmithing 处理器递归恢复源用户数据，保留目标根级 craftengine:* 元数据和新的下界合金菜刀身份。来源用户值冲突时优先，目标缺省值保留；输入物品和嵌套数组不被修改，非法数据或共享输出会拒绝处理。

八项 Java 回归覆盖真实 CE 配方的角色匹配和 assemble 调用、深层数据合并、身份保护、输入不变及错误配置。测试中的 Item.mergeCopy 使用模拟实现，因此不证明真实原生组件合并已经在服务器通过。名称、附魔、损耗等采用 CE 的原生组件合并路径，已审计但尚未实服验证。菜刀的来源禁用横扫动作属于另一个尚未对齐的差异。

验收插件增加可选 smithing-smoke.enabled 探针：检查 CE/原生配方注册、重复预览、名称/描述/附魔/损耗/修复代价/PDC 保存、原料不变、序列化往返和重启读回，并拒绝三个错误角色。探针仅编译，未运行。原生槽位扣料、Shift 取物、取消及真人交互也未测试。食物探针同时改为通过 CE 读取 1.21.1 food 或新版 consumable，去除直接 Paper 现代组件类引用；这项兼容修改同样仅通过编译。

厨房 495 项 Java 全量通过，系列 71 项和旧 API 4 项保留未改代码的通过记录。Python 136＋59 全量通过。五插件、十组厨房版本 JAR 和 18 个 CE 配置包完成封装；所有厨房 JAR 包含相同的新处理器。厨房内容仍为 1,422 物品、81 方块、23 家具、4,255 显式状态、5,599 预期内部状态、1,480 可达模型；608/612 来源配方已导入，剩余稻草人、垃圾桶和两种带内容蒸笼配方。

分发文件为 `SenLuo-1.21.1-26.3-dev-20261117-smithing-v1.zip`，哈希与逐文件检查见 `research/cookery-kitchen-knife-smithing-build-2026-11-17.json`。日志为 `../smithing-tests.log`、`../smithing-build-all.log`、`../smithing-python-all.log`、`../smithing-content.log`、`../smithing-versioned-content.log`、`../smithing-bundle.log`。e/f 仍保留旧便当袋 JAR/内容，EULA=false，未启动。工作区未提交或推送。
