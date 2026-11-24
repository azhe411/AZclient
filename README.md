# AZclient — Minecraft 热注入反作弊测试客户端

AZclient 是面向 Minecraft 服主、反作弊开发者和客户端兼容性研究者的 Windows 测试工具。它通过 Java Attach API 在游戏已经运行时将 Agent 动态加载进 Minecraft JVM，无需修改游戏文件，也不要求把客户端预装成 Forge/Fabric 模组。

项目用于在自建服务器或获得明确授权的环境中复现并观察移动、方块交互、战斗时序、实体渲染以及不同游戏版本之间的行为差异，帮助定位反作弊误报、协议兼容和客户端表现问题。游戏内提供可视化面板、状态组件和一组可独立配置的测试模块。

当前仓库是 **8.0.0 Offline OSS**：启动、进程检测、热注入、游戏内配置和本地日志均不需要 AZclient 账号、邀请码、订阅服务或外部网络。

## 它如何工作

1. 注入器在本机查找正在运行的 Minecraft Java 进程；
2. 用户明确选择目标进程并执行注入；
3. 注入器通过 Java Attach API 加载 `lite-agent.jar`；
4. Agent 在目标 JVM 内安装兼容性 Hook，游戏内按右 Shift 打开测试面板；
5. 日志、配置与诊断信息保存在本机。

它不是服务端插件，也不是独立 Minecraft 客户端；仓库不包含 Minecraft、Forge、Fabric、Mojang 映射或游戏资产。

## 与原商业版本的区别

- 启动后直接进入进程与注入页面。
- 注入前不进行账号或订阅校验，Agent 也不换取远程票据。
- 在线短码、云端配置和账号存档被停用；“分享”改为复制完整配置串，可在聊天工具中自行发送。
- 构建产物不混淆、不加壳，方便审计和复现。
- 浏览器界面只连接本机随机端口 `127.0.0.1`。项目自身不包含外部 HTTP/HTTPS 传输类。

源码仍保留少量旧配置格式与错误文案的兼容解析；离线版的认证地址固定为空，远程提交入口会直接拒绝调用，构建脚本也会检查认证包未进入产物。

Minecraft 自身及玩家连接的服务器仍可能联网；“离线”只描述 AZclient 的账号、遥测、更新和配置服务。

## 支持范围

- Minecraft 1.8.9：主要支持分支。
- Minecraft 1.12.2：主要支持分支。
- Minecraft 1.20.1：仍是实验性适配，功能覆盖不等同于旧版。

## 构建

需要 Windows、JDK 8 和系统自带的 .NET Framework C# 编译器。

```powershell
pwsh -NoProfile -File .\build-open-source.ps1 `
  -JavaHome 'C:\path\to\jdk8\bin'
```

正常的完整 JDK 8 会提供 `lib/tools.jar`，脚本会从中提取 Java 8 Attach 实现。若使用裁剪过的工具链，可通过 `-AttachRuntime <目录>` 指向已经从同一 JDK 提取的 `com/sun/tools/attach`、`sun/tools/attach`、`sun/jvmstat` 与 SPI 文件；脚本会检查其完整性。

输出位于 `build-oss/AZclient-8.0.0-offline-oss.exe`。脚本会：

1. 编译 Agent 与 Injector；
2. 把 ASM、Java 8 Attach 实现和本地 Web UI 打进两个 JAR；
3. 验证产物中没有 `com/lite/auth` 网络认证类；
4. 生成内嵌 JAR 的单文件 EXE 并执行资源自检。

本仓库不提交 JDK、Minecraft、Forge、私钥、账号数据库或服务器配置。

## 使用

1. 启动受支持的 Minecraft 客户端并进入游戏。
2. 双击 `AZclient-8.0.0-offline-oss.exe`。
3. 选择检测到的 Minecraft 进程并点击注入。
4. 进入世界后按右 Shift 打开游戏内界面。

完全更新 Agent 时应先退出旧 Minecraft 进程。热更新桌面 EXE 不会替换已注入 JVM 中的旧类。

## 发布方案

建议每个版本只发布两个附件：

- `AZclient-x.y.z-offline-oss.exe`：可直接运行的离线二进制；
- `AZclient-x.y.z-source.zip`：与该二进制对应的源码快照、许可证和构建脚本。

发布页附上两个文件的 SHA-256。源码标签与 EXE 版本保持一致；任何第三方构建应改用自己的版本后缀，避免与官方构建混淆。

## 许可证与边界

项目代码按 [GNU GPL v3](LICENSE) 发布。第三方组件见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。Minecraft、Forge、Mojang 映射及其他游戏资产不属于本项目，也不随发布包分发。

本项目只用于自建服务器、单机存档、兼容性研究与获得明确许可的测试环境。维护者不为未经授权的服务器使用提供规避检测或对抗支持。
