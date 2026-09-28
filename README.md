# QQMigE（QQMiGe）

> 基于 Root 权限的 QQ 小游戏代码查看与修改工具

QQMiGe 是一款运行在已 Root 安卓设备上的工具应用，用于浏览 QQ 小游戏（如各类 H5 小游戏）的本地代码目录，支持语法高亮的文本查看与编辑，并内置 AI 助手，可用自然语言描述需求，由模型自动定位并改写对应代码文件。

---

## 功能特性

- **Root 权限管理**：启动即申请 Root，校验是否为真正的 root（拒绝降级 shell），保证后续读写真实生效。
- **小游戏目录浏览**：列出 QQ 小游戏数据目录，支持逐级进入、刷新。
- **语法高亮编辑器**：查看/编辑 JS 等文本文件，带大文件保护（超长文件进入编辑前二次确认）。
- **项目备注与删除**：长按项目可设置备注名（仅改显示名，不动目录），或整目录删除（删除前二次确认）。
- **AI 辅助改码**：接入 OpenAI 兼容接口，AI 可调用工具读写、检索沙箱范围内的文件，自动完成代码修改。
- **AI 对话历史**：支持新建对话、查看历史对话、恢复历史上下文、单条删除与清空全部，会话本地持久化。
- **关于页**：展示作者信息、QQ 头像（实时拉取）、一键加好友/加群、以及本项目引用的全部开源仓库（可点击跳转）。

---

## 作者

| | 名字 | QQ |
|---|---|---|
| 作者 | 超绝小学生233 | 790399726 |
| 我的爱人 | 小小宣 | 3193583971 |

> 关于页里的「我的爱人」不是玩笑，她是作者的爱人，作者只是单纯想炫耀她。

**QQ 交流群：1040835997** —— 欢迎加群反馈问题、交流改码技巧。

---

## 技术栈

- 语言：Kotlin
- 最低支持：Android 7.0（API 24）
- 目标版本：Android 14（API 34）
- UI：Material 3 + ViewBinding + ConstraintLayout

---

## 引用的开源仓库

本项目仅依赖以下开源项目，点击可跳转对应仓库：

| 项目 | 用途 |
|---|---|
| [AndroidX](https://github.com/androidx/androidx) | core-ktx / appcompat / drawerlayout / recyclerview 等基础兼容库 |
| [ConstraintLayout](https://github.com/androidx/constraintlayout) | 约束布局 |
| [Material Components for Android](https://github.com/material-components/material-components-android) | Material 3 组件（卡片、按钮、输入框、底部弹窗等） |
| [Kotlin](https://github.com/JetBrains/kotlin) | 开发语言与标准库 |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 协程，用于将文件读写与网络请求切到后台线程 |

---

## 构建

```bash
# 环境要求：JDK 17、Android SDK（API 34）、Gradle 8.7+
./gradlew assembleDebug
```

产物位于 `app/build/outputs/apk/debug/`。

---

## 免责声明

本项目仅供学习与交流使用，请勿用于任何商业或非法用途。修改游戏代码可能违反对应游戏的服务条款，请自行承担相关风险。
