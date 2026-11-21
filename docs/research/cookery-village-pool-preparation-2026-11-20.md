# 五类村庄有效池与 processor 准备

2026-11-20。本批准备逻辑保持未发布：没有调用它的启动入口，没有修改现有注册表、模板 cache、池或世界。自然厨房生成仍为 `NOT_IMPLEMENTED`。

`VillagePoolPreparation` 读取五个实际解析后的 houses 池，保存原元素对象、顺序、权重及 fallback。先检查全部五池，再为缺失厨房追加一个 weight 4、稳定命名、legacy rigid 元素；已有精确元素幂等，重复、嵌套、旧命名空间别名、跨 biome 或语义不一致的引用拒绝准备。原生随机选择使用展开的 `templates`，因此同时检查它与 raw pairs 的对象身份、顺序和重复次数一致，不能只保留编码列表。

原始条目在复制前限制为 65,536，展开总权重上限 1,048,576；仅缺少厨房的池预留一个条目与四份权重，达到上限的完整池仍可幂等。递归检查有深度、循环和累计节点界限。全部候选构造后复核原池／processor 身份、内容与 CE 双向映射；这些检查用于发现输入漂移，不构成并发生成屏障。

原生命名 processor 必须已绑定。候选规则使用当前成熟番茄、辣椒和生菜的真实 CE server state，建立一个原生 `RuleProcessor`，按来源顺序保留两个 0.33 状态匹配规则，沿原生 first-match RNG。没有独立随机抽签，也没有把两个结果改成各三分之一。模板及 processor 的注册名可持久化；本批没有验证原生 piece codec 往返。

## 数据包中的稳定名称

村庄导出包新增 `data/kaleidoscopecookery/worldgen/processor_list/crop_replace.json`，内容只有 `{"processors":[]}`。它为运行准备提供稳定名称，**不是已安装的来源替换规则**。来源 `source/crop_replace.json` 仍按固定原始字节保存。清单记录 `DORMANT_EMPTY_PLACEHOLDER`、`NOT_PUBLISHED` 和预安装要求。

只有在服务器首次读取原生 worldgen 注册表之前已安装并启用该数据包，稳定名称才有机会被注册；普通插件 `onEnable` 不会自动安装它。当前包也不输出自然生成池或可直接生成的厨房模板。生成已冻结的注册表后再解压、或只执行 CE reload，不满足此前置。

当前静态导出包为 `build/distribution/village-empty-processor-1.zip`，58,924 字节，SHA-256 `660e567a894617b318562205b8445058c07ebd055a322014e6da1bc9e7042174`。ZIP CRC、精确 `--check` 与村庄 Python 20 项检查通过；生成清单严格摘要更新为 `6dba1293d763b5bf62885e7a5d52397ab7e31fccb0a1156fbee69f62f1c669d8`。这些证据没有包含真实原生数据包解码或激活。

## 仍须实现和证明

下一发布事务必须保留原 registered pool／processor 对象及 holder 身份，一致更新模板 cache、processor 内容和池的 raw／expanded／maxSize 状态，并在每个失败切点完整回滚；只重新绑定 holder 会遗漏 registry 反向关系。调用方还须在真实无生成读者的生命周期建立屏障。随后需要五 biome 的自然选择、跨区块／region、CESection／CEChunk／controller 一致、结构片持久化、重排 CE 分配后的继续生成、两箱战利品及原生／CE reload 验收。当前静态 ABI 与构造检查不能替代这些要求。
