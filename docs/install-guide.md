# SenLuo 五插件安装指南（开发版）

目标组合：Paper/Folia 1.21.11、Java 21、CraftEngine 26.9.1。五个插件可以分别安装；CraftEngine 是唯一硬依赖，`series/common` 已内嵌到各系列 JAR，不需要单独安装。

1. 将需要的 JAR 放入 `plugins/`：`KaleidoscopeCookeryPlugin`、`KaleidoscopeDoll`、`KaleidoscopeTavern`、`KaleidoscopeDeco`、`KaleidoscopeMusic`。每个插件都可以单独安装，但对应玩法的 CE 包也要一起安装。
2. 解压厨房 ZIP，将其中的 `cookery_kitchen/` 放入 `plugins/CraftEngine/resources/`。四个系列 ZIP 分别解压到该目录的 `doll/`、`tavern/`、`deco/`、`music/`，共享图集 ZIP 解压到 `shared/`。各目录直接包含 `pack.yml`。
3. 五插件整合服在 CraftEngine 的 `config.yml` 设置 `block.serverside-blocks: 8000`、`resource-pack.cache-resource-files: false` 和 `misc.delay-configuration-load: false`。启动后核对包加载日志，在控制台执行 `ce workflow default` 生成并托管原版客户端包。
4. 首次安装先备份世界和 `plugins/CraftEngine/`。开发验收可使用 `series/tools/prepare_independent_servers.py` 生成隔离目录和 `INSTALL-MANIFEST.json`，该工具不会启动服务器。

只安装某一个系列时，不需要安装其他系列的 JAR 或内容包。跨插件联动使用 soft-depend/API；目标插件缺失时，发布方应继续工作。

此开发版的实测范围与玩家验收条件见[交接清单](HANDOFF.md)。
