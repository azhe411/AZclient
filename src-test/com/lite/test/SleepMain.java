package com.lite.test;

/** 假的目标进程: 一个空转 JVM, 模拟"游戏已启动" */
public class SleepMain {
    public static void main(String[] args) throws Exception {
        System.out.println("SleepMain started, pid=" + java.lang.management.ManagementFactory.getRuntimeMXBean().getName());
        Thread.sleep(60000);
    }
}