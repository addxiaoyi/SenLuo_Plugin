# 可复现构建基线

记录日期：2026-11-08（UTC）

这份基线固定首个验收组合：Paper/Folia 1.21.11、Java 21 主源码、Java 25 的 26.x NMS 源集、CraftEngine 26.9.1。上游八个 checkout 的提交和工作树状态以 [`organization-checkouts.json`](research/organization-checkouts.json) 为准；导入器只接受这些提交。

工程治理基线提交为 `aea96140d3c78395c1f8754fb4cb286d6e08ba92`。本地复核使用固定 JDK 25 执行根工程（17 个任务，成功）和系列工程（24 个任务，成功）；工具测试为根 76 项、系列 29 项，独立性与台账检查均为 0 errors。厨房状态信封迁移后根工程测试为 142 项通过。CI 会在新的提交上重复同一组命令；提交、工具链和产物 SHA-256 以 CI artifact 和发布包 `BUILD-MANIFEST.json` 为最终证据。

2026-11-15：根工程 `test` 现在依赖最终 `shadowJar`，打包回归检查 Mojang 映射标记、九个 NMS 桥的方法/字段签名及 CraftBukkit 引用。Shadow 产物必须声明 `paperweight-mappings-namespace: mojang` 并把编译用的版本化 CraftBukkit 前缀转换成 Paper 包名；缺少标记曾导致 Paper 二次映射把滴水检测的 `Fluid` 返回值改成 `FluidState`，源码测试无法发现。最新测试数量、运行与产物证据见 [验证记录](VALIDATION.md)。

## 本地复现

```bash
./gradlew javaToolchains --no-daemon --no-configuration-cache --console=plain
./gradlew test shadowJar acceptanceJar --no-daemon --no-configuration-cache --console=plain
./gradlew -p series build --no-daemon --no-configuration-cache --console=plain

python -m unittest discover -s tools -p 'test_*.py' -v
PYTHONPATH=series/tools python -m unittest discover -s series/tools -p 'test_*.py' -v
python tools/feature_ledger.py
python tools/audit_licenses.py --output build/license-audit.json
PYTHONPATH=series/tools python series/tools/check_independence.py
node --test tools/acceptance/connected/test-persistence.cjs
```

Node.js 22 用于不启动 Minecraft 的协议场景合同测试。也可以在 Linux/macOS 使用统一入口（Windows 会自动选择 `gradlew.bat`）：

```bash
python tools/build_all.py
```

如果要运行严格上游导入检查，先按锁文件恢复 `../.references/` 下的八个干净 checkout，再执行：

```bash
PYTHONPATH=series/tools python series/tools/import_sources.py --check
```

CI 会恢复五个玩法源码的固定提交后执行严格系列导入检查；没有上游输入时该检查会失败，不会把跳过导入当成成功。

## 证据边界

- Java/Python 任务通过只证明源码和工具测试通过，不证明 CE 在服务器运行。
- `check_independence.py` 只检查 plugin.yml、可选 JAR 和 CE ZIP 的结构；它不替代 Paper/Folia 或原版客户端验收。
- Folia 冷启动 watchdog、资源包下载、玩家操作、保护插件、多人和跨区域行为必须在 [`series/docs/ACCEPTANCE.md`](../series/docs/ACCEPTANCE.md) 中单独记录首次失败与后续复验。
