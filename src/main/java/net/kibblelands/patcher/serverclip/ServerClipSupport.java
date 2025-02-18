package net.kibblelands.patcher.serverclip;

import net.kibblelands.patcher.utils.IOUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

public class ServerClipSupport {
    // I don't have the time to finish this
    public static final String MC_VER = "$mcVer";
    private static final boolean LEAGCY_SUPPORT = false;
    private static final int INVALID_ARGS_CODE = -1234;
    private static final File self;

    static {
        try {
            self = new File(ServerClipSupport.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI().getPath()).getAbsoluteFile().getCanonicalFile();
        } catch (URISyntaxException | IOException e) {
            throw new RuntimeException("Path to self is invalid!", e);
        }
    }

    private static final File tmp = new File(new File(System.getProperty("java.io.tmpdir")),
            "KibblePatcher-" + UUID.randomUUID().toString()).getAbsoluteFile();
    private static final File javaHome = new File(System.getProperty("java.home")).getAbsoluteFile();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(ServerClipSupport::cleanServerClip));
    }

    private final String mcVer;
    private final ServerClipType serverClipType;

    private ServerClipSupport(String mcVer, ServerClipType serverClipType) {
        this.mcVer = mcVer;
        this.serverClipType = serverClipType;
    }

    public String getName() {
        return this.serverClipType.getDisplayName();
    }

    /**
     * @param file is a potential PaperClip.jar
     * @return {@code null} if the jar is not a paperclip jar
     */
    public static ServerClipSupport getServerClipSupport(File file) {
        String version = null;
        try(JarFile jarFile = new JarFile(file)) {
            // Paperclip support
            ZipEntry properties = jarFile.getEntry("patch.properties");
            if (properties != null &&
                    jarFile.getEntry("io/papermc/paperclip/Paperclip.class") != null) {
                for (String line :
                        new BufferedReader(new InputStreamReader(jarFile.getInputStream(properties),
                                StandardCharsets.UTF_8)).lines().collect(Collectors.toList())) {
                    if (line.startsWith("version=")) {
                        version = line.substring(8).trim();
                    }
                }
                if (version != null) {
                    return new ServerClipSupport(version, ServerClipType.PAPERCLIP);
                }
            }
            // YatoClip support
            properties = jarFile.getEntry("yatoclip-launch.properties");
            if (properties != null &&
                    jarFile.getEntry("org/yatopiamc/yatoclip/Yatoclip.class") != null) {
                for (String line :
                        new BufferedReader(new InputStreamReader(jarFile.getInputStream(properties),
                                StandardCharsets.UTF_8)).lines().collect(Collectors.toList())) {
                    if (line.startsWith("minecraftVersion=")) {
                        version = line.substring(17).trim();
                    }
                }
                if (version != null) {
                    return new ServerClipSupport(version, ServerClipType.YATOCLIP);
                }
            }
            // Legacy Paperclip support
            properties = jarFile.getEntry("patch.json");
            if (properties != null &&
                    jarFile.getEntry("com/destroystokyo/paperclip/Paperclip.class") != null) {
                String trimmedJson = IOUtils.trimJSON(new String(IOUtils.readAllBytes(
                        jarFile.getInputStream(properties)), StandardCharsets.UTF_8));
                int index = trimmedJson.indexOf("\"version\":\"");
                if (index != -1) {
                    index += 11;
                    int end = trimmedJson.indexOf('"', index);
                    if (end != -1) version = trimmedJson.substring(index, end);
                }
                if (version != null) {
                    return new ServerClipSupport(version, ServerClipType.PAPERCLIP_LEGACY);
                }
            }
        } catch (IOException ioe) {
            return null;
        }
        return null;
    }

    public File patchServerClip(File paperClip) throws IOException {
        if (mcVer == null) return paperClip;
        cleanServerClip();
        IOUtils.mkdirs(tmp);
        int exitCode;
        try {
            File javaEx = new File(javaHome, "bin/java.exe");
            if (!javaEx.exists()) {
                javaEx = new File(javaHome, "bin/java");
            }
            ProcessBuilder processBuilder;
            if (this.serverClipType == ServerClipType.PAPERCLIP) {
                // Only paperclip has a patch only mode
                processBuilder = new ProcessBuilder(javaEx.getPath(),
                        "-XX:-UseGCOverheadLimit", "-Dpaperclip.patchonly=true",
                        "-jar", paperClip.getAbsolutePath());
            } else {
                processBuilder = new ProcessBuilder(javaEx.getPath(),
                        "-XX:-UseGCOverheadLimit", "-cp",
                        self.getAbsolutePath() + File.pathSeparator
                                + paperClip.getAbsolutePath(),
                        "net.kibblelands.patcher.serverclip.ServerClipSupport",
                        this.serverClipType.name(), mcVer);
            }
            System.out.println("["+ this.getName()+ " start]");
            final Process process = processBuilder.directory(tmp)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
            exitCode = process.waitFor();
            System.out.println("["+ this.getName()+ " end]");
        } catch (InterruptedException exception) {
            throw new IOException("PatchInterrupted!", exception);
        }
        if (exitCode == INVALID_ARGS_CODE)
            throw new IOException("Invalids args provided!");
        if (exitCode != 0)
            throw new IOException("PatchFailed! (Error code: "+exitCode+")");
        for (String suffix:this.serverClipType.getFileSuffixes()) {
            File file = new File(tmp, suffix.replace(MC_VER, mcVer));
            if (file.exists()) {
                return file;
            }
        }
        throw new IOException("Could not get "+ this.getName() +" generated server!");
    }

    public static void cleanServerClip() {
        try {
            IOUtils.delete(tmp);
        } catch (IOException ignored) {}
    }

    //<editor-fold defaultstate="collapsed" desc="ServerClip exec code">
    // This code is executed on the Paperclip/Yatoclip process to run the patcher without running the server
    public static void main(String[] args) throws Exception {
        if (args.length != 2) System.exit(INVALID_ARGS_CODE);
        ServerClipType serverClipType;
        try {
            serverClipType = ServerClipType.valueOf(args[0]);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(INVALID_ARGS_CODE);
            return;
        }
        if (serverClipType.isNeedHax()) {
            new ServerClipThreadHax(() -> // the exception is propagated automatically
                    runServerClip(serverClipType)).exec();
            System.exit(0);
        } else {
            runServerClip(serverClipType);
        }
    }

    public static void runServerClip(ServerClipType serverClipType) throws Exception {
        switch (serverClipType) {
            default:
                System.exit(INVALID_ARGS_CODE);
            case YATOCLIP:
                Class.forName("org.yatopiamc.yatoclip.ServerSetup")
                        .getDeclaredMethod("setup").invoke(null);
                break;
            case PAPERCLIP_LEGACY:
                // Start the server
                Class.forName("com.destroystokyo.paperclip.Paperclip")
                        .getDeclaredMethod("main", String[].class)
                        .invoke(null, (Object) new String[0]);
                break;
        }
        System.exit(0); // Force exit as soon as possible
    }
}
