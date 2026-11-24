using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;

[assembly: AssemblyVersion("8.0.0.0")]
[assembly: AssemblyFileVersion("8.0.0.0")]
[assembly: AssemblyInformationalVersion("8.0.0-offline-oss")]

class AZclientOpenSourceLauncher
{
    [STAThread]
    static int Main(string[] args)
    {
        string tempDir = Path.Combine(Path.GetTempPath(), "AZclient-OSS", Guid.NewGuid().ToString("N"));
        try
        {
            byte[] injector = ReadResource("InjectorJar");
            byte[] agent = ReadResource("AgentJar");
            if (args.Length == 1 && args[0] == "--verify-release") return 0;

            Directory.CreateDirectory(tempDir);
            string injectorJar = Path.Combine(tempDir, "lite-injector.jar");
            string agentJar = Path.Combine(tempDir, "lite-agent.jar");
            File.WriteAllBytes(injectorJar, injector);
            File.WriteAllBytes(agentJar, agent);

            string exeDir = Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location);
            string java = FindJava(exeDir);
            if (java == null)
            {
                Message("没有找到 Java。请先启动 Minecraft，或安装 Java 8 及以上版本。", "AZclient Offline OSS");
                return 2;
            }

            ProcessStartInfo start = new ProcessStartInfo();
            start.FileName = java;
            start.Arguments = "-jar \"" + injectorJar + "\" \"" + agentJar + "\" \"" + exeDir + "\"";
            start.UseShellExecute = false;
            start.CreateNoWindow = true;
            using (Process process = Process.Start(start))
            {
                if (process == null) throw new IOException("无法启动本地注入器");
                process.WaitForExit();
                return process.ExitCode;
            }
        }
        catch (Exception error)
        {
            Message("启动失败：" + error.Message, "AZclient Offline OSS");
            return 1;
        }
        finally
        {
            try { if (Directory.Exists(tempDir)) Directory.Delete(tempDir, true); } catch { }
        }
    }

    static byte[] ReadResource(string name)
    {
        Assembly assembly = Assembly.GetExecutingAssembly();
        using (Stream input = assembly.GetManifestResourceStream(name))
        {
            if (input == null) throw new InvalidDataException("缺少内嵌组件：" + name);
            using (MemoryStream output = new MemoryStream())
            {
                input.CopyTo(output);
                if (output.Length < 1024) throw new InvalidDataException("内嵌组件无效：" + name);
                return output.ToArray();
            }
        }
    }

    static string FindJava(string exeDir)
    {
        string[] bundled = {
            Path.Combine(exeDir, "jdk8", "bin", "javaw.exe"),
            Path.Combine(exeDir, "jre", "bin", "javaw.exe"),
            Path.Combine(exeDir, "runtime", "bin", "javaw.exe")
        };
        foreach (string candidate in bundled)
            if (File.Exists(candidate)) return candidate;

        string running = FindRunningJava();
        if (running != null) return running;
        return FindOnPath("javaw.exe") ?? FindOnPath("java.exe");
    }

    static string FindRunningJava()
    {
        string best = null;
        long score = long.MinValue;
        foreach (Process process in Process.GetProcesses())
        {
            try
            {
                bool javaw = process.ProcessName.Equals("javaw", StringComparison.OrdinalIgnoreCase);
                if (!javaw && !process.ProcessName.Equals("java", StringComparison.OrdinalIgnoreCase)) continue;
                string path = process.MainModule.FileName;
                if (path == null || !File.Exists(path)) continue;
                long current = (javaw ? (1L << 41) : 0L) + Math.Max(0L, process.WorkingSet64);
                if (current > score) { score = current; best = path; }
            }
            catch { }
            finally { process.Dispose(); }
        }
        return best;
    }

    static string FindOnPath(string file)
    {
        string path = Environment.GetEnvironmentVariable("PATH");
        if (path == null) return null;
        foreach (string dir in path.Split(';'))
        {
            try
            {
                string candidate = Path.Combine(dir, file);
                if (File.Exists(candidate)) return candidate;
            }
            catch { }
        }
        return null;
    }

    static void Message(string text, string title)
    {
        System.Windows.Forms.MessageBox.Show(text, title);
    }
}
