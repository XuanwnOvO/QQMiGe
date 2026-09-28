package com.QQMiGe.XXS

import android.util.Base64
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Root Shell 封装（KernelSU / Magisk 通用）。
 *
 * ── 授权模型与「弹窗只出现一次」 ────────────────────────────────
 * KernelSU / Magisk 的授权是按【调用者 uid + 包名】记忆的。
 * 之所以会反复弹窗，是因为之前的实现【每条命令都 new ProcessBuilder("su")】：
 * 每个 su 进程都是一次独立的授权请求，只要设备的「记住」设置没生效，
 * 就会一次一条地弹。一次列目录要起 3 个 su 进程 = 弹 3 次。
 *
 * 解法是【常驻 shell】：进程内只起一个 `su`（交互式），
 * 之后所有命令都写进它的 stdin、从 stdout 读结果。
 * 于是整个 App 生命周期内 su 进程只创建一次，弹窗也就只出现一次。
 *
 * ── mount namespace 遮蔽（读 /data/data/<其他包> 报 ENOENT 的根因）────
 * Android 10+ 每个应用进程活在自己的 per-app mount namespace 里。
 * KernelSU 的 `su` 【默认继承调用者的 namespace】，于是：
 *   su 是 root，但 shell 看到的 /data/user/0 只有本应用自己的挂载视图，
 *   其他包的目录（如 com.tencent.mobileqq）在那里【根本不存在】，
 *   内核返回的不是 EACCES 而是 ENOENT —— 伪装成「没有这个文件」。
 * 这就是「MT 管理器能看到、本应用看不到」的原因：MT 用 global ns。
 *
 * 对齐办法（本类按顺序尝试）：
 *   1. `su -M`（mount-master，KernelSU/Magisk 通用的 global ns 开关）
 *   2. `su` + `nsenter --mount=/proc/1/ns/mnt` 把 shell 自身换到 init 的 ns
 *   3. 一次性 `su -M -c` / `su -c`
 * 归一化后用 readlink 复核 ns 是否真的等于 init 的，避免 exec 失败造成「假成功」。
 *
 * ── 命令传递 ─────────────────────────────────────────────────
 * ❌ 错误做法：ProcessBuilder("su") 起进程、写完命令就【关掉 stdin】。
 *    KernelSU 的 su 默认启用 ksu fd wrapper，stdin 关闭时机与 wrapper
 *    的 fd 传递存在时序竞争，命令会被截断/丢弃，
 *    表现为 ls 拿到残缺路径 -> "No such file or directory"。
 * ✅ 正确做法：常驻 shell 保持 stdin【常开不关】，用唯一 token 分隔每次命令；
 *    回退路径用 `su -c "<cmd>"` 传参，与 MT 管理器 / Termux 一致。
 *
 * ── 身份自证 ─────────────────────────────────────────────────
 * 每条命令都带 `$?` 退出码，shell 启动时取一次 `id`。
 * KernelSU 授权失败时 su 会【降级返回普通权限 shell】而非报错，
 * 此时读 /data/data/<其他包>/ 得到的是伪装成 ENOENT 的拒绝，
 * 所以必须靠 uid=0 判定，不能靠命令是否报错。
 */
object RootShell {

    private const val ID_MARK = "__QQMIGE_ID__"
    private const val DEFAULT_TIMEOUT_MS = 20_000L

    /** readFileSmart 的默认前缀上限（字节）。这个默认值是给 AI 读文件用的：
     * 丢给大模型的上下文本来就只能喂前缀，读全文纯属浪费传输和 token。
     * 编辑器不再走这个默认值 —— 查看态用 [VIEWER_MAX_BYTES]。 */
    const val PREVIEW_MAX_BYTES = 256 * 1024

    /** readFileSmart 的默认前缀上限（行数）。同上，服务于 AI 读取。
     * 行数和字节数取先触发者：普通脚本先撞行数，被压成一整行的 bundle
     * 先撞字节。 */
    const val PREVIEW_MAX_LINES = 2_000

    /** 编辑态字符上限。EditText 的 DynamicLayout 要为整个 Editable 维护
     * 「段 → 行」映射，代价与文本量成正比且绕不过去。超过这个量级的文件
     * 进编辑就是必然 ANR，所以进入编辑前要先征求用户同意。 */
    const val EDITOR_MAX_CHARS = 300_000

    /** 编辑态单行宽度上限。压缩过的 JS 整个文件就是一行，总量限制根本挡不住
     * 它 —— 一行 30 万字符和一万行各 30 字符，字符总数一样，但换行器要在多长
     * 的范围内找断点完全不同，渲染代价差一个数量级。 */
    const val EDITOR_MAX_LINE_CHARS = 8_000

    /** 查看态字节上限。查看走只读 TextView，没有可编辑结构要维护，所以敢放到
     * 编辑态的 32 倍。这个数是「异常兜底」而不是「日常截断」：小游戏的脚本、
     * 配置、bundle 极少超过它。 */
    const val VIEWER_MAX_BYTES = 8 * 1024 * 1024

    /** 探测 self / init 的 mount namespace id，用于判断是否需要归一化 */
    private const val NS_PROBE =
        "S=\$(readlink /proc/self/ns/mnt); I=\$(readlink /proc/1/ns/mnt); echo \"NSPROBE|\$S|\$I\""

    /**
     * 数据访问前缀兜底。
     *
     * 若 su 既没进 global mount ns、`nsenter` 也不可用，还有最后一条路：
     * Linux 的 /proc/<pid>/root 是指向该进程根目录的符号链接，
     * 解析它时会【跟随进入那个进程的 mount namespace】。init(pid=1) 永远在
     * global ns，所以 /proc/1/root/data/data/<pkg> 能绕过本进程 ns 的遮蔽。
     * 代价只是路径多一段前缀，比 nsenter 更不挑环境（不需要额外二进制）。
     */
    private const val PREFIX_INIT_ROOT = "/proc/1/root"

    /** 探测兜底前缀是否真的有效：比对两个视图里的条目数 */
    private const val PREFIX_PROBE =
        "A=\$(ls -1 /data/user/0 2>/dev/null | wc -l | tr -d ' '); " +
            "B=\$(ls -1 /proc/1/root/data/user/0 2>/dev/null | wc -l | tr -d ' '); " +
            "echo \"PFPROBE|\$A|\$B\""

    /**
     * 常驻 shell 的 su 启动方式，按优先级尝试：
     *  - `su -M`  : 直接在 global mount ns 起交互式 shell（最理想，一步到位）
     *  - `su`     : 继承 app 的 ns，之后靠 nsenter 归一化
     * 第一个能通过校验（uid=0 且 ns 可见）的会被采用。
     */
    private val SU_CANDIDATES = listOf(
        arrayOf("su", "-M"),
        arrayOf("su")
    )

    /** 常驻 shell；为 null 表示尚未建立或已失效 */
    @Volatile
    private var persistent: PersistentShell? = null

    /** 常驻 shell 建立失败后置位，本进程内不再重试，直接走一次性模式 */
    @Volatile
    private var persistentDisabled = false

    /**
     * 命令里给绝对路径加的前缀。空串 = 不需要（shell 已在 global ns）；
     * [PREFIX_INIT_ROOT] = 借 init 的 mount namespace 绕过遮蔽。
     */
    @Volatile
    private var pathPrefix: String = ""

    private val startLock = Any()

    // ---------------- 对外 API ----------------

    /** 允许逐行读取命令输出 */
    fun execLines(command: String): List<String> = exec(command).output.lines()

    /**
     * 执行命令（stdout + stderr 合并），并自证 root 身份。
     *
     * 路由顺序：
     *  1. 已有常驻 shell -> 直接用
     *  2. 尚未建立 -> 尝试建立（这一步会触发唯一一次授权弹窗）
     *  3. 建立失败 -> 回退「一次性 su -c」模式，并附带 -M 兜底
     */
    fun exec(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult {
        // 1 & 2
        val shell = obtainPersistent()
        if (shell != null) {
            val r = shell.exec(command, timeoutMs)
            if (r != null) return r
            // 进程已死或被撤销授权：销毁并永久回退
            synchronized(startLock) {
                persistent = null
                persistentDisabled = true
            }
        }

        // 3 回退：一次性 su -c
        val first = execOneShot(command, mountMaster = false, timeoutMs = timeoutMs)
        if (first.success) return first
        if (!first.isRoot) return first

        val second = execOneShot(command, mountMaster = true, timeoutMs = timeoutMs)
        return if (second.success) second else first
    }

    /** 检测 root 是否可用：`id` 应返回 uid=0(root)。 */
    fun isRootAvailable(): Boolean = exec("true").isRoot

    /** 返回身份行，便于界面展示排查。 */
    fun whoami(): String = exec("true").idLine.ifBlank { "无法执行 su" }

    /** 当前是否走常驻 shell（界面可用它说明「已授权，后续不再弹窗」） */
    fun isPersistent(): Boolean = obtainPersistent() != null

    /** 主动关闭常驻 shell（退出应用时调用，释放 su 进程） */
    fun shutdown() {
        synchronized(startLock) {
            persistent?.close()
            persistent = null
        }
    }

    // ---------------- 常驻 shell ----------------

    private fun obtainPersistent(): PersistentShell? {
        persistent?.let { if (it.isAlive()) return it }
        if (persistentDisabled) return null

        synchronized(startLock) {
            persistent?.let { if (it.isAlive()) return it }
            if (persistentDisabled) return null

            // 逐个候选启动方式试；能建起来但校验不过（非 root / ns 仍被遮蔽）就换下一个
            for (args in SU_CANDIDATES) {
                val shell = try {
                    PersistentShell(args)
                } catch (e: Exception) {
                    continue
                }
                if (shell.isUsable()) {
                    // shell 就绪后立刻定下数据访问通道，之后所有文件操作都按它拼路径
                    pathPrefix = probePathPrefix(shell)
                    persistent = shell
                    return shell
                }
                shell.close()
            }

            persistentDisabled = true
            return null
        }
    }

    /**
     * 常驻的交互式 su shell。
     *
     * 结构：一个 su 进程 + 一个读线程。
     * 读线程把 stdout 逐行塞进阻塞队列，exec 时按 token 从队列取行，
     * 这样「读取」天然支持超时（poll 带时限），不会像 readLine 那样永久阻塞。
     */
    private class PersistentShell(suArgs: Array<String>) {

        private val process: Process =
            ProcessBuilder(*suArgs).redirectErrorStream(true).start()

        private val stdin = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
        private val queue = LinkedBlockingQueue<String>()
        private val lock = Any()

        @Volatile
        private var alive = true

        /** su shell 的 id 原始输出，启动时取一次 */
        var idLine: String = ""
            private set

        /** shell 最终所在 mount namespace；空串表示未取到 */
        var namespace: String = ""
            private set

        /** 是否成功站到 init（global）的 mount namespace 上 */
        var namespaceAligned: Boolean = false
            private set

        init {
            val reader = Thread {
                try {
                    val r = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
                    while (true) {
                        val line = r.readLine() ?: break
                        queue.put(line)
                    }
                } catch (_: Exception) {
                    // 进程被杀 / 流被关，按结束处理
                } finally {
                    alive = false
                    queue.put(POISON)
                }
            }
            reader.isDaemon = true
            reader.start()

            // 启动后的握手与 namespace 归一化；任何一步失败都把 alive 置 false
            bootstrap()
        }

        /**
         * 启动握手：
         *  1. 取一次 `id`，确认 su 真的给了 uid=0（KernelSU 授权失败会降级返回普通 shell）
         *  2. 把 shell 换到 init 的 mount namespace，否则看不到其他包的数据目录
         *  3. 再取一次 `id` 并吸收 prompt 噪声
         */
        private fun bootstrap() {
            // 预热：丢掉 shell 启动横幅 + 取身份行
            idLine = runCommand("id", 15_000)?.first?.trim().orEmpty()
            if (idLine.isBlank()) {
                alive = false
                return
            }

            // 关键一步：把自己换到 global mount namespace，否则看不到其他包的数据目录
            alignMountNamespace()

            // 归一化后再确认身份（nsenter 后是新 shell，可能换了进程）
            val again = runCommand("id", 10_000)?.first?.trim().orEmpty()
            if (again.contains("uid=0")) idLine = again

            // 吸收切换过程残留的 prompt 噪声，避免污染第一条真实命令的输出
            runCommand("true", 5_000)
        }

        fun isAlive(): Boolean = alive && process.isAlive

        fun isRoot(): Boolean = idLine.contains("uid=0")

        /** 可用 = 活着 + 真 root + ns 已对齐（或本来就在同一 ns） */
        fun isUsable(): Boolean = isAlive() && isRoot() && namespaceAligned

        /**
         * 把常驻 shell 换到 init 的 mount namespace。
         *
         * 用 `exec nsenter ...` 而不是起子 shell：exec 会用 nsenter 替换当前
         * shell 进程，stdin/stdout 保持不变，于是 token 协议继续有效，
         * 而不会出现「父 shell 与子 shell 抢读同一个 stdin」。
         *
         * 换完后必须 readlink 复核：若 nsenter 不存在或 SELinux 拒绝，
         * exec 会失败、shell 原地不动，只看 `id` 会得到「假成功」。
         */
        private fun alignMountNamespace(): Boolean {
            val probe = runCommand(NS_PROBE, 10_000) ?: return false
            val line = probe.first.lineSequence()
                .firstOrNull { it.startsWith("NSPROBE|") } ?: return false
            val parts = line.trim().split("|")
            if (parts.size < 3) return false

            val selfNs = parts[1].trim()
            val initNs = parts[2].trim()
            if (initNs.isBlank()) return false

            if (selfNs == initNs) {
                namespace = initNs
                namespaceAligned = true
                return true
            }

            // ns 不一致：切入 init 的 mount ns
            try {
                stdin.write("exec nsenter --mount=/proc/1/ns/mnt -- /system/bin/sh\n")
                stdin.flush()
            } catch (e: Exception) {
                return false
            }

            Thread.sleep(1_500)   // 等 nsenter 接管 + 新 shell 出 prompt
            drainQueue()

            val check = runCommand("readlink /proc/self/ns/mnt; id", 10_000) ?: return false
            val nowNs = check.first.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("mnt:") }
                .orEmpty()

            namespace = nowNs
            namespaceAligned = nowNs == initNs && check.first.contains("uid=0")
            return namespaceAligned
        }

        /** 丢弃队列里已堆积的行（切换 shell 时的 prompt 噪声） */
        private fun drainQueue() {
            val sink = ArrayList<String>()
            queue.drainTo(sink)
        }

        /** 执行一条命令，返回 ShellResult；null 表示 shell 已失效 */
        fun exec(command: String, timeoutMs: Long): ShellResult? {
            if (!isAlive()) return null
            val r = synchronized(lock) { runCommand(command, timeoutMs) } ?: return null
            val root = isRoot()
            return ShellResult(
                success = r.second == 0 && root,
                output = if (root) r.first.trim() else idLine,
                exitCode = r.second,
                isRoot = root,
                idLine = idLine,
                usedMountMaster = namespaceAligned
            )
        }

        /**
         * 把命令写进 stdin，读输出直到 token 行。
         *
         * 写成三行：命令体、(cmd) 的 stderr 合并、携带 $? 的 token。
         * token 用纳秒时间戳保证唯一，不会与命令输出碰撞。
         * 命令体允许是多行脚本（writeTextFile 会用到），shell 会自行聚合复合命令。
         */
        private fun runCommand(command: String, timeoutMs: Long): Pair<String, Int>? {
            val token = "__QQMIGE_${System.nanoTime()}__"
            try {
                stdin.write(command)
                if (!command.endsWith("\n")) stdin.write("\n")
                stdin.write("echo \"$token:\$?\"\n")
                stdin.flush()
            } catch (e: Exception) {
                alive = false
                return null
            }

            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) return null

                val line = try {
                    queue.poll(remain, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    return null
                } ?: return null

                if (line === POISON) {
                    alive = false
                    return null
                }
                if (line.startsWith("$token:")) {
                    val code = line.removePrefix("$token:").trim().toIntOrNull() ?: 0
                    return sb.toString() to code
                }
                sb.append(line).append('\n')
            }
        }

        fun close() {
            alive = false
            try {
                stdin.write("exit\n")
                stdin.flush()
            } catch (_: Exception) {
            }
            process.destroy()
        }

        companion object {
            /** 读线程结束时的哨兵，用于唤醒正在 poll 的调用方 */
            private val POISON = String("__QQMIGE_POISON__".toCharArray())
        }
    }

    // ---------------- 一次性模式（回退路径） ----------------

    /**
     * 实际执行：`su [-M] -c "<cmd>"`。
     *
     * 仅在常驻 shell 不可用时使用。用 -c 传参、不写 stdin，
     * 避免 ksu fd wrapper 的时序竞争。
     */
    private fun execOneShot(command: String, mountMaster: Boolean, timeoutMs: Long): ShellResult {
        var process: Process? = null
        return try {
            val wrapped = "id; echo $ID_MARK; $command"
            val args = if (mountMaster) {
                arrayOf("su", "-M", "-c", wrapped)
            } else {
                arrayOf("su", "-c", wrapped)
            }

            process = ProcessBuilder(*args).redirectErrorStream(true).start()
            val raw = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return ShellResult(false, raw.trim(), -1, isRoot = false, timedOut = true)
            }

            val idx = raw.indexOf(ID_MARK)
            val idLine = if (idx >= 0) raw.substring(0, idx).trim() else raw.trim()
            val body = if (idx >= 0) raw.substring(idx + ID_MARK.length).trim() else ""
            val isRoot = idLine.contains("uid=0")

            ShellResult(
                success = process.exitValue() == 0 && isRoot,
                output = if (isRoot) body else idLine,
                exitCode = process.exitValue(),
                isRoot = isRoot,
                idLine = idLine,
                usedMountMaster = mountMaster
            )
        } catch (e: Exception) {
            ShellResult(false, e.message ?: "su 执行失败", -1, isRoot = false)
        } finally {
            process?.destroy()
        }
    }

    // ---------------- 目录 / 文件操作 ----------------

    private const val M_DIRS = "<<<QQMIGE_DIRS>>>"
    private const val M_NS = "<<<QQMIGE_NS>>>"

    /**
     * 选定数据访问通道。
     *
     * 判据：同一个 `/data/user/0`，在「本 shell 的视图」与「init 的视图
     * (/proc/1/root)」里能看到的条目数。若后者明显更多，说明本 shell 的
     * per-app mount namespace 把别的包遮蔽了，此时改用 init 的视图访问。
     *
     * 返回空串表示不需要前缀（shell 已在 global ns，或两条路都走不通）。
     */
    private fun probePathPrefix(shell: PersistentShell): String {
        val r = shell.exec(PREFIX_PROBE, 20_000) ?: return ""
        val line = r.output.lineSequence()
            .firstOrNull { it.startsWith("PFPROBE|") } ?: return ""
        val parts = line.trim().split("|")
        if (parts.size < 3) return ""

        val own = parts[1].trim().toIntOrNull() ?: 0
        val init = parts[2].trim().toIntOrNull() ?: 0

        // init 那边能看到更多包 -> 本 shell 被遮蔽，借它的 namespace 走
        return if (init > own && init > 1) PREFIX_INIT_ROOT else ""
    }

    /**
     * 把「逻辑路径」映射成 shell 里实际可访问的路径。
     *
     * 对上层完全透明：listDir/readTextFile/writeTextFile 收发的都是逻辑路径，
     * 只有拼进 shell 命令那一刻才加上前缀，返回给界面的仍是原路径。
     */
    private fun shellPath(path: String): String =
        if (pathPrefix.isEmpty()) path else pathPrefix + path

    /** 当前数据访问通道，供诊断显示 */
    fun dataChannel(): String =
        if (pathPrefix.isEmpty()) "直接访问（shell 已在 init 的 mount namespace）"
        else "$pathPrefix（借 init 的 mount namespace 绕过遮蔽）"

    /** 供需要自己拼 shell 命令的调用方使用：逻辑路径 -> shell 可访问路径 */
    fun mapPath(path: String): String = shellPath(path)

    /**
     * 反向映射：把 shell 侧路径还原成逻辑路径。
     *
     * 用于校验类场景（如 `readlink -f` 的输出）：加上前缀后路径多了一段，
     * 直接跟沙箱根比较会误判为「逃逸」。readlink 若已把 /proc/1/root 解析掉，
     * 这里原样返回也不会出错。
     */
    fun unmapPath(path: String): String =
        if (pathPrefix.isNotEmpty() && path.startsWith(pathPrefix)) path.removePrefix(pathPrefix)
        else path

    /**
     * 读取目录内容（目录 + 文件）。
     *
     * 合并为【一个脚本一次 su 调用】，包含四段：
     *  1. ls -1a 取条目名
     *  2. find -maxdepth 1 -type d 取子目录名（用于判类型）
     *  3. readlink /proc/self/ns/mnt 与 /proc/1/ns/mnt 取 namespace（诊断用）
     *  4. 命中 ENOENT 时额外 dump 挂载视图，用来区分「目录真不存在」与
     *     「per-app mount namespace 把别的包遮蔽了」
     *
     * 为什么判类型不用 `test -d` 逐名探测：那需要把文件名【重新拼回 shell 命令】，
     * 名字里的空白/特殊字符会在「拼接 -> shell 解析 -> 输出 -> 再切分」这一圈里被破坏，
     * 结果是目录被误判成文件（点击时走 head 而不是进目录），
     * 或切出残缺名字拼成伪路径（表现为 No such file）。
     * 用 find 则只传【目录】给 shell，不重建任何文件名，输出即完整路径。
     */
    fun listDir(path: String): DirResult {
        val normalized = path.trimEnd('/')
        // 真正传给 shell 的路径：可能带 /proc/1/root 前缀
        val shellDir = shellPath(normalized)
        val prefixDesc = if (pathPrefix.isEmpty()) "直接访问" else pathPrefix

        val script = buildString {
            append("ls -1a \"").append(shellDir).append("\" 2>&1\n")
            append("echo '").append(M_DIRS).append("'\n")
            append("find \"").append(shellDir).append("\" -maxdepth 1 -type d 2>/dev/null\n")
            append("echo '").append(M_NS).append("'\n")
            append("echo \"self=\$(readlink /proc/self/ns/mnt) init=\$(readlink /proc/1/ns/mnt)\"\n")
        }

        val result = exec(script)
        val nsLine = result.output.substringAfter(M_NS, "").trim()
            .lines().firstOrNull().orEmpty()

        if (!result.isRoot) {
            return DirResult(false, emptyList(), DirError.NOT_ROOT, result.output, result.idLine, nsLine)
        }

        val listingPart = result.output.substringBefore(M_DIRS)
        val dirsPart = result.output.substringAfter(M_DIRS, "").substringBefore(M_NS)
        val lower = listingPart.lowercase()

        if (lower.contains("permission denied")) {
            return DirResult(
                false, emptyList(), DirError.PERMISSION_DENIED,
                listingPart.trim(), result.idLine, nsLine
            )
        }
        if (lower.contains("no such file") || lower.contains("not a directory")) {
            // 关键诊断：ENOENT 属于「目录真不存在」还是「mount ns 遮蔽」。
            // 若两个 ns 不同名，说明 shell 没站在 init 的 ns 上，
            // /data/data/<其他包> 在本 ns 里压根不存在，必然 ENOENT。
            val diag = exec(
                "echo '--- 通道 ---'; echo \"$prefixDesc\"; echo \"实际路径: $shellDir\"; " +
                    "echo '--- stat ---'; stat \"$shellDir\" 2>&1; " +
                    "echo '--- stat parent ---'; stat \"${shellDir.substringBeforeLast('/')}\" 2>&1; " +
                    "echo '--- ns ---'; echo \"self=\$(readlink /proc/self/ns/mnt) init=\$(readlink /proc/1/ns/mnt)\"; " +
                    "echo '--- /data/user/0 (head) ---'; ls -1 /data/user/0 2>&1 | head -20; " +
                    "echo '--- /proc/1/root/data/user/0 (head) ---'; ls -1 /proc/1/root/data/user/0 2>&1 | head -20; " +
                    "echo '--- mounts on /data ---'; mount 2>/dev/null | grep ' /data' | head -20"
            ).output
            return DirResult(
                false, emptyList(), DirError.NOT_FOUND,
                "${listingPart.trim()}\n\n$diag", result.idLine, nsLine
            )
        }

        val names = listingPart.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .distinct()
            .toList()

        val dirNames = dirsPart.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != shellDir && it != "$shellDir/"}
            .map { it.trimEnd('/').substringAfterLast('/') }
            .filter { it.isNotEmpty() }
            .toSet()

        // 诊断：若名字里出现空白字符，说明名字在传输链路上被污染，
        // 直接把逐字节 dump 带回界面（od -c 能显示换行/制表/空格）
        val suspicious = names.any { it.any { c -> c.isWhitespace() } }
        val nameDump = if (suspicious) {
            exec("ls -1a \"$shellDir\" | od -c | head -40 2>&1").output
        } else ""

        val entries = names.map { name ->
            DirEntry(
                name = name,
                isDirectory = dirNames.contains(name),
                fullPath = "$normalized/$name"
            )
        }.sortedWith(compareByDescending<DirEntry> { it.isDirectory }.thenBy { it.name.lowercase() })

        return DirResult(
            ok = true,
            entries = entries,
            error = null,
            raw = if (suspicious) {
                "${listingPart.trim()}\n\n--- name bytes (od -c) ---\n$nameDump"
            } else listingPart.trim(),
            idLine = result.idLine,
            namespace = nsLine
        )
    }

    /** 列出指定目录下的文件夹名称。 */
    fun listFolders(path: String): List<String> =
        listDir(path).entries.filter { it.isDirectory }.map { it.name }

    /**
     * readFileSmart 的结果。
     *
     * [truncated] 是安全开关：为 true 时 content 只是文件前缀，
     * 此时【绝不能】拿编辑器内容覆盖整个文件，否则会把后面的字节截掉。
     */
    data class FileRead(
        val ok: Boolean,
        /** 文本判定；由 NUL 字节嗅探得出，规则见 looksBinary */
        val isText: Boolean,
        /** 文件总字节数；-1 表示取不到 */
        val sizeBytes: Long,
        /** 已读内容；ok=false 或二进制时为空串 */
        val content: String,
        /** 内容只是文件前缀（后面还有），此时必须锁住保存 */
        val truncated: Boolean,
        /** 原始输出，ok=false 时供界面显示诊断 */
        val raw: String = ""
    )

    /**
     * 智能读取：一次 su 往返拿到「是否文本 + 总字节 + 前若干内容」。
     *
     * 三处刻意的取舍，都是为了把单次往返里的 shell 命令压到最少 ——
     * 每条命令都是独立的进程或整文件扫描，比传输本身贵得多：
     *
     * 1) 不用 `file -b` 判类型。toybox 的 file 要起进程再解析 magic 数据库，
     *    在 Android 上常是几百毫秒，比读 256KB 还贵。二进制判定改在 Kotlin 侧
     *    做 NUL 字节嗅探（见 [looksBinary]），零成本且判据更硬。
     * 2) 用 `stat -c %s` 而不是 `wc -c` 取大小。stat 是 O(1) 系统调用。
     * 3) 不统计整文件行数。`wc -l` 要读完整个文件，MB 级就是几百毫秒纯等待，
     *    而总行数只用于界面提示 —— 直接砍掉，不为此付整文件扫描的代价。
     *
     * 为什么限行 + 限字节双上限：MB 级文件卡的不是传输，是 EditText 装载后的
     * DynamicLayout —— 要为每一行算基线。两个上限取先触发者：
     *  - 普通脚本：先撞行数上限
     *  - 被压成一整行的 bundle：先撞字节上限（否则 head -n 等于读全文件）
     *
     * 内容用 base64 回传：文本自带的行很可能与分隔标记同形，base64 是纯 ASCII、
     * 行边界无歧义，解析不会被内容污染；代价是 +33% 体积，换掉的是
     * 「逐行拼接再还原换行」的精度问题。
     */
    fun readFileSmart(
        path: String,
        maxBytes: Int = PREVIEW_MAX_BYTES,
        maxLines: Int = PREVIEW_MAX_LINES
    ): FileRead {
        val marker = "QQMIGE_${System.nanoTime()}"
        val target = shellPath(path)

        val script = buildString {
            append("P=\"").append(target).append("\"\n")
            // stat 取不到（部分 toybox 版本/特殊文件）再退回 wc -c
            append("SZ=\$(stat -c %s \"\$P\" 2>/dev/null || wc -c < \"\$P\" 2>/dev/null)\n")
            append("echo \"SZ|\$SZ\"\n")
            append("echo 'CT|").append(marker).append("'\n")
            append("head -c ").append(maxBytes).append(" \"\$P\" 2>/dev/null | head -n ")
                .append(maxLines).append(" | base64\n")
            append("echo '").append(marker).append("|END'\n")
        }

        val res = exec(script, timeoutMs = 90_000)
        if (!res.isRoot) {
            return FileRead(
                ok = false, isText = false, sizeBytes = -1,
                content = "", truncated = false, raw = res.output
            )
        }

        val out = res.output
        val size = out.lineSequence()
            .firstOrNull { it.startsWith("SZ|") }
            ?.substringAfter('|')?.trim()?.toLongOrNull() ?: -1L

        val ctMark = "CT|$marker"
        val endMark = "$marker|END"
        val from = out.indexOf(ctMark)
        val to = out.indexOf(endMark)
        if (from < 0 || to <= from) {
            return FileRead(
                ok = false, isText = false, sizeBytes = size,
                content = "", truncated = false, raw = out
            )
        }

        val b64 = out.substring(from + ctMark.length, to).filter { !it.isWhitespace() }
        val bytes = try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (_: Exception) {
            ByteArray(0)
        }

        // 读到的字节少于总字节 -> 后面还有内容没进来
        val truncated = size > 0 && bytes.size.toLong() < size

        if (looksBinary(bytes)) {
            return FileRead(
                ok = true, isText = false, sizeBytes = size,
                content = "", truncated = truncated, raw = out
            )
        }

        // head -c 可能把某个多字节字符截成半个，解码后尾部会留替换符，去掉
        var text = String(bytes, Charsets.UTF_8)
        while (text.endsWith('\uFFFD')) text = text.dropLast(1)

        return FileRead(
            ok = true, isText = true, sizeBytes = size,
            content = text, truncated = truncated, raw = out
        )
    }

    /**
     * 二进制嗅探：只看开头 8KB，出现 NUL 字节即判为二进制。
     *
     * 这是 git、`grep -I`、`file(1)` 内部共用的判据。为什么比关键字匹配好：
     *  - NUL 在 UTF-8 / GBK / ASCII 文本里几乎不可能出现，命中即铁证；
     *  - file 的那套关键字（executable/binary/zip…）对压缩过的 JS、
     *    带 BOM 的 JSON 都会误判，而这些恰恰是 QQ 小游戏的常见形态。
     *
     * 代价是 UTF-16 文本会被误判成二进制 —— 但小游戏的 JS 全是 UTF-8，
     * 且 UTF-16 本来也没法在编辑器里正常改，可接受。
     *
     * 只嗅探前缀的头部，不是整个 256KB：NUL 检测没有任何可累积的收益，
     * 扫 8KB 足够，越界扫描纯浪费。
     */
    private fun looksBinary(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 8 * 1024)
        for (i in 0 until n) {
            if (bytes[i] == 0.toByte()) return true
        }
        return false
    }

    /**
     * 查看态读取：全文，基本不截断。
     *
     * 为什么查看态敢读全文：只读 TextView 用 StaticLayout，一次布局、按行按需
     * 绘制，没有可编辑状态要维护。EditText 的 DynamicLayout 要为整个 Editable
     * 维护「段 → 行」映射并随每次输入增量更新 —— 那才是卡死的原因，跟文本量
     * 成正比且绕不过去。换成只读控件后，「不截断」和「不卡」才能同时成立。
     *
     * 连行数都不限：超宽行（压缩过的 bundle 整个文件就是一行）交给 TextView 的
     * 换行去消化即可，不需要切进那一行内部。
     */
    fun readFileForView(path: String): FileRead =
        readFileSmart(path, maxBytes = VIEWER_MAX_BYTES, maxLines = Int.MAX_VALUE)

    /**
     * 写回文本内容。
     *
     * 两个关键点：
     *
     * 1) 内容【不直接拼进 shell 命令】。文本里可能有引号、反引号、$、换行等
     *    一切会破坏 shell 解析的字符，所以先做 base64 编码，
     *    命令里只出现 [A-Za-z0-9+/=]，再在设备侧 base64 -d 还原。
     *
     * 2) 脚本本身【是多行的】，每行一个 printf，最后用 `{ ... }` 组成复合命令再解码。
     *    写成多行而不是超长单行，是为了避开两个上限：
     *    - 单行过长会被交互式 shell 的读行缓冲截断
     *    - 一次性模式下的 `su -c` 受 MAX_ARG_STRLEN（约 128KB）限制
     *    多行脚本在两种模式下都能正确聚合执行。
     */
    fun writeTextFile(path: String, content: String): Boolean {
        val encoded = android.util.Base64.encodeToString(
            content.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )

        val script = buildString {
            append("{\n")
            encoded.chunked(4000).forEach { chunk ->
                append("printf '%s' '").append(chunk).append("'\n")
            }
            append("} | base64 -d > \"").append(shellPath(path)).append("\"\n")
        }

        val r = exec(script, timeoutMs = 60_000)
        return r.isRoot && r.exitCode == 0
    }

    /** 计算目录大小（人类可读），失败返回 null。 */
    fun duSize(path: String): String? {
        val r = exec("du -sh \"${shellPath(path)}\" 2>/dev/null")
        return r.output.trim().substringBefore('\t').ifBlank { null }
    }

    /**
     * 递归删除文件或目录。
     *
     * `rm -rf` 对不存在的路径也返回 0，光看 exitCode 会把「没删掉」
     * 误判成成功，所以删完立刻 `test -e` 复查一次残留。
     * 两条命令写在同一个脚本里，只花一次 su 往返。
     */
    fun deletePath(path: String): Boolean {
        val p = shellPath(path)
        val script = buildString {
            append("rm -rf \"").append(p).append("\" 2>&1\n")
            append("if [ -e \"").append(p).append("\" ]; then echo 'DEL|FAIL'; ")
            append("else echo 'DEL|OK'; fi\n")
        }
        val r = exec(script, timeoutMs = 120_000)
        return r.isRoot && r.output.contains("DEL|OK")
    }

    // ---------------- 数据结构 ----------------

    data class DirEntry(
        val name: String,
        val isDirectory: Boolean,
        val fullPath: String
    )

    data class DirResult(
        val ok: Boolean,
        val entries: List<DirEntry>,
        val error: DirError?,
        val raw: String,
        val idLine: String = "",
        /** su shell 所在的 mount namespace，用于诊断 ENOENT 是否为 namespace 遮蔽 */
        val namespace: String = ""
    )

    enum class DirError { PERMISSION_DENIED, NOT_FOUND, NOT_ROOT }

    data class ShellResult(
        val success: Boolean,
        val output: String,
        val exitCode: Int,
        /** 是否取得真正的 uid=0（而非 KernelSU 降级返回的普通 shell） */
        val isRoot: Boolean,
        /** su shell 的 `id` 原始输出，用于界面排查 */
        val idLine: String = "",
        val timedOut: Boolean = false,
        /** 本条命令是否走了 mount-master（global namespace） */
        val usedMountMaster: Boolean = false
    )
}
