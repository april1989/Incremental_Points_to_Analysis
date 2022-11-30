package edu.tamu.wala.increpta.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;

public class GitUtil {
	 
	public static class GitResult {
		
		private Git git; 
		private ObjectId commitID;
		
		public GitResult(Git git, ObjectId commitID) {
			this.git = git;
			this.commitID = commitID;
		}
		
		public Git getGit() {
			return git;
		}
		
		public ObjectId getCommitID() {
			return commitID;
		}
		
	}
	
	
	/**
	 * @param gitpw -> null if public repo
	 * @param gituser -> null if public repo
	 * @return
	 */
	@SuppressWarnings("resource")
	public static GitResult doGitClone(String gitusername, String gitreponame, String gitpw, String local_dir) {
		File local = new File(local_dir);
		Path gitPath = Paths.get(local_dir, ".git");
		if(local.exists() && Files.isDirectory(gitPath, LinkOption.NOFOLLOW_LINKS)) {
			System.out.println("We already have this git repo on local@" + local_dir + ". REUSE NOW.");
			return retrieveGitFromExistingClone(local_dir, true);
		}
		
		ObjectId commitID = null;
		Git git = null;
		String gitfullname = gitusername + "/" + gitreponame;
		try {
			git = gitpw.equals("000000") ? //default pw i set ...
					Git.cloneRepository()//public repo
					  .setURI("https://github.com/" + gitfullname)
					  .setDirectory(local) //make sure this dir not exist or empty
					  .call()
					: Git.cloneRepository()//private repo
					  .setURI("https://github.com/" + gitfullname)  //not working ... 
					  .setCredentialsProvider(new UsernamePasswordCredentialsProvider(gitusername, gitpw))
					  .setDirectory(local) //make sure this dir not exist or empty
					  .call();
			commitID = getHeadCommitId(git);
		} catch (GitAPIException e) {//testing throws TransportException
			e.printStackTrace();
		} catch (Exception e) { //others i do not know now
			e.printStackTrace();
		}
		
		if(git == null) {//re-try this git clone
			System.out.println("FAIL: doGitClone(). Will retry.");
			try {
				Thread.sleep(2000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			
			return doGitClone(gitusername, gitreponame, gitpw, local_dir);
		}
        
        return new GitResult(git, commitID);
	}
	

	/**
	 * recover from .git in disk
	 * @param local_dir: absolute path
	 * @return
	 */
	public static GitResult retrieveGitFromExistingClone(String local_dir, boolean doPull) {
		File local = new File(local_dir);
		ObjectId commitID = null;
		if(local.exists()) {
			try (Git git = Git.open(local)) {
				if(doPull)
					git.pull().call();
				
				commitID = getHeadCommitId(git);
				return new GitResult(git, commitID);
			} catch (Exception e) {
				System.out.println("Wrong state when recovering from db: Cannot recover Git from local repo for " + local_dir + ".");
				e.printStackTrace();
			}
		}
		return null;
	}
	
	/**
	 * bz: get commit id 
	 * @param repo
	 * @return commit id
	 * @throws Exception
	 */
	@SuppressWarnings("resource")
	public static ObjectId getHeadCommitId(Git git) throws Exception {
		Repository repo = git.getRepository();
		try {
			ObjectId headId = repo.resolve("HEAD");

			RevWalk walk = new RevWalk(repo);
			RevCommit headCommit = walk.lookupCommit(headId);
			walk.dispose();

			System.out.println("COMMIT HEAD is [" + headCommit.getId() + "] ");
			return headCommit.getId();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return null;
	} 

	public static ObjectId doGitPull(Git git) {
		PullResult result = null;
		ObjectId commitID = null;
		try {
			result = git.pull().call();//pull diff 
			if(!result.isSuccessful()) {
				//should retry later 
				System.out.println("CANNOT GIT PULL NOW @" + git.toString());
				return null;
			}
			commitID = getHeadCommitId(git);
		} catch (GitAPIException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		if(result == null) { //re-try 
			System.out.println("FAIL: doGitPull(). Will retry.");
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			
			return doGitPull(git);
		}
		
        return commitID;
	}
	
	
	public static void doGitCheckout(Git git, String commitID, String branch) {
		try {
			System.out.println("Git checking out commit " + commitID);
			git.checkout().setName(commitID).call();
			ObjectId head;
			try {
				head = git.getRepository().resolve(Constants.HEAD);
				if(head.getName().equals(commitID)) {
					System.out.println("Successfully checked out. COMMIT HEAD is [" + head.getName() + "] ");
					return;
				}else {
					throw new IllegalStateException("Wrong checked out. Want COMMIT HEAD is [" + commitID + "], but got COMMIT HEAD [" + head.getName() + "].");
				}
			} catch (RevisionSyntaxException | IOException e) {
				e.printStackTrace();
			}
		} catch (GitAPIException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * created to replace org.eclipse.jgit.diff.DiffEntry 
	 * to convenient the local debug
	 * 
	 * @author bozhen
	 */
	public static class GitDiffInfo {
		private ChangeType changeType;
		private String sourceChangePath;
		
		public GitDiffInfo(ChangeType changeType, String sourceChangePath) {
			this.changeType = changeType;
			this.sourceChangePath = sourceChangePath;
		}
		
		public ChangeType getChangeType() {
			return changeType;
		}
		
		/**
		 * @return diffEntry.getNewPath()
		 */
		public String getSourceChangePath() {
			return sourceChangePath;
		}
		
		/**
		 * e.g. src/benchmarks/testcases/TestRace6.java
		 * @return
		 */
		public String getPackageName() {
			return sourceChangePath.substring(0, sourceChangePath.lastIndexOf('/'));
		}
		
		public String getClassName() {
			return sourceChangePath.substring(sourceChangePath.lastIndexOf('/') + 1, sourceChangePath.indexOf('.'));
		}
	}
	

	/**
	 * get difference between two commits
	 * @param oldcommitid
	 * @param newcommidid
	 * @return
	 */
	@SuppressWarnings("resource")
	public static List<GitDiffInfo> getDiffEntries(Git git, String oldcommitid, String newcommidid, boolean print) {
		// NOTE: must add "^{tree}" at the end to get a tree
		if(oldcommitid == null && newcommidid == null) {
			//compare the most recent two commits
			oldcommitid = "HEAD~1^{tree}";
			newcommidid = "HEAD^{tree}"; 
		}else {
			oldcommitid = oldcommitid + "^{tree}";
			newcommidid = newcommidid + "^{tree}";
		}
		
		List<DiffEntry> diffEntries = null;
		ObjectReader reader = git.getRepository().newObjectReader();
		CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
		
		try {
			ObjectId oldTree = git.getRepository().resolve(oldcommitid);  
			oldTreeIter.reset(reader, oldTree);
			CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
			ObjectId newTree = git.getRepository().resolve(newcommidid);  
			newTreeIter.reset(reader, newTree);
			DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE);
			diffFormatter.setRepository(git.getRepository());
			diffEntries = diffFormatter.scan(oldTreeIter, newTreeIter);
			diffFormatter.close();
		} catch (RevisionSyntaxException | IOException e) {
			e.printStackTrace();
		}
		
		if(diffEntries == null) {//re-try 
			System.out.println("FAIL: getDiffEntries(). Will retry.");
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			
			return getDiffEntries(git, oldcommitid, newcommidid, print);
		}
		
		return translateDiffEntry(diffEntries, print);
	}
	
	
	private static List<GitDiffInfo> translateDiffEntry(List<DiffEntry> diffEntries, boolean print) {
		List<GitDiffInfo> result = new ArrayList<>();
		for(DiffEntry diffEntry : diffEntries) {
			ChangeType changetype = diffEntry.getChangeType();
			String sourceChangePath = diffEntry.getNewPath();
			if(!sourceChangePath.contains(".java")) {
				System.out.println(">>>> We see non java code change: " + changetype + " -> " + sourceChangePath + ". Cannot handle now.");
				continue;
			}
			
			if(print) {
				System.out.println(">> " + changetype + " " + sourceChangePath);
			}
			
			GitDiffInfo info = new GitDiffInfo(changetype, sourceChangePath);
			result.add(info);
		}
		return result;
	}
	
	
	
}
