# ChargeIsland · 充电进度岛

面向小米澎湃 OS（HyperOS）的充电进度显示 APP，适配 Xiaomi 18 Pro Max。实时展示电量、充电功率、电池温度与预计充满时间，并接入**小米超级岛**与 **Android 16 实时动态（Live Updates / Promoted Notifications）**，充电进度同步展示于超级岛、通知栏、锁屏、息屏与背屏。

## 功能

- **充电监控前台服务**：电量、充电状态、功率（电压 × 电流估算）、电池温度、预计充满时间
- **电量充电曲线**：进程内采样绘制
- **小米超级岛**：通过 `miui.focus.param` 岛通知参数上岛（摘要态 / 展开态 / 焦点通知卡片）
- **Android 16 实时动态**：API 36+ 写入官方 extras key `android.requestPromotedOngoing` 请求提升，`hasPromotableCharacteristics` / `FLAG_PROMOTED_ONGOING` / `canPostPromotedNotifications` 全套状态诊断，一键跳转系统设置
- **锁屏 / 息屏 / 背屏展示**：通知渠道与通知均设为 `VISIBILITY_PUBLIC`；配合系统「设置 → 妙享背屏 → 背屏通知」开关同步显示
- **应用内更新（机制对齐 VideoSplitter）**：Release 上的 `version.json` 为检查源，启动静默自动检查 + 手动检查；前台服务后台下载（通知栏实时进度）、三重校验、缓存复用、PackageInstaller 官方安装会话一键安装
- **更新日志页**：三层数据降级（本地缓存 → 内置 assets → 远端 CHANGELOG.md raw/CDN 双源），完整版本历史离线可读、联网自动刷新

## 下载

前往 [Releases](https://github.com/Leoxijing/ChargeIsland/releases) 下载最新 `ChargeIsland.apk`，覆盖安装即可（签名一致，数据保留）。安装后 APP 会自动检查更新，也可在主界面「检查更新」手动检查、「更新日志」查看完整版本历史。

## 使用说明

1. 安装后打开 APP，授予通知权限
2. 点击「开始监控充电进度」，常驻通知与超级岛即开始刷新
3. **背屏显示**：设置 → 妙享背屏 → 背屏通知 → 开启「背屏通知」与「显示通知详情」
4. **实时动态**：点击「实时动态设置」确认系统开关已开启，状态行显示「已提升展示 ✓」即生效

## 构建

```bash
bash build.sh
```

依赖环境：JDK 17、Android SDK platform-34、build-tools 34.0.0（aapt2 / d8 / apksigner / zipalign）。

## 版本规则

- 版本号小数位仅保留个位数（0–9），到 9 进位整数位（1.9 → 2.0）
- 版本号到 9.9 时下一个版本为 1.0.0，9.9.9 后为 1.0.0.0，以此类推
- `versionCode` 每次改代码必须递增

| 版本 | versionCode | 说明 |
|------|-------------|------|
| 1.5  | 6           | 应用内更新对齐 VideoSplitter（version.json 检查/前台服务下载/一键安装）、更新日志页（三层数据） |
| 1.4  | 5           | 修复 Promoted 提升请求写入方式（extras key `android.requestPromotedOngoing`，API 36+），实时动态可上背屏 |
| 1.3  | 4           | 背屏/锁屏可见性修复（VISIBILITY_PUBLIC + 新通知渠道）、GitHub 联网检查更新 |
| 1.2  | 3           | 实时动态全套 API：资格检查、提升状态跟踪、设置入口、状态展示 |
| 1.1  | 2           | Promoted Notifications（Android 16 实时动态）通道 |
| 1.0  | 1           | 首版：充电进度 + 小米超级岛 |

## 署名

- 本仓库全部源码由作者原创编写。
- **第三方代码/库：无。** JSON 解析（`org.json`）与网络请求（`HttpURLConnection`）均为 Android 系统自带组件，不构成第三方依赖。
- 参考文档（仅参考 API 用法，未复制其代码）：
  - 小米超级岛开发指南 — https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131
  - Android 实时动态（Live Updates）通知 — https://developer.android.com/develop/ui/views/notifications/live-update
- **署名规则：引入任何第三方代码/库时，必须同步更新本署名区域，逐项列出来源与用途。**
