# NexusTerm Android

Android 上的 SSH / SFTP 终端客户端。Rust 核心（russh）+ Kotlin / Jetpack Compose 界面，
终端由 WebView 里的 xterm.js 渲染。

从桌面版 [NexusTerm](../NexusTerm)（Tauri）移植：`nexus-core` 是桌面版 `src-tauri` 的
fork，SSH 握手、认证、通道管理、SFTP 子系统这些真正难的部分原样保留，Android 这一侧只
重做了界面与平台适配。

> **独立性声明**：本项目所有代码均为独立实现，依据 SSH 公开标准（RFC 4251–4254）与开源
> 组件（russh / russh-sftp / xterm.js / SQLite）编写。开发过程中**未反编译任何第三方
> 商业软件**，也未参考其实现细节。多会话、SFTP、密钥认证属于 SSH 客户端的通用标准能力。

---

## 功能

### 连接与会话

| 能力 | 说明 |
|---|---|
| SSH 连接 | russh 纯 Rust 实现；密码与私钥两种认证 |
| 会话管理 | SQLite 持久化（nexus-core 的 `store`），增删改查 |
| 凭据存储 | 密码 / 私钥口令写进 Android `EncryptedSharedPreferences`，由 Keystore 托管；**从不**经过 JNI 进入 Rust，也从不落到 SQLite |
| 多连接 | 同一台主机可以开多条独立会话，各自拥有终端与滚动历史 |
| 断线自动重连 | 最多 10 次，2s 起逐次翻倍、上限 30s；网络恢复时立刻重试而不是等退避 |

### 终端

| 能力 | 说明 |
|---|---|
| 终端渲染 | xterm.js（WebView），5000 行滚动历史 |
| 多会话切换 | 双指下滑把终端缩成卡片，左右滑动选择，点按切入，上滑断开 |
| 会话保活 | 切走不销毁终端：WebView 由会话持有而非界面持有，后台会话的输出不会丢 |
| 扩展按键行 | Termux 风格两行，三页可切；粘滞 Ctrl / Alt 对软键盘输入同样生效 |
| 虚拟大窗口 | 菜单里可把远端 pty 宽度固定为 160 列，单指左右滑动平移视窗 |
| 尺寸同步 | 键盘弹出、按键行出现都会把真实 cols / rows 同步给远端 |
| 前台服务 | 常驻通知显示「N 个会话已连接」并列出各标签页，点击回到应用；最后一个会话断开后自行停止 |

### 手势

终端上几乎没有可见控件，操作全在手势里。会话列表右下角「?」打开的「操作说明」是同一份清单
（`ui/HelpDialog.kt`），改手势时两边要一起改。

**终端**

- 双指捏合 —— 缩放字体（8–28 号，松手才落盘）。**分档**而非连续跟手：双指间距每变化约 22dp（两指实际分开约 43dp）跳一号，每跳一次把参考间距重置到当前值，这点迟滞挡住了档位边界上的来回抖动
- 双指按住不动 **0.8 秒后抬起** —— 打开菜单：粘贴 / 清屏 / 滑至底部 / 字体还原 / 虚拟大窗口 / SFTP 文件管理 / 断开。0.8 秒到点只震动一下，意思是「现在抬手就是菜单」；菜单在按住的位置弹出。震动后手指再动起来就当没按过——按之前先停顿一下瞄准，接下来的捏合或上下拖动照常生效
- 双指上下拖动 —— 会话切换卡片：左右滑选择，点击进入，向上甩出断开该会话，向上拖回取消。屏幕顶部 12% 不响应，那是系统的快捷设置手势
- 单指上下滑动 —— 滚动历史，1.6 倍增益，松手带惯性滑动；远端开启鼠标跟踪或处于备用屏幕（vim、less、tmux）时整条手势交还给程序
- 单指左右滑动 —— 仅在虚拟大窗口下有效，横向平移

**文本选择**

- 单指长按 **0.8 秒** —— 选中手指下的词，震动 + 落点涟漪 + 选择手柄弹入；按住继续拖动可扩展，拖到屏幕上下边缘会逐行滚动
- 选中状态下单指拖动 —— 选区终点跟到手指所在位置（绝对定位，手指就是手柄），拖到上下边缘自动滚动
- 单指轻点 —— 结束选择并复制到剪贴板；该次点击被吞掉，不会同时落到终端上
- **代价**：选中期间单指不能滚动终端，先轻点结束（同时就完成了复制）再滚。选中状态下多指手势一律忽略，不会误触发捏合或卡片切换

> 这里原本是三指滑动移动选区终点。MIUI 的「三指下滑截屏」在第三根手指落下的瞬间就把触摸流抢走，这条手势在多数小米机上根本走不通，于是换成了上面的单指绝对拖动。

**常用键栏**

- 闲置 **6 秒**（终端无触摸无输入、键栏无触摸、无修饰键待用、软键盘未弹出）自动滑走，终端随之收回那几行
- **软键盘弹出时键栏常驻**：键盘是「我正要打字」最明确的信号，所以它不是会过期的「活动」而是一条常设请求——闲置计时在键盘期间整个挂起，键盘收起后才从零重新计时
- 在缓冲区底部继续单指上推约 60px（过度滚动）把它召回；滚进历史时也会自动隐藏
- 三页：常用键（`ESC / — HOME ↑ END PGUP` / `⇥ CTRL ALT ← ↓ → PGDN`）、符号（``| ~ ` \ $ & * { } [ ] < > ;``）、功能键（F1–F12 + INS + DEL）。**在键栏上单指左右滑动**切页，底部圆点指示当前页。点按仍然是打字：横向滑开约 14dp 才判定为翻页，判定后该次按键被取消（键帽熄灭、不发送）
- CTRL / ALT 点一下待用（键帽反色），作用于下一个按键 —— 包括软键盘打出的字符 —— 再点一下取消
- 起点落在键栏上的触摸不会被终端手势抢走

### 界面

- 绿色主题，取自图标配色（`ui/theme/Theme.kt` 的 `NexusGreen` / `NexusMint` / `NexusDeep`），
  深浅两套，键栏与页面内的强调色同源
- 自适应启动图标（background / foreground / monochrome 三层）
- 会话列表右下角「?」→「操作说明」，列出上面全部手势

### 文件

| 能力 | 说明 |
|---|---|
| SFTP | 列目录、上传、下载、新建目录、重命名、删除、读取；传输带进度与取消 |
| 连接复用 | 同一会话已有终端连接时，SFTP 直接在那条连接上开子系统，省掉一次握手与一次验证码 |
| 本地浏览 | 走 Storage Access Framework（`OpenDocumentTree` + `DocumentFile`），不需要存储权限；授权过的目录会被记住 |

---

## 尚未实现

和桌面版的差距，按影响排序。都是真实缺口，不是「快好了」：

| 缺口 | 现状 |
|---|---|
| 二次验证（keyboard-interactive / 2FA） | `promptUserForAnswer` 直接返回空串，等于取消。连 2FA 主机会快速失败而不是挂住，但**无法登录** |
| 主机指纹持久化 | `KnownHosts` 是进程内 `HashMap`，重启即忘。首次连接信任（TOFU）只在单次运行内有效 |
| 重连后的上下文恢复 | 桌面版会探测 cwd / tmux / docker 并在重连后还原。`probe()` 已在 Rust 里，但没导出到 UniFFI，界面也还没做 |
| 远端资源监控 | 同上，`stats()` 存在但未导出 |
| ZMODEM（rz / sz） | 未移植 |
| 端口转发、密钥管理界面、AI 助手 | 未移植 |

`eof` 关闭的连接不会自动重连（只有 `lost` 会）。这是刻意取舍：把 `eof` 也当成可重连，
会让那些不返回 exit status 的服务器上再也输不了 `exit`。

---

## 构建

### 前置

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | **21** | Gradle 8.7 最高支持 Java 21，不要用 22+ |
| Android SDK | API 34 | `compileSdk` / `targetSdk` 34，`minSdk` 26 |
| Android NDK | 26.x | 交叉编译 nexus-core |
| Rust | ≥ 1.77.2 | 另需 `cargo install cargo-ndk` |
| Python | 3 | rust-android-gradle 的链接器包装脚本要用（见下） |

环境变量：`ANDROID_HOME`、`ANDROID_NDK_HOME`、`JAVA_HOME`。

### 命令

```bash
gradle :app:assembleDebug      # 产物 app/build/outputs/apk/debug/app-debug.apk
gradle :app:installDebug
```

默认交叉编译 `arm64-v8a` 与 `x86_64`（`gradle.properties` 的 `nexusterm.rustTargets`）。
构建链路是 `cargoBuild` → `generateUniffiBindings` → `compileDebugKotlin`：Rust 先产出
`.so`，再由 nexus-core 自带的 `uniffi-bindgen` 从宿主架构的 `.so` 里读元数据生成 Kotlin
绑定。

### 可选：跨容器 / 跨机器保持签名一致

调试签名默认由 AGP 生成在 `~/.android/`。如果开发环境是会被重建的容器，重建后签名就变了，
`adb install -r` 会以 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 失败，只能卸载重装——连同用户
保存的会话与凭据一起没掉。把 keystore 放在环境之外并指向它即可避免：

```bash
export NEXUSTERM_DEBUG_KEYSTORE=/path/to/debug.keystore
# 或者让它跟着工具链走：
export NEXUSTERM_ANDROID_TOOLCHAIN=/path/to/toolchain   # 取 $TOOLCHAIN/debug.keystore
```

两个变量都没设时此配置不生效，普通开发机不受影响。

### 构建上的坑

| 现象 | 原因 |
|---|---|
| `linker-wrapper.sh: python: command not found` | 插件默认调 `python`，Debian 12+ 只有 `python3`。已在 `app/build.gradle.kts` 里用 `pythonCommand = "python3"` 固定。**只在 nexus-core 真的重新链接时才会触发**，缓存的 `.so` 会把它藏起来 |
| 运行期 `UniFFI API checksum mismatch` 或 `java.nio.BufferUnderflowException` | APK 里的 `.so` 比 UniFFI 绑定旧。`mergeXJniLibFolders` 会在 cargo 重建前就快照 jniLibs，插件没声明这个依赖。已加 `dependsOn("cargoBuild")` |

---

## 目录

```
app/src/main/
├── java/com/nexusterm/android/
│   ├── MainActivity.kt          导航图 · 会话生命周期 · 自动重连调度
│   ├── SessionService.kt       前台服务 · 「N 个会话已连接」常驻通知
│   ├── ssh/
│   │   ├── SshClient.kt         connect / write / resize / disconnect 的协程封装
│   │   ├── SftpClient.kt        SFTP 调用封装
│   │   ├── SessionStore.kt      会话 CRUD（转调 nexus-core）
│   │   └── SecretsStore.kt      EncryptedSharedPreferences，密码与口令只存这里
│   └── ui/
│       ├── SessionListScreen.kt 会话列表
│       ├── ConnectScreen.kt     新建 / 编辑会话
│       ├── TerminalScreen.kt    WebView 宿主 + TerminalHandle（终端不归 Compose 所有）
│       ├── TerminalTabsPager.kt 卡片切换器 + 终端手势识别器 + 终端菜单
│       ├── TerminalKeyBar.kt    扩展按键行（三页）+ 粘滞修饰键 + 自动隐藏
│       ├── HelpDialog.kt        操作说明（手势清单，与本文同步）
│       └── SftpScreen.kt        本地（SAF）/ 远端双标签文件浏览
└── assets/terminal/index.html   xterm.js 宿主页 + Kotlin 双向桥

nexus-core/src/
├── lib.rs                       UniFFI 导出层
├── ssh/                         russh Handler · 会话池 · 旁路 exec 通道
├── sftp/                        SFTP 子系统（含终端连接复用）
├── store/                       SQLite 会话仓库
├── keys/                        OpenSSH / PuTTY ppk 私钥解析
└── errors.rs                    错误 → 中文可读提示
```

## 架构

```
┌── UI · Kotlin / Jetpack Compose ─────────────────────────────────┐
│  MainActivity      导航 · LiveSession 生命周期 · 重连退避         │
│  TerminalTabsPager 卡片切换器（双指手势、始终按全屏测量）          │
│  TerminalKeyBar    扩展按键（经页面注入，与软键盘同一条输入路径）  │
│  WebView + xterm.js  终端渲染，由 LiveSession 持有而非 Compose    │
└──────────────────────────────┬───────────────────────────────────┘
                     UniFFI · JNI · callback interface
┌── Core · Rust / tokio ───────┴───────────────────────────────────┐
│  ssh/    russh 握手 / 认证 / pty / 读写循环 · 已认证连接登记表     │
│  sftp/   russh-sftp · 复用终端连接 · 分片传输（进度 + 取消）       │
│  store/  rusqlite（bundled）                                      │
└──────────────────────────────────────────────────────────────────┘
```

三个值得知道的取舍：

**终端不归 Compose 所有。** WebView 由 `LiveSession` 持有，`createTerminal()` 在 composition
之外构造。否则离开标签页就会销毁 WebView，而 `evaluateJavascript` 对已销毁的 WebView 是
静默空操作——后台会话的输出会无声丢失，回来时只剩一个空终端。

**卡片切换器只缩放不重新测量。** `index.html` 把任何 resize 都转成发往远端的 window-change，
所以缩小卡片若靠改变测量尺寸实现，用户真正的 shell 会被 reflow 成卡片大小。卡片始终按全屏
测量，只用 `graphicsLayer` 做绘制期缩放。扩展按键行是唯一允许改变测量的东西——它确实占掉了
真实行数，但每次出现只改两次布局，滑动过程中不改。

**按键行的输入走页面，不直连 `SshClient`。** 否则「Ctrl 是否已按下」会同时存在于 Kotlin 和
JS 两处，必须跨异步桥保持一致，而那个不一致的窗口正好就是 Ctrl+C 被吞掉或发两次的地方。

## 许可

待定。代码中不含任何第三方专有实现。
