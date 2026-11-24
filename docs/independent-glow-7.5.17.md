# 7.5.17 背景与柔光独立控制

修正 7.5.16 将背景不透明度同时乘入柔光强度的问题。

- 13 类视觉组件分别保留 `Background Opacity`，新增 `Glow Strength`，均为 0–100 的独立滑条。
- `Background Opacity = 0` 隐藏底板，仍保留周围柔光；`Glow Strength = 0` 关闭该组件柔光，底板和文字不变。
- 通知使用 `Theme → Notice Opacity / Notice Glow`。
- 1.20.1 的“界面”页提供 HUD 背景、HUD 柔光、通知背景、通知柔光四个可拖动滑条，松开鼠标保存。
- 全局 Glow / Bloom 仍作为柔光总开关和强度上限。组件原有 Background 开关与 Watermark 的 Text/Box 模式继续生效。
- 旧版配置可以导入；原有透明度不变，新增组件柔光默认 100%。ESP / Tracers 的均匀细线方案和 GUI 风格保持不变。

验证包括真实绘制函数的外围像素对比、独立滑条点击/拖动/释放、7.5.15/7.5.16 配置迁移、保存往返、GUI 布局及打包后的 1.20.1 回调。离线预览使用合成背景，未代表 Minecraft 实机效果已验收。

本次构建与交付证据位于 `work/independent-glow-7.5.17-20261018`，包含上一版 EXE 备份。升级后完全退出旧 Minecraft 进程，再启动并重新注入。
