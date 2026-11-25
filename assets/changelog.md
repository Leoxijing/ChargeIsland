# 更新日记

「充电进度岛」APP（ChargeIsland）的版本更新记录。每次发版同步更新本文件。

APP 内可通过「检查更新」获取最新版本。

---

## v1.6 (versionCode 7)
- **通知形态完全对齐 VideoSplitter（已验证可上背屏）**：移除 `miui.focus.param`/`miui.focus.pics` 厂商私有参数（VideoSplitter 源码明确"不写入任何厂商私有 extras"，带厂商参数的通知疑似不参与实时动态→背屏映射）；补上缺失的 `setCategory(CATEGORY_PROGRESS)` 与 `setTicker`；promoted extras 改为 API 36+ 无条件写入（原 `ongoing` 附加条件导致首条前台通知不带提升请求）
- **更新频率对齐**：轮询 3 秒 → 1 秒，移除"内容未变不刷新"去重，每次轮询都 notify，让系统持续重新评估提升资格
- **通知渠道对齐**：新渠道 `charging_island_v3`（IMPORTANCE_LOW，同 VideoSplitter "split" 渠道参数）
- **校验通过**：编译成功、aapt 版本号 7/1.6

## v1.5 (versionCode 6)
- **应用内更新全面对齐 VideoSplitter**：检查源改为 Release 上的 `version.json` 固定地址（启动静默自动检查 + 手动检查）；发现新版后 APP 内前台服务下载（通知栏实时进度、切后台不中断）、体积/版本/可解析三重校验、缓存复用、PackageInstaller 官方安装会话一键安装（含未知应用授权引导）
- **新增更新日志页**：三层数据降级（本地缓存 → 内置 assets → 远端 CHANGELOG.md raw/CDN 双源），完整版本历史离线可读、联网自动刷新
- **下载进度通知接入实时动态**：更新下载通知在 API 36+ 同步请求 Promoted 资格
- **校验通过**：编译成功、aapt 版本号 6/1.5

## v1.4 (versionCode 5)
- **修复 Promoted 提升请求写入方式**：原反射调用 `Notification.Builder.setRequestPromotedOngoing` 因 framework 无此方法静默失败，提升请求从未写入——实时动态无法上背屏的根因；改为官方要求的 extras key `android.requestPromotedOngoing`（API 36+，字面值同 androidx `NotificationCompat.EXTRA_REQUEST_PROMOTED_ONGOING`），与 VideoSplitter 8.3 已验证实现一致
- **校验通过**：编译成功、dex 含 extras key、aapt 版本号 5/1.4

## v1.3 (versionCode 4)
- **背屏/锁屏可见性修复**：通知渠道与通知设为 `VISIBILITY_PUBLIC`（新渠道 `charging_island_v2`），充电进度在锁屏、息屏、背屏完整显示；配合系统「设置 → 妙享背屏 → 背屏通知」开关
- **新增联网检查更新**：查询 GitHub Releases，发现新版跳转下载
- **校验通过**：编译成功、aapt 版本号 4/1.3

## v1.2 (versionCode 3)
- **实时动态全套 API**：`hasPromotableCharacteristics` 资格检查、`FLAG_PROMOTED_ONGOING` 提升状态跟踪、`canPostPromotedNotifications` 开关检测、一键跳转系统设置、主界面状态展示
- **校验通过**：编译成功、aapt 版本号 3/1.2

## v1.1 (versionCode 2)
- **接入 Android 16 Promoted Notifications（推广常驻通知）**：声明 `POST_PROMOTED_NOTIFICATIONS` 权限，请求提升为实时动态
- **校验通过**：编译成功、aapt 版本号 2/1.1

## v1.0 (versionCode 1)
- **首版**：充电进度实时监控（电量/功率/温度/预计充满）、电量充电曲线、小米超级岛接入（miui.focus.param）、常驻前台通知
- **校验通过**：编译成功、aapt 版本号 1/1.0
