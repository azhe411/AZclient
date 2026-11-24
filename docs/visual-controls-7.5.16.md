# 7.5.16 视觉修订

- ESP / Tracers 保留原本主线宽度，以均匀截面的微弱柔光代替拉长的椭圆光斑。1.20.1 ESP 的外围光晕也缩减。
- HUD 的 Rainbow 现在直接驱动标题、功能名和色条，文字内部及列表行之间均有夕阳渐变，随时间流动；独立于 Theme 的 Rainbow 开关。
- 以下模块新增 `Background Opacity`，范围 0–100：HUD、Watermark、PingDisplay、Keystrokes、MotionStats、Coordinates、Compass、SessionInfo、ItemStatus、Vitals、HotbarAccent、NameTags、ItemESP。
- 通知使用 `Theme → Notice Opacity`。1.20.1 独立界面在“界面”页提供“HUD 背景”和“通知背景”。
- 0 隐藏客户端底板及其背景柔光，文字、图标及信息线条保留。原有 Background 开关、Watermark 的 Text/Box 模式仍生效。HotbarAccent 控制客户端叠加层，不改变 Minecraft 原生物品栏底图。
- GUI 使用原来的背景与外框光效，未随小组件透明度变更。
- 新参数追加在各模块末尾；支持直接导入此前 7.5.15 的配置，保留原有参数数值。

## 验证范围

完整构建和已有门禁通过。新增验证使用实际绘制函数和字体图集，覆盖旧配置导入、13 个透明度控件的保存、线条截面、文字几何、HUD 动态颜色与独立背景透明度。GUI 布局、交互、通知队列、RShift 输入与打包后的 1.20.1 模块加载测试通过。参数接线扫描器增加了具名读取识别，删除读取器或模块调用的负向检查仍会失败。

`work/visual-controls-7.5.16-20261018/visual-controls-preview.png` 是实际绘制代码生成的离线预览，背景为合成图；未在 Minecraft 游戏进程中完成视觉验收。

部署时校验 EXE 产品版本 7.5.16、文件版本 7.5.16.0，以及内嵌 AgentJar / InjectorJar 与构建产物的 SHA-256。升级后需完全退出旧 Minecraft 进程，再启动游戏并重新注入。
