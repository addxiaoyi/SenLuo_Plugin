# 酒馆展板文字变换核查

核查日期：2026-11-19（Asia/Shanghai）。本记录解释固定版本的源码／字节码数学核查及其可复现测试，不是原版客户端画面验收，也没有启动服务。

## 输入与证据范围

Tavern 上游固定提交为 `c4ec1880bd44cf3139d3ba744ab30bb379cf1416`。以下 SHA-256 均取本次实际读取的原始文件字节：

| 输入 | SHA-256 |
| --- | --- |
| 上游 `client/render/block/SandwichBlockEntityRender.java` | `0dd99b6a031063e0024193dfec3a923cacf1abc9af9f071295c1ec2434a2df2f` |
| 上游 `client/render/block/TextBlockEntityRender.java` | `7cd9a6d986493e213ff563b70138a9cfffb61eb3dfd404c71f55e112569c0678` |
| 官方 `mojang_client_1.21.11.jar` | `1473c9489ac50fda3c435049a76a70d61a10b8610db27f5ba9d8756b686cd3bd` |
| 对应官方 `client-mappings.txt` | `517799a8485e107e932dc1bd27c002b2d0b9207eb2396b685bcfe6c3321a9fbd` |
| `craft-engine-core-26.9.1.jar` | `3db3473986aefe52936aa807a0b5858355775fa16927eba96b701e84431a0d2b` |
| `craft-engine-bukkit-26.9.1.jar` | `011afd3b1cfcaf5fc4b4af4757843e5479e1be4a9763c1a97bde04dcb1c0795d` |

上游 Java 路径相对于 `src/main/java/com/github/ysbbbbbb/kaleidoscopetavern/`。使用 `git show <commit>:<path>` 读取固定提交，不依赖上游可变工作树。

本次以 JDK 21 `javap -c -p` 检查这些实际入口：

- CE `FurniturePlacement.rotateOffset`、`position`、`elementPosition`，以及 `Furniture.rotateHitboxOffset`：家具 yaw 为 0 时，配置坐标 `(x,y,z)` 对应世界偏移 `(-x,y,z)`；一般 yaw 为 θ 时再乘 `RY(-θ)`。不能把配置坐标当作普通模型坐标直接相加。
- CE `TextDisplayFurnitureElementConfig.Factory.create`：读取 position、scale、pitch、yaw 和 alignment；billboard 默认 FIXED，未显式设置的 translation／rotation 为零／单位变换。
- CE `TextDisplayFurnitureElement`：原样将 `WorldPosition` 的 pitch／yaw 写入生成包。
- 官方 `DisplayRenderer.calculateOrientation`（本客户端 `hwe`）：FIXED 分支为 `RY(-yaw) RX(pitch)`，然后应用 display transformation。
- 官方 `DisplayRenderer.TextDisplayRenderer.submitInner`（本客户端 `hwe$c`）：依次 `rotateY(π)`、`scale(-.025,-.025,-.025)`，再 `translate(1-width/2,-(lineCount*10-1),0)`。每行的绘制 y 步进为 10。
- 官方 `TextDisplayRenderer.splitLines`：缓存宽度为实际各行宽度的最大值。当前一行一个 display，故一行的内部高度固定为 9，不能忽略仍存在的 1 像素水平边距。

复核命令示意：

```powershell
& $javap -classpath $ceCore -c -p net.momirealms.craftengine.core.entity.furniture.FurniturePlacement
& $javap -classpath $ceCore -c -p net.momirealms.craftengine.core.entity.furniture.Furniture
& $javap -classpath $ceBukkit -c -p 'net.momirealms.craftengine.bukkit.entity.furniture.element.TextDisplayFurnitureElementConfig$Factory'
& $javap -classpath $ceBukkit -c -p net.momirealms.craftengine.bukkit.entity.furniture.element.TextDisplayFurnitureElement
& $javap -classpath $client -c -p hwe 'hwe$c'
```

## 独立参考变换

约定矩阵左乘列向量；`RX`、`RY` 为通常的右手旋转矩阵，`S` 为缩放矩阵。θ 是家具朝向，α 为上游板面倾角 22.5°，`w` 是已排成一行后的字体像素宽度，`i` 是从 0 开始的行号。所有位置均相对家具中心的地面位置。

固定上游的文字变换为：

```text
M_up = RY(-θ) RX(α) RY(-π) S(.01,-.01,.01)
P_up = RY(-θ) (0,1.06,-.06) + M_up (a,10i-19,0)

a_left   = -55/2
a_center = -w/2
a_right  = 55/2-w
```

这里的 `P_up` 是字形绘制原点；测试名称中的 baseline 指这一坐标，不表示已检查每个字体字形的视觉基线。上游实际使用的文本为 bold。

由 CE 与原版客户端分别解释配置后，当前单行 text display 的变换为：

```text
M_ce = RY(-θ-β) RX(p) S(sx,sy,sz) RY(π) S(-.025,-.025,-.025)
P_ce = RY(-θ) (-x,y,z) + M_ce (1-w/2,-9,0)
```

β、p、sx/sy/sz、x/y/z 从生产 Java 的实际表达式取得。选择 β=180°、p=-22.5°、scale=(.4,.4,.4) 后，`M_ce = M_up`。若误用正 22.5° pitch，字形本身会朝板面相反方向倾斜；仅修改每行的位置不能修复字形平面。

再令 `P_ce = P_up`，得到配置坐标：

```text
x_left   = -(55-w)*.005-.01
x_center = -.01
x_right  = +(55-w)*.005-.01
y        = 1.06+(.1-.1i)*cos(α)
z        = -.06+(.1-.1i)*sin(α)
```

负的水平符号来自 CE 坐标映射，额外 `-.01` 抵消原版 text display 的 1 像素边距。首行配置位置为 `(-.01,1.1523879533,-.0217316568)`，不是字形绘制后的最终世界坐标。当前位置字符串保留五位小数，故核查需容许序列化量化误差。

## 可复现测试

新增 `series/tools/test_sandwich_transform.py`，只使用 Python 标准库，可独立运行：

```powershell
python -m unittest discover -s series/tools -p test_sandwich_transform.py -v
```

测试每次从生产 `SandwichBoardDisplay.configuration` 中提取 x 的三个 switch 分支、数值局部变量、position 的格式与参数，以及 pitch／yaw／scale／line_width。它只解释有限的数值 AST（加减乘除、括号、sin／cos／toRadians），没有执行 Java、任意 Python eval、服务器或 Minecraft 客户端。无法识别的新表达式或额外 rotation／translation／billboard 配置会使测试失败，要求重新审查合同，而非默默采用旧值。

参考端单独按固定上游操作顺序构造矩阵，未用生产端的目标坐标公式计算“期望值”。测试覆盖：

- 360 个整数角度 × 8 行 × 3 个对齐方向 × 4 个宽度（1、7、30、55），共 **34,560** 个字形绘制原点。
- 另检查原生 16 个 22.5° 摆放方向的全部行、对齐和宽度，共 **1,536** 个原点，包含整数角度循环遗漏的 22.5°、67.5° 等半度方向。
- 360 个方向的完整字形轴向，防止只有原点重合而倾角错误。
- 对实际 Java 源码的内存变异反例：旧正 pitch、旧左右公式、丢失 1 像素边距、旧首行锚点，均必须被核查器识别为偏离。不会为这些反例写回源文件。

2026-11-19 本次运行 **7 个测试通过**；五位小数配置下的整数角度最大原点距离误差为 `6.292970966872238e-06` 块（yaw=336°、w=55、i=4、LEFT），小于测试阈值 `1e-5` 块。原生 16 个方向也全部在阈值内；未量化的独立数学比较最大误差为 `3.33e-16` 块。

当次生产 Java 原始字节 SHA-256 为 `9eb492c56a889604d10cae436454dfe942a9f36ba2e7d0ee51dc8bf2c3f16c07`。这仅标识本次核查快照，测试没有将该哈希写死为必须维持的结果；后续修改会重新提取并比较实际配置。

## 不能由这些测试得出的结论

- `w` 是注入的字体宽度结果，测试没有证明服务端 ASCII 表与任意客户端字形、资源包、Unicode fallback、bidi／RTL、组合符或 uniform font 完全一致。当前非 ASCII 使用保守估算，完整 320 字符保存与最多 8 个渲染行是另外的合同。
- 这是固定输入的源码合同与数学测试，不是对已打包 Java 字节码、CE 运行解析器或网络包的集成测试；没有模拟 float/JOML/包角度的所有量化行为，也没有启动服务器截图。
- 没有覆盖 MiniMessage 标签转义、Dialog 回调、库存守恒、存档迁移或家具生命周期；这些分别由其他实现与测试承担。
- 发光描边、黑色特例、遮挡／z-fighting、光照、实际正面可见性、资源包中的板面几何、双格碰撞与水浸，仍须真实客户端验收。矩阵相等不能替代这些验收，也不能据此宣称展板已与上游全部行为完全一致。
