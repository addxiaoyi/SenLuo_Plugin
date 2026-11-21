# 上游许可证据

本地开发包的来源固定在 [organization-checkouts.json](../../docs/research/organization-checkouts.json)。以下记录以固定提交为准。已找到许可文本或声明的模块仍需按文件核对适用范围、署名、修改说明及对应源码。当前整包没有完成公开分发审核。

| 模块 | 固定提交中的证据 | 发布前处理 |
| --- | --- | --- |
| Cookery | [代码 BSD 3-Clause](https://github.com/KaleidoscopeMods/KaleidoscopeCookery/blob/2f4e386ce23f49a385ddf003c67fc6415c55417a/LICENSE-CODE)，[素材 CC BY-NC-SA 4.0](https://github.com/KaleidoscopeMods/KaleidoscopeCookery/blob/2f4e386ce23f49a385ddf003c67fc6415c55417a/LICENSE-ASSETS) | 核对署名、修改说明、非商用和相同方式共享条件。 |
| Tavern | [代码 BSD 3-Clause](https://github.com/KaleidoscopeMods/KaleidoscopeTavern/blob/c4ec1880bd44cf3139d3ba744ab30bb379cf1416/LICENSE-CODE)，[素材 CC BY-NC-SA 4.0](https://github.com/KaleidoscopeMods/KaleidoscopeTavern/blob/c4ec1880bd44cf3139d3ba744ab30bb379cf1416/LICENSE-ASSETS) | 同样核对代码和素材各自条件。 |
| Deco | [GPL v3 许可文本](https://github.com/KaleidoscopeMods/KaleidoscopeDeco/blob/6ce180e1226ce93b0fb0d45ea80e0ed5b01b1dc0/LICENCE)，导入器已随内容包保留原文。 | 确认可分发的文件范围和对应源码义务。 |
| Doll | [README](https://github.com/KaleidoscopeMods/KaleidoscopeDoll/blob/62303aa58bbd5ee52498f3ad0fbf4a0c7d04c183/README.md#license) 声明代码为 BSD 3-Clause、素材保留所有权利；[Gradle 元数据](https://github.com/KaleidoscopeMods/KaleidoscopeDoll/blob/62303aa58bbd5ee52498f3ad0fbf4a0c7d04c183/gradle.properties#L13) 也标注 All Rights Reserved。 | 对外发布前取得素材授权，或删除、替换相关素材并核验整包。 |
| MusicPlus | [固定提交的 Gradle 元数据](https://github.com/KaleidoscopeMods/MusicPlus/blob/cc84f29e2a1abe79805567fddd218b3a7b252655/gradle.properties#L12) 声明 BSD 3-Clause / CC BY-NC-SA 4.0，未找到独立许可文件。 | 核对两种许可分别适用哪些文件，以及吉他贴图的署名和修改条件。 |

另三个固定来源未作为独立玩法包导入。Demo 有 `LICENSE-ASSETS.txt`；Web 和 midi 未发现独立许可文件。如果后续使用其中的文件，先核实授权范围。

用 `python tools/audit_licenses.py --output build/license-audit.json` 重跑证据扫描。输出列出固定 Git 对象中的许可文件、声明位置和哈希，成功生成报告不代表允许公开分发。
