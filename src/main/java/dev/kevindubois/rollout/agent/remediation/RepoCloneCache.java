package dev.kevindubois.rollout.agent.remediation;

import io.quarkus.logging.Log;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches local git clones so that remediation retries reuse the same
 * working copy instead of cloning from scratch every time.
 */
@ApplicationScoped
public class RepoCloneCache {

    private final ConcurrentHashMap<String, GitOperations.ClonedRepo> cache = new ConcurrentHashMap<>();

    @Inject
    GitOperations gitOps;

    /**
     * Return a local clone for the given repo URL, cloning only on the first
     * call.  Subsequent calls fetch from origin and hard-reset to the default
     * branch so the working copy is clean and up-to-date.
     */
    public Path getOrClone(String repoUrl, String token) throws Exception {
        GitOperations.ClonedRepo existing = cache.get(repoUrl);

        if (existing != null && existing.path().toFile().exists()) {
            Log.info(MessageFormat.format("Reusing cached clone for {0}", repoUrl));
            // Pass the default branch captured at clone time - it must not be
            // re-derived from the currently checked-out branch, since a prior
            // remediation attempt may have left HEAD on a throwaway feature
            // branch. See GitOperations.fetchAndReset for the full explanation.
            gitOps.fetchAndReset(existing.path(), token, existing.defaultBranch());
            return existing.path();
        }

        Log.info(MessageFormat.format("First clone for {0}", repoUrl));
        GitOperations.ClonedRepo cloned = gitOps.cloneRepository(repoUrl, token);
        cache.put(repoUrl, cloned);
        return cloned.path();
    }

    @PreDestroy
    void cleanup() {
        cache.forEach((url, repo) -> {
            Log.info(MessageFormat.format("Cleaning up cached clone: {0}", repo.path()));
            gitOps.cleanup(repo.path());
        });
        cache.clear();
    }
}
