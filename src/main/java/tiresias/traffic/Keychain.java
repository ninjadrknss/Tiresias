package tiresias.traffic;

import static tiresias.Tiresias.*;
import static tiresias.spec.Families.*;
import static tiresias.spec.Spec.*;
import static tiresias.traffic.Can.*;
import static tiresias.ui.Look.*;
import static tiresias.ui.Ui.*;
import static tiresias.ui.Widgets.*;

import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;
import java.util.prefs.Preferences;
import java.util.regex.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.plaf.*;
import javax.swing.table.*;
import javax.swing.tree.*;
import tiresias.*;
import tiresias.editor.*;
import tiresias.spec.*;
import tiresias.traffic.dashboards.*;
import tiresias.ui.*;

/**
 * The OS password store, for remembering the SSH password: the macOS Keychain (through the security tool) or the
 * Linux Secret Service (secret-tool). The password is never written to a plain file or put on a command line.
 */
public final class Keychain {

    public static final String SERVICE = "Tiresias SSH";
    public static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    public static final boolean MAC = OS.contains("mac"),
        LINUX = OS.contains("linux");
    public static Boolean avail;

    public static synchronized boolean available() {
        if (avail == null) avail = MAC
            ? Files.isExecutable(Path.of("/usr/bin/security"))
            : LINUX && onPath("secret-tool");
        return avail;
    }

    public static String where() {
        return MAC ? "in the Keychain" : "in the system keyring";
    }

    public static boolean onPath(String tool) {
        for (String d : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator))
            if (!d.isEmpty() && Files.isExecutable(Path.of(d, tool))) return true;
        return false;
    }

    /** Accounts go into security's command language, so keep them to plain characters. */
    public static boolean safe(String account) {
        return account != null && account.matches("[\\w.@:\\-\\[\\]]{1,200}");
    }

    /** Runs a command with optional input; returns its output, or null if it failed or took too long. */
    public static String run(List<String> cmd, String input) {
        return run(cmd, input, false);
    }

    public static String run(List<String> cmd, String input, boolean withErrors) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(withErrors).start();
            try (var w = p.outputWriter(StandardCharsets.UTF_8)) {
                if (input != null) w.write(input);
            }
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? new String(out, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public static String get(String account) {
        if (!available() || !safe(account)) return null;
        if (MAC) {
            // -w prints the password as is, except that one with non-ASCII bytes comes out as bare hex; -g shows
            // "password: 0x<hex>" whenever it isn't plain, which is unambiguous, so read that form first
            String g = run(
                List.of("/usr/bin/security", "find-generic-password", "-s", SERVICE, "-a", account, "-g"),
                null,
                true
            );
            if (g == null) return null;
            for (String line : g.split("\n"))
                if (line.startsWith("password: 0x")) {
                    String h = line.substring("password: 0x".length()).split("\\s")[0];
                    byte[] bytes = new byte[h.length() / 2];
                    for (int k = 0; k < bytes.length; k++) bytes[k] = (byte) Integer.parseInt(
                        h.substring(2 * k, 2 * k + 2),
                        16
                    );
                    return new String(bytes, StandardCharsets.UTF_8);
                }
        }
        String out = MAC
            ? run(List.of("/usr/bin/security", "find-generic-password", "-s", SERVICE, "-a", account, "-w"), null)
            : run(List.of("secret-tool", "lookup", "service", "tiresias-ssh", "account", account), null);
        if (out == null) return null;
        out = out.endsWith("\n") ? out.substring(0, out.length() - 1) : out;
        return out.isEmpty() ? null : out;
    }

    public static boolean put(String account, String password) {
        if (!available() || !safe(account)) return false;
        if (MAC) {
            // security -i reads the command from stdin, and -X takes the password as hex, so it never appears
            // in the process list
            StringBuilder hex = new StringBuilder();
            for (byte b : password.getBytes(StandardCharsets.UTF_8)) hex.append(String.format("%02x", b & 0xFF));
            String line = "add-generic-password -U -s \"" + SERVICE + "\" -a \"" + account + "\" -X " + hex + "\n";
            return run(List.of("/usr/bin/security", "-i"), line) != null && password.equals(get(account));
        }
        return (
            run(
                List.of(
                    "secret-tool",
                    "store",
                    "--label=Tiresias SSH (" + account + ")",
                    "service",
                    "tiresias-ssh",
                    "account",
                    account
                ),
                password
            ) != null
        );
    }

    public static void delete(String account) {
        if (!available() || !safe(account)) return;
        if (MAC) run(List.of("/usr/bin/security", "delete-generic-password", "-s", SERVICE, "-a", account), null);
        else run(List.of("secret-tool", "clear", "service", "tiresias-ssh", "account", account), null);
    }
}
