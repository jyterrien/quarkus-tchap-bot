package fr.gouv.tchap.quarkus.deployment;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Configuration évaluée au build time.
 * Pour le MVP : un seul paramètre, l'activation native.
 */
@ConfigMapping(prefix = "quarkus.tchap")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface TchapBuildTimeConfig {

    @WithName("native.enabled")
    @WithDefault("true")
    boolean nativeEnabled();
}
