# 积分小屋 · PointsSystem

面向家庭的安卓积分应用。家长记录孩子的努力、审批申报和兑现奖励；孩子查看积分、申报项目和兑换奖励。作业助手只提供 AI 检查建议，评价与积分决定由家长确认。

当前安卓版本：**0.2.0**。

## 功能

- **账号与家庭**：家长邮箱注册、邮件验证码、密码找回；家长创建孩子账号；同一孩子可关联多位家长，同一家长可关联多个孩子。
- **积分**：正数／负数项目、次数和有效期、孩子申报、家长审批和主动发放。扣分延迟 30 分钟生效，可取消；已生效操作可撤销并保留流水。
- **奖励**：普通奖励兑换、积分扣除、家长提供与孩子收货确认，保留兑换凭据。
- **作业助手**：家长上传照片、AI 检查与聊天追问；家长选择“好／一般／差”，核对后决定是否发放积分。支持 DeepSeek API 设置和在线刷新模型。
- **本机应用限制**：家长在孩子手机设置本机密码和受限应用；孩子兑换独立分钟余额，家长批准后由绑定手机领取。仅受限应用处于前台、屏幕解锁时扣时；不记录或上传应用使用历史。
- **界面**：绿色小屋图标、圆角卡片、统一弹窗，适配系统安全区域、窄屏、大字体和横屏。

普通安装的无障碍权限和基础设备管理员不能保证防卸载；系统级保护需要兼容设备上的 Device Owner。本机家长密码只保护应用内部操作。真实荣耀手机兼容性和 Device Owner 分支尚未验证，详见[本机应用限制说明](deploy/本机应用限制使用说明.md)。

## 工程结构

| 目录 | 用途 |
| --- | --- |
| `android/` | Kotlin + Jetpack Compose 客户端，Gradle 9.6 / AGP 9.4 |
| `backend/` | Spring Boot 4.1.1 API，Java 17，JDBC + Flyway，Maven |
| `deploy/linux/` | systemd、Nginx、HTTPS、备份恢复和升级脚本 |
| `deploy/` | 打包脚本、部署和使用文档 |

后端使用单实例 H2 文件数据库。AI 通过外部服务调用，无需在服务器上运行大模型。

## 开发环境

- Windows PowerShell、Java 17、Maven 3.9。
- Android Studio、Android SDK 37，模拟器或 Android 6.0 / API 23 及以上设备。
- Gradle Wrapper 已包含在仓库中；Gradle JVM 配置见 `android/gradle/gradle-daemon-jvm.properties`，首次同步可能需要联网获取对应 JDK 和依赖。

脚本保留原开发环境的默认工具路径，可通过参数指定自己的安装位置。下面的示例假定 `mvn.cmd` 已在 `PATH` 中。

## 本地启动

### 后端

在项目根目录运行：

```powershell
cd backend
.\run-local.ps1 -MavenExecutable mvn.cmd -JavaSocketDirectory C:\Temp\points-home-java
```

本地服务默认监听 `127.0.0.1:8080`，健康接口为 `http://127.0.0.1:8080/api/v1/health`。

本地脚本默认在控制台显示注册和密码重置验证码。首次启动自动生成验证码签名密钥和 AI 加密主密钥，数据库、密钥和作业照片保存在 `backend/data/`；这些内容已被 Git 排除。

如需通过 QQ 邮箱发送真实验证码，先在 QQ 邮箱设置中开启 SMTP 并生成授权码，停止本地服务，再运行：

```powershell
.\run-qq-smtp.ps1 -MavenExecutable mvn.cmd -JavaSocketDirectory C:\Temp\points-home-java
```

脚本交互输入发件邮箱和授权码，授权码不会回显或写入源码。发件邮箱使用 QQ 不影响收件人使用 QQ、163 等其他邮箱。

### 安卓客户端

1. 在 Android Studio 中打开 `android/`，等待 Gradle 同步。
2. 选择模拟器或手机，运行 `app` 的 debug 版本。
3. debug 版默认访问 `http://10.0.2.2:8080`，这是安卓模拟器访问开发电脑的地址。
4. 真机局域网调试时，在启动后端前设置 `$env:SERVER_ADDRESS='0.0.0.0'`，并把 App 的服务器地址改为 `http://电脑的局域网IP:8080`。HTTP 只用于 debug 版调试。

使用顺序：家长注册 → 创建孩子账号 → 添加积分项目／奖励 → 孩子登录申报或兑换 → 家长审批。更多说明见[本机应用限制与时间奖励](deploy/本机应用限制使用说明.md)。

## AI 与作业助手

家长在 ☰ → **AI 设置** 中输入自己的 DeepSeek API 密钥，联网刷新模型并保存选择。作业照片需要服务商实际支持图片输入的模型，纯文本模型无法检查照片；模型列表可刷新并不代表其中所有模型都能识别图片。当前未接入 OpenCode Go。

家长在 **作业助手** 中选择孩子，上传 1～3 张清晰照片，并可补充答案或要求。AI 给出检查建议后可以继续文字追问，最多 5 次。家长核对原图后选择“好／一般／差”，确认关联积分项目；AI 不自动发放或扣除积分。

API 密钥在后端加密保存，读取设置不会返回密钥。真实分析和追问可能产生服务商费用。已有测试使用模拟模型响应，模型实际检查质量仍需使用自己的 API 密钥和作业照片验证。

## 配置自己的服务器地址

release 版必须使用 HTTPS。复制配置模板，填写自己的后端地址：

```powershell
Copy-Item android\local-config.properties.example android\local-config.properties
```

编辑 `android/local-config.properties`：

```properties
releaseApiUrl=https://api.example.com
```

配置优先级：Gradle 参数 `-PpointsHomeReleaseApiUrl=...` → 环境变量 `POINTS_HOME_RELEASE_API_URL` → 本机配置文件 → 示例地址。`api.example.com` 只是占位地址，仓库不包含开发者的实际服务器配置。

`android/local.properties` 由 Android Studio 配置 SDK 路径，不应提交。服务器配置、数据库、签名证书和真实密钥也不在仓库中。

## 构建与验证

```powershell
# 后端
cd backend
mvn test
mvn package

# Android
cd ..\android
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintRelease
```

调试 APK 位于 `android/app/build/outputs/apk/debug/`。签名 release 包由 `deploy/build-internal-apk.ps1` 构建，产物保存在被 Git 排除的 `deploy/dist/`，构建方法见[部署说明](deploy/README.md)。

[0.2.0 验证记录](deploy/验证记录-0.2.0.md)包含已完成的自动测试、模拟器流程和仍待验证的真机行为。

## 部署与备份

生产服务使用 Spring Boot + systemd + Nginx HTTPS：后端仅监听本机 `18080`，对外使用 `443`。部署、升级、邮件、证书续期及备份恢复见[部署文档](deploy/README.md)。

备份必须包含数据库、作业照片、验证码签名密钥、AI 加密主密钥和后端环境配置。签名证书与签名配置必须一起私密保管，后续更新 APK 使用相同签名。

后续计划以真机验证、实际家庭数据恢复检查为先；电话手表适配尚未实施。
