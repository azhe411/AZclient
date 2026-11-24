package com.lite.test;

import com.lite.agent.LiteAgent;
import java.io.FileOutputStream;

/** 验证 genLiteGui 生成的字节码可写盘并被 javap 解析 */
public class LiteGuiGenCheck {
    public static void main(String[] args) throws Exception {
        byte[] bytes = LiteAgent.genLiteGui();
        FileOutputStream out = new FileOutputStream("D:\\temp\\LiteGui-dump.class");
        out.write(bytes);
        out.close();
        System.out.println("wrote " + bytes.length + " bytes");
    }
}