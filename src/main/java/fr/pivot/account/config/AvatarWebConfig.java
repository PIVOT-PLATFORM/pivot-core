package fr.pivot.account.config;

import java.nio.file.Path;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Exposes stored avatar files as static resources under {@code /avatars/**} (US02.1.1).
 *
 * <p><strong>Design choice — static resource path vs. authenticated streaming endpoint:</strong>
 * avatars are served unauthenticated, from an unguessable {@link java.util.UUID}-named file
 * (never {@code {userId}.{ext}} — see {@code AvatarStorageService}), the simpler of the two
 * options named in the US: no per-request auth/token plumbing is needed for a plain
 * {@code <img src>} tag, and the identifier space is not enumerable. This mirrors how most
 * avatar CDNs (Gravatar, Slack public avatar links) work. Accepted trade-off, documented for
 * review: a leaked/observed avatar URL grants read access to that one image with no
 * expiry — acceptable for a non-sensitive profile picture, revisited only if PIVOT later needs
 * per-viewer authorization on avatars.
 */
@Configuration
public class AvatarWebConfig implements WebMvcConfigurer {

    private final AvatarStorageProperties properties;

    /**
     * Constructs the configuration with the avatar storage properties.
     *
     * @param properties bound {@code pivot.storage.avatars.*} configuration
     */
    public AvatarWebConfig(final AvatarStorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addResourceHandlers(final ResourceHandlerRegistry registry) {
        final String location = "file:" + Path.of(properties.basePath()).toAbsolutePath().normalize() + "/";
        registry.addResourceHandler("/avatars/**")
                .addResourceLocations(location)
                .setCachePeriod(3600);
    }
}
