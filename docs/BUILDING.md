# 构建与签名

## 环境

JDK 17、Android SDK Platform 36、Build Tools 36.0.0。通过 Android Studio SDK Manager 或官方 command-line tools 安装 SDK；设置 `ANDROID_HOME`，或在不入库的 `local.properties` 中填写 `sdk.dir`。Gradle Wrapper 固定版本与分发包 SHA-256，首次运行需要网络。

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:assembleRelease
```

Windows 使用 `./gradlew.bat`。Debug 使用本机自动生成的开发签名；没有 Release 配置时输出 `app-release-unsigned.apk`，unsigned 文件不能直接安装。仓库没有团队发布私钥或可供公众覆盖安装的统一签名。

## 本机 Release 签名

自行通过 Android Studio 或 `keytool` 创建 PKCS12 签名文件并妥善备份。不要在命令历史中写明文密码。

1. 建立不入库的 `.signing/` 目录。
2. 将 `docs/signing.properties.example` 复制为 `.signing/signing.properties`，填入自己的文件名、别名和密码；配置文件本身是明文秘密，不得提交或共享。
3. `storeFile` 相对于该配置文件所在目录。构建只读取现有文件，不创建或更换签名。

```sh
./gradlew -Pwordmatch.requireReleaseSigning=true :app:assembleRelease
```

要求签名但配置缺失时构建会失败，不降级为 Debug 签名。已有其他证书签名的同包名应用通常不能直接覆盖；应用数据保护与迁移需自行处理。

可以通过 `-Pwordmatch.signingPropertiesFile=relative/path/signing.properties` 指定其他私有位置。CI 不设置此值，也不持有发布密钥。

## 网络与镜像

默认使用 Google Maven、Maven Central 和 Gradle Plugin Portal。需要阿里云镜像时显式添加 `-Pwordmatch.useMirror=true`；这是第三方下载源选择，应自行评估信任与可用性。不得关闭 TLS 校验来解决下载问题。代理配置应保存在机器自己的用户级设置，不能提交凭据或个人路径。

## 验证

测试覆盖配词、双向组句、多题型解析、答案记忆、恢复及 AI 协议与传输边界；无障碍操作、屏幕共享、OEM 行为和未适配题面仍需实际设备验证。CI 默认执行单元测试、Debug 构建和无密钥 Release 构建，不发布二进制。首次运行 CI 可能受 SDK 下载、网络或 GitHub Actions 额度影响。
