# 玩家状态管线

`Hook.java` 的输入、移动、发包和渲染钩子发生在同一玩家 tick 的不同位置。功能代码不能再各自维护一套“当前角度”；每个阶段必须从 `PlayerStatePipeline` 提交或读取姿态。

## 顺序

1. `onPlayerTickStart`：保存相机姿态，并确定本 tick 的移动姿态。
2. `onScaffoldMovementInputUpdated`：原版读取按键后，只变换输入坐标系。
3. `onPlayerMoved`：原版移动结束，记录实际移动姿态。
4. 同一方法的发包区间：提交出站姿态。若同一功能拥有移动和发包，两者必须一致。
5. `onPlayerTickEnd`：恢复第 1 步保存的相机姿态并结束 tick。
6. `onCameraReady`：相机矩阵完成后提交模型姿态；同一功能的模型头部姿态必须和已提交的包姿态一致。

## 不变量

- 相机姿态独立保存，不参与 Scaffold 的移动和发包姿态。
- 同一功能拥有移动阶段时，出站姿态不能换成另一个角度。
- 模型头部与同一 tick 的出站姿态保持一致，身体仍由原版递推。
- 玩家对象、世界对象发生替换或 tick 回退时，旧姿态全部作废。
- 阶段乱序和姿态分叉写入 `%TEMP%\liteclient-agent.log`，并由 `PlayerStatePipelineProbe` 在构建时覆盖正常、分叉和换世界场景。

## 迁移边界

当前第一阶段已经接管 Scaffold 以及 Natural Aim 的相机、移动、包和模型头部姿态。旧字段暂时保留给放置射线、插值和诊断代码使用。后续迁移应逐项删除重复状态，而不是再增加跨帧布尔标志。
