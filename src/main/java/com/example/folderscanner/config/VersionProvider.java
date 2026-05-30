package com.example.folderscanner.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import picocli.CommandLine.IVersionProvider;

/**
 * Supplies the {@code --version} string from the build version so the POM stays the single source.
 * The version is read from {@code version.properties}, which Maven resource-filtering populates with
 * {@code ${project.version}} at build time; nothing in the source tree repeats the version literal.
 */
final class VersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() throws IOException {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/version.properties")) {
            if (in == null) {
                throw new IOException("version.properties missing from the classpath");
            }
            props.load(in);
        }
        return new String[] {"folder-scanner " + props.getProperty("version")};
    }
}
