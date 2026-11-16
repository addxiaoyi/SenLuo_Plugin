<div align="center">

# 🍳 森罗物语 · SenLuo Plugin

**Paper/Folia + CraftEngine 的五插件森罗物语移植工程**

Cookery 厨房 · Doll 玩偶 · Tavern 酒馆 · Deco 装饰 · MusicPlus 音乐

![CraftEngine](https://img.shields.io/badge/CraftEngine-26.9.1-4C8BF5?style=flat-square)
![Paper/Folia](https://img.shields.io/badge/Paper%2FFolia-1.21.11-EF6C00?style=flat-square)
![Java](https://img.shields.io/badge/Java-21%2F25-007396?style=flat-square)

</div>

## 项目定位

本仓库把 [KaleidoscopeMods](https://github.com/KaleidoscopeMods) 的五类玩法移植为独立 Bukkit 插件和 CraftEngine 内容包，让原版客户端通过服务器资源包参与玩法。Demo、Web、midi 保留为上游参考，不作为玩法插件。

每个插件都能独立发布。CraftEngine 是唯一硬依赖，系列共享代码在构建时 shade 进对应 JAR。跨插件联动通过 soft-depend、公开 API 或事件完成。

| 插件 | JAR | CE 内容包 | 当前证据 | 主要缺口 |
| --- | --- | --- | --- | --- |
| Cookery | `KaleidoscopeCookeryPlugin-1.3.0.jar` | `cookery_kitchen` | 完整厨房包 534 物品、63 方块、1,554 状态，536/612 来源配方已导入；319 Java/104 Python、茶旗控制器与 16×7 状态模型、38 种大菜与 5 种盖饭 native recipe 各完成 Paper/Folia 三轮严格探针；旧厨房、取物盘、竹筛回归通过 | 茶旗真实旗杆摆动/墙面朝向、真人营养/效果、Shift/自动合成、玩家/客户端、76 条 pending 配方、完整天气/自动化、保护/多人、卸载/跨区 |
| Doll | `KaleidoscopeDoll-0.1.0-dev.jar` | `doll-ce-pack-*.zip` | Paper/Folia 启动加载通过 | 精确物理、Bedrock 模型、玩家流程 |
| Tavern | `KaleidoscopeTavern-0.1.0-dev.jar` | `tavern-ce-pack-*.zip` | Paper/Folia 启动加载通过 | 葡萄世界生成、复杂家具、饮用和多人 |
| Deco | `KaleidoscopeDeco-0.1.0-dev.jar` | `deco-ce-pack-*.zip` | Paper/Folia 启动加载通过 | 418 条聚合缺口、脚本事件、座位/碰撞 |
| MusicPlus | `KaleidoscopeMusic-0.1.0-dev.jar` | `music-ce-pack-*.zip` | Paper/Folia 启动加载通过，吉他纹理已接入 | Bedrock 几何、持续声部、客户端合成器 |

当前代码、单测和服务器加载已经完成多层验证。原版客户端资源包、真人玩家闭环和许可证人工核验仍按台账记录，项目不把静态通过写成 100% 完成。

## 工程结构

```text
SenLuo_Plugin/
├── src/                         # Cookery 主插件与 NMS 适配
├── Kaleidoscope/                # 厨房资源入口和菜单图标
├── series/
│   ├── common/                  # 构建时内嵌的共享库
│   ├── doll/                    # 独立 Doll 插件
│   ├── tavern/                  # 独立 Tavern 插件
│   ├── deco/                    # 独立 Deco 插件
│   ├── music/                   # 独立 MusicPlus 插件
│   ├── tools/                   # 来源同步、资源转换、打包
│   └── docs/                    # 移植矩阵和验收表
├── tools/                      # 厨房导入、基线、许可证和验收工具
└── docs/                       # 证据、安装、升级和配置参考
```

## 当前运行基线

- Minecraft 1.21.11
- Paper build 132 和 Folia build 14
- Java 21 运行与主源码，Java 25 编译 26.x NMS 源集
- CraftEngine 26.9.1
- 官方来源固定在 [`organization-checkouts.json`](docs/research/organization-checkouts.json)
- 模块同步规格在 [`module-specs.json`](series/tools/module-specs.json)

2026-11-08 的历史五插件整合服已在 Paper/Folia 启动并正常关闭。当时 Cookery 运行加载 451 个物品、64 个方块和 90 个配方，四个系列插件分别完成启用。这些是该次整合服的运行统计，不等于当前厨房包 manifest 的内容数。完整哈希、版本和首次失败修复见 [`series-five-runtime-acceptance-2026-11-08.json`](docs/research/series-five-runtime-acceptance-2026-11-08.json)。

## 厨房开发检查点（2026-11-15）

完整厨房包包含 534 物品、63 方块、1,554 状态、1 石磨家具和 658 个已检查模型。固定源码的 612 条配方中，536 条已导入、76 条 pending；包括茶旗配方、28 条竹筛配方、5 条盖饭 native recipe 和 73 条原版/合成配方配置（53 合成、20 炉具）。来源记录、模型引用和包内文件哈希仍严格校验。

2026-11-16 附属物品静态导出已补入农夫靴、护腿、胸甲、草帽、花草帽、镰刀、厨铲和茶旗。镰刀配置连接现有范围收割行为；茶旗控制器支持 16 色、6 种原版旗帜图案、剪刀取下、物品状态存档和带状态掉落，资源包按颜色与图案生成 112 个模型状态。锅铲基础/沾油 ID 与运行时代码一致，油态旧 ID 由 CE 生成独立 item-model；茶旗配方随本轮导入。新导出包为 534 物品、63 方块、658 个已检查模型、536 imported/76 pending。旗杆摆动、墙面朝向、原版客户端实机资源包、实际锅铲交互和农夫装备槽效果仍待实测，证据见 [附属物品审计](docs/research/cookery-auxiliary-items-2026-11-16.json) 和 [双端启动摘要](docs/research/cookery-shovel-runtime-2026-11-16.json)。

38 种直接食用大菜（`FoodBiteRegistry` 注册表 37＋`ModBlocks` 冷切火腿片 1）已接入 CE 代码与导出：34 种单格、3 种双格、1 种九格，共 49 个放置单元、1,024 个状态。每口快照、品质、多格归属、耗尽后保留容器和整菜/额外掉落已有实现；消费提交检查同步重入、过期回调和可取消饥饿事件。319 项 Java、102 项 Python 通过，盖饭 v2 Paper/Folia 各三轮 native 探针 PASS 并正常停服，`25583/25584` 已释放；详见 [大菜验收摘要](docs/research/cookery-placed-meals-acceptance-2026-11-15.json)。

每端首轮提交 38 口并验证各 38 次阻挡放置、取消消费、坏档与过期回调拒绝；二轮提交剩余 93 口、核对额外掉落和 38 份整菜回收（含 4 次非中心格破坏），三轮 49 格无复活/重复。两端各核对 1,718 个源文件哈希，ItemIcons 运行生成的 261 图标配置单列；539 个厨房可达模型检查通过，全部 1,260 个生成模型无 Forge loader。v4 Folia 第三轮的 5.1581751 秒启动 watchdog 严格失败仍保留为 OPEN，后续成功不关闭它。

当前碰撞使用细绊线替代，服务器粒子近似官方客户端动画，放置会拒绝未归属的 Folia 区域。真人营养/效果、主副手、保护/多人、客户端画面、FOOD 原因效果桥、跨区清理、独立区块卸载、CE 热重载和爆炸行为仍未实测。[大菜来源差异](docs/research/cookery-food-bite-gap-2026-11-15.json) 是接入前审计快照；[配方审计](docs/research/cookery-pending-recipes-2026-11-15.json) 是前序快照，当前剩余 80 条。

前序修正的 `PlateRegistry` 11 种整份取放盘与大菜每口食用分开处理：默认满盘，单食物盘补 1 件增加 1 份，浆果盘每份同时给甜浆果和发光浆果且禁补；最后一份取走后保留空盘，下次空盘交互才拆除并返 1 碗。基础 loot 为空，破坏仅由控制器掉剩余各份食物和容器。schema 2 保存份数及物品快照，旧档迁移已有单元回归；交付前先扣份，回调锁阻止同步重入。v1 关服存档失败已改为构造时建立快照、保存时不再构造物品，原失败保留于 [验证记录](docs/VALIDATION.md)。

前序取物盘验收为 203 项 Java、86 项 Python 通过，v2 Paper/Folia 各三轮服务端探针 PASS 并正常停服，当时 `25583/25584` 已释放。每端首轮从 11 盘取出 48 份、52 件食物并保存空盘；二轮验证 10 种单件补份、拒绝浆果补份、两批各 11 碗和破坏剩余 40 件食物，三轮 11 个位置无复活或重复产出。当时 24 种厨具（含未操作盘）的保存重载通过。该范围不覆盖大菜玩法或真实玩家；详见 [取物盘验收摘要](docs/research/cookery-plate-acceptance-2026-11-15.json)。

竹筛已接入官方模型和完整厨房包，每个非空槽按数量展示 1–5 层，复用固定位置的随机偏移和旋转；物品、数量及组件未变化时复用显示数据包。坏 NBT 在整批校验通过前不改变库存。展示算法和服务端实现不代替原版客户端画面、碰撞或玩家操作验收。

前序竹筛验收为 179 项 Java、81 项 Python 通过，v3 Paper/Folia 各三轮服务端探针 PASS 并正常停服，当时测试端口 `25583/25584` 已释放。竹筛 28 条配方仅验证注册与产物构造；实际加工产出覆盖海绵、干海带、泥巴和黏土样例，另验证反向润湿、下方漏斗取消 2 次后接受 1 次，以及中途重启和空槽防重复。玩家、客户端、雨天和完整自动化仍待验收。

前序竹筛两端各核对 1,709 个源包文件哈希，另将 ItemIcons 运行时生成的 1 个字体配置单列；当时 351 个厨房可达模型引用检查通过，全部 1,251 个生成模型无 Forge loader。营火类型键、缺少 Mojang 映射标记导致 Paper 二次 remap、石磨探针时序的失败均保留；默认 40 秒牛驱动和原断言不变，历史 Folia 启动 CE watchdog 仍 OPEN。完整历史范围见 [竹筛验收摘要](docs/research/cookery-bamboo-acceptance.json) 与 [验证记录](docs/VALIDATION.md)。

## 新设备构建

官方源码需要放在仓库同级 `.references/`，路径和提交以锁定文件为准。准备 Python 环境后执行：

```powershell
$referenceRoot = Join-Path (Split-Path -Parent (Get-Location).Path) '.references'
New-Item -ItemType Directory -Force -Path $referenceRoot | Out-Null
$checkouts = @(
    @{ Name = 'KaleidoscopeCookery'; Branch = 'main'; Url = 'https://github.com/KaleidoscopeMods/KaleidoscopeCookery.git'; Commit = '2f4e386ce23f49a385ddf003c67fc6415c55417a' },
    @{ Name = 'KaleidoscopeDoll'; Branch = '1.20-forge'; Url = 'https://github.com/KaleidoscopeMods/KaleidoscopeDoll.git'; Commit = '62303aa58bbd5ee52498f3ad0fbf4a0c7d04c183' },
    @{ Name = 'KaleidoscopeTavern'; Branch = 'main'; Url = 'https://github.com/KaleidoscopeMods/KaleidoscopeTavern.git'; Commit = 'c4ec1880bd44cf3139d3ba744ab30bb379cf1416' },
    @{ Name = 'KaleidoscopeDeco'; Branch = 'master'; Url = 'https://github.com/KaleidoscopeMods/KaleidoscopeDeco.git'; Commit = '6ce180e1226ce93b0fb0d45ea80e0ed5b01b1dc0' },
    @{ Name = 'MusicPlus'; Branch = 'main'; Url = 'https://github.com/KaleidoscopeMods/MusicPlus.git'; Commit = 'cc84f29e2a1abe79805567fddd218b3a7b252655' }
)
foreach ($checkout in $checkouts) {
    $path = Join-Path $referenceRoot $checkout.Name
    if (-not (Test-Path $path)) {
        git clone --branch $checkout.Branch $checkout.Url $path
        if ($LASTEXITCODE -ne 0) { throw "Clone failed: $($checkout.Name)" }
    }
    $head = (git -C $path rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw "Not a Git checkout: $path" }
    if ($head -ne $checkout.Commit) {
        if (git -C $path status --porcelain) { throw "Refusing to move dirty checkout: $path" }
        git -C $path fetch origin $checkout.Commit
        if ($LASTEXITCODE -ne 0) { throw "Fetch failed: $($checkout.Name)" }
        git -C $path checkout --detach $checkout.Commit
        if ($LASTEXITCODE -ne 0) { throw "Checkout failed: $($checkout.Name)" }
    }
    if ((git -C $path rev-parse HEAD).Trim() -ne $checkout.Commit) {
        throw "Pinned commit mismatch: $($checkout.Name)"
    }
}

python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r tools\requirements.txt -r series\tools\requirements.txt
$env:PYTHONPATH = 'tools;series/tools'
```

```bash
python -m venv .venv
.venv/bin/python -m pip install -r tools/requirements.txt -r series/tools/requirements.txt

PYTHONPATH=series/tools .venv/bin/python tools/export_cookery_catalog.py \
  --source ../.references/KaleidoscopeCookery --check
PYTHONPATH=series/tools .venv/bin/python series/tools/import_sources.py --check

PYTHONPATH=series/tools .venv/bin/python -m unittest discover -s tools -p 'test_*.py' -v
PYTHONPATH=series/tools .venv/bin/python -m unittest discover -s series/tools -p 'test_*.py' -v

JAVA_HOME=/path/to/jdk-25 bash ./gradlew test shadowJar acceptanceJar --no-daemon --no-configuration-cache
JAVA_HOME=/path/to/jdk-25 bash ./gradlew -p series build --no-daemon --no-configuration-cache
```

统一入口是 `python tools/build_all.py`。Windows 使用 `.venv\Scripts\python.exe` 和 `gradlew.bat`。

## 隔离服准备

隔离服准备工具会复制五个 JAR、五个 CE 内容包、CraftEngine 运行 JAR 和基础资源包，Cookery 厨房包会自动解包成 `cookery_kitchen/` 目录。它不会启动服务器或自动接受 EULA。

```bash
PYTHONPATH=series/tools .venv/bin/python series/tools/prepare_independent_servers.py \
  --root build/acceptance/series-five-runtime \
  --artifacts series \
  --packs series/build/distribution/ce-packs-current \
  --cookery-jar build/libs/KaleidoscopeCookeryPlugin-1.3.0.jar \
  --cookery-pack build/acceptance/cookery-runtime.zip \
  --craft-engine build/acceptance/runtime/craft-engine-paper-plugin-26.9.1.jar \
  --base-resources build/acceptance/runtime/craftengine-resources \
  --paper-jar build/acceptance/runtime/paper-1.21.11-132.jar \
  --folia-jar build/acceptance/runtime/folia-1.21.11-14.jar
```

安装、升级和回滚说明见 [`docs/install-guide.md`](docs/install-guide.md) 与 [`docs/upgrade-guide.md`](docs/upgrade-guide.md)。

## 验证入口

- 功能台账：[`docs/feature-ledger.json`](docs/feature-ledger.json)
- 验收矩阵：[`series/docs/acceptance-matrix.json`](series/docs/acceptance-matrix.json)
- 许可证审计：`python tools/audit_licenses.py --output build/license-audit.json`
- 独立性检查：`PYTHONPATH=series/tools python series/tools/check_independence.py`
- 厨房状态信封：`CookeryStateEnvelope` 和 `CookeryBlockStateEnvelope`
- 系列统一存档：`series/common/SavedState`
- 构建基线：[`docs/build-baseline.md`](docs/build-baseline.md)
- 厨房对照：[`docs/COOKERY-PARITY.md`](docs/COOKERY-PARITY.md)
- 系列移植矩阵：[`series/docs/PORTING-MATRIX.md`](series/docs/PORTING-MATRIX.md)
- 详细厨房配置参考：[`docs/COOKERY-CONFIGURATION.md`](docs/COOKERY-CONFIGURATION.md)

状态枚举固定为 `not_started`、`implemented`、`unit_tested`、`runtime_tested`、`client_tested`、`accepted` 和 `blocked`。`accepted` 只能由明确证据更新。

## 存档与交易边界

状态保存使用 `namespace/schema_version/source_commit/payload` 信封。Cookery BlockEntity 保留旧 payload 作为兼容投影，系列插件读取旧裸状态并拒绝未知版本、错误来源和损坏数据。

玩家物品操作采用预检查、可取消事件、一次提交、状态保存和清理处理中标记的事务流程。Folia 世界、玩家和实体操作必须在所属调度器执行，纯文件和资源处理放到异步路径。

## 主要剩余任务

1. Cookery 继续验证大菜真人营养/效果、主副手、FOOD 效果桥、保护/多人和客户端表现；继续 80 条 pending 配方、原料获取与取物盘玩家闭环。竹筛还需雨天切换、上方/侧方漏斗、锁定/满目标/矿车、完整组件恢复、独立区块卸载和 Folia 跨区验收。
2. Doll 补精确物理、移动玩偶姿态和原版客户端显示。
3. Tavern 补葡萄世界生成、酒桶/果盆复杂状态、液面和多人守恒。
4. Deco 按模型组件、overlay、脚本事件、座位和碰撞分类处理 418 条聚合缺口。
5. MusicPlus 补原版可行的乐器模型、持续声部和多人演奏生命周期。
6. 五插件完成原版客户端资源包、保护插件、多人、重启/卸载和性能验收。
7. 完成八个上游仓库的许可证人工复核。

## 许可证与发布

[`docs/license-policy.json`](docs/license-policy.json) 记录八个上游仓库的审核状态。没有许可证文件或未经人工确认的素材不会自动进入正式发布。开发包、独立包和整合包都应携带 `BUILD-MANIFEST.json` 与 `SHA256SUMS.txt`。

## 继续开发

```bash
git clone --branch main https://github.com/addxiaoyi/SenLuo_Plugin.git luosengchufang
cd luosengchufang
git status --short --branch
git log -1 --oneline
```

先恢复 `.references/`，运行上面的严格导入和构建命令，再从 [厨房验证记录](docs/VALIDATION.md) 和 [移植矩阵](series/docs/PORTING-MATRIX.md) 的最新检查点继续。保留未提交改动与历史失败记录；已通过的服务端探针不把完整玩家闭环升级为 `accepted`。
