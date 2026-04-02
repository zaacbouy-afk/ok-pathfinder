package name.ezforaging.pathfinding;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class PathfinderConfig {
    public static double reachDistance = 1.2;
    public static float forwardAngleThreshold = 50f;
    public static double stuckThreshold = 1.0;
    public static int stuckRepathTicks = 20;
    public static int maxRepathAttempts = 3;
    public static float maxYawPerFrame = 5f;
    public static float smoothSpeedMax = 0.25f;

    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("ezforaging.properties");

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            save();
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(CONFIG_PATH)) {
            props.load(in);
            reachDistance = Double.parseDouble(props.getProperty("reachDistance", "1.2"));
            forwardAngleThreshold = Float.parseFloat(props.getProperty("forwardAngleThreshold", "50"));
            stuckThreshold = Double.parseDouble(props.getProperty("stuckThreshold", "1.0"));
            stuckRepathTicks = Integer.parseInt(props.getProperty("stuckRepathTicks", "20"));
            maxRepathAttempts = Integer.parseInt(props.getProperty("maxRepathAttempts", "3"));
            maxYawPerFrame = Float.parseFloat(props.getProperty("maxYawPerFrame", "5"));
            smoothSpeedMax = Float.parseFloat(props.getProperty("smoothSpeedMax", "0.25"));
        } catch (IOException | NumberFormatException e) {
            save();
        }
    }

    public static void save() {
        Properties props = new Properties();
        props.setProperty("reachDistance", String.valueOf(reachDistance));
        props.setProperty("forwardAngleThreshold", String.valueOf(forwardAngleThreshold));
        props.setProperty("stuckThreshold", String.valueOf(stuckThreshold));
        props.setProperty("stuckRepathTicks", String.valueOf(stuckRepathTicks));
        props.setProperty("maxRepathAttempts", String.valueOf(maxRepathAttempts));
        props.setProperty("maxYawPerFrame", String.valueOf(maxYawPerFrame));
        props.setProperty("smoothSpeedMax", String.valueOf(smoothSpeedMax));
        try (OutputStream out = Files.newOutputStream(CONFIG_PATH)) {
            props.store(out, "ezForaging Settings");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
