package io.fabric8.kit.build.service.docker;

import java.io.IOException;

import io.fabric8.kit.build.api.RegistryContext;
import io.fabric8.kit.build.api.RegistryService;
import io.fabric8.kit.build.api.auth.RegistryAuth;
import io.fabric8.kit.build.api.auth.RegistryAuthConfig;
import io.fabric8.kit.build.service.docker.access.DockerAccess;
import io.fabric8.kit.common.KitLogger;
import io.fabric8.kit.common.TimeUtil;
import io.fabric8.kit.config.image.ImageConfiguration;
import io.fabric8.kit.config.image.ImageName;
import io.fabric8.kit.config.image.build.BuildConfiguration;
import io.fabric8.kit.config.image.build.ImagePullPolicy;


/**
 * Allows to interact with registries, eg. to push/pull images.
 */
public class DockerRegistryService implements RegistryService {

    private final DockerAccess docker;
    private final KitLogger log;
    private final ImagePullCache imagePullCache;

    public DockerRegistryService(DockerAccess docker, KitLogger log, ImagePullCache.Backend backend) {
        this.docker = docker;
        this.log = log;
        this.imagePullCache = new ImagePullCache(backend);
    }

    /**
     * Push a set of images to a registry
     *
     * @param imageConfig image to push but only if it has a build configuration
     * @param retries how often to retry
     * @param skipTag flag to skip pushing tagged images
     */
    @Override
    public void pushImage(ImageConfiguration imageConfig,
                          int retries, boolean skipTag, RegistryContext context) throws IOException {
        BuildConfiguration buildConfig = imageConfig.getBuildConfiguration();
        String name = imageConfig.getName();
        if (buildConfig != null) {
            String registry = firstRegistryOf(
                new ImageName(imageConfig.getName()).getRegistry(),
                imageConfig.getRegistry(),
                context.getRegistry(RegistryAuthConfig.Kind.PUSH));


            RegistryAuth registryAuth = context.getAuthConfig(RegistryAuthConfig.Kind.PUSH, new ImageName(name).getUser(), registry);

            long start = System.currentTimeMillis();
            docker.pushImage(name, registryAuth.toHeaderValue(), registry, retries);
            log.info("Pushed %s in %s", name, TimeUtil.formatDurationTill(start));

            if (!skipTag) {
                for (String tag : imageConfig.getBuildConfiguration().getTags()) {
                    if (tag != null) {
                        docker.pushImage(new ImageName(name, tag).getFullName(), registryAuth.toHeaderValue(), registry, retries);
                    }
                }
            }
        }
    }

    private String firstRegistryOf(String... checkFirst) {
        for (String registry : checkFirst) {
            if (registry != null) {
                return registry;
            }
        }
        // Check environment as last resort
        return System.getenv("DOCKER_REGISTRY");
    }


    /**
     * Check an image, and, if <code>autoPull</code> is set to true, fetch it. Otherwise if the image
     * is not existent, throw an error
     *
     */
    public void pullImage(String image, ImagePullPolicy policy, RegistryContext registryContext)
        throws IOException {

        // Already pulled, so we don't need to take care
        if (imagePullCache.hasAlreadyPulled(image)) {
            return;
        }

        // Check if a pull is required
        if (!imageRequiresPull(docker.hasImage(image), policy, image)) {
            return;
        }

        ImageName imageName = new ImageName(image);
        long time = System.currentTimeMillis();
        String registry = firstRegistryOf(
            imageName.getRegistry(),
            registryContext.getRegistry(RegistryAuthConfig.Kind.PULL));

        docker.pullImage(imageName.getFullName(),
                         registryContext.getAuthConfig(RegistryAuthConfig.Kind.PULL, null, registry).toHeaderValue(),
                         registry);
        log.info("Pulled %s in %s", imageName.getFullName(), TimeUtil.formatDurationTill(time));
        imagePullCache.pulled(image);

        if (registry != null && !imageName.hasRegistry()) {
            // If coming from a registry which was not contained in the original name, add a tag from the
            // full name with the registry to the short name with no-registry.
            docker.tag(imageName.getFullName(registry), image, false);
        }
    }


    // ============================================================================================================


    private boolean imageRequiresPull(boolean hasImage, ImagePullPolicy pullPolicy, String imageName) {

        // The logic here is like this (see also #96):
        // otherwise: don't pull

        if (pullPolicy == ImagePullPolicy.Never) {
            if (!hasImage) {
                throw new IllegalArgumentException(
                    String.format("No image '%s' found and pull policy 'Never' is set. Please chose another pull policy or pull the image yourself)", imageName));
            }
            return false;
        }

        // If the image is not available and mode is not ImagePullPolicy.Never --> pull
        if (!hasImage) {
            return true;
        }

        // If pullPolicy == Always --> pull, otherwise not (we have it already)
        return pullPolicy == ImagePullPolicy.Always;
    }

}
