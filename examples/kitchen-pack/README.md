# 厨房 CE 隔离验收包

固定官方 Cookery 提交 `2f4e386ce23f49a385ddf003c67fc6415c55417a`；Paper/Folia 1.21.11、Java 21、CraftEngine 26.9.1。生成器沿用现有厨具控制器及存档，不改旧厨房规则。

包中有 1,206 个 CE 物品（含 672 个茶旗显示物品）、63 方块、4,130 个显式外观组合及 1 石磨家具，1,218 个模型通过引用检查。包含 103 可消费品、空杯、原料、工具、8 种去底板茶杯、64 切割阶段模型、6 茶壶字形、9 汤底映射、3 个配方汤面模型、11 种整份取物盘、38 种直接食用大菜和官方竹筛模型。3 个 Forge 视角模型转成原版 `display_context`；18 个音效别名保留官方音频。石磨 12 个 cube 来自固定 Java 模型；九格冷切火腿片另有源 Java 模型转换。官方资产与许可证来自固定 Git blob；字形与奶液纹理由生成器创建，是原版客户端替代。

`recipe-parity.json` 逐条记录 612 个官方配方的原始 JSON、来源路径、SHA-256、导入状态及差异。550 条来源已导入，配置包含 11 茶壶、4 蒸笼、90 石磨、16 砧板、28 竹筛、101 高汤锅、221 炒锅、63 普通合成、20 炉具和 5 条盖饭 native recipe，62 条 pending。八组固定原版标签映射记录在 `targetTagMappings`，来源中已有的标签完整保留并优先使用。剩余加工配方中 4 条高汤锅和 4 条炒锅缺 `forge:mushrooms`。完整状态清单随每次导出生成。

竹筛采用官方 base/stand 模型，每个非空槽按数量显示 1–5 层，按固定方块位置复现随机偏移和旋转；槽内物品、数量及组件未变化时复用显示数据包。完整厨房包和竹筛独立示例使用同一控制器，但独立示例仍使用自制竹材模型。客户端层叠画面和精确碰撞尚未验收。

11 种取物盘默认满盘；单食物盘每补 1 件增加 1 份，浆果盘每份同时产甜浆果和发光浆果且禁补。最后一份取走后保留空盘，下一次空盘交互拆除并返 1 碗。基础 loot 置空，破坏由控制器统一掉落剩余各份食物和容器。schema 2 保存份数、食物与容器快照并迁移旧档；取物先扣份再回调，转移期间拒绝同步重入。


盖饭配方另有 5 条 native recipe：额外格允许、整张网格只计一格菜和一格熟米饭、质量取全网格最高、熟米饭返碗，其余原版余料由 native result 槽处理。Paper/Folia 各三轮原生服务端检查通过；Shift 合成、自动合成器、玩家界面与外部可选米饭仍未验收，见 [盖饭摘要](../../docs/research/cookery-rice-bowls-acceptance-2026-11-16.json)。
38 种直接食用大菜（`FoodBiteRegistry` 注册表 37＋`ModBlocks` 冷切火腿片 1）已接入代码和导出包：34 单格、3 双格、1 九格，共 49 个放置单元、1,024 个状态。控制器保存品质、每口计数和多格归属，未吃整菜回收完整物品，吃过后的移除返回来源容器/额外掉落；每口消费不发放 bite item。碰撞使用细绊线替代，服务器粒子近似客户端动画，未归属区域放置拒绝。真人营养/效果、主副手、FOOD 效果桥实际调用、保护/多人及客户端均待验收。[大菜来源差异](../../docs/research/cookery-food-bite-gap-2026-11-15.json) 与 [90 条配方审计](../../docs/research/cookery-pending-recipes-2026-11-15.json) 是接入前快照，当前已有 38 大菜方块、62 条 pending。

高汤锅新增 `stockpot_recipes`，`match: exact` 按全部原料数量匹配，`distinct` 先按真实物品 ID 去重；均匹配汤底，再回退旧 `stock_flex_foods`。输出数量/时长来自官方；品质继续用原插件档位。新批次保存 cooking/finished 模型、RGB 及液面份数；旧 plan v1 和无计划旧存档继续原规则。菜单只读查看九格和备选原料，旧编辑入口仍服务原配方。

炒锅新增 `pot_recipes`（195 精准、24 种类），使用相同备选匹配和品质算法，固定官方产量。配置保存 `stir_fry_count` 与 `source_time`，只有翻炒次数参与完成判定，最后一次动画仍为 24 tick；不添加模组的独立时间门槛。进行中的批次不受后续配方改动影响，旧无快照批次仍沿用默认翻炒次数与旧余弦。

石磨复用 `accurate_foods`，`cook: MILLSTONE`、`result_count` 和 `rotations: 1`；旧配置默认圈数和旧存档不变。事件取消、空产物或掉落失败保留原料并持久化阻塞；允许玩家取回重投。补箱只在完成、取料后及既有空转间隔发生。模型保留原插件动画锚点和滚动倍率，父级变换变化会拒绝导入；官方竖杆抬升、磨石高度、实体碰撞和客户端画面仍有差异。

这是独立验收包，不能与 `cookery_foods` 或已有同名厨房配置叠加。原版客户端、完整获取链和玩家物品守恒尚未验收，不应直接作为生产整合包。

## 生成与验证

在仓库根目录执行，输出路径必须不存在；导出失败不会覆盖前一代 ZIP。

```powershell
python -X utf8 tools/export_cookery_kitchen_pack.py --source ../.references/KaleidoscopeCookery --output build/acceptance/cookery-meals-next.zip
python -m unittest discover -s tools -p 'test_*.py'
.\gradlew.bat test shadowJar acceptanceJar --console=plain
python -X utf8 tools/run_enchantment_acceptance.py --root build/acceptance/meals-next --kitchen-pack build/acceptance/cookery-meals-next.zip --accept-eula --timeout 360
```

前置测试服由 `tools/prepare_test_servers.py` 准备。用户已接受本地测试服 EULA。运行器只创建 `build/acceptance` 下的新副本，监听 `127.0.0.1:25583/25584`，保留原 `25581/25582` 实例。旧食品目录移到副本内 `fixture-backups`；安装前校验包路径和全部哈希。未知 loader/模型引用/状态、manifest 不符及启动/停服 ERROR 都会失败，不豁免缺失厨具定义。

配方探针在 `acceptance-world` 所属区域执行：219 个加工计划（含 87 石磨）、219 个炒锅计划和 66 原版配方的注册/输出检查，炉具另核对类型、经验与时长；API/存档注入蒸笼、茶壶、砧板、两锅高汤锅及两锅炒锅，暂停后重启续作，再取出部分成品重启。石磨在独立区块 `(2,0)` 放置，以默认 40 秒/圈的成年牛驱动真实 CE tick；验证未完成存档、重启续磨产出 10 骨粉、再次重启空槽无重复。包含高汤锅包时运行器自动跑三轮；任何失败立即退出，日志保留。它不是玩家点击、工作台余物或客户端画面测试。

竹筛探针在区块 `(4,0)` 检查 28 条配方注册及产物构造，再用代表配方运行真实 CE tick，覆盖露天、实心屋顶、玻璃、叠放、无源/有源滴水石锥、中途重启、反向加工，以及下方漏斗取消 2 次后接受 1 次输出。样例完成产出为 2 海绵、3 干海带、2 泥巴和 1 黏土，第三轮检查空槽不重复产出。28 条注册检查不等于全部配方逐条计时，重启快照只比较材质、数量、进度和完成状态，不证明完整物品组件或真人玩家流程。

取物盘探针在区块 `(6,0)` 通过公共控制器 API 和真实 CE 移除验收：每端首轮 11 盘共取出 48 份、52 件食物并保存空盘；二轮 10 种单件补份、拒绝浆果补份、两批各 11 碗、40 件剩余食物及 21 次回调重入检查；三轮 11 个位置无复活/重复。它不模拟真人玩家；旧档迁移仅有单元回归，未做实服旧档导入。

大菜探针在区块 `(8,0)` 核对 38 种/49 单元/1,024 状态，模拟无玩家消费回调提交。每端首轮提交 38 口并验证各 38 次阻挡放置、取消、坏档及过期回调拒绝；二轮提交剩余 93 口、精确核对额外掉落与 38 份整菜回收（含 4 次非中心格破坏）；第三轮 49 格无复活或重复。它不证明真人营养、饥饿事件或 FOOD 效果桥运行。

最终 v5 Paper/Folia 各三轮严格探针 PASS，旧厨房、取物盘和竹筛回归通过，均正常停服且 `25583/25584` 已释放；319 项 Java、102 项 Python 通过。每端 1,718 个源文件哈希一致，ItemIcons 运行生成的 261 图标配置单列；539 个厨房可达模型检查通过，全部 1,260 个生成模型无 Forge loader。大菜和盖饭范围见 [大菜摘要](../../docs/research/cookery-placed-meals-acceptance-2026-11-15.json) 与 [盖饭摘要](../../docs/research/cookery-rice-bowls-acceptance-2026-11-16.json)。v4 Folia 第三轮的 5.1581751 秒启动 watchdog 严格失败仍为 OPEN。

前序取物盘验收为 203 项 Java、86 项 Python 通过，v2 Paper/Folia 各三轮 PASS 并正常停服，当时 `25583/25584` 已释放。24 种厨具（含未操作盘）的保存重载通过；每端 1,709 个源文件哈希一致，额外生成字体单列，351 个厨房模型引用检查通过、全部 1,251 个生成模型无 Forge loader。详见 [取物盘验收摘要](../../docs/research/cookery-plate-acceptance-2026-11-15.json)。v1 关服失败已修为构造时建立物品快照、保存时不再访问物品注册表，原失败保留于 [验证记录](../../docs/VALIDATION.md)。

前序竹筛验收为 179 项 Java、81 项 Python 通过，v3 Paper/Folia 各三轮 PASS 并正常停服，当时 `25583/25584` 已释放。每端 1,709 个源包文件哈希与 manifest 一致，ItemIcons 运行生成的 1 个字体配置单列；351 个厨房可达模型引用检查通过，全部 1,251 个生成模型均无 Forge loader。范围、产物和可携带证据包见 [竹筛验收摘要](../../docs/research/cookery-bamboo-acceptance.json)。

前序营火配方类型键已修正；NMS 失败来自 JAR 缺少 Mojang 映射标记引起 Paper 二次 remap，已补标记。石磨探针首轮改为等待其他本轮报告、重启轮优先检查，40 秒牛驱动及原断言不变。历史失败均保留在 [验证记录](../../docs/VALIDATION.md)，历史 Folia 启动 CE watchdog 仍 OPEN。

## 石磨历史证据（2026-11-06）

`millstone-v2` Paper/Folia 各三轮 PASS 并正常停服；默认 40 秒/圈的牛驱动重启续磨产出 10 骨粉，空槽再次重启无重复。两端各 1,709 安装文件与 manifest 一致，生成各 1,251 模型无 Forge loader。132 Java、71 Python 通过；资源 ZIP SHA-256 `02ad7037bfcb603d3fa26910f80ec7271f26d405269275cdba82055f85099bf1`。详见 [石磨验收 JSON](../../docs/research/cookery-millstone-acceptance.json)。历史 Folia 启动 watchdog 仍 OPEN。

## 前序炒锅证据

`build/acceptance/cookery-pot-v1.zip`：2,264,500 字节，SHA-256 `f59fd9beeb7abd95ad34cd6c7d9411c1f7660977409b875c1d6c97835a690091`，重复导出逐字节一致。118 Java、64 Python 通过；Paper cycle 1–4、Folia 1/3/4/5 通过，最终 JAR 在 Paper 4/Folia 5 验证。219 炒锅计划及原加工回归、两锅中途重启续炒、部分盛出后再重启 2/0 通过。

Folia cycle 2 启动 watchdog 5.2812568 秒仍 OPEN，未进入探针；首轮 `pot-v1` 的解析依赖失败与所有历史失败保留。两端 1,705 安装文件与 manifest 一致，生成包各 1,247 模型无 Forge loader。见 [炒锅验收 JSON](../../docs/research/cookery-pot-acceptance.json) 和 [验证记录](../../docs/VALIDATION.md)。

## 高汤锅历史证据与限制

`build/acceptance/cookery-stockpot-v2.zip`：2,259,358 字节；SHA-256 `8a5d15b1dbbeea64bdcff015c574a7823ff41e21315e9ccb0ad2f567d8862e33`。重新导出逐字节一致。105 项 Java、61 项 Python 测试通过。两端 263 物品/12 方块/180 状态及非空容器存档通过；1,705 个安装文件哈希一致，生成包 1,247 模型无 Forge loader。

Paper cycle 1–5、Folia cycle 2–5 通过并正常停服。3 份米饭和 1 份骨汤完成后分别取 1 份，再次重启剩余 2/0 份、液面 -0.105、空锅不重复产出。原蒸食/茶饮/砧板回归继续通过。最终 JAR 在 cycle 5 两端安装哈希一致。证据 `build/acceptance/stockpot-v1`，[验证记录](../../docs/VALIDATION.md) 与 [高汤锅验收 JSON](../../docs/research/cookery-stockpot-acceptance.json)；历史 ZIP/日志保留。

Folia cycle 1 冷启动出现 5.7309607 秒 watchdog，采样在 CEWorld 构造/BukkitWorldManager.delayedInit；前轮字体缓存/join 失败也保留。整体 `PARTIAL_FOLIA_COLD_START_WATCHDOG_OPEN`；后续成功不能关闭它，不豁免日志或延长阈值。

- 非炉灶厨具用共享透明细绊线和显示实体，精确 voxel 碰撞、水浸未完成。
- 茶壶支架/吊链暂取官方高汤锅支撑；蒸笼保留旧 `slab_type`，TOP 用半笼上移。
- 茶壶/油壶由控制器掉落带内容物的物品，基础 loot 为空；沙威玛基础掉落仅 lower；双层蒸笼保留额外掉落。玩家保护取消和真实取存未测。
- 奶桶茶壶和高汤锅无法构造返回桶时保留汤底有单元证据；官方汤面/高度有服务端证据，彩色泡泡用 dust 替代，动物桶 NBT/玩家容器操作及客户端画面未验收。
- 下一步验证大菜真人营养/效果、主副手、FOOD 效果桥、保护/多人和客户端。11 种取物盘的真人流程与实服旧档导入、80 条配方、原料获取和菜单编辑未完成。大菜细绊线碰撞、服务器粒子仍为替代；跨区清理、独立卸载、CE 热重载及爆炸行为未实测。竹筛降雨、玻璃板/遮光玻璃、完整漏斗输入/锁定/满容器/矿车、完整组件恢复及 Folia 跨区仍待验收；最新证据见 [验证记录](../../docs/VALIDATION.md)。
