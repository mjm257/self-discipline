# 每日自律 TODO 实施计划

依据 ../specs/2026-11-20-daily-todo-design.md，在 GPL 桌面底座上交付离线 Kotlin、Compose Material 3、Room 和 DataStore 应用。新源码位于 app/src/main/java/dev/daily/todo，包名 dev.daily.todo。

- [x] 数据：Store.kt 暴露 Room、DataStore 流、按日查询、原子提交/冷却、PIN 慢哈希、清空和本地导出。验证空白字符、重复提交和暂停。
- [x] 守护：GuardService.kt、OverlayManager.kt 实现窗口状态会话和按目标保留草稿。验证冷却 0、切换目标、来电/未知窗口、权限撤销。
- [x] 界面：MainActivity.kt 与 Compose 页面实现引导、今日、历史、规则编辑、应用抽屉、权限设置和桌面启动前提问。验证旋转、Home、输入法和权限页返回。
- [x] 集成：注册新 Home 和守护服务，复用 LauncherApps 路径，最小 SDK 26，目标 SDK 37，清理最终 Manifest；Gradle 测试、assembleDebug 和 APK 检查。README 记录真机步骤与实际测试范围。
