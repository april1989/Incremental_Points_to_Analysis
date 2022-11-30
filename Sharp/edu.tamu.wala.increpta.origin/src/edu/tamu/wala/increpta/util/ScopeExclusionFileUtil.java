package edu.tamu.wala.increpta.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class ScopeExclusionFileUtil {
	
	private static boolean DEBUG = false;
	
	
	private String benchmark, version, taskname, gitrepo_dir, jar_path, classes_path, full_classes_path, exclusion;
	
	/**
	 * handle all things about scope file and exclusion file
	 * 
	 * @param benchmark
	 * @param version
	 * @param taskname == d4task.getName() 
	 * @param gitrepo_dir
	 * @param jar_path
	 * @param classes_path
	 * @param full_classes_path
	 */
	public ScopeExclusionFileUtil(String benchmark, String version, String taskname, String gitrepo_dir, String jar_path, String classes_path, String full_classes_path,
			String exclusion) {
		this.benchmark = benchmark;
		this.version = version;
		this.taskname = taskname;
		this.gitrepo_dir = gitrepo_dir;
		this.jar_path = jar_path;
		this.classes_path = classes_path;
		this.full_classes_path = full_classes_path;
		this.exclusion = exclusion;
	}
	
	/**
	 * create a scope file if not exist
	 */
	public String createScopeFile() {
		String Scope_File = gitrepo_dir + "/wala.testdata_" + benchmark + "_" + taskname + ".txt"; 
		if(version != null) {
			Scope_File = gitrepo_dir + "/wala.testdata_" + benchmark + "_" + version + ".txt"; 
		} 
		File file1 = new File(Scope_File);
		if(!file1.exists()) {
			file1 = new File(Scope_File);	
			FileWriter myWriter;
			//write the detail in the scope file
			try {
				myWriter = new FileWriter(Scope_File);
				myWriter.write("Primordial,Java,stdlib,none\n" + "Primordial,Java,jarFile,primordial.jar.model\n");
				
				String found = existCreateJar(gitrepo_dir);//Jar_Path assigned here
				if(!found.isEmpty()) {
					myWriter.close();
					return found;
				}
				
				if(jar_path != null) {//we use the jar
					System.out.println("Using built jar file @" + jar_path);
					myWriter.write("Application,Java,jarFile," + jar_path + "\n");
				}else if(classes_path != null) {	//we fill the class files
					System.out.println("Using built classes file @" + full_classes_path);
					File[] gitfiles = new File(full_classes_path).listFiles();
					fillFiles(gitfiles, myWriter);
				}else { 
					System.out.println("No specified file");
					File[] gitfiles = new File(gitrepo_dir).listFiles(); 
					fillFiles(gitfiles, myWriter);
				}

				myWriter.close();
				System.out.println("Successfully wrote to a new scope file: " + benchmark);
			} catch (IOException | InterruptedException e) {
				e.printStackTrace();
				return null;
			} 
		}		
		
		if(DEBUG) {
			//debug: print out scope file
			BufferedReader reader;
			try {
				reader = new BufferedReader(new FileReader(Scope_File));
				String line = reader.readLine();
				while (line != null) {
					System.out.println(line);
					// read next line
					line = reader.readLine();
				}
				reader.close();
			} catch (IOException e) {
				e.printStackTrace();
				return null;
			}
		}
		
		return Scope_File;
	}
	
	/**
	 * create a exclusion file if not exist
	 * @param wala_success 
	 */
	public String createExclusionFile(boolean wala_success) {
		String Exclusion_Packages = gitrepo_dir + "/MyDefaultExclusions_" + benchmark + "_" + taskname + ".txt";
		if(version != null) {
			Exclusion_Packages = gitrepo_dir + "/MyDefaultExclusions_" + benchmark + "_" + version + ".txt";
		}
		File file = new File(Exclusion_Packages);
		if(exclusion == null) { //use common exclusion file
			Exclusion_Packages = "tmp/MyDefaultExclusions.txt";
			file = new File(Exclusion_Packages);
		}
		try {
			FileWriter myWriter = new FileWriter(file);
			myWriter.write(wala_success ? (exclusion == null ? IPAUtil.DEFAULT_EXCLUSION_PACKAGES : exclusion) : IPAUtil.EMPTY_EXCLUSION_PACKAGES);
			myWriter.close();
			System.out.println("Successfully wrote to a new exclusion file: " + benchmark);
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
		
		return Exclusion_Packages;
	}
	
	/**
	 * check if exist created jar file as described from d4config.yml after build
	 * Jar_Path assigned here
	 * @return "" -> exist, "Jar_Path xxx " -> do not exist
	 */
	public String existCreateJar(String dir) {
		if(jar_path != null) {
			System.out.println("Jar_Path assigned by d4config.yml @" + jar_path);
			//if Jar_Path is a dir or a jar 
			File jar_file = new File(jar_path);
			if(jar_file.isFile()) { //using this jar 
				return "";
			}else if(jar_file.isDirectory()) { //search all jar in this dir
//				File[] files = new File(jar_file.getAbsolutePath()).listFiles(); 
//				for(File file : files) {
//					if(file.isFile() && file.getName().contains(".jar")) {
//						jar_path = file.getAbsolutePath();
//						return true;
//					}
//				}
				return "Jar_Path is illegal: it is a directory, no jar file exists. Return.";
			}else {
				return "Jar_Path is illegal: either a directory or a jar file. Return.";
			}
		}
		
		File[] gitfiles = new File(dir).listFiles();//re-list files -> recheck 
		for(File file : gitfiles) {
//			System.out.println(file.getAbsolutePath());
			if(file.isFile()) {
				if(file.getName().contains(".jar")) {
					jar_path = file.getAbsolutePath();
					return "";
				}
			}else {
				String found = existCreateJar(file.getAbsolutePath());
				if(found.isEmpty())
					return "";
			}
		}
		return ""; //Jar_Path cannot be found
	}
	
	


	private void fillFiles(File[] files, FileWriter writer) throws IOException, InterruptedException {
		if(files == null) {
			System.out.println("WRONG DIRECTORY WITH NULL FILES ... CANNOT CREATE SCOPE FILE");
			return;
		}
		
	    for (File file : files) {
	        if (file.isDirectory()) {
	            fillFiles(file.listFiles(), writer);  
	        } else { //files
	        	String filename = file.getName();
	        	int idx = filename.indexOf('.'); //not .java or .class file
	        	if(idx == -1)
	        		continue;
	        	
	        	String filetype = filename.substring(idx);
	        	if(filetype.equals(".java")) {
	        		writer.write("Application,Java,sourceFile," + file.getAbsolutePath() + "\n");
	        	}else if(filetype.equals(".class")) {// write to scope file when there is only one java file requires to compile using "java"
	        		writer.write("Application,Java,classFile," + file.getAbsolutePath() + "\n");
	        	}
	        }
	    }
	    
	}

}
