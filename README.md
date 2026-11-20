# 每日自律 TODO

本分支保存 Android 桌面底座，供后续实现每日计划、反思记录和可暂停的应用守护。当前代码仍运行上游桌面，尚未交付 TODO 功能。

底座取自 [Olauncher v6.9.17](https://github.com/tanujnotes/Olauncher/tree/66712f74de4030dc364d573708bea51d24823b40)，保留 app、Gradle Wrapper 和构建配置。作者署名与说明见 [上游 README](docs/upstream/Olauncher-README.md)，许可证见 [GPL-3.0](docs/upstream/Olauncher-LICENSE)。

## 已确认的行为

- 用户选择本应用作为默认桌面后，系统进入 Home 即进入今日页。解锁后系统可能恢复之前使用的应用。
- 今日页在冷启动、按 Home、从其他应用返回、从其他页面切回时提问。旋转、弹出输入法、从系统权限页返回时不重复提问。
- 离开目标应用再进入且该应用冷却结束时，才触发下一次守护提问。
- 验收机型为 iQOO Z10 Turbo+（V2507A）。Android API 级别以连机读数为准。

上游 Manifest 仍含联网、全量包查询及设备管理声明。后续 TODO 版本需精简相关功能和权限，再按离线自律工具的要求验收。
