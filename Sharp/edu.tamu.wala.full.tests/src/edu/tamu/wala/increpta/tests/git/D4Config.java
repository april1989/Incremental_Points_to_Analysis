package edu.tamu.wala.increpta.tests.git;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.eclipse.jgit.api.Git;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;



public class D4Config {
	
	//follow by jackson
	private String name;
	private List<String> branches;
	private BuildCMD build;
	private List<D4Task> d4tasks;
	
	//for my use 
	protected String gitpw;
	protected Git git; 
	
	public D4Config() {
	    // Without a default constructor, Jackson will throw an exception
	}
	
	public D4Config(String name, List<String> branches, BuildCMD build, List<D4Task> d4tasks) {
		this.name = name;
		this.branches = branches;
		this.build = build;
		this.d4tasks = d4tasks;
	}
	
	//follow by jackson
	public String getName() {
		return name;
	}
	
	public List<String> getBranches() {
		return branches;
	}
	
	public BuildCMD getBuild() {
		return build;
	}
	
	public List<D4Task> getD4tasks() {
		return d4tasks;
	}

//	//for my use
//	public void setGitPW(String gitpw) {
//		this.gitpw = gitpw;
//	}
//	
//	public String getGitPW() {
//		return gitpw;
//	}
//	
//	public void setGit(Git git) {
//		this.git = git;
//	}
//	
//	public Git getGit() {
//		return git;
//	}
//	
//	public void setD4(D4 d4) {
//		this.d4 = d4;
//	}
//	
//	public D4 getD4() {
//		return d4;
//	} 
	
	@Override
	public String toString() {
		return "name: \n" + name + "\nbranches: \n" + branches.toString() + "\nbuild: \n" + build.toString() + "\nd4tasks: \n" + d4tasks.toString();
	}

	public static class BuildCMD {
		
		protected String name, runs_on;
		protected List<BuildStep> steps;
		
		public BuildCMD() {
		}
		
		public BuildCMD(String name, String runs_on, List<BuildStep> steps) {
			this.name = name;
			this.runs_on = runs_on;
			this.steps = steps;
		}
		
		//follow by jackson
		public String getName() {
			return name;
		}
		
		public String getRuns_on() {
			return runs_on;
		}
		
		public List<BuildStep> getSteps() {
			return steps;
		}
		
		//for my use
		public List<String> getStepsAsOne() {
			ArrayList<String> result = new ArrayList<>();
			for(BuildStep step : steps) {
			  String tmp = step.getRun();
			  String[] tmps = tmp.split("\n");
			  for(int i=0; i<tmps.length; i++) {
			    result.add(tmps[i]);
			  }
			}
			return result;
		}
		
		@Override
		public String toString() {
			return " - name: \n" + name + "\n - java_version: \n" + runs_on + "\n - steps: \n" + steps.toString();
		}
	}
	
	public static class BuildStep {
		
		protected String name; 
		protected String run;
		
		public BuildStep() {
		}
		
		public BuildStep(String name, String run) {
			this.name = name;
			this.run = run;
		}
		
		//follow by jackson
		public String getName() {
			return name;
		}
		
		public String getRun() {
			return run;
		}
		
		@Override
		public String toString() {
			return " - name: \n" + name + "\n - run: \n" + run;
		}
		
	}
	
	public static class D4Task {
		
		protected String name, which_main_class, exclusions, jar, classes;
		
		public D4Task() {
		}
		
		public D4Task(String name, String which_main_class, String jar, String classes, String exclusions) {
			this.name = name;
			this.which_main_class = which_main_class;
			this.jar = jar;
			this.classes = classes;
			this.exclusions = exclusions;
		}
		
		//follow by jackson
		public String getName() {
			return name;
		}
		
		public String getWhich_main_class() {
			return which_main_class;
		}
		
		public String getExclusions() {
			return exclusions;
		}
		
		public String getJar() {
			return jar;
		}
		
		public String getClasses() {
			return classes;
		}

		//for my use
		public String getWhichPTA() {
			return "thread_old";
		}
		
		@Override
		public int hashCode() {
			return name.hashCode() * 131 + which_main_class.hashCode() * 11; // jar ? classes ? exclusion ?
		}
		
		@Override
		public String toString() {
			return " - name: \n" + name + "\n - main class: \n" + which_main_class + "\n - jar: \n" + (jar == null ? "empty" : jar) +
					"\n - classes: \n" + (classes == null ? "empty" : classes) +
					"\n - exclusions: \n" + exclusions.toString();
		}
	}


	/**
	 * if exist d4config.yml file from git repo, return it
	 */
	public static D4Config existD4ConfigFile(String benchmarkname, String dir_str) {
		File dir = new File(dir_str);
		if(!dir.isDirectory()) {
			LogUtil.addResponse("Directory (" + dir_str + ") does not exist in our disk. Please git clone first.");
			return null;
		}

		File[] gitfiles = dir.listFiles();
		for(File file : gitfiles) {//should be under the root dir
			if(file.isFile() && file.getName().equals("d4config.yml")) {
				//find it: read the config
				// Instantiating a new ObjectMapper as a YAMLFactory
				ObjectMapper om = new ObjectMapper(new YAMLFactory());

				// Mapping the employee from the YAML file to the Employee class
				D4Config config;
				try {
					config = om.readValue(file, D4Config.class);
					return config;
				} catch (IOException e) {
					LogUtil.add(e);
					e.printStackTrace();
					return null;
				}
			}
		}

		LogUtil.addResponse("Project (" + benchmarkname + ") has no d4config.json file. Please add your config file first.");
		return null;
	}


	//test
	public static void main(String[] args) throws Exception {
		File file = new File("/Users/bozhen/Documents/tmp/weblech-0.0.3/d4config.yml");
//		File file = new File("/Users/bozhen/Documents/tmp/MyGitAppTest/d4config.yml");
		if(file.isFile() && file.getName().equals("d4config.yml")) {
			//find it: read the config
			// Instantiating a new ObjectMapper as a YAMLFactory
			ObjectMapper om = new ObjectMapper(new YAMLFactory());

			// Mapping the employee from the YAML file to the Employee class
			D4Config config;
			try {
				config = om.readValue(file, D4Config.class);
				System.out.println(config);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}
