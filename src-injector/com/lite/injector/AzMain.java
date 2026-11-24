package com.lite.injector;

/**
 * AZclient 入口。
 *
 * 参数: [0]=agent jar 路径, [1]=exe 所在目录 (用于读取 azclient-auth.txt)
 *       --no-window 只起服务不开窗口 (无人值守验证用)
 *
 * 界面在浏览器里 (见 WebUi), 逻辑全在 Core。这个文件只负责把两边接起来。
 */
public class AzMain {

    public static void main(String[] args) throws Exception {
        // exe 传进来的就这两个: javaw -jar lite-injector.jar <agentJar> <exeDir>
        // --no-window: 只起服务不开窗口, 用来无人值守地验证页面 (截图 / 打接口)
        boolean openWindow = true;
        StringBuilder rest = new StringBuilder();
        for (String a : args) {
            if ("--no-window".equals(a)) { openWindow = false; continue; }
            rest.append(a).append('\u0000');
        }
        String[] pos = rest.toString().split("\u0000", -1);
        String agentPath = pos.length > 0 && !pos[0].isEmpty() ? pos[0] : "lite-agent.jar";
        String exeDir = pos.length > 1 && !pos[1].isEmpty()
                ? pos[1] : new java.io.File(".").getAbsolutePath();

        String url = WebUi.start(agentPath, exeDir, openWindow);
        // 打到 stdout: 无头截图 / 手动开浏览器排查都要用它
        System.out.println("AZclient UI " + url);

        // HttpServer 是非守护线程, main 退出后进程仍然活着 —— 这正是要的,
        // 它靠 watchdog 在页面关掉之后自己收尾 (见 WebUi.watchdog)。
    }
}
