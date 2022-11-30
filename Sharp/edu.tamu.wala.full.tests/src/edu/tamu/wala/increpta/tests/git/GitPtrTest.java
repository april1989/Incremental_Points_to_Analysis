package edu.tamu.wala.increpta.tests.git;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Scanner;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;

import com.ibm.wala.classLoader.Language;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisOptions.ReflectionOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.util.io.FileProvider;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import edu.tamu.wala.increpta.callgraph.impl.IPAExplicitCallGraph;
import edu.tamu.wala.increpta.change.IRChangeAnalyzer;
import edu.tamu.wala.increpta.change.IRChangedSummary;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPointerAnalysisImpl;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPASSAPropagationCallGraphBuilder;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.bridge.AbstractKIPASSAPropagationCallGraphBuilder;
import edu.tamu.wala.increpta.tests.git.D4Config.BuildCMD;
import edu.tamu.wala.increpta.tests.git.D4Config.D4Task;
import edu.tamu.wala.increpta.util.GitUtil;
import edu.tamu.wala.increpta.util.GitUtil.GitDiffInfo;
import edu.tamu.wala.increpta.util.IPAUtil;
import edu.tamu.wala.increpta.util.JavaUtil;
import edu.tamu.wala.increpta.util.KCFAIPAUtil;
import edu.tamu.wala.increpta.util.KObjIPAUtil;
import edu.tamu.wala.increpta.util.ScopeExclusionFileUtil;

public class GitPtrTest {

	enum TYPE {
		kcfa, kobj, ci
	}

	public static int k = 2; // kobj/kcfa
	private TYPE PTA_TYPE = // run kcfa or kobj or context-insensitive
			TYPE.kcfa; 
//			TYPE.kobj;
//			TYPE.ci; //context-insensitive
	private static int PARALLEL_THREADS = 1; // the number of parallel threads in pta
	private static boolean MAX = false; // whether parallel all: SHARP
	private static String proj =
//			"weblech-0.0.3";  
			"MyGitHubAppExample"; 
//			"ktest_weblech";
//............................................
//			"h2database";  
//			"hbase";    
//      	"lucene";  
//			"hadoop";  
//			"zookeeper";  
	private static String initialcommit = "2795588a38c2b171d18c16af0c47629074602c38"; //the whole program analysis starts from this commit
	
	// please config the following local settings
	private static String git_username = "april1989";  // your git account username, please use public repo
	private static String this_dir = "/Users/bozhen/Documents/D4-Plugin/Incremental_Points_to_Analysis/Sharp/edu.tamu.wala.full.tests"; // your local path of this project
	private static String yml_dir = this_dir + "/ymls/" + git_username + "/" + proj; // where is your yml file of this project 
	private static String local_dir = this_dir + "/tmp/" + git_username + "/" + proj; // where do you put/want to put your git checkout repo for this project

	// general settings
	private static boolean SKIP_GITCLONE = false; // i'll clone it or already cloned
	private static boolean SKIP_INITIAL_BUILD = false; // i'll build it or already built
	private static boolean SKIP_INITIAL_JAR = false; // i'll jar it or already jared
	private static boolean INITIAL_ONLY = false; // only do initial whole program pta

	private static boolean CHECK_GIT_DIFF_ONLY = false; //only want to see the git diff files, not running any analysis
	private static boolean DEBUG = false;
	
	// pta setting
	private static ReflectionOptions REFLECTION = ReflectionOptions.MULTI_FLOW_TO_CASTS_APPLICATION_GET_METHOD;
	// Self-def, Reduced, NONE, MULTI_FLOW_TO_CASTS_APPLICATION_GET_METHOD
	private static String mainSignature = ".main([Ljava/lang/String;)V";
	private static boolean includeAllMainEntryPoints = false;

	// from user, cannot be changed
	private String gituser; // = gitusername
	private String benchmark; // = gitreponame -> gitfullname; // = gitusername + gitreponame
	private String gitrepo_dir; // local dir -> absolute path, where is this repo on local machine
	private List<String> build_cmd; // from .yml
	private String branch; // from .yml
	private String task_name; // from .yml
	private String version;
	private String mainClassName;
	private String mainMethodSig; // default: mainClassName + mainSignature;
	private String jar_path;
	private String classes_path, full_classes_path, exclusion;

	private String Scope_File;
	private String Exclusion_Packages = "tmp/MyDefaultExclusions.txt";// default ??

	// for my use
	private ArrayList<String> commits = new ArrayList<>(); // analyzed commits for incremental (excluding initial commit)
	private ArrayList<String> nonEmptyCommits = new ArrayList<>(); // analyzed commits that touches/changes pts
	private Iterator<RevCommit> commitIterator; // the current head is initialcommit
	private String oldCommitID;
	private String newCommitID;

	private IRChangeAnalyzer irAnalyzer;
	private ScopeExclusionFileUtil fileutil;
	private IPASSAPropagationCallGraphBuilder ipabuilder;
	private Git git;

	// pta data:
	// - time:
	public long sum_total_add_pta_nonempty = 0;
	public long sum_total_del_pta_nonempty = 0;
	public long sum_total_opt_pta_nonempty = 0;

	public long sum_total_add_pta = 0;
	public long sum_total_del_pta = 0;
	public long sum_total_opt_pta = 0;
	public long final_worst_add_pta = 0;
	public long final_worst_del_pta = 0;
	public long final_worst_opt_pta = 0;
	
	// - record the worst performance inst
	public SSAInstruction worstAddInstruction;
	public SSAInstruction worstDelInstruction;
	public String worstOPTCommit;

	// - size:
	public int sum_total_inst_add_pta = 0;
	public int sum_total_inst_del_pta = 0;
	public int sum_total_del_new_pta = 0;
	public int sum_total_del_invoke_pta = 0;


	/**
	 * 
	 * @param benchmark
	 * @param version
	 * @param gituser
	 * @param gitrepo_dir
	 * @param buildCMD
	 * @param mainClassName
	 * @param jar_path
	 * @param classes_path
	 * @param commits
	 */
	public GitPtrTest(String benchmark, String version, String gituser, String gitrepo_dir, BuildCMD buildCMD,
			String branch, String task_name, String mainClassName, String jar_path, String classes_path,
			String exclusion, String initialcommit) {
		this.gituser = gituser;
		this.benchmark = benchmark;
		this.version = version;
		this.gitrepo_dir = gitrepo_dir;
		this.build_cmd = buildCMD.getStepsAsOne();
		this.branch = branch;
		this.task_name = task_name;
		this.initialcommit = initialcommit;
		this.mainClassName = mainClassName;
		this.mainMethodSig = mainClassName + mainSignature;
		this.jar_path = jar_path == null ? jar_path : gitrepo_dir + jar_path;
		this.classes_path = classes_path;
		this.full_classes_path = gitrepo_dir + classes_path;
		this.exclusion = exclusion;
	}

	private void initial() {
		compile(true);

		this.fileutil = new ScopeExclusionFileUtil(benchmark, version, task_name, gitrepo_dir, jar_path, classes_path,
				full_classes_path, exclusion);

		String ret = fileutil.createExclusionFile(true);
		if (ret == null) {
			return;
		}
		Exclusion_Packages = ret;

		String ret2 = fileutil.createScopeFile();
		if (ret2 == null || ret2.startsWith("Jar_Path")) {
			return;
		}
		Scope_File = ret2;

		this.irAnalyzer = new IRChangeAnalyzer(Scope_File, Exclusion_Packages);
	}
	
	
	private void execute(boolean print, String[] cmds) {
		// init shell
		ProcessBuilder builder = new ProcessBuilder("/bin/zsh");
		builder.redirectErrorStream(true);

		try {
			final Process p = builder.start();
			
			// get stdin of shell
			BufferedWriter p_stdin = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()));

			// execute the desired command (here: ls) n times
			if (print)
				System.out.println("------ Build CMD ------ ");
			for (int i = 0; i < cmds.length; i++) {
				try {
					// single execution
					if (print)
						System.out.println(cmds[i]);
					p_stdin.write(cmds[i]);
					p_stdin.newLine();
					p_stdin.flush();
				} catch (IOException e) {
					System.out.println("Compile Exception: cmds: " + e.getMessage());
					System.out.println(e);
					return;
				}
			}
			if (print)
				System.out.println("------ ------ ------ ------ ");

			Thread inputT = new Thread(new Runnable() {
				@Override
				public void run() {
					// write stdout of shell (=output of all commands)
					if (print)
						System.out.println("------ Build Output ------ ");
					Scanner scanner = new Scanner(p.getInputStream());
					while (scanner.hasNextLine()) {
						String line = scanner.nextLine();
						if (print)
							System.out.println(line);
						if(line.contains("BUILD SUCCESS")) {
							p.destroy(); // compiling zookeeper/lucene will not exit normally -> manually terminate 
							break;
						}
					}
					scanner.close();
					if (print)
						System.out.println("------ ------ ------ ------ ");
				}
			});
			
			Thread errorT = new Thread(new Runnable() {
				@Override
				public void run() {
					Scanner scanner2 = new Scanner(p.getErrorStream());
					if (scanner2.hasNextLine()) {
//						if (print)
							System.out.println("------ Build Error ------ ");
						while (scanner2.hasNextLine()) {
							String line = scanner2.nextLine();
//							if (print)
								System.out.println(line);
						}
//						if (print)
							System.out.println("------ ------ ------ ------ ");
					}
					scanner2.close();
				}
			});
			
			inputT.start();
			errorT.start();

			errorT.join();
			inputT.join();
			
		} catch (IOException | InterruptedException e) {
			System.out.println("Compile Exception: ProcessBuilder: " + e.getMessage());
			System.out.println(e);
			return;
		}
	}
	
	private String[] assembleCmd(List<String> input_cmd) {
		boolean isAnt = false;
		for(String c : input_cmd) {
			if(c.contains("ant")) {
				isAnt = true; break;
			}
		}
		
		// generate cmd
		String[] cmds = null;
		cmds = new String[4 + input_cmd.size()];
		cmds[0] = "cd " + gitrepo_dir;
		
		if(isAnt) {
			// these two lines solves all "zsh permission denied" problems in ant
			cmds[1] = "export ANT_HOME=/usr/local/bin/ant";
			cmds[2] = "export PATH=$PATH:/usr/local/Cellar/ant/1.10.9/bin";
		} else {
			// these two lines solves all "cmd not find" problems in maven/gradle
			cmds[1] = "source ~/.bash_profile";
			cmds[2] = "source ~/.zshrc";
		}
//				cmds[1] = "chmod 755 ./build.sh"; //permission deny

		for (int i = 3; i < input_cmd.size() + 3; i++) {
			cmds[i] = input_cmd.get(i - 3);
		}
		cmds[3 + input_cmd.size()] = "exit";
		
		return cmds;
	}

	/**
	 * COPIED bz: simple javac or build.xml compile ...
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private void compile(boolean print) {
		// separate build_cmd into build and jar two parts
		int idx = build_cmd.indexOf("echo \"jaring ... \"");
		
		if(idx == -1) {
			// only has build part
			if (SKIP_INITIAL_BUILD && ipabuilder == null) {
				System.out.println("Skip build ...");
				return;
			}else {
				System.out.println("Building ...");
				String[] cmds = assembleCmd(build_cmd);
				execute(print, cmds);
			}
		}else {
			// do build first 
			if (SKIP_INITIAL_BUILD && ipabuilder == null) {
				System.out.println("Skip build ...");
			}else {
				System.out.println("Building ...");
				String[] bcmds = assembleCmd(build_cmd.subList(0, idx));
				execute(print, bcmds);
			}

			// do jar part
			if (SKIP_INITIAL_JAR && ipabuilder == null) {
				System.out.println("Skip jar ...");
			}else {
				System.out.println("Jaring ...");
				String[] jcmds = assembleCmd(build_cmd.subList(idx, build_cmd.size()));
				execute(print, jcmds);
			}
		}
	}

	// iter to the commit that == initial commit
	private void getGitLog() throws Exception {
		// inital
		this.oldCommitID = GitUtil.getHeadCommitId(git).name();

		// get all commits by log
		Iterable<RevCommit> log = git.log().call();
		Iterator<RevCommit> it = log.iterator();
		while (it.hasNext()) {
			RevCommit commit = it.next();
			if (commit.getId().getName().contains(initialcommit)) {
				System.out.println("Iterating to initialcommit: " + initialcommit);
				commitIterator = it;
				return;
			}
		}

		throw new IllegalStateException("CANNOT FIND initialcommit in git.log().");
	}

	private void doGitVersionPTATest() throws Exception {
		if (SKIP_GITCLONE) {
			System.out.println("Skip git clone ...");

			// retrieve git
			this.git = GitUtil.retrieveGitFromExistingClone(gitrepo_dir, false).getGit();

			// check if head is initial commit
			String curCommit = GitUtil.getHeadCommitId(git).name();
			if (curCommit != initialcommit) {
				System.out.println("Git Checkout Initial Commit ...");

				// checkout initialcommit
				GitUtil.doGitCheckout(this.git, initialcommit, branch);
			}

		} else {
			// git setup: initial clone
			this.git = GitUtil.doGitClone(gituser, benchmark, "000000", gitrepo_dir).getGit();

			// the analysis start from the 1st git commit
			GitUtil.doGitCheckout(this.git, initialcommit, branch);
		}

		getGitLog();
		
		if(!CHECK_GIT_DIFF_ONLY) { // do analysis
			// build the project and initialize exclusion and scope files
			initial();

			Runtime rt = Runtime.getRuntime();
			long free = rt.freeMemory();

			System.out.println(
					"\n\n" + task_name + "\n*******************************************************\n*******************************************************");
			
			switch (PTA_TYPE) {
			case kcfa:
				System.out.println("Start the " + k + "-cfa test (#threads " + PARALLEL_THREADS + "; " + (MAX ? "max" : "basic") + ") for test case: " + benchmark);
				runKCFAPTA();
				break;

			case kobj:
				System.out.println("Start the " + k + "-obj test (#threads " + PARALLEL_THREADS + "; " + (MAX ? "max" : "basic") + ") for test case: " + benchmark);
				runKObjPTA();
				break;

			case ci:
				System.out.println("Start the context-insensitive test (#threads " + PARALLEL_THREADS + "; " + (MAX ? "max" : "basic") + ") for test case: " + benchmark);
				runContextInsensitivePTA();
				break;

			default:
				throw new IllegalArgumentException("do not have such pta type: " + PTA_TYPE);
			}

			long total = rt.totalMemory();
			System.out.println("TOTAL MEMORY (initial pta): " + (total - free) / mb + "MB.");

			if(INITIAL_ONLY)
				return;
		}

		System.out.println("\n\n**********************************************************************************"
				+ "\nStart the correctness check for incremental pointer analysis in " + mainClassName);
		doGitVersionCheck();

		// performace summary
		System.out.println("\nComplete the git version test for " + mainClassName + ")");
		System.out.println("=================================================================================");
		System.out.println("PTA DATA:");
		System.out.println("Total Analyzed Commits: " + commits.size());
		System.out.println("Total Analyzed Effective Commits: " + nonEmptyCommits.size());
		System.out.println();

		System.out.println("Total Num of del new insts: " + sum_total_del_new_pta);
		System.out.println("Total Num of del invoke insts: " + sum_total_del_invoke_pta);
		System.out.println("Total Num of add insts: " + sum_total_inst_add_pta);
		System.out.println("Total Num of del insts: " + sum_total_inst_del_pta);
		System.out.println();

		System.out.println("Total Add : " + sum_total_add_pta + "ms (including all commits)");
		System.out.println("Total Del : " + sum_total_del_pta + "ms (including all commits)");
		if (PTA_TYPE != TYPE.ci) {
			System.out.println("Total OPT : " + sum_total_opt_pta + "ms (including all commits)");
			System.out.println("Total Del + OPT : " + (sum_total_opt_pta + sum_total_del_pta) + "ms (including all commits)");
		}
		System.out.println();

		System.out.println("Total Add : " + sum_total_add_pta_nonempty + "ms (including effective commits)");
		System.out.println("Total Del : " + sum_total_del_pta_nonempty + "ms (including effective commits)");
		if (PTA_TYPE != TYPE.ci) {
			System.out.println("Total OPT : " + sum_total_opt_pta_nonempty + "ms (including effective commits)");
			System.out.println("Total Del + OPT : " + (sum_total_opt_pta_nonempty + sum_total_del_pta_nonempty) + "ms (including all commits)");
		}
		System.out.println();

		if (sum_total_inst_add_pta == 0) {
			System.out.println("Total Avg add: 0ms (no changed instruction)");
		} else {
			System.out.println("Total Avg add: " + ((double) sum_total_add_pta / (double) sum_total_inst_add_pta)
					+ "ms/instruction (including all commits)");
			System.out.println("Total Avg add: " + ((double) sum_total_add_pta_nonempty / (double) sum_total_inst_add_pta)
							+ "ms/instruction (including effective commits)");
		}

		if (sum_total_inst_del_pta == 0) {
			System.out.println("Total Avg del: 0ms (no changed instruction)");
		} else {
			System.out.println("Total Avg del: " + ((double) sum_total_del_pta / (double) sum_total_inst_del_pta)
					+ "ms/instruction (including all commits)");
			System.out.println("Total Avg del: " + ((double) sum_total_del_pta_nonempty / (double) sum_total_inst_del_pta)
							+ "ms/instruction (including effective commits)");
			if (PTA_TYPE != TYPE.ci) {
				System.out.println("Total Avg OPT: " + ((double) sum_total_opt_pta_nonempty / (double) (sum_total_del_invoke_pta + sum_total_del_new_pta))
						+ "ms/instruction (including effective commits: news + invokes)");
				System.out.println("Total Avg del + OPT: " + ((double) (sum_total_del_pta_nonempty + sum_total_opt_pta_nonempty) / (double) sum_total_inst_del_pta)
						+ "ms/instruction (including effective commits)");
			}
		}
		System.out.println();

		System.out.println("Total Avg add: " + ((double) sum_total_add_pta / (double) commits.size())
				+ "ms/commit (including all commits)");
		System.out.println("Total Avg del: " + ((double) sum_total_del_pta / (double) commits.size())
				+ "ms/commit (including all commits)");
		if (PTA_TYPE != TYPE.ci) {
			System.out.println("Total Avg OPT: " + ((double) sum_total_opt_pta / (double) commits.size())
				+ "ms/commit (including all commits)");
			System.out.println("Total Avg del + OPT: " + ((double) (sum_total_opt_pta + sum_total_del_pta) / (double) commits.size())
					+ "ms/commit (including all commits)");
		}
		System.out.println();

		System.out.println("Total Avg add: " + ((double) sum_total_add_pta_nonempty / (double) nonEmptyCommits.size())
				+ "ms/commit (including effective commits)");
		System.out.println("Total Avg del: " + ((double) sum_total_del_pta_nonempty / (double) nonEmptyCommits.size())
				+ "ms/commit (including effective commits)");
		if (PTA_TYPE != TYPE.ci) {
			System.out.println("Total Avg OPT: " + ((double) sum_total_opt_pta_nonempty / (double) nonEmptyCommits.size())
				+ "ms/commit (including effective commits)");
			System.out.println("Total Avg del + OPT: " + ((double) (sum_total_opt_pta_nonempty + sum_total_del_pta_nonempty) / (double) nonEmptyCommits.size())
					+ "ms/commit (including effective commits)");
		}
		System.out.println();

		if (worstAddInstruction != null) {
			System.out.println("The Worst add: " + final_worst_add_pta + "ms -> " + worstAddInstruction.toString());
			System.out.println("The Worst del: " + final_worst_del_pta + "ms -> " + worstDelInstruction.toString());
			if (PTA_TYPE != TYPE.ci) {
				System.out.println("The Worst OPT: " + final_worst_opt_pta + "ms -> " + worstOPTCommit);
			}
		}
	}
	
	// to measure performance below
	private long ipa_start;
	private long total_start;

	private void doGitVersionCheck() throws Exception {
		ipa_start = 0;
		total_start = System.currentTimeMillis();
		
		// iterate over 10 git commits
		while (nonEmptyCommits.size() < 10 && commitIterator.hasNext()) {
			// record
			if(newCommitID != null)
				oldCommitID = newCommitID;
			newCommitID = commitIterator.next().getId().getName();
			commits.add(newCommitID);				
			
			doGitVersionJob();
		}

		System.out.println("\n\nTotal Incremental PTA Time (pure): " + ipa_start + "ms");
		System.out.println("Total Incremental PTA Time (including git checkout/recompile): "
				+ (System.currentTimeMillis() - total_start) + "ms");
	}
	
	
	private void doGitVersionJob() throws ClassHierarchyException, IOException {
		GitUtil.doGitCheckout(git, newCommitID, branch);
		System.out.println("\nIter " + nonEmptyCommits.size() + ". TESTING GIT COMMIT: " + newCommitID);

		// list git diff
		List<GitDiffInfo> diffInfos = GitUtil.getDiffEntries(git, oldCommitID, newCommitID, CHECK_GIT_DIFF_ONLY);
		if(diffInfos.isEmpty()) {
			System.out.println(">>>>>> This commit only contains non-java changes. Skip.");
			return;
		}
		
		if(CHECK_GIT_DIFF_ONLY) 
			return;

		// recompile to get new CHA
		System.out.println("--- Recompile for this commit ---");
		compile(false);
		System.out.println("--- Finish recompile this commit ---");

		// officially starts here
		long start = System.currentTimeMillis();
		// read in the diff methods and ir diff
		IRChangedSummary summary = new IRChangedSummary();
		irAnalyzer.computeCommitChanges(diffInfos, summary, ipabuilder);

		sum_total_inst_add_pta = sum_total_inst_add_pta + summary.total_inst_add;
		sum_total_inst_del_pta = sum_total_inst_del_pta + summary.total_inst_del;
		sum_total_del_new_pta = sum_total_del_new_pta + summary.total_del_new;
		sum_total_del_invoke_pta = sum_total_del_invoke_pta + summary.total_del_invoke;

		// incremental pta algo
		runIncrementalPTA(summary);

		// update time
		long pta_total = System.currentTimeMillis() - start;
		ipa_start = ipa_start + pta_total;

		// whether this commit touches pta: total_change == 0 -> no touch
		int total_change = summary.total_inst_add + summary.total_inst_del + summary.total_del_new
				+ summary.total_del_invoke;
		if (total_change > 0) {
			// update if touches
			nonEmptyCommits.add(newCommitID);
			sum_total_add_pta_nonempty = sum_total_add_pta_nonempty + summary.total_add;
			sum_total_del_pta_nonempty = sum_total_del_pta_nonempty + summary.total_del;
			sum_total_opt_pta_nonempty = sum_total_opt_pta_nonempty + summary.total_opt;
		}

		sum_total_add_pta = sum_total_add_pta + summary.total_add;
		sum_total_del_pta = sum_total_del_pta + summary.total_del;
		sum_total_opt_pta = sum_total_opt_pta + summary.total_opt;

		if (final_worst_add_pta < summary.worst_add) {
			final_worst_add_pta = summary.worst_add;
			worstAddInstruction = summary.worstAddInstruction;
		}

		if (final_worst_del_pta < summary.worst_del) {
			final_worst_del_pta = summary.worst_del;
			worstDelInstruction = summary.worstDelInstruction;
		}

		if (final_worst_opt_pta < summary.total_opt) {
			final_worst_opt_pta = summary.total_opt;
			worstOPTCommit = newCommitID;
		}

		// output data for this commit
		System.out.println("DONE FOR THIS GIT COMMIT: " + (System.currentTimeMillis() - start) + "ms.");
		if (ipabuilder instanceof AbstractKIPASSAPropagationCallGraphBuilder) {
			((AbstractKIPASSAPropagationCallGraphBuilder) ipabuilder).printData(newCommitID);
		}
		System.out.println("============== pta data for commit " + newCommitID + " ==============");
		System.out.println("#add ir insts: " + summary.total_inst_add);
		System.out.println("#del ir insts: " + summary.total_inst_del);
		System.out.println("#del new insts: " + summary.total_del_new);
		System.out.println("#del invoke insts: " + summary.total_del_invoke);
		System.out.println("opt for this commit: " + summary.total_opt + "ms");
		System.out.println("worst add in this commit: " + summary.worst_add + "ms");
		System.out.println("worst del in this commit: " + summary.worst_del + "ms");
		System.out.println("total time for this commit: " + pta_total + "ms"); //incremental pta only
		System.out.println("=================================================================\n\n");

		if (ipabuilder instanceof AbstractKIPASSAPropagationCallGraphBuilder) {
			((AbstractKIPASSAPropagationCallGraphBuilder) ipabuilder).clear();
		}

		if (DEBUG) {
			// check if the cg is correct
			IPAUtil.printData(null, ipabuilder.getCallGraph(), null);
		}

	}

	/**
	 * subclass please override this
	 * 
	 * @param summary
	 */
	public void runIncrementalPTA(IRChangedSummary summary) {
		// update pag and cg here
		if (summary.doUpdatePTA()) {
			if (ipabuilder == null)
				throw new IllegalStateException("ipabuilder should not be null");

			if (PTA_TYPE == TYPE.kcfa || PTA_TYPE == TYPE.kobj) {
				AbstractKIPASSAPropagationCallGraphBuilder builder = (AbstractKIPASSAPropagationCallGraphBuilder) ipabuilder;
				if (summary.onlySyncModifierChanges()) {
					builder.updateCallGraph(summary);
				} else {
					try {
						builder.updatePointerAnalysis(summary);
					} catch (CancelException e) {
						e.printStackTrace();
					}
				}
			} else {
				if (summary.onlySyncModifierChanges()) {
					ipabuilder.updateCallGraph(summary);
				} else {
					try {
						ipabuilder.updatePointerAnalysis(summary);
					} catch (CancelException e) {
						e.printStackTrace();
					}
				}
			}

		}
	}

	/**
	 * new kobj algo
	 */
	private void runKObjPTA() {
		long pta_start = System.currentTimeMillis();
		AnalysisCache cache = new AnalysisCacheImpl();
		AnalysisScope scope = null;
		ClassHierarchy cha = null;
		try {
			scope = AnalysisScopeReader.readJavaScope(Scope_File, (new FileProvider()).getFile(Exclusion_Packages),
					GitPtrTest.class.getClassLoader());
			cha = ClassHierarchyFactory.make(scope);
		} catch (IOException | ClassHierarchyException e) {
			e.printStackTrace();
			return;
		}

		Iterable<Entrypoint> entrypoints = IPAUtil.findEntryPoints(cha, mainClassName, includeAllMainEntryPoints);
		mainMethodSig = mainClassName.replace("/", ".").substring(1) + mainSignature;
		AnalysisOptions options = new AnalysisOptions(scope, entrypoints);
		options.setReflectionOptions(REFLECTION);

		ipabuilder = KObjIPAUtil.makeKObjBuilder(Language.JAVA, k, options, cache, cha, scope);
		JavaUtil.setBuilder(ipabuilder);
		IPAExplicitCallGraph cg = null;
		try {
			cg = (IPAExplicitCallGraph) ipabuilder.makeCallGraph(options, null, PARALLEL_THREADS, MAX);
		} catch (IllegalStateException | CallGraphBuilderCancelException e) {
			e.printStackTrace();
			return;
		}

		IPAPointerAnalysisImpl pta = (IPAPointerAnalysisImpl) ipabuilder.getPointerAnalysis();

		long pta_time = System.currentTimeMillis() - pta_start;
		System.out.println("Finish the whole program pta analysis for " + mainClassName + " using " + pta_time + "ms");

		// ptg statistics
		IPAUtil.printData(cha, cg, pta);
		ipabuilder.setKObj(true);

	}

	private void runKCFAPTA() {
		long pta_start = System.currentTimeMillis();
		AnalysisCache cache = new AnalysisCacheImpl();
		AnalysisScope scope = null;
		ClassHierarchy cha = null;
		try {
			scope = AnalysisScopeReader.readJavaScope(Scope_File, (new FileProvider()).getFile(Exclusion_Packages),
					GitPtrTest.class.getClassLoader());
			cha = ClassHierarchyFactory.make(scope);
		} catch (IOException | ClassHierarchyException e) {
			e.printStackTrace();
			return;
		}

		Iterable<Entrypoint> entrypoints = IPAUtil.findEntryPoints(cha, mainClassName, includeAllMainEntryPoints);
		mainMethodSig = mainClassName.replace("/", ".").substring(1) + mainSignature;
		AnalysisOptions options = new AnalysisOptions(scope, entrypoints);
		options.setReflectionOptions(REFLECTION);

		ipabuilder = KCFAIPAUtil.makekCallsiteIPABuilder(Language.JAVA, k, options, cache, cha, scope);
		JavaUtil.setBuilder(ipabuilder);
		IPAExplicitCallGraph cg = null;
		try {
			cg = (IPAExplicitCallGraph) ipabuilder.makeCallGraph(options, null, PARALLEL_THREADS, MAX);
		} catch (IllegalStateException | CallGraphBuilderCancelException e) {
			e.printStackTrace();
			return;
		}

		IPAPointerAnalysisImpl pta = (IPAPointerAnalysisImpl) ipabuilder.getPointerAnalysis();

		long pta_time = System.currentTimeMillis() - pta_start;
		System.out.println("Finish the whole program pta analysis for " + mainClassName + " using " + pta_time + "ms");

		// ptg statistics
		IPAUtil.printData(cha, cg, pta);
		ipabuilder.setKCFA(true);

	}

	private void runContextInsensitivePTA() {
		long pta_start = System.currentTimeMillis();
		AnalysisCache cache = new AnalysisCacheImpl();
		AnalysisScope scope = null;
		ClassHierarchy cha = null;
		try {
			scope = AnalysisScopeReader.readJavaScope(Scope_File, (new FileProvider()).getFile(Exclusion_Packages),
					GitPtrTest.class.getClassLoader());
			cha = ClassHierarchyFactory.make(scope);
		} catch (IOException | ClassHierarchyException e) {
			e.printStackTrace();
			return;
		}

		Iterable<Entrypoint> entrypoints = IPAUtil.findEntryPoints(cha, mainClassName, includeAllMainEntryPoints);
		mainMethodSig = mainClassName.replace("/", ".").substring(1) + mainSignature;
		AnalysisOptions options = new AnalysisOptions(scope, entrypoints);
		options.setReflectionOptions(REFLECTION);

		ipabuilder = IPAUtil.makeIPAZeroOneCFABuilder(Language.JAVA, options, cache, cha, scope);
		JavaUtil.setBuilder(ipabuilder);
		IPAExplicitCallGraph cg = null;
		try {
			cg = (IPAExplicitCallGraph) ipabuilder.makeCallGraph(options, null, PARALLEL_THREADS, MAX);
		} catch (IllegalStateException | CallGraphBuilderCancelException e) {
			e.printStackTrace();
			return;
		}

		IPAPointerAnalysisImpl pta = (IPAPointerAnalysisImpl) ipabuilder.getPointerAnalysis();

		long pta_time = System.currentTimeMillis() - pta_start;
		System.out.println("Finish the whole program pta analysis for " + mainClassName + " using " + pta_time + "ms");

		// ptg statistics
		IPAUtil.printData(cha, cg, pta);
		ipabuilder.setKCFA(true);

	}
	
	public static String translateExclusions(String origin) {
		if(origin != null) {
			String result = origin.toString().replaceAll("/", "\\\\/");
			result = result.replace(", ", "\n"); 
			return result;
		}
		return null;
	}

//////////////////////////////////////////////////////////////////////////////////////////////////////////

	private static int mb = 1024 * 1024;

	public static void main(String[] args) throws Exception {
		Runtime rt = Runtime.getRuntime();
		long free = rt.freeMemory();

		String pureName = proj;
		if (pureName.contains("-"))
			pureName = proj.substring(0, proj.indexOf('-'));

		// borrow: just want to read in the configs
		D4Config fullConfig = D4Config.existD4ConfigFile(proj, yml_dir);
		D4Task task = fullConfig.getD4tasks().get(0);

		long start = System.currentTimeMillis();
		GitPtrTest test = new GitPtrTest(proj, null, git_username, local_dir, fullConfig.getBuild(),
				fullConfig.getBranches().get(0), task.getName(), task.getWhich_main_class(), task.getJar(),
				task.getClasses(), translateExclusions(task.getExclusions()), initialcommit);
		test.doGitVersionPTATest();
		System.out.println(
				"\n\n\nTotal Time (this whole git test including initial whole program/git checkout/recompile): "
						+ (System.currentTimeMillis() - start) + "ms");
		long total = rt.totalMemory();
		System.out.println("Total Memory (this whole git test including initial whole program/git checkout/recompile): "
				+ (total - free) / mb + "MB.");

		System.out.println("\n\n\nAll analyzed commits: ");
		for (int i = 0; i < test.commits.size(); i++) {
			String commit = test.commits.get(i);
			boolean non = test.nonEmptyCommits.contains(commit);
			System.out.println(i + ". " + commit + (non ? " (effective)" : ""));
		}
	}

}
