package laggyboi.vivemonkecraft.client;

import laggyboi.vivemonkecraft.client.platform.VmcPlatform;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Focused debug trace for Vivecraft↔ViveMonkeCraft interactions, written to
 * {@code <gameDir>/logs/vivemonkecraft-debug.log} ONLY while
 * {@link MovementConfig#debugLogging} is on.
 *
 * <p>This exists because the mod's trickiest bugs are VR-only (teleport desync, room
 * origin lag, grip-stick) and can't be observed from a desktop — the user enables
 * logging, reproduces in VR, and shares this file. Kept separate from the main game
 * log so it's small and easy to read.
 */
public final class VmcDebugLog {

    private static final DateTimeFormatter TF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static BufferedWriter writer;
    private static boolean failed = false;
    private static long written = 0;

    private static final long ROTATE_BYTES      = 20L * 1024 * 1024;
    private static final long SESSION_MAX_BYTES = 50L * 1024 * 1024;

    private VmcDebugLog() {}

    /** Tagged discrete-event line, e.g. event("NET", "→ WallSlide(true)"). */
    public static void event(String tag, String msg) {
        log("[" + tag + "] " + msg);
    }

    /** Whether logging is on — lets callers skip building expensive strings. */
    public static boolean on() {
        return MovementConfig.debugLogging && !failed;
    }

    public static synchronized void log(String msg) {
        if (!MovementConfig.debugLogging || failed) return;
        try {
            if (writer == null) {
                Path dir = VmcPlatform.gameDir().resolve("logs");
                Files.createDirectories(dir);
                Path file = dir.resolve("vivemonkecraft-debug.log");
                // Several lines per tick add up fast: start over (keeping one .old
                // copy) once the file is big, so it can't fill the disk across sessions.
                if (Files.exists(file) && Files.size(file) > ROTATE_BYTES) {
                    Files.move(file, dir.resolve("vivemonkecraft-debug.log.old"),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                writer.write("\n==== ViveMonkeCraft debug session " + LocalDateTime.now() + " ====\n");
                // Full one-shot dump of what the mod is running on and configured with,
                // so a shared log is self-contained for debugging (no need to ask the
                // user which versions / mods / settings they had).
                writer.write(environmentDump());
            }
            String line = "[" + LocalDateTime.now().format(TF) + "] " + msg + "\n";
            writer.write(line);
            writer.flush();
            written += line.length();
            if (written > SESSION_MAX_BYTES) {
                writer.write("==== log size limit reached, logging stopped for this session ====\n");
                writer.close();
                failed = true;
            }
        } catch (Throwable t) {
            failed = true;   // never let logging break gameplay
        }
    }

    // Builds the session's environment/dependency/config dump. Best-effort: any
    // failure just yields a short note so it can never break the log session.
    private static String environmentDump() {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("---- environment ----\n");
            sb.append("  os      = ").append(System.getProperty("os.name"))
              .append(' ').append(System.getProperty("os.version")).append('\n');
            sb.append("  java    = ").append(System.getProperty("java.version")).append('\n');
            sb.append("  loader  = ").append(VmcPlatform.loaderName()).append('\n');
            sb.append("  mc      = ").append(VmcPlatform.modVersion("minecraft")).append('\n');
            sb.append("  vmc     = ").append(VmcPlatform.modVersion("vivemonkecraft")).append('\n');

            sb.append("---- key dependencies ----\n");
            for (String id : new String[]{"vivecraft", "fabric-api", "fabric",
                                          "cloth-config", "cloth-config2", "modmenu"}) {
                sb.append("  ").append(pad(id)).append(" = ")
                  .append(VmcPlatform.isModLoaded(id) ? VmcPlatform.modVersion(id) : "ABSENT").append('\n');
            }

            sb.append("---- all loaded mods (").append(VmcPlatform.modCount()).append(") ----\n");
            for (String line : VmcPlatform.allMods()) {
                sb.append("  ").append(line).append('\n');
            }

            sb.append("---- active config ----\n");
            java.util.Map<String, Object> snap = MovementConfig.snapshot();
            snap.keySet().stream().sorted()
                .forEach(k -> sb.append("  ").append(k).append(" = ").append(snap.get(k)).append('\n'));

            sb.append("---- end environment ----\n");
        } catch (Throwable t) {
            sb.append("  (environment dump failed: ").append(t).append(")\n");
        }
        return sb.toString();
    }

    private static String pad(String s) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < 12) b.append(' ');
        return b.toString();
    }
}
