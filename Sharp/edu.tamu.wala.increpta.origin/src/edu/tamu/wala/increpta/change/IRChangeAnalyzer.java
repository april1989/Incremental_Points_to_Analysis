package edu.tamu.wala.increpta.change;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.eclipse.jgit.diff.DiffEntry.ChangeType;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IClassLoader;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSACache;
import com.ibm.wala.ssa.SSAOptions;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.util.io.FileProvider;

import edu.tamu.wala.increpta.callgraph.impl.IPAExplicitCallGraph.IPAExplicitNode;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPASSAPropagationCallGraphBuilder;
import edu.tamu.wala.increpta.util.GitUtil.GitDiffInfo;

/**
 * 
 * analyze incremental ir changes for a commit
 * 
 * @author bozhen
 *
 */
public class IRChangeAnalyzer {
	
	//for my use
	private String Scope_File;
	private String Exclusion_Packages = "tmp/MyDefaultExclusions.txt";//default  ??
	
	//immediate results == current value; always updated with new ipabuilder 
	// -> cached all the time
	private IPASSAPropagationCallGraphBuilder ipabuilder;
	private SSACache ssacache;
	private SSAOptions ssaoptions;
	
	//when has new changes
	private ClassHierarchy cha_new;
	
	//tmp imput from subclass

	
	/** 
	 * need them for new scope
	 * @param scope -> file
	 * @param exclusions -> file
	 */
	public IRChangeAnalyzer(String scope, String exclusions) {
		this.Scope_File = scope;
		this.Exclusion_Packages = exclusions;
	}
	
	/**
	 * @param summary
	 * @param ipabuilder 
	 * @param oldCommitID -> can be null 
	 * @param newCommitID -> can be null
	 * @throws IOException 
	 * @throws ClassHierarchyException 
	 */
	public void computeCommitChanges(List<GitDiffInfo> diffEntries, IRChangedSummary summary, IPASSAPropagationCallGraphBuilder ipabuilder) throws IOException, ClassHierarchyException {
		long start = System.currentTimeMillis();
		
		//tmp input for this iteration
		this.ipabuilder = ipabuilder;
		AnalysisCache cache = (AnalysisCache) ipabuilder.getAnalysisCache();
		this.ssacache = cache.getSSACache();
		this.ssaoptions = cache.getSSAOptions();
		
		//scope and cha need to be updated
		AnalysisScope scope_new = AnalysisScopeReader.readJavaScope(Scope_File, (new FileProvider()).getFile(Exclusion_Packages), IRChangeAnalyzer.class.getClassLoader());
		this.cha_new = ClassHierarchyFactory.make(scope_new);
		
		System.out.println("List All Diff in This Commit: (all should be .java files if any exists) ");
		for(GitDiffInfo diffEntry : diffEntries) {
			//this is a class ... here we find method changes
			String sourceChangePath = diffEntry.getSourceChangePath();
			if(!sourceChangePath.contains(".java"))
				continue;
			
			ChangeType changetype = diffEntry.getChangeType(); 
			String packagenamediff = diffEntry.getPackageName();
			String classnamediff = diffEntry.getClassName(); 
			System.out.println(" - " + changetype + " -> " + sourceChangePath);
			
			switch (changetype) {
			case DELETE:
				break;
			case ADD:
				break;
			case COPY:
				break;
			case RENAME:
				break;
			case MODIFY:
				//compute if any difference in its methods
				computeIRChanges(summary, packagenamediff, classnamediff);
				break;
			default: 
				throw new IllegalStateException("New ChangeType: " + changetype);
			}
			
		}
		
		System.out.println("Compute Commit Change/Diff Done: " + (System.currentTimeMillis() - start) + "ms.");
	}
	
	
	/**
	 * bz: compute IR diff for all methods in a sub class
	 * @param summary
	 * @param class_new
	 * @param class_old
	 * @param furItems
	 */
	private void computeForIClass(IRChangedSummary summary, IClass class_new, IClass class_old, ArrayList<AstChangedItem> furItems) {
		for(IMethod method_new : class_new.getDeclaredMethods()){
			IMethod method_old = class_old.getMethod(method_new.getSelector()); 
			if (method_old == null) {//this has been excluded ... 
				System.out.println("Null method_old: " + method_new.getSelector().toString());
				continue;
			}
			
			Set<CGNode> cgnode_olds = ipabuilder.getCallGraph().findExistingNode(method_old);
			if(cgnode_olds == null || cgnode_olds.size() == 0) {//some <init> also do not exist in call graph
				// or this has been excluded ... 
				System.out.println("NOW IT'S TIME TO IMPLEMENT cgnode_olds == null : " + method_old.toString());
				continue; 
			}
			
			//ir does not matter here for ir_old: 
			IR ir_old = cgnode_olds.iterator().next().getIR(); 
			          //ssacache.findOrCreateIR(method_old, Everywhere.EVERYWHERE, ssaoptions);
			IR ir_new = ssacache.createIR(method_new, Everywhere.EVERYWHERE, ssaoptions);
			
			boolean check = false; //avoid repeatively compute ir changes for the same method reference
			for (CGNode cgnode_old : cgnode_olds) {
				summary.computeIRChanges(cgnode_old, method_old, method_new, ir_old, ir_new, furItems, check);
				furItems = summary.getFurItem(cgnode_old);
				check = true;
			}
			
			//TODO: add lambda expr ?? replace field from class_old ?? ShrikeClass
			
		}
		
		if(furItems != null && !furItems.isEmpty()){
//			discoverChangedCGNodes(javaProject, file, furItems, summary);
		}
	}

	/**
	 * previous name: discoverChangedCGNodes()
	 * compute all method changes in a class; only handle if code modification
	 * 
	 * @param summary
	 * @param packagenamediff
	 * @param classnamediff
	 * @param classfile
	 */
	private void computeIRChanges(IRChangedSummary summary, String packagenamediff, String classnamediff) {
		ClassHierarchy cha = (ClassHierarchy) ipabuilder.getClassHierarchy();
		
		// format issue: some heuristics to locate the correct names
		if(packagenamediff.startsWith("src/"))
			packagenamediff = packagenamediff.substring(4); //remove "src/"
		
		if(packagenamediff.contains("org/")) {
			int idx = packagenamediff.indexOf("org/");
			packagenamediff = packagenamediff.substring(idx); // remove until "org/"
		}
		
		ArrayList<AstChangedItem> furItems = new ArrayList<>(); //ƒor special code pattern

		IClass class_old = getIClassFromCHA(cha, packagenamediff, classnamediff);
		IClass class_new = getIClassFromCHA(cha_new, packagenamediff, classnamediff);
		
		if(class_new != null && class_old != null) {
			computeForIClass(summary, class_new, class_old, furItems);
			System.out.println("Checking sub classes ...");
			computeSubIRChanges(summary, cha, packagenamediff, classnamediff, furItems);
		}else if(class_new == null || class_old == null) { //this has been excluded or class only has sub classes ... 
			System.out.println("Null class: L" + packagenamediff + "/" + classnamediff + "... Checking sub classes ...");
			computeSubIRChanges(summary, cha, packagenamediff, classnamediff, furItems);
			return;
		}
	}
	
	private void computeSubIRChanges(IRChangedSummary summary, ClassHierarchy cha, String packagenamediff, String classnamediff,
			ArrayList<AstChangedItem> furItems) {
		HashMap<String, IClass> class_olds = getSubIClassFromCHA(cha, packagenamediff, classnamediff);
		HashMap<String, IClass> class_news = getSubIClassFromCHA(cha_new, packagenamediff, classnamediff);
		if (class_news == null || class_olds == null || class_news.isEmpty() || class_olds.isEmpty()) {
			System.out.println("No sub class for: L" + packagenamediff + "/" + classnamediff);
			return;
		}
		
		for(String name : class_news.keySet()) {
			IClass _class_old = class_olds.get(name);
			IClass _class_new = class_news.get(name);
			if(_class_old == null || _class_new == null) {
				System.out.println("Null sub class: " + name);
				continue;
			}
			
			computeForIClass(summary, _class_new, _class_old, furItems);
		}
	}
	
	private IClass getIClassFromCHA(ClassHierarchy cha, String packagename, String classname) {
		IClassLoader[] loaders = cha.getLoaders();
		String typename = "L" + packagename + "/" + classname;
		TypeReference typeReference = TypeReference.find(typename);
		if(typeReference == null) 
			return null;
		
		for (IClassLoader loader : loaders) {
			IClass iClass = loader.lookupClass(typeReference.getName());
			if(iClass == null) {
				continue;
			}
			return iClass;
		}
		return null;
	}
	
	private HashMap<String, IClass> getSubIClassFromCHA(ClassHierarchy cha, String packagename, String classname) {
		IClassLoader[] loaders = cha.getLoaders();
		String ptypename = "L" + packagename + "/" + classname;
		HashSet<TypeReference> ptypeReferences = TypeReference.findSub(ptypename);
		if(ptypeReferences == null) 
			return null;
		
		HashMap<String, IClass> ret = new HashMap<>();
		for (IClassLoader loader : loaders) {
			for(TypeReference ptypeReference : ptypeReferences) {
				IClass iClass = loader.lookupClass(ptypeReference.getName());
				if(iClass == null) {
					continue;
				}
				ret.put(iClass.toString(), iClass);
			}
		}
		return ret;
	}
	
	
}
