package com.lite.agent;
import java.io.*;
import java.nio.file.*;
final class AtomicFiles {
    private AtomicFiles() {}
    static void writeUtf8(File file, String text, boolean backup) throws IOException {
        Path target = file.toPath().toAbsolutePath(), parent = target.getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temp.toFile())) {
                out.write(text.getBytes("UTF-8")); out.getFD().sync();
            }
            if (backup && Files.isRegularFile(target)) {
                Path previous = Files.createTempFile(parent, "az-backup-", ".tmp");
                try { Files.copy(target, previous, StandardCopyOption.REPLACE_EXISTING);
                    move(previous, target.resolveSibling(target.getFileName() + ".bak"));
                } finally { Files.deleteIfExists(previous); }
            }
            move(temp, target);
        } finally { Files.deleteIfExists(temp); }
    }
    private static void move(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException unsupported) {
            // Preserve the old file: do not fall back to a truncate or non-atomic move.
            throw new IOException("Filesystem does not support atomic config replacement", unsupported);
        }
    }
}
