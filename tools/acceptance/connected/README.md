# 双人协议验收（Java 1.21.11）

测试在只监听 `127.0.0.1` 的一次性离线测试服运行。两名普通生存玩家通过 1.21.11 网络协议操作，由单独构建的 `CookeryAcceptanceProbe.jar` 布置场景并从服务端记录结果。受测插件 JAR 不包含探针。

1. 按[隔离服准备说明](../../../README.md#隔离服准备)为 Paper 132 和 Folia 14 各建一份可丢弃的副本，安装 5 个插件、6 个 CE 内容包和 CraftEngine 26.9.1。选择 `--server-blocks 8000 --early-pack-load`。在已接受 EULA 的副本内生成并托管资源包（`ce workflow default`），随后停止服务器。CE 自托管地址也设成 `127.0.0.1`，保留会话令牌及玩家身份校验。
2. 仅在这两份副本内设置 `online-mode=false` 和 `enforce-secure-profile=false`。保留 `server-ip=127.0.0.1`、`level-name=acceptance-world`；关闭 RCON，两个平台使用不同端口。将 `build/acceptance/probe/CookeryAcceptanceProbe.jar` 放进各自的 `plugins/`。在各自的 `plugins/CookeryAcceptanceProbe/config.yml` 新增：

   ```yaml
   connected-smoke:
     enabled: true
   ```

3. 用 Java 21、Node.js 22 和 Python 3.11+ 在仓库根目录执行。用官方启动器安装 1.21.11，把客户端 JAR 和版本对应的 Java 库完整类路径赋给 `SENLUO_CLIENT_CLASSPATH`。以下 PowerShell 示例中的副本路径按实际目录替换。每次使用新的输出目录。

   ```powershell
   npm ci --prefix tools/acceptance/connected --ignore-scripts
   $env:SENLUO_CLIENT_CLASSPATH = '<完整类路径>'
   $java = '<Java 21 的 java.exe 路径>'
   .\.venv\Scripts\python.exe tools/acceptance/connected/run.py build/acceptance/net/folia build/acceptance/net/folia-new --java $java
   .\.venv\Scripts\python.exe tools/acceptance/connected/run.py build/acceptance/net/paper build/acceptance/net/paper-new --java $java
   ```

`result.json` 包含每位玩家的下载与解码结果，以及锻造、蒸笼、稻草人、Deco 郁金香展板和沙发核对项。展板场景检查满背包装饰、剪取掉落、交互和掉落事件被取消及两人连续剪取。沙发场景由协议玩家染色、切换形态、拆下、拾取并重新放置；探针监听器依次注入取消交易、切换热栏和另一玩家同步重入，检查失败不扣料、重复染色不扣料和样式保留。同步重入由测试回调调用控制器，其他动作经客户端协议发送。Deco JAR 和 Deco 内容包都要从同一提交构建。

玩偶场景用 `doll_0` 和微调工具打开真实四行菜单，逐项增加九轴数值，再拆下、拾取并重新放置，核对恢复的姿态、物品消耗和唯一掉落。沙发和玩偶拾取均按服务端快照的 CE 物品 ID 选择槽位，避免把同为 paper 材质的工具当成家具。此流程验证拆装物品状态，不包括重启恢复及图形渲染。

## 三次启动持久化验收

在相同准备条件下添加 `--restart-suite`，运行器会对同一测试世界依次正常启动和停止三次。每个平台单独运行，每次使用新的输出目录：

```powershell
.\.venv\Scripts\python.exe tools/acceptance/connected/run.py build/acceptance/net/paper build/acceptance/net/paper-restart-new --java $java --restart-suite
.\.venv\Scripts\python.exe tools/acceptance/connected/run.py build/acceptance/net/folia build/acceptance/net/folia-restart-new --java $java --restart-suite
```

`seed` 先清理固定测试位置的已知残留，再跑上述完整双人流程，最后安排三件独立位置的家具。玩家把沙发染红并切为左段，逐项增加玩偶九轴，向果盆投入一份带独立 PDC 标记的发光浆果。此时写入只创建一次的 `persistence-state.json`，记录家具 UUID、位置、源物品、自定义状态和两位玩家的 UUID/库存。

`verify` 在重启后只定位已有家具，比较 UUID、源物品 PDC 和存档；由玩家拆下并拾取沙发和玩偶，分别核对唯一掉落，潜行空手从果盆取回唯一原料，再次空取不能多得。`empty` 再次重启，确认沙发和玩偶未复活、原 UUID 的果盆仍为空、两人合计物品及 PDC 守恒且地面无遗留物。后两阶段不创建家具、不注入库存，也不改写首轮快照。

每阶段保留独立的 `fixture.json`、`players.json`、`result.json`、控制台日志、资源读取结果和 JFR；顶层 `result.json` 汇总三轮。运行器锁定服务器、插件 JAR、托管资源包和验收脚本哈希，任何阶段失败、快照缺失或被改写、受测产物或场景代码变化都会停止后续阶段。`--restart-suite` 必须使用自动客户端。这个场景覆盖三种指定家具的正常服务器重启，不代表整个系列的卸载、热重载、跨区域或全部存档均已验收。

脚本保留控制台日志、服务端回复和 JFR。只有服务器启动完成且食物、附魔两项 smoke 均成功才启动客户端；超时、watchdog、服务器错误、非零退出、强制停服或客户端断言失败均返回失败，旧的成功报告不能覆盖失败。报告包含客户端与服务器退出码和结束原因；异常收尾会取消资源下载并等待 Java 解码子进程退出。客户端确认加载前先校验托管包的哈希，并使用官方客户端代码解码下载内容。此流程覆盖协议、资源格式与真实服务端交互；画面、声音、在线账号认证、第三方保护插件及跨区交互需另测。

## 2026-11-19 冻结候选结果

同一套插件、探针和场景脚本的 Paper 132 `paper-restart-12` 与 Folia 14 `folia-restart-3` 均通过三阶段验收，六次服务器和客户端退出码均为 0，无服务器错误或 watchdog。红色左段沙发、`doll_0` 九轴姿态与果盆内一份带标记的未加工发光浆果，其 UUID、状态、取回数量和 PDC 均按上述范围核对；完整哈希、资源读取及历史失败记录见[冻结候选证据](../../../docs/research/series-restart-acceptance-2026-11-19.json)。Folia 较早一轮仍出现 CE 类加载／原生配方注册 watchdog，根因保持 OPEN。

展板的满库存断言指 36 个存储槽均被占用，同色花仍可合并。进入下一夹具前，场景须从服务端确认两名玩家相对原库存合计恰好新增唯一返花，其他物品和组件不变；必要时以一次正常背包操作将一组圆石移至空副手再拾取，副手状态来自客户端网络观察。无需 Minecraft 的 75 项场景契约测试可用 `node --test tools/acceptance/connected/test-persistence.cjs` 复核。
