package org.gradle.wrapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


public final class GradleWrapperMain {
    private static final String DEFAULT_PATH = "wrapper/dists";
    private static final int BUFFER_SIZE = 16 * 1024;

    private GradleWrapperMain() {
    }

    public static void main(String[] args) throws Exception {
        File wrapperJar = wrapperJar();
        File wrapperProperties = new File(
            wrapperJar.getParentFile(),
            wrapperJar.getName().replaceFirst("\\.jar$", ".properties")
        );
        File projectDir = wrapperJar.getParentFile().getParentFile().getParentFile();

        Properties properties = loadProperties(wrapperProperties);
        URI distributionUri = resolveDistributionUri(properties, wrapperProperties.getParentFile());
        File gradleUserHome = gradleUserHome();

        File zipBaseDir = resolveBaseDir(
            properties.getProperty("zipStoreBase", "GRADLE_USER_HOME"),
            gradleUserHome,
            projectDir
        );
        File distBaseDir = resolveBaseDir(
            properties.getProperty("distributionBase", "GRADLE_USER_HOME"),
            gradleUserHome,
            projectDir
        );

        String zipStorePath = properties.getProperty("zipStorePath", DEFAULT_PATH);
        String distributionPath = properties.getProperty("distributionPath", DEFAULT_PATH);
        String fileName = Paths.get(distributionUri.getPath()).getFileName().toString();
        String distName = fileName.endsWith(".zip") ? fileName.substring(0, fileName.length() - 4) : fileName;
        String rootDirName = distName + "/" + shortHash(distributionUri.toString());

        File localZip = new File(new File(zipBaseDir, zipStorePath), rootDirName + "/" + fileName);
        File installDir = new File(new File(distBaseDir, distributionPath), rootDirName);

        File gradleHome = locateGradleHome(installDir);
        if (gradleHome == null) {
            downloadDistribution(distributionUri.toURL(), localZip.toPath());
            unzip(localZip.toPath(), installDir.toPath());
            gradleHome = locateGradleHome(installDir);
        }

        if (gradleHome == null) {
            throw new IllegalStateException("Unable to locate Gradle home after extraction.");
        }

        launchGradle(args, gradleHome);
    }

    private static File wrapperJar() throws URISyntaxException {
        URI location = GradleWrapperMain.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        return Paths.get(location).toFile();
    }

    private static Properties loadProperties(File propertiesFile) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = new FileInputStream(propertiesFile)) {
            properties.load(input);
        }
        return properties;
    }

    private static URI resolveDistributionUri(Properties properties, File propertiesDir) throws URISyntaxException {
        String raw = properties.getProperty("distributionUrl");
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("distributionUrl is missing in gradle-wrapper.properties");
        }
        URI uri = new URI(raw);
        if (uri.isAbsolute()) {
            return uri;
        }
        return propertiesDir.toURI().resolve(uri);
    }

    private static File gradleUserHome() {
        String configured = System.getenv("GRADLE_USER_HOME");
        if (configured != null && !configured.isBlank()) {
            return new File(configured);
        }
        return new File(System.getProperty("user.home"), ".gradle");
    }

    private static File resolveBaseDir(String base, File gradleUserHome, File projectDir) {
        if ("PROJECT".equals(base)) {
            return projectDir;
        }
        return gradleUserHome;
    }

    private static String shortHash(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] hash = digest.digest(value.getBytes());
        return HexFormat.of().formatHex(hash).substring(0, 24);
    }

    private static void downloadDistribution(URL url, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        if (Files.exists(target) && Files.size(target) > 0) {
            return;
        }

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(30_000);
        connection.connect();

        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(target.toFile())) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void unzip(Path archive, Path targetDir) throws IOException {
        if (Files.exists(targetDir) && locateGradleHome(targetDir.toFile()) != null) {
            return;
        }
        Files.createDirectories(targetDir);
        try (ZipInputStream zipInput = new ZipInputStream(new FileInputStream(archive.toFile()))) {
            ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                Path resolved = targetDir.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(targetDir)) {
                    throw new IOException("Unsafe zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    try (FileOutputStream output = new FileOutputStream(resolved.toFile())) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int read;
                        while ((read = zipInput.read(buffer)) >= 0) {
                            output.write(buffer, 0, read);
                        }
                    }
                }
                zipInput.closeEntry();
            }
        }
    }

    private static File locateGradleHome(File installDir) {
        File[] children = installDir.listFiles(File::isDirectory);
        if (children == null) {
            return null;
        }
        for (File child : children) {
            File launcher = findLauncherJar(child);
            if (launcher != null) {
                return child;
            }
        }
        return null;
    }

    private static File findLauncherJar(File gradleHome) {
        File libDir = new File(gradleHome, "lib");
        File[] jars = libDir.listFiles((dir, name) -> name.startsWith("gradle-launcher-") && name.endsWith(".jar"));
        if (jars != null && jars.length == 1) {
            return jars[0];
        }
        return null;
    }

    private static void launchGradle(String[] args, File gradleHome) throws Exception {
        File launcherJar = findLauncherJar(gradleHome);
        if (launcherJar == null) {
            throw new IllegalStateException("Could not find gradle-launcher jar in " + gradleHome);
        }
        try (URLClassLoader classLoader = new URLClassLoader(
            new URL[]{launcherJar.toURI().toURL()},
            ClassLoader.getSystemClassLoader().getParent()
        )) {
            Thread.currentThread().setContextClassLoader(classLoader);
            Class<?> mainClass = classLoader.loadClass("org.gradle.launcher.GradleMain");
            Method mainMethod = mainClass.getMethod("main", String[].class);
            mainMethod.invoke(null, new Object[]{args});
        }
    }
}
