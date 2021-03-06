package de.otto.edison.togglz.s3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.togglz.core.Feature;
import org.togglz.core.context.FeatureContext;
import org.togglz.core.repository.FeatureState;
import org.togglz.core.repository.StateRepository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Togglz state repository, that fetches the s3 state async
 * to avoid s3 access while asking for togglz state.
 */
public class S3TogglzRepository implements StateRepository {

    private final static Logger LOG = LoggerFactory.getLogger(S3TogglzRepository.class);
    private static final int SCHEDULE_RATE_IN_MILLISECONDS = 60000;

    private final Map<Feature, CacheEntry> cache = new ConcurrentHashMap<>();
    private final FeatureStateConverter featureStateConverter;

    public S3TogglzRepository(final FeatureStateConverter featureStateConverter) {
        this.featureStateConverter = featureStateConverter;
    }

    @Override
    public FeatureState getFeatureState(final Feature feature) {

        final CacheEntry cachedEntry = cache.get(feature);

        if (cachedEntry != null) {
            return cachedEntry.getState();
        }

        // no cache hit - refresh state from delegate
        final FeatureState featureState = featureStateConverter.retrieveFeatureStateFromS3(feature);
        cache.put(feature, new CacheEntry(featureState));

        return featureState;
    }

    @Override
    public void setFeatureState(final FeatureState featureState) {
        featureStateConverter.persistFeatureStateToS3(featureState);
        cache.put(featureState.getFeature(), new CacheEntry(featureState));
    }

    @Scheduled(initialDelay = 0, fixedRate = SCHEDULE_RATE_IN_MILLISECONDS)
    protected void prefetchFeatureStates() {
        if (cache.size() == 0) {
            LOG.debug("Initialize state for features");
            initializeFeatureStates();
        } else {
            LOG.debug("Refreshing state for features");
            cache.replaceAll((feature, cacheEntry) -> new CacheEntry(featureStateConverter.retrieveFeatureStateFromS3(feature)));
        }
    }

    private void initializeFeatureStates() {
        try {
            FeatureContext.getFeatureManager().getFeatures().forEach(this::getFeatureState);
        } catch (final Exception ex) {
            LOG.error("Unable to Initialize feature states", ex);
        }
    }

    /**
     * This class represents a cached repository lookup
     */
    private static class CacheEntry {

        private final FeatureState state;

        public CacheEntry(final FeatureState state) {
            this.state = state;
        }

        public FeatureState getState() {
            return state;
        }

    }
}
