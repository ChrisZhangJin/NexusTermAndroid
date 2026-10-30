use parking_lot::Mutex;
use std::collections::HashMap;
use std::sync::Arc;

use russh::client::Handle;
use russh::{ChannelMsg, Disconnect};
use serde::Serialize;
use tokio::sync::mpsc;

use super::prompt::{AskAbort, AuthPrompt, AuthPromptField, AuthPrompts};
use super::{ClientHandler, KnownHosts};
use crate::errors;
use crate::listener::TerminalEventListener;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum SessionError {
    /// 内容已是完整的中文提示（含原始错误小字），故 Display 不再加前缀
    #[error("{0}")]
    Connect(String),
    #[error("{0}")]
    Auth(String),
    #[error("通道错误: {0}")]
    Channel(String),
    #[error("会话不存在: {0}")]
    NotFound(String),
    #[error("主机公钥指纹不匹配，疑似中间人攻击")]
    HostKeyMismatch,
}

// thiserror 派生 Display，这里再补一个统一的序列化形态给前端
impl Serialize for SessionError {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&self.to_string())
    }
}

/// 前端 → SSH 的输入指令
#[derive(Debug)]
pub enum SessionInput {
    Data(Vec<u8>),
    Resize {
        cols: u16,
        rows: u16,
        pix_w: u16,
        pix_h: u16,
    },
    Close,
}

#[derive(Debug, Clone, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ConnectRequest {
    pub host: String,
    pub port: Option<u16>,
    pub username: String,
    /// 登录凭据。SFTP 复用已认证连接时用不上，因此允许缺省；
    /// 真要独立拨号而又拿不到凭据时，由后端给出明确错误。
    #[serde(default)]
    pub auth: Option<AuthMethod>,
    #[serde(default = "default_cols")]
    pub cols: u16,
    #[serde(default = "default_rows")]
    pub rows: u16,
    /// TCP 连接超时（秒）。缺省为 [`errors::DEFAULT_CONNECT_TIMEOUT_SECS`]。
    #[serde(default)]
    pub connect_timeout_secs: Option<u32>,
    /// 旧服务器兼容开关。`None`（默认）= 自动，先按现代算法握手，
    /// KEX 协商失败时自动用 SHA-1 系列的旧算法重试一次；`Some(true)` =
    /// 一开始就带旧算法；`Some(false)` = 只用现代算法、绝不降级。
    #[serde(default)]
    pub legacy_crypto: Option<bool>,
    /// 这条连接属于哪个「会话配置」（侧栏里那一项，不是标签 id）。
    ///
    /// 一个配置可以开多个标签，SFTP 面板要复用其中任意一条活连接开子系统，
    /// 就靠这个键去登记表里找 —— 否则对着要 OTP 的服务器，每开一次文件面板
    /// 都得重新验证一次。
    #[serde(default)]
    pub config_id: Option<String>,
    /// 是否允许在这条连接上开旁路通道（`probe` 探测 cwd/tmux、`stats` 采资源）。
    ///
    /// 默认允许。企业堡垒机常把一条 SSH 连接当成「一条终端」，收到第二个
    /// `CHANNEL_OPEN` 会直接断开客户端 —— 表现为「连上几秒就掉线」。
    /// 这类会话把这里设为 `false`，状态栏与上下文恢复相应退化（不影响终端本身）。
    #[serde(default)]
    pub sidecar: Option<bool>,
}

/// 连接成功后的协商结果，回给前端以便提示「本次用了旧算法」。
#[derive(Debug, Clone, Default, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConnectOutcome {
    /// 是否协商到了 SHA-1 系列的旧密钥交换算法
    pub legacy_crypto: bool,
}

fn default_cols() -> u16 {
    120
}
fn default_rows() -> u16 {
    32
}

#[derive(Debug, Clone, serde::Deserialize, uniffi::Enum)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum AuthMethod {
    Password { password: String },
    PrivateKey { path: String, passphrase: Option<String> },
    Agent,
}

/// 发送队列的容量（按消息条数）。
///
/// 必须是**有界**的：终端输入的每条消息只有几十字节，而 ZMODEM 上传是
/// 256KB 一片 —— 16 条约等于 4MB 峰值。用无界队列时，前端读盘速度远快于
/// 网络，600MB 的文件会整个堆在后端内存里（实测 400MB+），进度条冲到 99%
/// 之后看起来像卡死，其实是在等网络把积压发完。
const INPUT_QUEUE: usize = 16;

/// 输出投递队列的容量（按消息条数）。见 [`spawn_event_delivery`]。
///
/// **不要照抄 [`INPUT_QUEUE`]**：那是输入方向的，每条几十字节、一次 ZMODEM
/// 分片 256KB，16 条就够。输出方向的突发特征完全不同 —— 一次 `tmux attach`
/// 的重绘实测是 150+ 个小块、总共才 18 KB，块多而每块小。队列若按输入那样
/// 定成 16，光一次 attach 就要把读循环顶住十来次。
///
/// 256 的取法：覆盖实测突发（150+）并留一倍余量；上界由 russh 的通道窗口
/// 决定，单块不超过 32KB，于是最坏占用 256 × 32KB ≈ 8MB —— 有界、可接受。
/// 再大就只是把「远端狂吐数据」的内存压力往后推，并不能解决根因。
const OUTPUT_QUEUE: usize = 256;

/// 旁路通道被服务端拒绝时返回的文案。
///
/// 前端靠这段文字识别「这台机器只允许一条通道」（企业堡垒机很常见），
/// 一旦命中就为这个会话配置打开「兼容模式」，以后连上不再开旁路通道 ——
/// 否则每 3 秒一次的 probe 会把整条连接反复顶掉，表现为「连上几秒就断」。
pub const SIDECAR_DENIED: &str = "该服务器不支持并行通道（堡垒机限制）";

/// 兼容模式下拒绝旁路请求的文案。不是错误，只是不再尝试。
pub const SIDECAR_OFF: &str = "该连接已启用兼容模式，不打开旁路通道";

/// 一个 SSH 会话的运行时句柄
struct SessionRuntime {
    /// 连接句柄：用于在同一连接上开旁路 exec 通道（探测 pwd / tmux / docker）
    handle: Arc<Handle<ClientHandler>>,
    /// 数据通道：**有界**，发送时 await 即可把「网卡有多快」传导回前端
    data_tx: mpsc::Sender<SessionInput>,
    /// 控制通道（resize / close）：无界，保证数据队列堵满时仍能立刻改变窗口或断开
    ctrl_tx: mpsc::UnboundedSender<SessionInput>,
    /// 是否允许开旁路 exec 通道（probe / stats）。
    /// 堡垒机常常只放行一条通道，开第二条会被直接掐断整条连接，所以必须能关掉。
    sidecar: bool,
    /// 最后一次旁路通道尝试的时刻（epoch ms）。断线时对比它，
    /// 就能在日志里看出「是不是这次旁路操作把连接弄死的」。
    last_sidecar: Arc<std::sync::atomic::AtomicI64>,
}

/// 探测脚本：在同一 SSH 连接上开 exec 通道执行（stdin 喂给 `sh -s`，不依赖用户登录 shell 类型）。
///
/// 原理：同一 TCP 连接上的所有通道共享同一个 `SSH_CONNECTION`（含客户端端口，全局唯一）。
/// 据此在 /proc 里找出「父进程是 sshd、且 stdin 是 pts」的进程 —— 就是本标签的交互 shell；
/// 再从其 stat 的 tpgid 拿到前台进程组，读出 cwd / 命令行；tmux 则按 tty 查询所附着的 session。
/// 只读、不修改远端任何东西；非 Linux（无 /proc）时输出为空，前端按「未知」处理。
const PROBE_SCRIPT: &str = r#"
sc="$SSH_CONNECTION"
[ -n "$sc" ] || exit 0
[ -d /proc/self ] || exit 0
shell=""; tty=""
for p in /proc/[0-9]*; do
  [ -O "$p" ] || continue
  pid=${p#/proc/}
  [ "$pid" = "$$" ] && continue
  t=$(readlink "$p/fd/0" 2>/dev/null) || continue
  case "$t" in /dev/pts/*) ;; *) continue ;; esac
  tr '\0' '\n' < "$p/environ" 2>/dev/null | grep -qxF "SSH_CONNECTION=$sc" || continue
  pp=$(sed 's/.*) //' "$p/stat" 2>/dev/null | awk '{print $2}')
  case "$(cat /proc/$pp/comm 2>/dev/null)" in sshd*) shell=$pid; tty=$t; break ;; esac
done
[ -n "$shell" ] || exit 0
echo "TTY=$tty"
echo "SHELL_CWD=$(readlink /proc/$shell/cwd 2>/dev/null)"
fg=$(sed 's/.*) //' /proc/$shell/stat 2>/dev/null | awk '{print $6}')
if [ -n "$fg" ] && [ "$fg" -gt 0 ] 2>/dev/null && [ -r "/proc/$fg/cmdline" ]; then
  echo "FG_COMM=$(cat /proc/$fg/comm 2>/dev/null)"
  echo "FG_CWD=$(readlink /proc/$fg/cwd 2>/dev/null)"
  printf 'FG_ARGS='; tr '\0' '\037' < /proc/$fg/cmdline 2>/dev/null; echo
fi
if command -v tmux >/dev/null 2>&1; then
  # list-clients 按 tty 匹配（display-message -p -c 在 tmux 3.2 不可用）
  tmux list-clients -F '#{client_tty}	#{session_name}	#{pane_current_path}' 2>/dev/null |
    awk -F '\t' -v t="$tty" '$1==t { print "TMUX_SESSION=" $2; print "TMUX_PATH=" $3; exit }'
fi
"#;

/// 资源快照脚本（Linux /proc）。CPU 占用率需两次采样 /proc/stat 做差（间隔 0.5s）；
/// 内存取 MemAvailable（旧内核没有时用 MemFree+Buffers+Cached 近似）；磁盘取根分区 df -kP。
/// 只读，输出 KEY=VALUE；非 Linux 时各项缺省，前端显示「—」。
const STATS_SCRIPT: &str = r#"
LC_ALL=C; export LC_ALL
cpu() { awk '/^cpu /{ idle=$5+$6; t=0; for(i=2;i<=NF;i++) t+=$i; print t, idle; exit }' /proc/stat 2>/dev/null; }
a=$(cpu); sleep 0.5; b=$(cpu)
if [ -n "$a" ] && [ -n "$b" ]; then
  echo "$a $b" | awk '{ dt=$3-$1; di=$4-$2; if (dt>0) printf "CPU_PCT=%.1f\n", (dt-di)*100/dt }'
fi
n=$(grep -c '^processor' /proc/cpuinfo 2>/dev/null)
[ -n "$n" ] && [ "$n" -gt 0 ] 2>/dev/null || n=$(nproc 2>/dev/null)
echo "CPUS=$n"
[ -r /proc/loadavg ] && awk '{ print "LOAD=" $1 " " $2 " " $3 }' /proc/loadavg
awk '/^MemTotal:/{t=$2} /^MemAvailable:/{a=$2} /^MemFree:/{f=$2} /^Buffers:/{b=$2} /^Cached:/{c=$2}
  END { if (t) { if (!a) a=f+b+c; print "MEM_TOTAL=" t*1024; print "MEM_AVAIL=" a*1024 } }' /proc/meminfo 2>/dev/null
df -kP / 2>/dev/null | awk 'NR==2 { print "DISK_TOTAL=" $2*1024; print "DISK_USED=" $3*1024; print "DISK_MOUNT=" $6 }'
"#;

/// POSIX 单引号转义
fn shq(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\\''"))
}

/// `cat` 命令：`~/x` 展开为 "$HOME"/'x'，其余整体单引号包裹，防注入。
/// 只用 sh / bash / zsh / fish 通用的语法。
fn cat_command(path: &str) -> String {
    if path == "~" {
        return "cat -- \"$HOME\"".into();
    }
    match path.strip_prefix("~/") {
        Some(rest) => format!("cat -- \"$HOME\"/{}", shq(rest)),
        None => format!("cat -- {}", shq(path)),
    }
}

#[cfg(test)]
mod cat_tests {
    use super::cat_command;
    #[test]
    fn quoting() {
        assert_eq!(cat_command("/a b/c.md"), "cat -- '/a b/c.md'");
        assert_eq!(cat_command("/x/it's.md"), "cat -- '/x/it'\\''s.md'");
        assert_eq!(cat_command("~/d/r.md"), "cat -- \"$HOME\"/'d/r.md'");
        assert_eq!(cat_command("/x/$(rm -rf).md"), "cat -- '/x/$(rm -rf).md'");
    }
}

/// 探测结果：KEY=VALUE 原样回传，由前端解释（规则改动不必重编 Rust）
pub type ProbeInfo = HashMap<String, String>;

/// 登记表里的一条记录：一条已认证连接，以及它连的是谁。
///
/// 记住 host/port/username 而不是只留句柄，是因为会话配置可以被编辑 ——
/// 用户把主机 A 改成 B 之后，旧标签仍连着 A。若只按配置 id 复用，文件面板
/// 会莫名其妙列出 A 的文件，而用户以为自己连的是 B。
pub struct SharedConn {
    pub config_id: String,
    pub host: String,
    pub port: u16,
    pub username: String,
    pub handle: Arc<Handle<ClientHandler>>,
}

/// 「已认证连接」登记表：标签 id → 那条连接。
///
/// 只服务一个目的：让 SFTP 面板不必重新认证就能在同一条连接上开 `sftp` 子系统。
/// 对要求 keyboard-interactive（OTP / 堡垒机）的服务器，这一步等于省掉一次
/// 验证码输入 —— 否则每打开一次文件面板都要重新要一次码。
///
/// 键是**标签 id** 而不是配置 id：同一个配置可能开了多个标签，关掉其中一个
/// 不该影响其它标签登记的那条连接。
pub type SharedConns = Arc<Mutex<HashMap<String, SharedConn>>>;

/// 全局会话管理器
pub struct SessionManager {
    inner: Arc<Mutex<HashMap<String, SessionRuntime>>>,
    known_hosts: Arc<KnownHosts>,
    /// 二次验证的问答登记表。放在管理器上是为了让「关闭会话」能顺手解除挂起，
    /// 否则用户点了断开，后台的 connect 任务还会傻等 3 分钟。
    auth_prompts: Arc<AuthPrompts>,
    /// 见 [`SharedConns`]
    shared: SharedConns,
}

impl SessionManager {
    pub fn new() -> Self {
        Self {
            inner: Arc::new(Mutex::new(HashMap::new())),
            known_hosts: Arc::new(KnownHosts::new()),
            auth_prompts: Arc::new(AuthPrompts::new()),
            shared: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    /// 供 `SftpManager` 复用已认证连接。
    pub fn shared(&self) -> SharedConns {
        self.shared.clone()
    }

    /// 供 `ssh_auth_respond` 命令与 SFTP 通道共享同一个登记表。
    pub fn auth_prompts(&self) -> Arc<AuthPrompts> {
        self.auth_prompts.clone()
    }

    /// 共享的主机指纹表。SFTP 连接复用同一份，保证 TOFU 策略一致。
    pub fn known_hosts(&self) -> Arc<KnownHosts> {
        self.known_hosts.clone()
    }

    /// 该会话是否还活着。**轻量且无副作用**（不握手、不占通道），
    /// 供需要「先判断是 SSH 还是本地，再决定往哪写」的调用方使用。
    pub fn contains(&self, id: &str) -> bool {
        self.inner.lock().contains_key(id)
    }

    pub fn close(&self, id: &str) {
        // 认证可能正卡在「等用户填验证码」上，先把它叫醒，
        // 否则后台 connect 任务会挂到超时为止。
        self.auth_prompts.cancel(id);
        // 先从共享表摘掉：这里持有 Arc，不摘的话即使 SessionRuntime 被移除，
        // 连接也不会因为引用清零而关闭 —— 远端会留一个永不退出的 shell 进程。
        self.shared.lock().remove(id);
        if let Some(rt) = self.inner.lock().remove(id) {
            // 走控制通道：数据队列可能正被上传堵满，Close 不能排在它后面
            let _ = rt.ctrl_tx.send(SessionInput::Close);
        }
    }

    /// 建立 SSH 连接并启动读写循环
    pub async fn connect(
        &self,
        listener: Arc<dyn TerminalEventListener>,
        id: String,
        req: ConnectRequest,
    ) -> Result<ConnectOutcome, SessionError> {
        let port = req.port.unwrap_or(22);

        // 内核默认的 TCP 超时约 75 秒，用户会以为软件卡死。显式给上限，
        // 超时后立刻返回可读的中文提示，而不是干等。
        let timeout = errors::resolve_timeout(req.connect_timeout_secs);

        // 握手 + 认证统一走 ssh::open_authenticated：算法协商（含老服务器自动降级）、
        // keepalive、以及二次验证的「重连后以 KBI 重试」都在那里处理，
        // SFTP 通道复用同一套逻辑。
        let auth = req.auth.as_ref().ok_or_else(|| {
            SessionError::Auth("这条连接没有可用的登录凭据，请编辑会话并填写密码或私钥".into())
        })?;

        let opened = super::open_authenticated(
            &req.host,
            port,
            self.known_hosts.clone(),
            timeout,
            super::LegacyPolicy::from_flag(req.legacy_crypto),
            super::keepalive_interval(),
            &req.username,
            auth,
            &id,
            AuthContext {
                listener: listener.clone(),
                prompts: self.auth_prompts.clone(),
            },
        )
        .await
        .map_err(|e| match e {
            super::OpenError::Timeout => SessionError::Connect(errors::compose(
                &errors::timeout_message(&req.host, port, timeout),
                &format!("等待握手超过 {} 秒", timeout.as_secs()),
            )),
            super::OpenError::HostKeyMismatch => SessionError::HostKeyMismatch,
            super::OpenError::Handshake { err, legacy_tried } => SessionError::Connect(
                errors::handshake_error(&req.host, port, &err, legacy_tried),
            ),
            super::OpenError::Auth(msg) => SessionError::Auth(msg),
        })?;

        let legacy_crypto = opened.legacy;
        let session = opened.session;


        // 建通道 / 申请 pty / 起 shell：任何一步失败，这条已经认证过的连接就废了，
        // 但服务端那边可能已经给它建好会话、算进「同账号并发数」里了。
        // 所以失败路径统一走 abort_session，主动告别而不是留个孤儿。
        let prepared = async {
            let channel = session
                .channel_open_session()
                .await
                .map_err(|e| SessionError::Channel(e.to_string()))?;

            channel
                .request_pty(
                    false,
                    "xterm-256color",
                    req.cols as u32,
                    req.rows as u32,
                    0,
                    0,
                    &[],
                )
                .await
                .map_err(|e| SessionError::Channel(e.to_string()))?;

            channel
                .request_shell(false)
                .await
                .map_err(|e| SessionError::Channel(e.to_string()))?;
            Ok::<_, SessionError>(channel)
        }
        .await;
        let channel = match prepared {
            Ok(channel) => channel,
            Err(e) => {
                super::abort_session(&session, "建立终端通道失败").await;
                return Err(e);
            }
        };

        let (data_tx, data_rx) = mpsc::channel(INPUT_QUEUE);
        let (ctrl_tx, ctrl_rx) = mpsc::unbounded_channel();
        let handle = Arc::new(session);
        let sidecar = req.sidecar.unwrap_or(true);
        let last_sidecar = Arc::new(std::sync::atomic::AtomicI64::new(0));

        self.inner.lock().insert(
            id.clone(),
            SessionRuntime {
                handle: handle.clone(),
                data_tx,
                ctrl_tx,
                sidecar,
                last_sidecar: last_sidecar.clone(),
            },
        );

        // 登记给 SFTP 复用：同一配置下任意一条活连接都能开 sftp 子系统，
        // 这样文件面板不必再走一次认证（对 OTP 服务器就是省一次验证码）。
        // 连的是谁一并记下 —— 配置可能被改成另一台机器，那时不该再复用。
        if let Some(config_id) = req.config_id.clone() {
            self.shared.lock().insert(
                id.clone(),
                SharedConn {
                    config_id,
                    host: req.host.clone(),
                    port,
                    username: req.username.clone(),
                    handle: handle.clone(),
                },
            );
        }

        tokio::spawn(read_loop(
            listener,
            id,
            channel,
            data_rx,
            ctrl_rx,
            sidecar,
            last_sidecar,
        ));

        Ok(ConnectOutcome { legacy_crypto })
    }

    /// 写数据。**队列满时会等** —— 这个 await 就是全链路的背压：
    /// 网络慢 → 队列堵 → IPC 不返回 → 前端读盘停在这一片，内存恒定。
    ///
    /// 先用 `try_send` 走快路径：普通键入/粘贴时队列永远是空的，同步入队可以
    /// 沿用「命令按到达顺序执行」的旧语义；只有批量上传把队列填满时才退化成
    /// 等待（那时本来就是单条数据流，顺序由前端的发送队列保证）。
    pub async fn write(&self, id: &str, data: Vec<u8>) -> Result<(), SessionError> {
        // 取完 sender 立刻放锁：parking_lot 的 guard 不是 Send，跨 await 持有会编译不过
        let tx = {
            let map = self.inner.lock();
            map.get(id)
                .ok_or_else(|| SessionError::NotFound(id.into()))?
                .data_tx
                .clone()
        };
        match tx.try_send(SessionInput::Data(data)) {
            Ok(()) => Ok(()),
            Err(mpsc::error::TrySendError::Full(msg)) => tx
                .send(msg)
                .await
                .map_err(|_| SessionError::Channel("输入通道已关闭".into())),
            Err(mpsc::error::TrySendError::Closed(_)) => {
                Err(SessionError::Channel("输入通道已关闭".into()))
            }
        }
    }

    /// 探测该标签交互 shell 的工作目录 / 前台进程 / tmux 会话。
    /// 走旁路 exec 通道，不会往用户终端里写任何东西。
    pub async fn probe(&self, id: &str) -> Result<ProbeInfo, SessionError> {
        self.run_kv_script(id, PROBE_SCRIPT, "探测超时").await
    }

    /// 运行中改旁路通道开关。
    ///
    /// 前端一发现「服务端拒绝并行通道」就调这里把当前连接也关掉：
    /// 光记住配置不够，这条还活着的连接上每 3 秒仍会再试一次 probe，
    /// 而堡垒机往往在第二次被拒时就把整条连接掐了。
    pub fn set_sidecar(&self, id: &str, on: bool) {
        if let Some(rt) = self.inner.lock().get_mut(id) {
            rt.sidecar = on;
        }
    }

    /// 远端资源快照（CPU / 内存 / 磁盘），供状态栏每 5 秒刷新。同样走旁路 exec 通道。
    pub async fn stats(&self, id: &str) -> Result<ProbeInfo, SessionError> {
        self.run_kv_script(id, STATS_SCRIPT, "资源采集超时").await
    }

    /// 在同一连接上开 exec 通道跑 `sh -s` 脚本，解析 `k=v` 行输出。
    async fn run_kv_script(
        &self,
        id: &str,
        script: &'static str,
        timeout_msg: &'static str,
    ) -> Result<ProbeInfo, SessionError> {
        let text = self
            .run_sidecar(id, &format!("exec sh -s"), Some(script), 8, timeout_msg)
            .await?;
        let mut info = ProbeInfo::new();
        for line in text.lines() {
            if let Some((k, v)) = line.split_once('=') {
                if !v.is_empty() {
                    info.insert(k.trim().to_string(), v.to_string());
                }
            }
        }
        Ok(info)
    }

    /// 在该会话的旁路 exec 通道上跑一条**完整 shell 命令**，原样返回输出。
    ///
    /// `stdin_script` 为 `Some` 时先 `exec sh -s` 再把脚本喂进 stdin（这是 `probe` /
    /// `stats` 用的形态，脚本体不必出现在命令行上）；为 `None` 时直接执行 `command`。
    ///
    /// 与那些脚本不同：console 自动补全要拿的是 `<binary> --help` 的**原始输出**，
    /// 不能被 `k=v` 的解析规则吃掉。
    pub async fn run_sidecar(
        &self,
        id: &str,
        command: &str,
        stdin_script: Option<&str>,
        timeout_secs: u64,
        timeout_msg: &'static str,
    ) -> Result<String, SessionError> {
        let (handle, sidecar, last_sidecar) = {
            let map = self.inner.lock();
            let rt = map
                .get(id)
                .ok_or_else(|| SessionError::NotFound(id.into()))?;
            (rt.handle.clone(), rt.sidecar, rt.last_sidecar.clone())
        };
        // 兼容模式：一条额外通道都不开。堡垒机收到第二条 CHANNEL_OPEN 就会断开
        // 整条连接，连「试试看」都不允许。
        if !sidecar {
            return Err(SessionError::Channel(SIDECAR_OFF.into()));
        }
        last_sidecar.store(epoch_ms(), std::sync::atomic::Ordering::Relaxed);
        let run = async move {
            let mut ch = handle
                .channel_open_session()
                .await
                // 被拒（而不是网络断）时给出可识别的文案，前端据此自动切兼容模式
                .map_err(|e| SessionError::Channel(sidecar_error(&e)))?;
            ch.exec(true, command)
                .await
                .map_err(|e| SessionError::Channel(sidecar_error(&e)))?;
            if let Some(script) = stdin_script {
                ch.data(script.as_bytes())
                    .await
                    .map_err(|e| SessionError::Channel(e.to_string()))?;
                ch.eof().await.map_err(|e| SessionError::Channel(e.to_string()))?;
            }
            let mut out = Vec::new();
            while let Some(msg) = ch.wait().await {
                match msg {
                    ChannelMsg::Data { data } => out.extend_from_slice(&data),
                    ChannelMsg::Close => break,
                    _ => {}
                }
            }
            Ok::<_, SessionError>(out)
        };
        let out =
            tokio::time::timeout(std::time::Duration::from_secs(timeout_secs), run)
                .await
                .map_err(|_| SessionError::Channel(timeout_msg.into()))??;
        Ok(String::from_utf8_lossy(&out).into_owned())
    }

    /// 在同一连接上开 exec 通道 `cat` 一个文件（阅读窗口用，不依赖 SFTP 子系统）。
    /// `path` 为绝对路径或 `~/` 开头；超过 `max` 字节即中止。
    pub async fn read_file(
        &self,
        id: &str,
        path: &str,
        max: usize,
    ) -> Result<Vec<u8>, SessionError> {
        let handle = {
            let map = self.inner.lock();
            map.get(id)
                .ok_or_else(|| SessionError::NotFound(id.into()))?
                .handle
                .clone()
        };
        let cmd = cat_command(path);
        let run = async move {
            let mut ch = handle
                .channel_open_session()
                .await
                .map_err(|e| SessionError::Channel(e.to_string()))?;
            ch.exec(true, cmd.as_bytes())
                .await
                .map_err(|e| SessionError::Channel(e.to_string()))?;
            let mut out = Vec::new();
            let mut err = Vec::new();
            let mut code: Option<u32> = None;
            while let Some(msg) = ch.wait().await {
                match msg {
                    ChannelMsg::Data { data } => {
                        out.extend_from_slice(&data);
                        if out.len() > max {
                            let _ = ch.close().await;
                            return Err(SessionError::Channel(format!(
                                "文件超过 {} MB，不适合阅读",
                                max / 1024 / 1024
                            )));
                        }
                    }
                    ChannelMsg::ExtendedData { data, .. } => {
                        if err.len() < 4096 {
                            err.extend_from_slice(&data);
                        }
                    }
                    ChannelMsg::ExitStatus { exit_status } => code = Some(exit_status),
                    ChannelMsg::Close => break,
                    _ => {}
                }
            }
            if code.unwrap_or(0) != 0 {
                let msg = String::from_utf8_lossy(&err).trim().to_string();
                return Err(SessionError::Channel(if msg.is_empty() {
                    format!("读取失败（退出码 {}）", code.unwrap_or(0))
                } else {
                    msg
                }));
            }
            Ok::<_, SessionError>(out)
        };
        tokio::time::timeout(std::time::Duration::from_secs(30), run)
            .await
            .map_err(|_| SessionError::Channel("读取文件超时".into()))?
    }

    pub fn resize(&self, id: &str, cols: u16, rows: u16) -> Result<(), SessionError> {
        let map = self.inner.lock();
        let rt = map
            .get(id)
            .ok_or_else(|| SessionError::NotFound(id.into()))?;
        rt.ctrl_tx
            .send(SessionInput::Resize {
                cols,
                rows,
                pix_w: 0,
                pix_h: 0,
            })
            .map_err(|_| SessionError::Channel("会话已关闭".into()))
    }
}

/// 认证期间需要的人机交互上下文。
///
/// 目前只需要「问用户」这一件事的两个依赖：往哪个监听者回调、用哪张登记表收答案。
/// 会话 id 与主机名单独传给 `open_authenticated`，因为它们同时也是日志/事件路由的键。
#[derive(Clone)]
pub struct AuthContext {
    pub listener: Arc<dyn crate::listener::TerminalEventListener>,
    pub prompts: Arc<AuthPrompts>,
}

/// 第一次认证的结果。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AuthOutcome {
    /// 认证完成，可以直接开通道
    Done,
    /// 用户选的方式没通过，服务端还可能有二次验证。
    ///
    /// 调用方**必须换一条新连接**再用 [`authenticate_kbi`] 重来，原因见下。
    NeedKeyboardInteractive,
}

/// 现在只在**换连接重试**这条兜底路径上出现（见 [`super::open_authenticated`]）。
///
/// 历史：russh 0.49 的 `auth_request.current` 只在 SERVICE_ACCEPT 那一刻被置为
/// `KeyboardInteractive`，而 SERVICE_ACCEPT 只对应本次连接的**第一个**认证请求。
/// 先密码再转 KBI 时 `current` 仍是 `None`，服务端下发 USERAUTH_INFO_REQUEST
/// 后两个分支都不匹配，包被**静默丢弃**，客户端永久挂起。
///
/// 该缺陷已在 `vendor/russh` 里修掉（判据改为「当前请求是不是 KBI」，不再依赖
/// `current`），因此「密码 + 验证码」可以在同一条连接上完成。这条常量与重连兜底
/// 仍然保留：对极少数只允许 KBI 作为首个请求的服务器，换连接才是对的。
pub const KBI_MUST_BE_FIRST: &str = "keyboard-interactive 必须是本次连接的第一个认证请求";

/// keyboard-interactive 允许的最大问答轮数。正常的多因素也就 2~3 轮，
/// 给到 8 纯粹是防服务端异常时无限循环。
const MAX_KBI_ROUNDS: u32 = 8;

/// 主认证方式失败时给用户看的文案。
pub fn refusal_message(auth: &AuthMethod) -> &'static str {
    match auth {
        AuthMethod::Password { .. } => "认证失败：密码不正确",
        AuthMethod::PrivateKey { .. } => "认证失败：公钥被拒绝",
        AuthMethod::Agent => "认证失败：服务器不接受无凭据登录，请改用密码或私钥",
    }
}

/// 可以直接复用去回答 KBI 问题的凭据。
///
/// 只有密码算数 —— 私钥的 passphrase 不是登录密码，不能拿去填 "Password:"。
fn reusable_password(auth: &AuthMethod) -> Option<&str> {
    match auth {
        AuthMethod::Password { password } => Some(password.as_str()),
        _ => None,
    }
}

/// 这条提示是不是在问密码（用于自动带入已保存的密码）。
///
/// 只认 `echo == false` 的秘密输入：明文回显的提示（比如 "Enter new password"）
/// 自动填进去没有意义，交给用户自己写。
fn asks_for_password(text: &str, echo: bool) -> bool {
    if echo {
        return false;
    }
    let t = text.to_ascii_lowercase();
    t.contains("password") || t.contains("passwd") || t.contains("密码")
}

/// RSA 公钥认证要按服务端能力挑签名算法。
///
/// 两种世界都存在，且**光看版本号没法区分**：
/// * OpenSSH ≥ 7.2 支持 `rsa-sha2-256/512`，新版本默认已禁用 `ssh-rsa`（SHA-1）。
/// * OpenSSH < 7.2（5.3 的 CentOS 6 / VOS 之类）只认 `ssh-rsa`，收到
///   `rsa-sha2-256` 会直接回 `USERAUTH_FAILURE`。
///
/// 服务端「支持哪些公钥签名算法」是通过 `server-sig-algs` 扩展告知的，但这个扩展
/// 本身也是 7.2 才加的，老服务器根本没有；russh 0.49 又没把它暴露出来
/// （`client::encrypted::handle_ext_info` 收到就丢）。所以只能按「先新后旧」
/// 实试：SHA-256 被拒就在同一条连接上改用 `ssh-rsa` 再来一次。
///
/// 顺序不能反：现代服务器（尤其被加固过的）把 `ssh-rsa` 关掉了，
/// 先试 SHA-1 会白白失败一次，还可能触发 fail2ban 计数。
async fn authenticate_key<H>(
    handle: &mut Handle<H>,
    username: &str,
    key: Arc<russh::keys::PrivateKey>,
) -> Result<bool, SessionError>
where
    H: russh::client::Handler,
{
    // None = 算法自带的默认（RSA 即 ssh-rsa / SHA-1），ed25519 / ecdsa 只能传 None，
    // 传 Some 会被 `PrivateKeyWithHashAlg::new` 判为 InvalidParameters。
    let candidates: &[Option<russh::keys::HashAlg>] = if key.algorithm().is_rsa() {
        &[
            Some(russh::keys::HashAlg::Sha256),
            None, // ssh-rsa（SHA-1），兼容 OpenSSH < 7.2
        ]
    } else {
        &[None]
    };

    for (i, hash_alg) in candidates.iter().enumerate() {
        let key_with_alg = russh::keys::key::PrivateKeyWithHashAlg::new(key.clone(), *hash_alg)
            .map_err(|e| SessionError::Auth(errors::auth_error(&e.to_string())))?;
        let label = key_with_alg.algorithm().as_str().to_string();

        match handle
            .authenticate_publickey(username, key_with_alg)
            .await
        {
            Ok(true) => {
                tracing::debug!(algorithm = %label, "公钥认证通过");
                return Ok(true);
            }
            Ok(false) => {
                // 还有下一档就继续试。真正的「密钥不在 authorized_keys 里」
                // 会在最后一档失败，不会因为降级而变成误报成功。
                if i + 1 < candidates.len() {
                    tracing::warn!(
                        algorithm = %label,
                        "服务端拒绝该签名算法，改用 ssh-rsa(SHA-1) 重试"
                    );
                }
            }
            Err(e) => {
                tracing::warn!(algorithm = %label, error = %e, "公钥认证请求失败");
                return Err(SessionError::Auth(errors::auth_error(&e.to_string())));
            }
        }
    }

    Ok(false)
}

/// 执行用户选定的认证方式（第一步）。
///
/// 返回 `NeedKeyboardInteractive` 表示「方式没通过，值得再走一次二次验证」，
/// 由调用方重连后交给 [`authenticate_kbi`]。
///
/// 抽成独立函数是为了让 SSH 终端与 SFTP 子系统复用同一套逻辑 ——
/// 两者若各写一份，认证规则一变动就必然出现漂移。
pub async fn authenticate<H>(
    handle: &mut Handle<H>,
    username: &str,
    auth: &AuthMethod,
) -> Result<AuthOutcome, SessionError>
where
    H: russh::client::Handler,
{
    let ok = match auth {
        AuthMethod::Password { password } => handle
            .authenticate_password(username, password)
            .await
            .map_err(|e| SessionError::Auth(errors::auth_error(&e.to_string())))?,
        AuthMethod::PrivateKey { path, passphrase } => {
            let key = Arc::new(
                crate::keys::load_private_key(path, passphrase.clone())
                    .await
                    .map_err(|e| SessionError::Auth(errors::auth_error(&e)))?,
            );
            authenticate_key(handle, username, key).await?
        }
        AuthMethod::Agent => handle
            .authenticate_none(username)
            .await
            .map_err(|e| SessionError::Auth(errors::auth_error(&e.to_string())))?,
    };

    Ok(if ok {
        AuthOutcome::Done
    } else {
        AuthOutcome::NeedKeyboardInteractive
    })
}

/// keyboard-interactive 的两种结局。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum KbiOutcome {
    /// 认证完成
    Done,
    /// 服务端**一个问题都没问**就直接拒绝 —— 说明此刻它不接受 KBI
    /// （例如只认密码 / 公钥的服务器）。调用方据此决定要不要换个连接重试。
    Unavailable,
}

/// 只走 keyboard-interactive。两种调用姿势都对：
///
/// * **纯 KBI 服务器**（KBI 就是第一因子）：新连接上直接调；
/// * **密码 / 公钥 + 动态口令**（`AuthenticationMethods password,keyboard-interactive`
///   或 Go 写的堡垒机）：先做完第一因子，**在同一条连接上**接着调。
///
/// 第二种曾经做不到 —— russh 只在「本次连接的第一个认证请求就是 KBI」时才认
/// INFO_REQUEST，其余情况静默丢包并永久挂起。已在 `vendor/russh` 里修掉，
/// 所以现在可以直接在已认证一半的连接上继续。
///
/// 「问用户」做成闭包参数而不是写死 `AuthPrompts`，是为了让这些路径能对着
/// 真服务端跑通，不必拉起 Tauri 窗口。
///
/// 只有测试用（生产走 [`authenticate_kbi`]，因为同一条闭包要在两条路径上复用）。
#[cfg(test)]
pub(crate) async fn authenticate_kbi_with<H, F, Fut>(
    handle: &mut Handle<H>,
    username: &str,
    auth: &AuthMethod,
    session_id: &str,
    host: &str,
    ask: F,
) -> Result<KbiOutcome, SessionError>
where
    H: russh::client::Handler,
    F: Fn(AuthPrompt) -> Fut,
    Fut: std::future::Future<Output = Result<Vec<String>, AskAbort>>,
{
    authenticate_kbi(handle, username, auth, session_id, host, &ask).await
}

/// [`authenticate_kbi_with`] 的实现体，闭包按引用传 —— 调用方常常要在
/// 「同一连接重试」和「换连接重试」两条路径上复用同一个闭包。
pub(crate) async fn authenticate_kbi<H, F, Fut>(
    handle: &mut Handle<H>,
    username: &str,
    auth: &AuthMethod,
    session_id: &str,
    host: &str,
    ask: &F,
) -> Result<KbiOutcome, SessionError>
where
    H: russh::client::Handler,
    F: Fn(AuthPrompt) -> Fut,
    Fut: std::future::Future<Output = Result<Vec<String>, AskAbort>>,
{
    use russh::client::KeyboardInteractiveAuthResponse as Resp;

    let password_hint = reusable_password(auth);

    let mut resp = handle
        .authenticate_keyboard_interactive_start(username, None)
        .await
        .map_err(|e| SessionError::Auth(errors::auth_error(&e.to_string())))?;

    // 已经问过用户几轮。为 0 说明服务端压根不支持 KBI，
    // 那就不是「验证码错了」，该报主认证方式的失败原因。
    let mut round = 0u32;
    // 是否出现过「非密码」的问题(验证码 / 挑战码)。有这个才说明真的做了二次验证，
    // 否则只是把密码重问了一遍，失败时就该说「密码不正确」。
    let mut saw_challenge = false;

    loop {
        match resp {
            Resp::Success => return Ok(KbiOutcome::Done),
            Resp::Failure if round == 0 => {
                // 一个问题都没问就拒了：此刻这条连接不认 KBI。
                // 交给调用方决定「换连接再来」还是「直接报错」。
                return Ok(KbiOutcome::Unavailable);
            }
            Resp::Failure if saw_challenge => {
                return Err(SessionError::Auth(
                    "二次验证未通过：验证码错误或已过期".into(),
                ));
            }
            // 只问过密码类问题 → 等价于密码错误
            Resp::Failure => {
                return Err(SessionError::Auth(refusal_message(auth).to_string()))
            }
            Resp::InfoRequest {
                name,
                instructions,
                prompts,
            } => {
                round += 1;
                if round > MAX_KBI_ROUNDS {
                    return Err(SessionError::Auth(
                        "服务器连续下发过多验证问题，已中止".into(),
                    ));
                }

                let mut answers = if prompts.is_empty() {
                    // 纯提示（没有可填字段），回空列表表示「知道了」
                    Vec::new()
                } else {
                    let fields: Vec<AuthPromptField> = prompts
                        .iter()
                        .map(|p| {
                            let prefill = match password_hint {
                                Some(pw) if asks_for_password(&p.prompt, p.echo) => {
                                    Some(pw.to_string())
                                }
                                _ => None,
                            };
                            if prefill.is_none() {
                                saw_challenge = true;
                            }
                            AuthPromptField {
                                prompt: p.prompt.clone(),
                                echo: p.echo,
                                prefill,
                            }
                        })
                        .collect();

                    let prompt = AuthPrompt {
                        session_id: session_id.to_string(),
                        kind: "keyboard-interactive".into(),
                        host: host.to_string(),
                        username: username.to_string(),
                        name,
                        instructions,
                        fields,
                        round,
                    };

                    match ask(prompt).await {
                        Ok(a) => a,
                        Err(AskAbort::Timeout) => {
                            return Err(SessionError::Auth(
                                "等待验证码输入超时，连接已中止".into(),
                            ))
                        }
                        Err(AskAbort::Unavailable) => {
                            return Err(SessionError::Auth(
                                "无法显示验证码输入框，连接已中止".into(),
                            ))
                        }
                        // 用户点了取消 / 会话被关
                        Err(AskAbort::Cancelled) => {
                            return Err(SessionError::Auth("已取消二次验证，连接中止".into()))
                        }
                    }
                };

                // 前端理应原样回填，但数量对不上服务端会直接断开，
                // 这里补齐/截断成和问题数一致，出错也好定位。
                answers.resize(prompts.len(), String::new());

                resp = handle
                    .authenticate_keyboard_interactive_respond(answers)
                    .await
                    .map_err(|e| SessionError::Auth(errors::auth_error(&e.to_string())))?;
            }
        }
    }
}


/// 会话结束事件负载：前端据此决定提示文案以及是否自动重连。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ClosedInfo {
    pub code: i32,
    /// exit = 远端 shell 正常退出；eof = 远端主动关闭通道；
    /// lost = 连接丢失（网络断开 / keepalive 超时 / 写失败）；user = 本地主动关闭
    pub reason: &'static str,
}

/// 送往 Kotlin 监听者的事件。
///
/// 数据和「会话已结束」必须走**同一条**队列：若 `on_closed` 绕过队列直接调，
/// 它就会插到尚未投递的数据前面，Kotlin 侧先收到「会话已关闭」、再收到最后
/// 几屏输出 —— 前端据此关标签页，用户就永远看不到退出前的报错。
enum OutEvent {
    Data(Vec<u8>),
    Closed { reason: String },
}

/// 起一条**专用**的 blocking 投递任务，返回它的入口队列和 join 句柄。
///
/// 为什么不是「每块数据 `spawn_blocking` 一下」：
///
/// - 带 `.await`（本次修复前的写法）等于没换：`.await` 在 select! 分支里会把
///   整条读循环挂起，直到回调跑完。换了个线程跑，循环照样等 —— 慢的 Kotlin
///   监听者依旧拖住读循环，连带单列出来的 `ctrl_rx` 一路（resize / close）
///   也一起卡住，而那一路存在的全部意义就是「上传堵满时断开仍要能立刻生效」。
/// - 去掉 `.await` 改成 fire-and-forget 更糟：多个 `spawn_blocking` 任务会
///   **并发**执行，终端字节流一旦乱序，xterm.js 的解析状态机就直接坏掉，
///   屏幕上是花屏而不是「慢一点」。**终端数据必须严格保序**，这是这段代码
///   最硬的约束，改动时第一个要守住的就是它。
///
/// 所以：单生产者（读循环）→ 有界队列 → **单消费者**（这条任务），顺序由
/// 「只有一个消费者、串行地一条条调 `on_data`」保证。读循环不再等回调跑完，
/// 只在队列满时等 —— 那是正确的背压（否则远端狂吐数据会让内存无限增长）。
///
/// 生命周期：与会话同生共死。读循环退出时发一条 [`OutEvent::Closed`] 再 drop
/// 发送端，这条任务把队列里剩下的数据投完、调一次 `on_closed`，然后自然结束。
fn spawn_event_delivery(
    listener: Arc<dyn TerminalEventListener>,
    id: String,
) -> (mpsc::Sender<OutEvent>, tokio::task::JoinHandle<()>) {
    let (tx, mut rx) = mpsc::channel::<OutEvent>(OUTPUT_QUEUE);
    // spawn_blocking 而非 spawn：UniFFI 的回调接口是同步方法，Kotlin 侧随手
    // 写点耗时操作并不违约（见 listener.rs 的说明），不能占着异步 worker 线程。
    let handle = tokio::task::spawn_blocking(move || {
        while let Some(ev) = rx.blocking_recv() {
            match ev {
                OutEvent::Data(bytes) => listener.on_data(id.clone(), bytes),
                OutEvent::Closed { reason } => {
                    listener.on_closed(id.clone(), reason);
                    // 关闭之后不可能再有输出；显式退出，杜绝 on_closed 之后
                    // 还往前端塞 on_data 这种前端最难排查的时序问题。
                    break;
                }
            }
        }
    });
    (tx, handle)
}

/// 读写主循环。select! 同时处理「远端数据」「本地输入」「控制指令」三条流。
///
/// 控制流单列一路：上传把数据队列堵满时，resize / close 仍要能立刻生效，
/// 否则「断开」会卡在几百 MB 的积压后面。
async fn read_loop(
    listener: Arc<dyn TerminalEventListener>,
    id: String,
    mut channel: russh::Channel<russh::client::Msg>,
    mut data_rx: mpsc::Receiver<SessionInput>,
    mut ctrl_rx: mpsc::UnboundedReceiver<SessionInput>,
    sidecar: bool,
    last_sidecar: Arc<std::sync::atomic::AtomicI64>,
) {
    let opened_at = epoch_ms();
    let mut info = ClosedInfo { code: 0, reason: "lost" };
    // 所有发往前端的事件都从这里走，顺序即投递顺序。详见 spawn_event_delivery。
    let (out_tx, out_task) = spawn_event_delivery(listener, id.clone());
    loop {
        tokio::select! {
            Some(ctrl) = ctrl_rx.recv() => {
                match ctrl {
                    SessionInput::Resize { cols, rows, pix_w, pix_h } => {
                        // 同步远端 COLUMNS/LINES，否则 vim / top 显示会错乱
                        let _ = channel
                            .window_change(cols as u32, rows as u32, pix_w as u32, pix_h as u32)
                            .await;
                    }
                    SessionInput::Close => {
                        let _ = channel.close().await;
                        info.reason = "user";
                        break;
                    }
                    // 数据不走这条路
                    SessionInput::Data(bytes) => {
                        // 原来是 `.is_err()`，错误被整个丢掉——于是 `reason=lost`
                        // 只说明「断了」，不说明「为什么断」。把 russh 的错误原文
                        // 打出来，否则排障时这里和下面那条 Data 分支完全无法区分。
                        if let Err(e) = channel.data(&bytes[..]).await {
                            tracing::warn!(session = id, error = %e, bytes = bytes.len(), "ctrl 分支写通道失败，判定连接丢失");
                            info.reason = "lost";
                            break;
                        }
                    }
                }
            }
            msg = channel.wait() => {
                match msg {
                    Some(ChannelMsg::Data { data, .. }) => {
                        // 原始字节直接交给监听者：不像桌面版那样先 base64 再走 IPC，
                        // UniFFI 回调可以原生传 Vec<u8>（JNI byte[]），不存在
                        // 「跨 IPC 必须是文本」的约束，也不会切断多字节字符的问题——
                        // Kotlin 侧原样喂给 xterm.js 的 writeUtf8 / write(Uint8Array)。
                        //
                        // 只把数据丢进投递队列，不在这里等回调跑完 —— 否则慢的
                        // Kotlin 监听者会拖住整条读循环，连 ctrl_rx 那一路也一起卡。
                        //
                        // 这里仍有一个 `.await`，但它等的是**队列有空位**而不是
                        // 回调执行完：队列满说明前端已经落后 OUTPUT_QUEUE 个块，
                        // 此时放慢读取正是我们要的背压（不然远端狂吐数据，积压
                        // 全堆在本进程内存里）。代价是这种极端情况下 ctrl_rx 会
                        // 跟着等，但等待上界是「一次 on_data 的耗时」，不再是
                        // 修复前的「每一块都等一次」。
                        if out_tx.send(OutEvent::Data(data.to_vec())).await.is_err() {
                            // 只可能是投递任务没了（Kotlin 回调 panic）。数据没人
                            // 收了，但连接本身还好，不改变 reason、不额外 break ——
                            // 让既有的三条退出路径继续决定会话怎么结束。
                            tracing::warn!(session = id, "事件投递任务已结束，丢弃一块输出");
                        }
                    }
                    Some(ChannelMsg::ExtendedData { data, ext, .. }) => {
                        if ext == 1 {
                            tracing::warn!(session = id, "stderr: {}", String::from_utf8_lossy(&data));
                        }
                    }
                    Some(ChannelMsg::ExitStatus { exit_status }) => {
                        // 先别 break：ExitStatus 之后通常还跟着 Eof/Close，统一在循环外发一次
                        tracing::info!(session = id, exit_status, "远端 shell 退出");
                        info = ClosedInfo { code: exit_status as i32, reason: "exit" };
                    }
                    // OpenSSH 的顺序通常是 Eof → ExitStatus → Close，所以 Eof 不能立刻退出，
                    // 否则会漏掉退出码；以 Close（或通道流结束）为准
                    Some(ChannelMsg::Eof) => {
                        if info.reason != "exit" {
                            info.reason = "eof";
                        }
                    }
                    Some(ChannelMsg::Close) => {
                        if info.reason != "exit" {
                            info.reason = "eof";
                        }
                        break;
                    }
                    Some(ChannelMsg::Success) | Some(ChannelMsg::Failure) => {}
                    Some(other) => {
                        tracing::trace!(session = id, "未处理的通道消息: {:?}", other);
                    }
                    // None：底层连接已断（keepalive 超时 / TCP 重置）。若之前收到过
                    // ExitStatus 则仍算正常退出
                    //
                    // `wait()` 返回 None 等于「russh 那条任务没了」——可能是
                    // 它自己 panic 了（这种情况 logging.rs 的 panic hook 会在
                    // 这条之前先打一条 ☠️ ），也可能是 TCP 真的断了。两者在
                    // 这一层无法区分，所以这里只负责把「走到了这个分支」这件
                    // 事本身记下来，配合上面的 panic 记录就能分辨。
                    None => {
                        tracing::warn!(
                            session = id,
                            prior_reason = info.reason,
                            "channel.wait() 返回 None：底层连接/russh 任务已结束"
                        );
                        break;
                    }
                }
            }
            Some(input) = data_rx.recv() => {
                match input {
                    SessionInput::Data(bytes) => {
                        // 这里是全链路唯一的「等」：远端窗口满时 russsh 会挂住，
                        // 队列随即填满，前端的写入 await 便跟着停下来
                        //
                        // 同上：错误原文必须打出来。这条分支是崩溃链的起点——
                        // 它一 break，`data_rx` 就被 drop，下一次 `write()` 立刻
                        // 拿到 `Channel("输入通道已关闭")`。
                        if let Err(e) = channel.data(&bytes[..]).await {
                            tracing::warn!(session = id, error = %e, bytes = bytes.len(), "写通道失败，判定连接丢失");
                            info.reason = "lost";
                            break;
                        }
                    }
                    // 控制类指令不从这里进来（见 ctrl_rx 分支），仅为匹配完备性
                    SessionInput::Resize { cols, rows, pix_w, pix_h } => {
                        let _ = channel
                            .window_change(cols as u32, rows as u32, pix_w as u32, pix_h as u32)
                            .await;
                    }
                    SessionInput::Close => {
                        let _ = channel.close().await;
                        info.reason = "user";
                        break;
                    }
                }
            }
            else => break,
        }
    }
    tracing::info!(session = id, reason = info.reason, code = info.code, "会话结束");
    log_session_end(&id, &info, opened_at, sidecar, &last_sidecar);
    // 关闭事件也必须走同一条队列排在数据后面，不能直接调回调插队，
    // 否则前端会先收到「已关闭」再收到最后几屏输出（比如退出前的报错）。
    let reason = info.reason.to_string();
    let _ = out_tx.send(OutEvent::Closed { reason }).await;
    // drop 发送端，投递任务收完队列里剩下的东西后自然退出；
    // await 它是为了保持原有语义：read_loop 结束时 on_closed 已经送达。
    drop(out_tx);
    let _ = out_task.await;
}

fn epoch_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// 把 `CHANNEL_OPEN` / `exec` 被服务端拒绝的情况翻译成可识别文案。
///
/// 关键的区分：**「服务端说不」和「网络断了」是两回事**。前者说明这台机器
/// 不允许并行通道（堡垒机），前端应当永久切到兼容模式；后者只是临时故障，
/// 切了反而会把正常服务器误标。只有明确被拒才返回 [`SIDECAR_DENIED`]。
fn sidecar_error(e: &russh::Error) -> String {
    match e {
        russh::Error::ChannelOpenFailure(_) | russh::Error::RequestDenied => {
            SIDECAR_DENIED.to_string()
        }
        other => other.to_string(),
    }
}

/// 记一条会话结束日志。
///
/// 桌面版把这行写进 `<app_data>/sessions.log`（GUI 版本看不到控制台输出，
/// 排障靠这个文件）。Android 没有等价的 `AppHandle::path()`，所以这里退化成
/// 一条结构化 tracing 记录，字段与原文件格式一一对应，方便日后如果要落盘
/// 也能照抄。
///
/// 这条记录真正落到 logcat，靠的是 `crate::logging` 里的 subscriber——它由
/// 导出的 `init_logging()`（Kotlin 在 `Application.onCreate` 调）安装，
/// `crate::runtime()` 里还有一道兜底。在补上那个模块之前，这里写的每一条
/// 都被直接丢弃，于是「会话为什么断」在设备上查不到，这正是它存在的理由。
fn log_session_end(
    id: &str,
    info: &ClosedInfo,
    opened_at: i64,
    sidecar: bool,
    last_sidecar: &std::sync::atomic::AtomicI64,
) {
    let now = epoch_ms();
    let last = last_sidecar.load(std::sync::atomic::Ordering::Relaxed);
    let since_sidecar = if last > 0 { now - last } else { -1 };
    tracing::info!(
        session = id,
        reason = info.reason,
        code = info.code,
        alive_ms = now - opened_at,
        sidecar = sidecar,
        since_sidecar_ms = since_sidecar,
        "会话结束（结构化日志，供落盘/上报）"
    );
}

#[allow(dead_code)]
async fn graceful_close(handle: &Handle<ClientHandler>) {
    let _ = handle
        .disconnect(Disconnect::ByApplication, "session closed", "en-US")
        .await;
}

#[cfg(test)]
mod auth_prompt_tests {
    use super::asks_for_password;

    #[test]
    fn detects_password_prompts() {
        assert!(asks_for_password("Password:", false));
        assert!(asks_for_password("user@host's password: ", false));
        assert!(asks_for_password("Enter PASSWD: ", false));
        assert!(asks_for_password("密码：", false));
    }

    #[test]
    fn ignores_otp_and_echoed_prompts() {
        // 验证码不是密码，不能拿密码去顶
        assert!(!asks_for_password("Verification code:", false));
        assert!(!asks_for_password("OTP Code:", false));
        assert!(!asks_for_password("Password: ", true));
        assert!(!asks_for_password("", false));
    }
}

#[cfg(test)]
mod kbi_probe {
    //! 对着本地假服务器（`tests/kbi_server.py`）跑真实的二次验证链路。
    //!
    //! 默认 `#[ignore]`，需要先手动起服务端：
    //!
    //! ```bash
    //! python tests/kbi_server.py 2222              # 一轮问完（密码 + 验证码）
    //! python tests/kbi_server.py 2223 --rounds 2   # 分两轮问
    //! NEXUS_KBI_PORT2=2223 cargo test --lib kbi_probe -- --ignored --nocapture
    //! ```
    //!
    //! 注意：每条用例都必须用**新连接**，且 KBI 是这条连接上的第一个认证请求。
    //! 这不是测试的偷懒，而是生产代码的真实形态 —— 见 `KBI_MUST_BE_FIRST`。

    use super::*;
    use std::sync::Mutex as StdMutex;
    use std::time::Duration;

    struct AcceptAnyKey;

    /// 测试用：不校验主机指纹。假服务器每次启动都是新密钥，没法做 TOFU。
    #[async_trait::async_trait]
    impl russh::client::Handler for AcceptAnyKey {
        type Error = russh::Error;
        async fn check_server_key(
            &mut self,
            _key: &russh::keys::PublicKey,
        ) -> Result<bool, Self::Error> {
            Ok(true)
        }
    }

    async fn connect(port_env: &str) -> Handle<AcceptAnyKey> {
        let port: u16 = std::env::var(port_env)
            .ok()
            .and_then(|p| p.parse().ok())
            .unwrap_or(2222);
        russh::client::connect(
            Arc::new(russh::client::Config::default()),
            format!("127.0.0.1:{port}"),
            AcceptAnyKey,
        )
        .await
        .expect("连不上本地假服务器，先跑 python tests/kbi_server.py 2222")
    }

    const PW: &str = "test123";
    const OTP: &str = "654321";

    fn password() -> AuthMethod {
        AuthMethod::Password {
            password: PW.to_string(),
        }
    }

    /// KBI 作为**首个**请求仍然要好用（纯 OTP / 纯 KBI 服务器的形态）。
    ///
    /// 注意：这条已不再代表「只能当第一个请求」—— 那个限制由 `vendor/russh`
    /// 的补丁解除了，见 [`kbi_after_failed_password_on_same_connection`]。
    #[tokio::test]
    #[ignore]
    async fn kbi_works_as_first_request() {
        let mut session = connect("NEXUS_KBI_PORT").await;
        let r = session
            .authenticate_keyboard_interactive_start("tester", None)
            .await
            .expect("KBI start 失败");
        match r {
            russh::client::KeyboardInteractiveAuthResponse::InfoRequest { prompts, .. } => {
                assert_eq!(prompts.len(), 2);
                assert!(!prompts[0].echo);
            }
            other => panic!("首个请求应当直接拿到问题，实际 {other:?}"),
        }
    }

    /// 回归：**先密码、再 KBI，全在同一条连接上**完成。
    ///
    /// 这是「密码 + 动态口令」类企业堡垒机的形态（它们用 USERAUTH_FAILURE 的
    /// partial-success 位说「密码对了，还差一步」）。修复前 russh 会在这里
    /// 静默丢掉服务端下发的 INFO_REQUEST 并永久挂起 —— 现象是客户端既不弹
    /// 验证码框、也不报错，最后只能超时；而断开重连又会让服务端重新要求密码，
    /// 于是一路退化成「密码不正确」。
    #[tokio::test]
    #[ignore]
    async fn kbi_after_failed_password_on_same_connection() {
        let mut session = connect("NEXUS_KBI_PORT").await;

        // 假服务端故意拒绝 password 方法，等价于「第一因子已过、等第二因子」
        let ok = session
            .authenticate_password("tester", PW)
            .await
            .expect("password 请求出错");
        assert!(!ok, "假服务端应当不认 password 方法");

        let seen = Arc::new(StdMutex::new(Vec::<AuthPrompt>::new()));
        let sink = seen.clone();

        let res = tokio::time::timeout(
            std::time::Duration::from_secs(5),
            authenticate_kbi_with(
                &mut session,
                "tester",
                &password(),
                "probe",
                "127.0.0.1",
                move |p: AuthPrompt| {
                    sink.lock().unwrap().push(p.clone());
                    async move {
                        Ok(p.fields
                            .iter()
                            .map(|f| f.prefill.clone().unwrap_or_else(|| OTP.to_string()))
                            .collect::<Vec<_>>())
                    }
                },
            ),
        )
        .await
        .expect("同连接上的 KBI 超时 —— 说明 INFO_REQUEST 又被丢了");

        assert_eq!(res.expect("KBI 失败"), KbiOutcome::Done);
        assert_eq!(seen.lock().unwrap().len(), 1, "应当只问一轮");
    }

    /// 一轮问完两个问题：密码走 prefill，验证码手动填。
    ///
    /// 这条覆盖了绝大多数 MFA 服务器（PAM 把 password 和 otp 一起问）的形态。
    #[tokio::test]
    #[ignore]
    async fn two_fields_single_round() {
        let mut session = connect("NEXUS_KBI_PORT").await;
        let seen = Arc::new(StdMutex::new(Vec::<AuthPrompt>::new()));
        let sink = seen.clone();

        let res = authenticate_kbi_with(
            &mut session,
            "tester",
            &password(),
            "probe",
            "127.0.0.1",
            move |p: AuthPrompt| {
                sink.lock().unwrap().push(p.clone());
                async move {
                    Ok(p.fields
                        .iter()
                        // 有 prefill 就用 prefill（等价于前端预填），否则手填验证码
                        .map(|f| f.prefill.clone().unwrap_or_else(|| OTP.to_string()))
                        .collect::<Vec<_>>())
                }
            },
        )
        .await;

        assert!(res.is_ok(), "二次验证应当成功：{res:?}");

        let prompts = seen.lock().unwrap();
        assert_eq!(prompts.len(), 1, "服务端只该问一轮");
        let p = &prompts[0];
        assert_eq!(p.round, 1);
        assert_eq!(p.session_id, "probe");
        assert_eq!(p.fields.len(), 2);

        // 密码字段必须被自动带入，否则用户得重输一遍
        assert!(p.fields[0].prompt.to_lowercase().contains("password"));
        assert_eq!(p.fields[0].prefill.as_deref(), Some(PW));
        assert!(!p.fields[0].echo, "密码必须隐藏");

        // 验证码字段绝不能预填，也不能被当成密码
        assert!(p.fields[1].prompt.to_lowercase().contains("verification"));
        assert_eq!(p.fields[1].prefill, None);

        // 认证成功后才可能开通道 —— 顺带确认服务端真的认了
        session
            .channel_open_session()
            .await
            .expect("认证成功但开不了通道");
    }

    /// 分两轮问：先密码、再验证码。轮次要递增，前端据此重建弹窗。
    #[tokio::test]
    #[ignore]
    async fn two_rounds() {
        let mut session = connect("NEXUS_KBI_PORT2").await;
        let seen = Arc::new(StdMutex::new(Vec::<AuthPrompt>::new()));
        let sink = seen.clone();

        let res = authenticate_kbi_with(
            &mut session,
            "tester",
            &password(),
            "probe",
            "127.0.0.1",
            move |p: AuthPrompt| {
                sink.lock().unwrap().push(p.clone());
                async move {
                    Ok(p.fields
                        .iter()
                        .map(|f| f.prefill.clone().unwrap_or_else(|| OTP.to_string()))
                        .collect::<Vec<_>>())
                }
            },
        )
        .await;

        assert!(res.is_ok(), "两轮问答应当成功：{res:?}");
        let prompts = seen.lock().unwrap();
        assert_eq!(prompts.len(), 2);
        assert_eq!(prompts[0].round, 1);
        assert_eq!(prompts[1].round, 2);
        assert_eq!(prompts[0].fields[0].prefill.as_deref(), Some(PW));
        assert_eq!(prompts[1].fields[0].prefill, None);
    }

    /// 验证码填错：必须报「二次验证未通过」，不能退化成「密码不正确」。
    #[tokio::test]
    #[ignore]
    async fn wrong_otp_reports_verification_failure() {
        let mut session = connect("NEXUS_KBI_PORT").await;

        let res = authenticate_kbi_with(
            &mut session,
            "tester",
            &password(),
            "probe",
            "127.0.0.1",
            move |p: AuthPrompt| async move {
                Ok(p.fields
                    .iter()
                    .map(|f| f.prefill.clone().unwrap_or_else(|| "000000".to_string()))
                    .collect::<Vec<_>>())
            },
        )
        .await;

        let msg = res.expect_err("错误验证码不该通过").to_string();
        assert!(msg.contains("二次验证未通过"), "实际文案：{msg}");
    }

    /// 第一轮只问密码时（`--rounds 2` 的服务端第一轮就是这样），密码错要报
    /// 「密码不正确」，不能莫名其妙冒出一句「验证码错误」。
    #[tokio::test]
    #[ignore]
    async fn password_only_kbi_blames_password() {
        let mut session = connect("NEXUS_KBI_PORT2").await;

        let res = authenticate_kbi_with(
            &mut session,
            "tester",
            &AuthMethod::Password {
                password: "wrong".to_string(),
            },
            "probe",
            "127.0.0.1",
            move |p: AuthPrompt| async move {
                Ok(p.fields
                    .iter()
                    .map(|f| f.prefill.clone().unwrap_or_default())
                    .collect::<Vec<_>>())
            },
        )
        .await;

        let msg = res.expect_err("错误密码不该通过").to_string();
        assert!(msg.contains("密码不正确"), "实际文案：{msg}");
    }

    /// 用户点取消：立刻中止，并且文案是「取消」而不是「超时」。
    #[tokio::test]
    #[ignore]
    async fn cancel_aborts_immediately() {
        let mut session = connect("NEXUS_KBI_PORT").await;
        let started = std::time::Instant::now();

        let res = authenticate_kbi_with(
            &mut session,
            "tester",
            &password(),
            "probe",
            "127.0.0.1",
            move |_p: AuthPrompt| async move { Err(AskAbort::Cancelled) },
        )
        .await;

        let msg = res.expect_err("取消不该通过").to_string();
        assert!(msg.contains("已取消"), "实际文案：{msg}");
        // 取消必须立即返回，而不是等到 3 分钟超时
        assert!(started.elapsed() < std::time::Duration::from_secs(10));
    }

    /// 在**同一条已认证连接**上直接开 `sftp` 子系统。
    ///
    /// 这是文件面板免二次认证的核心机制：`SftpManager::connect` 拿到终端那条
    /// 连接的句柄后，走的就是下面这三步。对着 OTP 服务器，省掉的就是这一整轮
    /// 重新握手 + 重新要验证码。
    ///
    /// 用真实握手真实验证，而不是只测登记表 —— 出问题的往往正是「这条连接还能
    /// 不能再开通道」。
    #[tokio::test]
    #[ignore]
    async fn sftp_opens_on_already_authenticated_connection() {
        let mut session = connect("NEXUS_KBI_PORT").await;

        authenticate_kbi_with(
            &mut session,
            "tester",
            &password(),
            "probe",
            "127.0.0.1",
            move |p: AuthPrompt| async move {
                Ok(p.fields
                    .iter()
                    .map(|f| f.prefill.clone().unwrap_or_else(|| OTP.to_string()))
                    .collect::<Vec<_>>())
            },
        )
        .await
        .expect("认证应当成功");

        // 关键：不新建连接，用这条已认证的连接再开一条通道
        let channel = session
            .channel_open_session()
            .await
            .expect("在已认证连接上开通道失败");
        channel
            .request_subsystem(true, "sftp")
            .await
            .expect("请求 sftp 子系统失败（假服务端没注册 subsystem handler？）");

        let sftp = russh_sftp::client::SftpSession::new(channel.into_stream())
            .await
            .expect("sftp 版本协商失败");
        let names: Vec<String> = sftp
            .read_dir(".".to_string())
            .await
            .expect("列目录失败")
            .map(|e| e.file_name())
            .collect();

        assert!(
            names.iter().any(|n| n == "readme.txt"),
            "应当看到假目录里的 readme.txt，实际：{names:?}"
        );
    }

    /// 明文私钥登录：`open_authenticated` 全链路（握手 → 公钥认证）跑通。
    ///
    /// 需要先起公钥模式的假服务端，并把私钥路径传进来：
    /// ```bash
    /// ssh-keygen -t ed25519 -f /tmp/nx_key -N "" -C nexus-test
    /// python tests/kbi_server.py 2244 --pubkey /tmp/nx_key.pub
    /// NEXUS_PK_PORT=2244 NEXUS_PK_KEY=/tmp/nx_key cargo test --lib publickey_login -- --ignored
    /// ```
    #[tokio::test]
    #[ignore]
    async fn publickey_login_with_plain_key() {
        let path = std::env::var("NEXUS_PK_KEY").expect("需要 NEXUS_PK_KEY=<私钥路径>");
        let port: u16 = std::env::var("NEXUS_PK_PORT")
            .ok()
            .and_then(|p| p.parse().ok())
            .unwrap_or(2244);

        let opened = super::super::open_authenticated_with(
            "127.0.0.1",
            port,
            Arc::new(KnownHosts::new()),
            Duration::from_secs(10),
            super::super::LegacyPolicy::Auto,
            None,
            "tester",
            &AuthMethod::PrivateKey {
                path,
                passphrase: None,
            },
            "probe-key",
            None,
            // 明文私钥不该问任何问题；问了就说明判定逻辑错了
            |_p: AuthPrompt| async move { Err(AskAbort::Cancelled) },
        )
        .await;

        assert!(opened.is_ok(), "公钥登录应当成功：{:?}", opened.err());
    }

    /// 加密私钥：后端应主动弹「私钥口令」，答对后继续登录。
    ///
    /// 这条覆盖的是最容易断的一环 —— 如果没有这个弹窗，用户在会话里没填口令时
    /// 只会看到「该私钥已加密，需要提供密码」，而且没有任何补救入口。
    #[tokio::test]
    #[ignore]
    async fn encrypted_key_asks_passphrase_then_logs_in() {
        let path = std::env::var("NEXUS_PK_ENC_KEY").expect("需要 NEXUS_PK_ENC_KEY=<加密私钥路径>");
        let secret = std::env::var("NEXUS_PK_PASSPHRASE").unwrap_or_else(|_| "test-pass".into());
        let port: u16 = std::env::var("NEXUS_PK_ENC_PORT")
            .ok()
            .and_then(|p| p.parse().ok())
            .unwrap_or(2245);

        let seen = Arc::new(StdMutex::new(Vec::<AuthPrompt>::new()));
        let sink = seen.clone();
        let opened = super::super::open_authenticated_with(
            "127.0.0.1",
            port,
            Arc::new(KnownHosts::new()),
            Duration::from_secs(10),
            super::super::LegacyPolicy::Auto,
            None,
            "tester",
            &AuthMethod::PrivateKey {
                path,
                passphrase: None,
            },
            "probe-encrypted",
            None,
            move |p: AuthPrompt| {
                sink.lock().unwrap().push(p.clone());
                let answer = secret.clone();
                async move { Ok(vec![answer]) }
            },
        )
        .await;

        assert!(opened.is_ok(), "带口令的私钥应当能登录：{:?}", opened.err());
        let prompts = seen.lock().unwrap();
        assert_eq!(prompts.len(), 1, "只该问一次口令");
        assert_eq!(prompts[0].kind, "passphrase");
        assert_eq!(prompts[0].round, 1);
        assert!(!prompts[0].fields[0].echo, "口令必须隐藏回显");
    }

    /// 第一次输错口令：应当带着「不正确」的提示再问一次，而不是直接放弃。
    #[tokio::test]
    #[ignore]
    async fn wrong_passphrase_is_asked_again() {
        let path = std::env::var("NEXUS_PK_ENC_KEY").expect("需要 NEXUS_PK_ENC_KEY=<加密私钥路径>");
        let secret = std::env::var("NEXUS_PK_PASSPHRASE").unwrap_or_else(|_| "test-pass".into());
        let port: u16 = std::env::var("NEXUS_PK_ENC_PORT")
            .ok()
            .and_then(|p| p.parse().ok())
            .unwrap_or(2245);

        let attempts = Arc::new(std::sync::atomic::AtomicU32::new(0));
        let seen = Arc::new(StdMutex::new(Vec::<String>::new()));
        let counter = attempts.clone();
        let sink = seen.clone();
        let opened = super::super::open_authenticated_with(
            "127.0.0.1",
            port,
            Arc::new(KnownHosts::new()),
            Duration::from_secs(10),
            super::super::LegacyPolicy::Auto,
            None,
            "tester",
            &AuthMethod::PrivateKey {
                path,
                passphrase: None,
            },
            "probe-wrong",
            None,
            move |p: AuthPrompt| {
                let n = counter.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                sink.lock().unwrap().push(p.instructions.clone());
                let answer = if n == 0 { "definitely-wrong".to_string() } else { secret.clone() };
                async move { Ok(vec![answer]) }
            },
        )
        .await;

        assert!(opened.is_ok(), "第二次口令正确后应当登录成功：{:?}", opened.err());
        assert_eq!(attempts.load(std::sync::atomic::Ordering::SeqCst), 2);
        let msgs = seen.lock().unwrap();
        assert!(
            msgs[1].contains("不正确"),
            "第二轮应明确告知口令错：{:?}",
            msgs[1]
        );
    }
}

#[cfg(test)]
mod out_delivery_tests {
    //! 守住 [`spawn_event_delivery`] 的两条不变式。
    //!
    //! 这两条都是「改坏了也能编译、也能连上、只有真机上花屏/早关标签」的
    //! 类型，靠人眼 review 很难发现，所以必须有测试钉住：
    //! 1. `on_data` 的顺序 == 入队顺序（并发投递会让 xterm.js 花屏）；
    //! 2. `on_closed` 永远排在所有数据之后（否则前端提前关掉标签页，
    //!    用户看不到退出前的报错）。

    use super::*;
    use std::sync::Mutex as StdMutex;

    /// 记录回调的实际调用顺序。数据记成块里的序号，关闭记成 -1。
    struct OrderRecorder {
        seen: Arc<StdMutex<Vec<i32>>>,
    }

    impl TerminalEventListener for OrderRecorder {
        fn on_data(&self, _session_id: String, bytes: Vec<u8>) {
            let n = i32::from_le_bytes(bytes[..4].try_into().unwrap());
            // 第一块故意慢一点：若有人把投递改回「每块一个 spawn_blocking」
            // 的并发写法，这一块就会落到后面去，顺序断言随即失败。
            if n == 0 {
                std::thread::sleep(std::time::Duration::from_millis(20));
            }
            self.seen.lock().unwrap().push(n);
        }
        fn on_pulse(&self, _session_id: String) {}
        fn on_closed(&self, _session_id: String, _reason: String) {
            self.seen.lock().unwrap().push(-1);
        }
        fn on_auth_prompt(&self, _prompt: AuthPrompt) -> String {
            String::new()
        }
    }

    /// 块数取成队列容量的数倍，确保中途真的把队列压满、走到背压那条路上。
    const BLOCKS: i32 = (OUTPUT_QUEUE * 3) as i32;

    #[tokio::test]
    async fn delivers_in_order_and_closes_last() {
        let seen = Arc::new(StdMutex::new(Vec::new()));
        let listener = Arc::new(OrderRecorder { seen: seen.clone() });
        let (tx, task) = spawn_event_delivery(listener, "s1".to_string());

        for n in 0..BLOCKS {
            tx.send(OutEvent::Data(n.to_le_bytes().to_vec()))
                .await
                .expect("投递任务不应提前结束");
        }
        tx.send(OutEvent::Closed {
            reason: "exit".to_string(),
        })
        .await
        .expect("关闭事件应当能入队");
        drop(tx);
        task.await.expect("投递任务不应 panic");

        let got = seen.lock().unwrap().clone();
        let mut want: Vec<i32> = (0..BLOCKS).collect();
        want.push(-1);
        assert_eq!(got, want, "投递顺序必须与入队顺序严格一致，且关闭排在最后");
    }

    /// 收到 `Closed` 之后投递任务必须退出：之后就算还有数据也不该再调 `on_data`。
    #[tokio::test]
    async fn stops_after_closed() {
        let seen = Arc::new(StdMutex::new(Vec::new()));
        let listener = Arc::new(OrderRecorder { seen: seen.clone() });
        let (tx, task) = spawn_event_delivery(listener, "s2".to_string());

        tx.send(OutEvent::Closed {
            reason: "user".to_string(),
        })
        .await
        .unwrap();
        // 现实里不会有，但万一将来有人在 read_loop 退出后又塞了数据，
        // 前端也绝不能在「已关闭」之后再收到输出。
        let _ = tx.send(OutEvent::Data(7i32.to_le_bytes().to_vec())).await;
        drop(tx);
        task.await.unwrap();

        assert_eq!(seen.lock().unwrap().clone(), vec![-1]);
    }
}
