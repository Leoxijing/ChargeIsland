# ChargeIsland · 充电进度岛

面向小米澎湃 OS（HyperOS）的充电进度显示 APP，适配 Xiaomi 18 Pro Max。实时展示电量、充电功率、电池温度与预计充满时间，通知形态完全对齐 VideoSplitter（纯标准通知），接入 **Android 16 实时动态（Live Updates / Promoted Notifications）**，充电进度同步展示于通知栏、锁屏、息屏与背屏。

## 功能

- **充电监控前台服务**：电量、充电状态、功率（电压 × 电流估算）、电池温度、预计充满时间
- **电量充电曲线**：进程内采样绘制
- **后台保活**：插电（`ACTION_POWER_CONNECTED`）与开机（`BOOT_COMPLETED`）自动拉起监控服务，无需手动确认；电池优化豁免引导提升后台存活
- **主页现代化 UI**：深空渐变背景、圆角卡片、黄→橙渐变电量大字、渐变主按钮
- **全新图标**：APP 图标 = 深空径向渐变 + 黄色进度环 + 白色闪电（含 Android 13+ 主题图标）；通知闪电为斜切锐利造型
- **通知样式**：第一行=温度·功率（充电/未充电分别显示充电/耗电功率），第二行=状态·电量（`充电中 · 54%`），第三行=预计充满/耗尽时间 · 推测速度 · 准确度 + 进度条
- **智能预估**：最小二乘线性回归估算电量速率（充电 30min / 放电 60min 窗口），R² + 样本量 + 跨度分级准确度（高/中/低）；未充电显示预计耗尽时间
- **Android 16 实时动态**：API 36+ 写入官方 extras key `android.requestPromotedOngoing` 请求提升，`hasPromotableCharacteristics` / `FLAG_PROMOTED_ONGOING` / `canPostPromotedNotifications` 全套状态诊断，一键跳转系统设置
- **通知形态对齐 VideoSplitter**：纯标准通知（不写厂商私有 extras）、`CATEGORY_PROGRESS` + `BigTextStyle` + 进度条 + `VISIBILITY_PUBLIC`、1 秒高频刷新
- **锁屏 / 息屏 / 背屏展示**：通知渠道与通知均设为 `VISIBILITY_PUBLIC`；配合系统「设置 → 妙享背屏 → 背屏通知」开关同步显示
- **应用内更新（机制对齐 VideoSplitter）**：Release 上的 `version.json` 为检查源，启动静默自动检查 + 手动检查；前台服务后台下载（通知栏实时进度）、三重校验、缓存复用、PackageInstaller 官方安装会话一键安装
- **更新日志页**：三层数据降级（本地缓存 → 内置 assets → 远端 CHANGELOG.md raw/CDN 双源），完整版本历史离线可读、联网自动刷新

## 下载

前往 [Releases](https://github.com/Leoxijing/ChargeIsland/releases) 下载最新 `ChargeIsland.apk`，覆盖安装即可（签名一致，数据保留）。安装后 APP 会自动检查更新，也可在主界面「检查更新」手动检查、「更新日志」查看完整版本历史。

## 使用说明

1. 安装后打开 APP，授予通知权限
2. 点击「开始监控充电进度」，常驻通知即开始每秒刷新
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
| 2.7  | 18          | 状态栏图标改为动态温度文字（26°），随电池温度实时更新 |
| 2.6  | 17          | 通知图标 alpha=1 近透明，修复系统把全透明图标回退为 APP 图标的问题 |
| 2.5  | 16          | 通知图标恒透明：状态栏/通知栏均不显示闪电图标 |
| 2.4  | 15          | 未充电时通知图标改全透明（仅充电中显示闪电） |
| 2.3  | 14          | 未充电显示耗电功率；预估充满/耗尽改线性回归（含速度与准确度分级），放电样本独立采集 |
| 2.2  | 13          | 通知闪电图标改斜切锐利造型；全新 APP 图标（深空渐变 + 黄色进度环 + 白色闪电，含主题图标） |
| 2.1  | 12          | 通知第一行=「温度 · 功率」（`26℃ · 22.2W`），第二行=状态·电量 |
| 2.0  | 11          | 通知改参考图布局：标题=状态·电量、第二行=功率、第三行=预计充满时间；图标回闪电 |
| 1.9  | 10          | 通知样式：顶部=温度、闪电图标位=动态功率文字；详情行去重温度 |
| 1.8  | 9           | 修复更新安装包校验失败（1MB 体积下限误杀 46KB 自家 APK）与检查更新等待窗卡住 |
| 1.7  | 8           | 主页现代化 UI、后台保活（插电/开机自动启动 + 电池优化豁免）、通知样式改版（标题=功率、电量温度换位） |
| 1.6  | 7           | 通知形态完全对齐 VideoSplitter：移除厂商私有 extras、补 CATEGORY_PROGRESS/ticker、1 秒高频刷新、渠道 LOW |
| 1.5  | 6           | 应用内更新对齐 VideoSplitter（version.json 检查/前台服务下载/一键安装）、更新日志页（三层数据） |
| 1.4  | 5           | 修复 Promoted 提升请求写入方式（extras key `android.requestPromotedOngoing`，API 36+），实时动态可上背屏 |
| 1.3  | 4           | 背屏/锁屏可见性修复（VISIBILITY_PUBLIC + 新通知渠道）、GitHub 联网检查更新 |
| 1.2  | 3           | 实时动态全套 API：资格检查、提升状态跟踪、设置入口、状态展示 |
| 1.1  | 2           | Promoted Notifications（Android 16 实时动态）通道 |
| 1.0  | 1           | 首版：充电进度 + 小米超级岛 |

## 署名

- 本仓库全部源码由作者原创编写。
- **第三方代码/库：无。** JSON 解析（`org.json`）与网络请求（`HttpURLConnection`）均为 Android 系统自带组件，不构成第三方依赖。
- 参考来源（仅参考机制/API 用法，未复制其代码）：
  - 小米超级岛开发指南 — https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131 （v1.3 前的超级岛参数接入；v1.3 起已移除厂商私有参数）
  - Android 实时动态（Live Updates）通知 — https://developer.android.com/develop/ui/views/notifications/live-update
  - **VideoSplitter**（作者同账号项目）— 应用内更新机制（检查源/三重校验/缓存复用/安装会话）、通知形态（渠道参数/CATEGORY/ticker/高频刷新）、更新日志页结构的机制参考（v1.4–v1.6，曾反汇编其 APK 对比实现；本项目代码为原创重写）
  - androidx `NotificationCompat.EXTRA_REQUEST_PROMOTED_ONGOING` — 提升请求 extras key 字面值 `android.requestPromotedOngoing` 的协议来源（v1.4 起）
- **历史资源说明**：v1.0–v2.1 的通知/启动图标曾使用 Material Design `flash_on` 闪电路径（Apache-2.0 设计资源）；v2.2 起已全部替换为本项目自绘图标，当前代码无该路径残留。
- **署名规则：引入任何第三方代码/库时，必须同步更新本署名区域，逐项列出来源与用途。**
