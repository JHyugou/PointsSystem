# 积分小屋部署与打包

本目录提供单实例家庭内测部署脚本。实际服务器地址、部署记录、环境配置、签名凭据和备份存放在本机私有目录，不随公开源码发布。

## 部署结构

| 项目 | 默认值 |
| --- | --- |
| 后端服务 | `points-home.service`，使用 `pointshome` 用户 |
| Java | Java 17，服务文件明确指定 `/usr/lib/jvm/jre-17-openjdk/bin/java` |
| 后端监听 | `127.0.0.1:18080` |
| 公网入口 | Nginx HTTPS `443`；`80` 用于证书验证 |
| 应用 JAR | `/opt/points-home/points-api.jar` |
| 数据与照片 | `/var/lib/points-home/data/` |
| 环境与密钥 | `/etc/points-home/backend.env`，仅 root 可读 |
| 备份 | `/var/backups/points-home/`，仅 root 可读 |

Linux 安装脚本面向使用 `dnf` 的 Alibaba Cloud Linux。其他发行版需要调整软件包安装和 Java 路径。部署前检查已有项目、监听端口、磁盘和内存，不要直接覆盖现有服务或 Nginx 主配置。

后端默认 JVM 堆上限 384 MiB，systemd 内存上限 850 MiB，适用于资源有限的家庭内测服务器；实际承载能力需按数据和访问量确认。H2 文件不能由两个后端实例同时使用。

## 1. 制作后端部署包

在项目根目录运行：

```powershell
.\deploy\build-bundle.ps1 -MavenExecutable mvn.cmd -JavaSocketDirectory C:\Temp\points-home-java
```

脚本运行后端测试并构建 JAR，生成 `deploy/dist/points-home-时间戳.tar.gz` 及 SHA-256 校验文件。它只打包 JAR、Linux 脚本和部署文档，不包含本地数据库、照片、SMTP 授权码或 AI 密钥。

脚本默认离线使用已有 Maven 缓存。新环境应先在 `backend/` 运行 `mvn package` 获取依赖，再打包。已有 JAR 可使用 `-SkipBuild`。

## 2. 首次部署

1. 上传并校验部署包，在新的工作目录解压。
2. 运行 `bash linux/inspect-server.sh` 检查系统、服务、资源和端口。
3. 确认目标目录和服务名可以使用后，运行 `sudo bash linux/install-backend.sh`。
4. 按提示输入 QQ 发件邮箱与 SMTP 授权码。脚本保存 root 专用的环境配置，生成或复用验证码签名密钥和 AI 加密主密钥。
5. 检查 `http://127.0.0.1:18080/api/v1/health`。后端应以 `pointshome` 用户运行并设置开机启动。

首次部署默认创建空数据库。如果需要迁移，先停止源后端并单独保存完整 `data/` 和匹配的密钥配置；不要用本地测试数据覆盖已有服务器数据。

## 3. 配置 HTTPS

公网入口需配置服务器安全组和主机防火墙：允许 HTTPS 的 `443`，以及证书验证的 `80`。后端 `18080` 保持仅本机监听。

仓库提供公网 IPv4 证书脚本：

```bash
sudo bash linux/configure-https.sh YOUR_PUBLIC_IPV4
```

将 `YOUR_PUBLIC_IPV4` 替换为自己的公网 IPv4。脚本检查 Nginx 是否加载 `conf.d/*.conf`，安装独立 Python / Certbot 环境，配置本应用站点并启用每 6 小时检查的续期定时器。已有 Nginx 配置应先检查并备份；`linux/nginx-base.conf` 是参考配置，不可直接覆盖正在承载其他项目的主配置。

公网 IP 证书有效期较短，必须保持续期任务正常运行。配置后运行正常 TLS 校验的健康检查和续期演练。也可以自行使用已配置域名与证书的 Nginx，转发 `/api/v1/` 到 `127.0.0.1:18080`。

Nginx 覆盖代理 IP 请求头，后端只信任本机代理。作业分析需上传照片，模板为此预留较长响应超时；这不会加快带宽或 AI 调用。

技术参考：[Let's Encrypt IP 证书](https://letsencrypt.org/2026/03/11/shorter-certs-certbot)、[Certbot 文档](https://eff-certbot.readthedocs.io/en/stable/using.html)。公开发布时应确认所在地区和平台的接入要求。

## 4. 构建签名安卓包

先复制 `android/local-config.properties.example` 为 `android/local-config.properties`，填写自己的 HTTPS API 地址。配置文件被 Git 排除；也可使用环境变量 `POINTS_HOME_RELEASE_API_URL`。

```powershell
.\deploy\build-internal-apk.ps1 -JavaHome 'C:\Java\jdk17' -AndroidSdk 'C:\Android\Sdk' -JavaSocketDirectory C:\Temp\points-home-java
```

替换工具路径为自己的安装位置。脚本首次生成 RSA 3072 位签名证书，后续复用；构建 debug / release，验证签名，并生成 `deploy/dist/points-home-internal-版本号.apk` 和校验文件。依赖缓存完成后可使用 `-Offline`。

以下文件必须一起私密备份，不能上传到公开仓库：

- `deploy/private/points-home-release.p12`
- `deploy/private/android-signing.properties`

签名更新必须沿用同一密钥。现有 debug 版与 release 版签名不同，不能直接覆盖；相同签名的 release 更新应覆盖安装以保留本地设置和时间余额。

## 5. 后端升级

数据库迁移由 Flyway 执行。0.2.0 的 V8 迁移增加时间奖励和设备绑定，保留原有账号、积分和普通奖励凭据。

```bash
sudo bash linux/upgrade-backend.sh /path/to/new/points-api.jar EXPECTED_SHA256
```

脚本校验 JAR、暂时使 HTTPS API 返回 503、制作包含旧 JAR、完整数据与配置的冷备份，然后安装新 JAR。健康检查和数据数量／余额对比通过后恢复外部访问；失败时恢复匹配的旧 JAR 与旧数据。

升级备份保留在 `/var/backups/points-home/upgrade-*`，含密钥，应私密保管。脚本面向本文约定的服务名和路径，执行前应核对实际部署。

## 6. 备份与恢复

默认每天北京时间约 03:30 进行备份，保留约 14 天。为保证 H2、照片与密钥一致，备份会优雅停止后端，复制完整数据和配置后恢复服务，再压缩。正在处理的 AI 请求可能延长停机等待。

备份包含 SMTP 授权码、AI 加密主密钥和验证码签名密钥，不能公开。定期将校验后的备份保存到服务器以外的私密存储。

验证恢复副本：

```bash
sudo bash /opt/points-home/verify-backup.sh /var/backups/points-home/具体备份文件.tar.gz
```

脚本使用独立恢复目录和本机 `18081` 端口，关闭真实邮件发送；结束后停止并清理副本，不覆盖正式数据。验证记录应区分空数据库演练和真实账号、照片、AI 密钥的完整恢复。

正式恢复时先校验归档，在新目录检查数据与配置；停止正式服务并保存当前数据后，再恢复匹配的 `data/` 与 `backend.env`，修复文件属主和权限，启动并核查。

## 日常查看

```bash
systemctl status points-home.service --no-pager
journalctl -u points-home.service -n 40 --no-pager
systemctl list-timers 'points-home-*' --no-pager
curl --fail http://127.0.0.1:18080/api/v1/health
sudo bash /opt/points-home/backup.sh
```

## Git 排除范围

`deploy/private/`、`deploy/dist/`、`backend/data/`、日志、构建输出、签名凭据、本机 SDK 和服务器地址配置均不提交。共享源码前应检查 `git status` 和待提交内容，真实验证码、令牌或用户作业不能进入 Git 历史。
