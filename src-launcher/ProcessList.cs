using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Management;
using System.Text;

// Read-only process inventory. Output goes only to the parent pipe; no files or attach.
class ProcessList {
    static int Main() {
        var found = new HashSet<int>();
        try {
            using (var query = new ManagementObjectSearcher(
                "SELECT ProcessId,CommandLine FROM Win32_Process WHERE Name='java.exe' OR Name='javaw.exe'")) {
                using (var rows = query.Get()) {
                    foreach (ManagementObject row in rows) {
                        using (row) {
                            int pid = Convert.ToInt32(row["ProcessId"]);
                            string command = row["CommandLine"] as string ?? "";
                            Console.WriteLine(pid + "\t" + Convert.ToBase64String(Encoding.UTF8.GetBytes(command)));
                            found.Add(pid);
                        }
                    }
                }
            }
        } catch { Console.WriteLine("STATUS\tWMI_UNAVAILABLE"); }
        // Even when WMI or command-line access is restricted, list selectable PIDs.
        try {
            foreach (string name in new [] {"java", "javaw"}) {
                foreach (Process p in Process.GetProcessesByName(name)) {
                    using (p) { if (found.Add(p.Id)) Console.WriteLine(p.Id + "\t"); }
                }
            }
        } catch { return 2; }
        return 0;
    }
}
