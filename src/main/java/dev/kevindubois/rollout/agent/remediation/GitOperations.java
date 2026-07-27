package dev.kevindubois.rollout.agent.remediation;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.File;
import java.text.MessageFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Deterministic git operations using JGit library.
 * This class does NOT use AI - all operations are standard library calls.
 */
@ApplicationScoped
public class GitOperations {
	
	/**
	 * Clone a repository to a temporary directory
	 * @param repoUrl GitHub repository URL
	 * @param token GitHub personal access token
	 * @return the cloned repo's path and its actual default branch
	 */
	public ClonedRepo cloneRepository(String repoUrl, String token) throws GitAPIException, IOException {
		Path localPath = Files.createTempDirectory("k8s-agent-fix-");
		Log.info(MessageFormat.format("Cloning repository {0} to {1}", repoUrl, localPath));

		// For GitHub, use token as username with empty password
		try (Git git = Git.cloneRepository()
				.setURI(repoUrl)
				.setDirectory(localPath.toFile())
				.setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, ""))
				.call()) {

			// Capture the default branch now, while HEAD is guaranteed to still be
			// on it (a fresh clone always checks out the remote's default branch).
			String defaultBranch = git.getRepository().getBranch();
			Log.info("Successfully cloned repository, default branch: " + defaultBranch);
			return new ClonedRepo(localPath, defaultBranch);
		}
	}

	/**
	 * Fetch latest changes from origin and hard-reset the working copy to the
	 * default branch, discarding any local modifications or branches.
	 *
	 * @param defaultBranch the repo's actual default branch, as captured once at
	 *                       clone time by {@link #cloneRepository}. This must NOT
	 *                       be re-derived from the currently checked-out branch:
	 *                       a prior remediation attempt may have left HEAD on a
	 *                       throwaway "fix/..." branch (e.g. if it failed after
	 *                       createBranch but before/during push), which would
	 *                       make git.getRepository().getBranch() return that
	 *                       feature branch instead of the real default - and
	 *                       since that branch was often never pushed, resetting
	 *                       to "origin/<that branch>" then fails outright with
	 *                       "Invalid ref name" (the remote-tracking ref doesn't
	 *                       exist).
	 */
	public void fetchAndReset(Path repoPath, String token, String defaultBranch) throws GitAPIException, IOException {
		Log.info(MessageFormat.format("Fetching and resetting cached clone at {0} to {1}", repoPath, defaultBranch));

		try (Git git = Git.open(repoPath.toFile())) {
			// Checkout the default branch first (in case a feature branch is checked out)
			git.checkout()
				.setName(defaultBranch)
				.setForced(true)
				.call();

			git.fetch()
				.setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, ""))
				.setRemote("origin")
				.call();

			git.reset()
				.setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
				.setRef("origin/" + defaultBranch)
				.call();

			// Remove any leftover local feature branches
			git.branchList().call().stream()
				.map(ref -> ref.getName().replace("refs/heads/", ""))
				.filter(name -> !name.equals(defaultBranch))
				.forEach(name -> {
					try {
						git.branchDelete().setBranchNames(name).setForce(true).call();
					} catch (GitAPIException e) {
						Log.warn(MessageFormat.format("Could not delete branch {0}: {1}", name, e.getMessage()));
					}
				});
		}

		Log.info("Cached clone is now clean and up-to-date");
	}

	public record ClonedRepo(Path path, String defaultBranch) {}

	/**
	 * Create and checkout a new branch
	 * @param repoPath Path to repository
	 * @param branchName Name of branch to create
	 */
	public void createBranch(Path repoPath, String branchName) throws GitAPIException, IOException {
		Log.info(MessageFormat.format("Creating branch: {0}", branchName));
		
		try (Git git = Git.open(repoPath.toFile())) {
			git.checkout()
				.setCreateBranch(true)
				.setName(branchName)
				.call();
		}
		
		Log.info(MessageFormat.format("Successfully created and checked out branch: {0}", branchName));
	}
	
	/**
	 * Apply file changes to the repository
	 * @param repoPath Path to repository
	 * @param fileChanges Map of file paths to new content
	 */
	public void applyChanges(Path repoPath, Map<String, String> fileChanges) throws IOException {
		Log.info(MessageFormat.format("Applying {0} file changes", fileChanges.size()));
		
		for (Map.Entry<String, String> change : fileChanges.entrySet()) {
			Path filePath = repoPath.resolve(change.getKey());
			
			// Create parent directories if they don't exist
			Files.createDirectories(filePath.getParent());
			
			// Write file content
			Files.writeString(filePath, change.getValue());
			Log.debug(MessageFormat.format("Updated file: {0}", change.getKey()));
		}
		
		Log.info("Successfully applied all changes");
	}
	
	/**
	 * Commit and push changes to remote
	 * @param repoPath Path to repository
	 * @param message Commit message
	 * @param token GitHub personal access token
	 */
	public void commitAndPush(Path repoPath, String message, String token) throws GitAPIException, IOException {
		Log.info("Committing and pushing changes");
		
		try (Git git = Git.open(repoPath.toFile())) {
			// Add all changes
			git.add()
				.addFilepattern(".")
				.call();
			
			// Commit
			git.commit()
				.setMessage(message)
				.call();
			Log.info(MessageFormat.format("Committed changes with message: {0}", message));
			
			// Get current branch name
			String branchName = git.getRepository().getBranch();
			Log.info(MessageFormat.format("Pushing branch: {0}", branchName));
			
			// Push - use token as username with empty password (GitHub standard)
			// Set upstream to create the branch on remote
			git.push()
				.setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, ""))
				.setRemote("origin")
				.setRefSpecs(new org.eclipse.jgit.transport.RefSpec(branchName + ":" + branchName))
				.call();
			
			Log.info("Successfully pushed changes to remote");
		}
	}
	
	/**
	 * Clean up temporary repository directory
	 * @param repoPath Path to repository
	 */
	public void cleanup(Path repoPath) {
		try {
			if (repoPath != null && Files.exists(repoPath)) {
				deleteDirectory(repoPath.toFile());
				Log.info(MessageFormat.format("Cleaned up temporary directory: {0}", repoPath));
			}
		} catch (Exception e) {
			Log.warn(MessageFormat.format("Failed to clean up directory {0}: {1}", repoPath, e.getMessage()));
		}
	}
	
	private void deleteDirectory(File directory) {
		File[] files = directory.listFiles();
		if (files != null) {
			for (File file : files) {
				if (file.isDirectory()) {
					deleteDirectory(file);
				} else {
					file.delete();
				}
			}
		}
		directory.delete();
	}
}


