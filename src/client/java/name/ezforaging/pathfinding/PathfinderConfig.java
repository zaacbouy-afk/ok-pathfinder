package name.ezforaging.pathfinding;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Holds all tunable pathfinding values as public static fields.
 * Values are loaded from / saved to config/ezforaging.properties so
 * settings persist across mod reinstalls and game restarts.
 *
 * Load is called once at mod init (EzForagingClient).
 * Save is called when the settings screen is closed (EzForagingScreen).
 */
public class PathfinderConfig {

    // ── Movement ───────────────────────────────────────────────────────

    /** How close (in blocks) the player needs to be to count a node as reached. */
    public static double reachDistance = 1.2;

    /**
     * Maximum yaw angle difference (degrees) between facing direction and
     * target direction while still moving forward. Prevents wall-sliding
     * when the player isn't yet aligned with the next node.
     */
    public static float forwardAngleThreshold = 50f;

    // ── Stuck detection & repathing ────────────────────────────────────

    /**
     * Minimum distance (blocks) the player must move every stuck-check
     * interval to be considered not stuck.
     */
    public static double stuckThreshold = 1.0;

    /** How many ticks of no movement before triggering a repath. */
    public static int stuckRepathTicks = 20;

    /** Maximum number of repath attempts before the script stops. */
    public static int maxRepathAttempts = 3;

    // ── Camera smoothing ───────────────────────────────────────────────

    /**
     * Maximum degrees of yaw change allowed per render frame.
     * Lower = smoother turns, higher = snappier.
     */
    public static float maxYawPerFrame = 5f;

    /**
     * Maximum lerp speed for camera rotation (0.0–1.0 range).
     * Controls how quickly the camera catches up to the target angle.
     */
    public static float smoothSpeedMax = 0.25f;

    /**
     * Max EMA blend speed for targetYaw (0.1–1.0).
     * How aggressively targetYaw tracks direction changes each tick.
     * Lower = more inertia / smoother, higher = snappier tracking.
     */
    public static float yawBlendSpeed = 0.53f;

    /**
     * EMA blend speed for targetPitch (0.02–0.5).
     * How quickly pitch transitions on step-ups/downs.
     */
    public static float pitchBlendSpeed = 0.15f;
    public static boolean debugEnabled = false;

    // ── Persistence ────────────────────────────────────────────────────

    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("ezforaging.properties");

    /** Reads saved values from disk. Creates the file with defaults if it doesn't exist. */
    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            save();
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(CONFIG_PATH)) {
            props.load(in);
            reachDistance          = Double.parseDouble(props.getProperty("reachDistance",          "1.2"));
            forwardAngleThreshold  = Float.parseFloat( props.getProperty("forwardAngleThreshold",  "50"));
            stuckThreshold         = Double.parseDouble(props.getProperty("stuckThreshold",         "1.0"));
            stuckRepathTicks       = Integer.parseInt(  props.getProperty("stuckRepathTicks",       "20"));
            maxRepathAttempts      = Integer.parseInt(  props.getProperty("maxRepathAttempts",      "3"));
            maxYawPerFrame         = Float.parseFloat(  props.getProperty("maxYawPerFrame",         "5"));
            smoothSpeedMax         = Float.parseFloat(  props.getProperty("smoothSpeedMax",         "0.25"));
            yawBlendSpeed          = Float.parseFloat(  props.getProperty("yawBlendSpeed",          "0.53"));
            pitchBlendSpeed        = Float.parseFloat(  props.getProperty("pitchBlendSpeed",        "0.15"));
            debugEnabled           = Boolean.parseBoolean(props.getProperty("debugEnabled",          "false"));
        } catch (IOException | NumberFormatException e) {
            // Corrupted or unreadable file — overwrite with current defaults
            save();
        }
    }

    /** Writes all current values to disk. */
    public static void save() {
        Properties props = new Properties();
        props.setProperty("reachDistance",         String.valueOf(reachDistance));
        props.setProperty("forwardAngleThreshold", String.valueOf(forwardAngleThreshold));
        props.setProperty("stuckThreshold",        String.valueOf(stuckThreshold));
        props.setProperty("stuckRepathTicks",      String.valueOf(stuckRepathTicks));
        props.setProperty("maxRepathAttempts",     String.valueOf(maxRepathAttempts));
        props.setProperty("maxYawPerFrame",        String.valueOf(maxYawPerFrame));
        props.setProperty("smoothSpeedMax",        String.valueOf(smoothSpeedMax));
        props.setProperty("yawBlendSpeed",         String.valueOf(yawBlendSpeed));
        props.setProperty("pitchBlendSpeed",       String.valueOf(pitchBlendSpeed));
        props.setProperty("debugEnabled",          String.valueOf(debugEnabled));
        try (OutputStream out = Files.newOutputStream(CONFIG_PATH)) {
            props.store(out, "ezForaging Settings");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
