# 资源路径兼容检查点（2026-11-17）

本批修正六个酒馆吊灯模型的原版铁链纹理引用，以及两个 Deco 食物图标的已搬迁模型路径。生产 Java 和玩法 JAR 沿用鱼架检查点，内容包重新导出。

## 铁链版本边界

从 Mojang 官方版本清单下载 1.21.1、1.21.8、1.21.9、26.3 客户端归档，全部校验官方 SHA-1。1.21.1/1.21.8 使用 `minecraft:block/chain`；1.21.9/26.3 使用 `minecraft:block/iron_chain`。四版方块纹理字节相同。物品纹理也在 1.21.9 改名，但 1.21.1 像素与后续版本不同；本批六个吊灯只使用方块纹理。

`tools/export_versioned_ce_pack.py` 按目标版本转换模型 `textures` 中的原版 iron_chain/chain 路径，保留 `#alias`、自定义命名空间、几何与原始文件。如果内容包显式提供同名原版纹理，则保留覆盖。模型哈希同时更新到来源 manifest 和版本 manifest。官方客户端只作为审计输入，没有打进分发包。

三组配置族仍为 1.21.1、1.21.2–1.21.8、1.21.9–26.3；只在第三组改变六个 Tavern 模型。`tools/check_chain_textures.py` 对照原始模型、最终生成文件和各版本官方纹理，验证几何没有变化。审计见 [官方纹理证据](research/vanilla-chain-texture-audit-2026-11-17.json)。

## Deco 搬迁别名

固定 Deco 来源 `6ce180e1226ce93b0fb0d45ea80e0ed5b01b1dc0` 中，`slime_ball_meal` 和 `mysterious` 的顶层物品定义引用 `models/item/`，对应真实模型位于 `models/meals/item/`；同来源的 meals 物品也使用后者。本批复制对应模型作为精确别名，保留既有旧路径覆盖。神秘料理 PNG 与 `.png.mcmeta` 原样保留。

`series/tools/deco_asset_aliases.py` 同时整理前序两个盘子纹理别名。不会猜测 frogspawn_jelly 的缺失 GUI 模型或替换未知美术。`check_deco_asset_aliases.py` 对照最终生成模型、PNG 和动画元数据。Deco 物品/家具/变体数量不变，资产文件增加两个。

## 验证与残余问题

Python 厨房/版本工具 140 项、系列 75 项通过；对应 JAR 的 Java 742 次执行记录保持有效，生产代码未改动。最终 g/20（1.21.1）、h/13（26.3）七组探针均无失败，资源生成与正常停服通过。运行结果见 [资源路径运行证据](research/resource-path-runtime-2026-11-17.json)，包含七组探针、逐文件安装检查和四类生成资源比较。

两端仍有来源资源缺失；旧版 30 个模型转换警告未在本批处理。玩偶 doll_271 含负 UV 和未定义 `#missing` 面，doll_722 有 `[37,44,51,55]` UV；详见 [来源 UV 审计](research/doll-uv-source-audit-2026-11-17.json)。没有用裁剪或缩放猜测外观，26.1+ 烘焙问题仍未关闭。CE 默认 light 纹理与 palm_tree 配置也仍有版本差异。

资源文件对照不是客户端画面验收。真人交互、完整资源渲染、Folia 和所有中间版本运行仍需继续完成。
