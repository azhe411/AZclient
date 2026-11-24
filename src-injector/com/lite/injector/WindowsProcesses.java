package com.lite.injector;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** Read-only OS discovery, independent of HotSpot performance-data availability. */
final class WindowsProcesses {
    private static volatile List<Core.Proc> cached = Collections.emptyList();
    private static long started;
    private static boolean running;
    static volatile String status = "尚未扫描";
    static volatile boolean available;
    private static volatile String detail = "";
    private static final Pattern ENTRY = Pattern.compile(
        "(?:^|[\\s\"/])((?:net\\.minecraft\\.client\\.main\\.Main"
        + "|net\\.minecraft\\.launchwrapper\\.Launch"
        + "|cpw\\.mods\\.bootstraplauncher\\.BootstrapLauncher"
        + "|cpw\\.mods\\.modlauncher\\.Launcher"
        + "|net\\.fabricmc\\.loader(?:\\.impl)?\\.launch\\.knot\\.KnotClient"
        + "|org\\.quiltmc\\.loader\\.impl\\.launch\\.knot\\.KnotClient))(?=$|[\\s\"])",
        Pattern.CASE_INSENSITIVE);

    static synchronized List<Core.Proc> snapshot() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return Collections.emptyList();
        long now = System.nanoTime();
        if (!running && (started == 0 || now - started > TimeUnit.SECONDS.toNanos(5))) {
            started = now;
            running = true;
            Thread worker = new Thread(() -> {
                try {
                    List<Core.Proc> found = query();
                    cached = Collections.unmodifiableList(found);
                    available = true;
                    status = "系统发现 " + found.size() + " 个 Java 进程（不代表可注入）" + detail;
                } catch (Exception e) {
                    cached = Collections.emptyList();
                    available = false;
                    status = "系统枚举失败：" + e.getClass().getSimpleName() + "；检查 .NET / 安全软件限制";
                } finally {
                    synchronized (WindowsProcesses.class) { running = false; }
                }
            }, "az-process-discovery");
            worker.setDaemon(true);
            worker.start();
        }
        return cached;
    }

    static boolean isGame(String command) {
        if (command == null) return false;
        String lower = command.toLowerCase(Locale.ROOT);
        if (Pattern.compile("--launchtarget(?:=|\\s+)[\"']?\\S*server").matcher(lower).find()
                || lower.contains("net.minecraft.server.")
                || lower.contains("knotserver")) return false;
        Matcher match = ENTRY.matcher(command);
        if (!match.find()) return false;
        String entry = match.group(1).toLowerCase(Locale.ROOT);
        if (entry.startsWith("cpw.mods.")) {
            // BootstrapLauncher is shared by dedicated servers and clients.
            return Pattern.compile("--launchtarget(?:=|\\s+)[\"']?(?:forge|fml|neoforge)client(?:userdev|dev)?(?:[\"']|\\s|$)")
                    .matcher(lower).find();
        }
        return true;
    }

    static String label(String command) {
        Matcher match = ENTRY.matcher(command == null ? "" : command);
        return match.find() ? match.group(1) : "Java（主类未知，可手动选择）";
    }

    static List<Core.Proc> parse(String output) {
        List<Core.Proc> list = new ArrayList<>();
        for (String line : output.split("\\r?\\n")) {
            String[] parts = line.split("\\t", -1);
            if (parts.length != 2 || !parts[0].matches("[1-9][0-9]*")) continue;
            try {
                String command = new String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                // Command lines may contain account tokens. Never return or log them.
                list.add(new Core.Proc(parts[0], label(command), isGame(command)));
            } catch (IllegalArgumentException ignored) { }
        }
        return list;
    }

    static List<Core.Proc> query() throws Exception {
        Path directory = Files.createTempDirectory("az-process-list-");
        Path helper = directory.resolve("process-list.exe");
        Process process = null;
        try {
            try (InputStream resource = WindowsProcesses.class.getResourceAsStream("/windows/process-list.exe")) {
                if (resource == null) throw new IOException("Process inventory helper missing");
                Files.copy(resource, helper);
            }
            process = new ProcessBuilder(helper.toString()).redirectErrorStream(true).start();
            final Process child = process;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream stream = child.getInputStream()) {
                    byte[] bytes = new byte[4096];
                    int n;
                    while ((n = stream.read(bytes)) != -1) {
                        if (buffer.size() + n <= 1024 * 1024) buffer.write(bytes, 0, n);
                    }
                } catch (IOException ignored) { }
            }, "az-process-output");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) throw new IOException("OS discovery timeout");
            reader.join(1000);
            if (reader.isAlive() || process.exitValue() != 0) throw new IOException("OS discovery failed");
            String output = new String(buffer.toByteArray(), StandardCharsets.US_ASCII);
            List<Core.Proc> found = parse(output);
            int unknown = 0;
            for (Core.Proc p : found) if (p.name.startsWith("Java（")) unknown++;
            detail = (output.contains("STATUS\tWMI_UNAVAILABLE") ? "；WMI 受限，已使用系统 PID 列表" : "")
                    + (unknown > 0 ? "；" + unknown + " 项主类未知，可能受权限限制，可手动选择" : "");
            return found;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            try { Files.deleteIfExists(helper); Files.deleteIfExists(directory); } catch (IOException ignored) { }
        }
    }
}
