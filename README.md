# TypeBridge-build

TypeBridge（手机当电脑/电视遥控）一键构建包仓库（**私人仓库**）。

| 子目录 | 内容 | 构建产物 |
|---|---|---|
| `win/` | Windows 电脑版（手机打字/语音注入 + 鼠标触摸板） | `TypeBridge.exe` |
| `tv/` | 安卓电视输入法版 | `TypeBridgeTV.apk` |

## 云构建（GitHub Actions）

两个工作流已配置，**不需要本地环境**，全部在 GitHub 云端完成：

| 工作流 | 触发方式 | 产物 |
|---|---|---|
| `Build Win exe` | 手动 `Run workflow`，或推送 `v*` tag | `TypeBridge.exe` |
| `Build TV APK` | 手动 `Run workflow`，或推送 `v*` tag | `TypeBridgeTV.apk` |

### 方式一：手动构建（产物同时自动发布到 Releases）

仓库页 → **Actions** → 选 `Build Win exe` 或 `Build TV APK` → **Run workflow**。
构建完成后：
- 本次运行页底部 **Artifacts** 可下载 `TypeBridge.exe` / `TypeBridgeTV.apk`；
- 同时自动发布到 **Releases** 的 `win-latest` / `tv-latest`（每次覆盖更新为最新构建，不刷屏）。

### 方式二：打 tag 自动构建并发布到 Releases（正式发布时用）

```bash
git tag win-v0.4.10 && git push origin win-v0.4.10     # 构建电脑版并发布
git tag tv-v0.6.53 && git push origin tv-v0.6.53       # 构建手机版并发布
```

推送 tag 后自动构建，并把产物上传到 **Releases** 对应 tag（页首 Releases 标签里下载）。

## 本地构建（可选）

两个子目录都有 `build.sh`，在 Linux 上直接跑（电脑版需要 mono，电视版需要 JDK8 + Android SDK build-tools 30.0.3 / android-30）：

```bash
bash win/build.sh   # 产物 win/build/TypeBridge.exe
bash tv/build.sh    # 产物 tv/TypeBridgeTV.apk
```

## 说明

- TV 版签名使用仓库内 `tv/tb.keystore`（密码见 `tv/build.sh`），云端与本地构建的 APK 签名一致。
- 每次构建脚本/源码更新后：`git add -A && git commit -m "..." && git push` 即可，云端下次构建自动用最新代码。
