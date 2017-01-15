package org.testcontainers.images.builder.traits;

import org.testcontainers.utility.PathUtils;

import java.nio.file.Paths;

/**
 * BuildContextBuilder's trait for classpath-based resources.
 *
 */
public interface ClasspathTrait<SELF extends ClasspathTrait<SELF> & BuildContextBuilderTrait<SELF> & FilesTrait<SELF>> {

    default SELF withFileFromClasspath(String path, String resourcePath) {
        final String resourceFilePath = PathUtils.getFilenameForClasspathResource(resourcePath);

        return ((SELF) this).withFileFromPath(path, Paths.get(resourceFilePath));
    }
}
