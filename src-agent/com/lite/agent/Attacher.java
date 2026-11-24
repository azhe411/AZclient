package com.lite.agent;

import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;

import java.io.File;

/**
 * 无界面 attacher —— 给引擎用的「按 PID 挂 agent」入口。
 *
 * <pre>
 *   java -cp lite-agent.jar com.lite.agent.Attacher &lt;pid&gt; &lt;agentJar&gt;
 * </pre>
 *
 * <h3>为什么需要这个（而不是命令行 -javaagent）</h3>
 *
 * 原来我们在游戏命令行里插 {@code -javaagent:<jar>}。功能上它是对的 —— 实测它在
 * 类加载前就挂上了 transformer，三个核心类全是 {@code NOT LOADED yet (will transform
 * on load)}，一次 retransform 都不需要，{@code [hook] Hook init OK} 也打出来了。
 *
 * 但它**进不去服**：命令行里多出 {@code -javaagent} 之后，网易那个 Themida 加壳的
 * native 认证会判定「非官方启动环境」并**自毁**（进程退出码 {@code 0xFF5674D5}，
 * 与已知的 {@code 0xFF56602A} / {@code 0xFF56752A} 同族）。对照实验：
 *
 * <pre>
 *   16:33:51  重写命令行：ctrl, lport, server, port                  → 16:34:35 ★ 进服成功！
 *   17:27:51  重写命令行：ctrl, lport, server, port, agent, agent_jar → 17:28:30 退出 0xFF5674D5
 * </pre>
 *
 * 两次唯一的差别就是 agent 那一段（{@code -XX:+DisableAttachMechanism} 两次都删了）。
 *
 * 所以改成：命令行**保持干净**，游戏起来之后由引擎按 PID attach 进去。attach 走的是
 * 命名管道（{@code \\.\pipe\java_pid<pid>}），不体现在命令行里。
 *
 * <h3>时机</h3>
 *
 * 必须在游戏类加载**之前**挂上。实测游戏从启动到加载 EntityRenderer 大约 12 秒
 * （17:27:57 启动 → 17:28:09 transformer 命中渲染类），而本进程启动加 attach 只要
 * 1～3 秒，所以引擎在 spawn 后等 3 秒再调这里，余量充足。
 *
 * <h3>关于 AgentInitializationException</h3>
 *
 * Windows + Forge 环境下 {@code loadAgent} 常常抛
 * 「Agent JAR loaded but agent failed to initialize」，而 agent 其实**已经跑起来了**
 * （看 D:\temp\liteclient-agent.log 里的 "Hook init OK"）。这是已知现象，不能当失败处理，
 * 所以这里捕获它、打印出来、照样返回 0 —— 真正的判据是 agent 日志。
 */
public final class Attacher {

    private Attacher() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("用法: java -cp lite-agent.jar com.lite.agent.Attacher <pid> <agentJar>");
            System.exit(2);
        }

        String pid = args[0].trim();
        String jar = args[1].trim();
        // 可选第三个参数 = 传给 agentmain 的 args（"null" 表示只加载不做事，见 LiteAgent）
        String agentArgs = args.length >= 3 ? args[2] : null;

        if (!new File(jar).isFile()) {
            System.out.println("ATTACH-FAIL agent jar 不存在: " + jar);
            System.exit(3);
        }

        // 重试: 游戏 JVM 刚起来时 attach 管道可能还没建好。
        // 在同一个 JVM 里重试比让引擎反复起进程便宜得多。
        Throwable last = null;
        for (int i = 1; i <= 6; i++) {
            VirtualMachine vm = null;
            try {
                vm = VirtualMachine.attach(pid);
                vm.loadAgent(jar, agentArgs);
                System.out.println("ATTACH-OK pid=" + pid + " 第" + i + "次"
                        + (agentArgs == null ? "" : " args=" + agentArgs));
                return;
            } catch (AgentInitializationException e) {
                // ★ 已知现象, 按成功处理 —— 见类注释。
                System.out.println("ATTACH-OK pid=" + pid + " 第" + i
                        + "次 (AgentInitializationException, 已知无害: " + e + ")");
                return;
            } catch (AgentLoadException e) {
                last = e;
                System.out.println("ATTACH-RETRY 第" + i + "次 AgentLoadException: " + e);
            } catch (AttachNotSupportedException e) {
                // attach 机制被关掉了 (-XX:+DisableAttachMechanism), 重试没用。
                System.out.println("ATTACH-FAIL attach 不可用 (可能还带着 -XX:+DisableAttachMechanism): " + e);
                System.exit(4);
            } catch (Throwable e) {
                // ★ 必须是 Throwable, 不能只 catch Exception。
                //   JRE 不带 attach.dll 时抛的是
                //       java.lang.UnsatisfiedLinkError: sun.tools.attach.WindowsVirtualMachine.init()V
                //   那是 Error 不是 Exception —— 只 catch Exception 会让它漏出去, 变成一堆栈,
                //   引擎在日志里就只能看到最后一行 "at com.lite.agent.Attacher.main(Attacher.java:75)"。
                //   真机上踩过这个。Error 类的问题重试没用, 直接报出来。
                if (e instanceof Error) {
                    System.out.println("ATTACH-FAIL " + e.getClass().getName() + ": " + e.getMessage()
                            + "  (多半是缺 attach.dll —— 需要用 -Djava.library.path 指过去)");
                    System.exit(6);
                }
                last = e;
                System.out.println("ATTACH-RETRY 第" + i + "次 " + e.getClass().getSimpleName() + ": " + e);
            } finally {
                if (vm != null) {
                    try {
                        vm.detach();
                    } catch (Throwable ignored) {
                        // detach 失败不影响结果
                    }
                }
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println("ATTACH-FAIL pid=" + pid + " 重试 6 次仍失败: " + last);
        System.exit(5);
    }
}
