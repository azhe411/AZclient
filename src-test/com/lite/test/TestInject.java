package com.lite.test;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.File;

/** 链路自检: 列出进程 -> attach -> loadAgent -> 检查日志是否生成 */
public class TestInject {
    public static void main(String[] args) throws Exception {
        File agent = new File("C:\\Users\\田哲睿\\Desktop\\liteclient-inject\\build\\lite-agent.jar");
        if (!agent.exists()) {
            System.out.println("AGENT NOT FOUND: " + agent);
            return;
        }
        // 找 SleepMain 的 pid
        String pid = null;
        for (VirtualMachineDescriptor v : VirtualMachine.list()) {
            if (v.displayName().contains("SleepMain")) {
                pid = v.id();
                System.out.println("FOUND SleepMain pid=" + pid + " name=" + v.displayName());
            }
        }
        if (pid == null) {
            System.out.println("SleepMain not running");
            return;
        }
        VirtualMachine vm = VirtualMachine.attach(pid);
        System.out.println("attached OK");
        try {
            vm.loadAgent(agent.getAbsolutePath());
            System.out.println("loadAgent returned WITHOUT exception");
        } catch (Exception e) {
            System.out.println("loadAgent EXCEPTION: " + e);
        }
        vm.detach();
        Thread.sleep(500);
        File log = new File(System.getProperty("java.io.tmpdir"), "liteclient-agent.log");
        System.out.println("log exists: " + log.exists() + " size=" + (log.exists() ? log.length() : 0));
        if (log.exists()) {
            System.out.println("--- LOG CONTENT ---");
            java.nio.file.Files.lines(log.toPath()).forEach(System.out::println);
        }
    }
}