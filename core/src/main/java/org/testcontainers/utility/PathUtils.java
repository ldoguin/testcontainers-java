package org.testcontainers.utility;

import com.google.common.base.Charsets;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static java.util.Arrays.asList;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Filesystem operation utility methods.
 */
@UtilityClass
public class PathUtils {

    private static final Logger log = getLogger(PathUtils.class);

    /**
     * Recursively delete a directory and all its subdirectories and files.
     *
     * @param directory path to the directory to delete.
     */
    public static void recursiveDeleteDir(final @NonNull Path directory) {
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }

    /**
     * Make a directory, plus any required parent directories.
     *
     * @param directory the directory path to make
     */
    public static void mkdirp(Path directory) {
        boolean result = directory.toFile().mkdirs();
        if (!result) {
            throw new IllegalStateException("Failed to create directory at: " + directory);
        }
    }

    /**
     * Create a MinGW compatible path based on usual Windows path
     *
     * @param path a usual windows path
     * @return a MinGW compatible path
     */
    public static String createMinGWPath(String path) {
        String mingwPath = path.replace('\\', '/');
        int driveLetterIndex = 1;
        if (mingwPath.matches("^[a-zA-Z]:\\/.*")) {
            driveLetterIndex = 0;
        }

        // drive-letter must be lower case
        mingwPath = "//" + Character.toLowerCase(mingwPath.charAt(driveLetterIndex)) +
                mingwPath.substring(driveLetterIndex + 1);
        mingwPath = mingwPath.replace(":", "");
        return mingwPath;
    }

    /**
     * Extract a file or directory tree from a JAR file to a temporary location.
     * This allows Docker to mount classpath resources as files.
     *
     * @param hostPath the path on the host, expected to be of the format 'file:/path/to/some.jar!/classpath/path/to/resource'
     * @return the path of the temporary file/directory
     */
    public static String extractClassPathResourceToTempLocation(final String hostPath) {
        File tmpLocation = new File(".testcontainers-tmp-" + Base58.randomString(5));
        //noinspection ResultOfMethodCallIgnored
        tmpLocation.delete();

        String jarPath = hostPath.replaceFirst("file:", "").replaceAll("!.*", "");
        String urldecodedJarPath;
        try {
            urldecodedJarPath = URLDecoder.decode(jarPath, Charsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalArgumentException("Could not URLDecode path with UTF-8 encoding: " + hostPath, e);
        }
        String internalPath = hostPath.replaceAll("[^!]*!/", "");

        try (JarFile jarFile = new JarFile(urldecodedJarPath)) {
            Enumeration<JarEntry> entries = jarFile.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                final String name = entry.getName();
                if (name.startsWith(internalPath)) {
                    log.debug("Copying classpath resource(s) from {} to {} to permit Docker to bind",
                            hostPath,
                            tmpLocation);
                    copyFromJarToLocation(jarFile, entry, internalPath, tmpLocation);
                }
            }

        } catch (IOException e) {
            throw new IllegalStateException("Failed to process JAR file when extracting classpath resource: " + hostPath, e);
        }

        // Mark temporary files/dirs for deletion at JVM shutdown
        deleteOnExit(tmpLocation.toPath());

        return tmpLocation.getAbsolutePath();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void copyFromJarToLocation(final JarFile jarFile,
                                              final JarEntry entry,
                                              final String fromRoot,
                                              final File toRoot) throws IOException {

        String destinationName = entry.getName().replaceFirst(fromRoot, "");
        File newFile = new File(toRoot, destinationName);

        if (!entry.isDirectory()) {
            // Create parent directories
            newFile.mkdirs();
            newFile.delete();
            newFile.deleteOnExit();

            try (InputStream is = jarFile.getInputStream(entry)) {
                Files.copy(is, newFile.toPath());
            } catch (IOException e) {
                log.error("Failed to extract classpath resource " + entry.getName() + " from JAR file " + jarFile.getName(), e);
                throw e;
            }
        }
    }

    public static void deleteOnExit(final Path path) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> recursiveDeleteDir(path)));
    }

    public static String getFilenameForClasspathResource(final String resourcePath) {

        return getFilenameForClasspathResource(resourcePath, PathUtils.class.getClassLoader());
    }

    public static String getFilenameForClasspathResource(final String resourcePath, final ClassLoader... classLoaders) {

        final LinkedHashSet<ClassLoader> classLoadersSet = new LinkedHashSet<>();
        classLoadersSet.addAll(asList(classLoaders));
        URL resource = getClasspathResource(resourcePath, classLoadersSet);

        return unencodeResourceURIToFilePath(resource);
    }

    @NotNull
    private static URL getClasspathResource(@NotNull final String resourcePath, final Set<ClassLoader> classLoaders) {

        // try context and system classloaders as well
        classLoaders.add(Thread.currentThread().getContextClassLoader());
        classLoaders.add(ClassLoader.getSystemClassLoader());

        for (final ClassLoader classLoader : classLoaders) {
            URL resource = classLoader.getResource(resourcePath);
            if (resource != null) {
                return resource;
            }
        }

        throw new IllegalArgumentException("Resource with path " + resourcePath + " could not be found on any of these classloaders: " + classLoaders);
    }

    private static String unencodeResourceURIToFilePath(@NotNull final URL resource) {
        try {
            // Convert any url-encoded characters (e.g. spaces) back into unencoded form

            System.err.println("resource: " + resource);
            System.err.println("resource.toString(): " + resource.toString());
            System.err.println("new URI(resource.toString()): " + new URI(resource.toString()));
            System.err.println("new URI(resource.toString()).getPath()): " + new URI(resource.toString()).getPath());

            return new URI(resource.toString()).getPath();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
