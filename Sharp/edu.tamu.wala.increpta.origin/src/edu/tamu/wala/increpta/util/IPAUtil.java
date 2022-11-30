/*******************************************************************************
 * Copyright (C) 2017 Bozhen Liu, Jeff Huang.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Bozhen Liu, Jeff Huang - initial API and implementation
 ******************************************************************************/
package edu.tamu.wala.increpta.util;

import java.util.Iterator;
import java.util.Set;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.CallGraphBuilder;
import com.ibm.wala.ipa.callgraph.ContextSelector;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.callgraph.impl.DefaultEntrypoint;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.AbstractPointerAnalysis;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.callgraph.propagation.SSAContextInterpreter;
import com.ibm.wala.ipa.callgraph.propagation.cfa.ZeroXInstanceKeys;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.collections.HashSetFactory;

import edu.tamu.wala.increpta.callgraph.impl.IPABasicCallGraph;
import edu.tamu.wala.increpta.cast.java.AstJavaIPAZeroXCFABuilder;
import edu.tamu.wala.increpta.cast.java.AstJavaIPAkCFABuilder;
import edu.tamu.wala.increpta.cast.java.IPAJavaScopeMappingInstanceKeys;
import edu.tamu.wala.increpta.instancekey.IPAZeroXInstanceKeys;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPointerAnalysisImpl;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationCallGraphBuilder;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPASSAPropagationCallGraphBuilder;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAZeroXCFABuilder;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAkCFABuilder;

public abstract class IPAUtil extends Util{
	
	
	//debug settings
	public static boolean PRINT_CHA = false;
	public static boolean PRINT_CG = false;
	public static boolean PRINT_PAG = false;
	public static boolean PRINT_CLASSES = false;
	
	/**
	 * a set of configs for instance policy
	 */
	private static boolean SEPARATE_CLASS_BASED_INSTANCE = true;//for testing 
	
	//SMUSH_STRINGS: individual String or StringBuffer allocation sites are not disambiguated. A single InstanceKey represents all String objects, and a single InstanceKey represents all StringBuffer objects.
	//SMUSH_THROWABLES: individual exception objects are disambiguated by type, and not by allocation site.
	//SMUSH_PRIMITIVE_HOLDERS: if a class has no reference fields, then all objects of that type are represented by a single InstanceKey
	//SMUSH_MANY: if there are more than k (current k=25) allocation sites of a particular type in a single method, then all these sites are represented by a single InstanceKey. For example, a library class initializer method might allocate 10,000 Font objects; this optimization will not disambiguate the 10,000 separate allocation sites.
	
	private static int INSTANCE_POLICY_1 = 
//			ZeroXInstanceKeys.ALLOCATIONS  | ZeroXInstanceKeys.SMUSH_MANY;
			ZeroXInstanceKeys.ALLOCATIONS  | ZeroXInstanceKeys.SMUSH_MANY | ZeroXInstanceKeys.SMUSH_THROWABLES;
	
	private static int INSTANCE_POLICY_2 = 
//			IPAZeroXInstanceKeys.ALLOCATIONS  | IPAZeroXInstanceKeys.CONSTANT_SPECIFIC | IPAZeroXInstanceKeys.SMUSH_THROWABLES;
//			IPAZeroXInstanceKeys.ALLOCATIONS  | IPAZeroXInstanceKeys.CONSTANT_SPECIFIC | IPAZeroXInstanceKeys.SMUSH_MANY | IPAZeroXInstanceKeys.SMUSH_THROWABLES;
			IPAZeroXInstanceKeys.ALLOCATIONS | IPAZeroXInstanceKeys.SMUSH_MANY | IPAZeroXInstanceKeys.SMUSH_THROWABLES;
//			IPAZeroXInstanceKeys.ALLOCATIONS | IPAZeroXInstanceKeys.SMUSH_MANY;

	//JEFF's code: ZeroXInstanceKeys.ALLOCATIONS ZeroXInstanceKeys.NONE
	
	
	public static String DEFAULT_EXCLUSION_PACKAGES = "java\\/awt\\/.*\n" +  //from Java60RegressionExclusions.txt
			"javax\\/swing\\/.*\n" +  
			"sun\\/awt\\/.*\n" + 
			"sun\\/swing\\/.*\n" + 
			"com\\/sun\\/.*\n" + 
			"sun\\/.*" +  
//			"java\\/applet\\/.*\n" +  //from mine 
//			"java\\/awt\\/.*\n" + 
//			"java\\/beans\\/.*\n" + 
//			"java\\/io\\/.*\n" + 
//			"java\\/math\\/.*\n" + 
//			"java\\/net\\/.*\n" + 
//			"java\\/nio\\/.*\n" + 
//			"java\\/rmi\\/.*\n" + 
//			"java\\/security\\/.*\n" + 
//			"java\\/sql\\/.*\n" + 
//			"java\\/text\\/.*\n" + 
//			"javax\\/.*\n" + 
//			"sun\\/.*\n" + 
//			"sunw\\/.*\n" + 
//			"com\\/sun\\/.*\n" + 
//			"com\\/ibm\\/.*\n" + 
//			"com\\/apple\\/.*\n" + 
//			"com\\/oracle\\/.*\n" + 
//			"apple\\/.*\n" + 
//			"org\\/xml\\/.*\n" + 
//			"jdbm\\/.*\n" + 
			"";
	
	public static String EMPTY_EXCLUSION_PACKAGES = "";
	
	
	/**
	 * @param options options that govern call graph construction
	 * @param cha governing class hierarchy
	 * @param scope representation of the analysis scope
	 * @return a 0-1-CFA Call Graph Builder.
	 */
	public static IPASSAPropagationCallGraphBuilder makeIPAZeroOneCFABuilder(Language l, AnalysisOptions options, IAnalysisCacheView cache,
			IClassHierarchy cha, AnalysisScope scope) {
		return makeIPAZeroOneCFABuilder(l, options, cache, cha, scope, null, null);
	}

	/**
	 * @param options options that govern call graph construction
	 * @param cha governing class hierarchy
	 * @param scope representation of the analysis scope
	 * @param customSelector user-defined context selector, or null if none
	 * @param customInterpreter user-defined context interpreter, or null if none
	 * @return a 0-1-CFA Call Graph Builder.
	 * @throws IllegalArgumentException if options is null
	 */
	public static IPASSAPropagationCallGraphBuilder makeIPAZeroOneCFABuilder(Language l, AnalysisOptions options, IAnalysisCacheView cache,
			IClassHierarchy cha, AnalysisScope scope, ContextSelector customSelector, SSAContextInterpreter customInterpreter) {

		if (options == null) {
			throw new IllegalArgumentException("options is null");
		}
		addDefaultSelectors(options, cha);
		addDefaultBypassLogic(options, scope, IPAUtil.class.getClassLoader(), cha);

		//original
//		return IPAZeroXCFABuilder.make(l, cha, options, cache, customSelector, customInterpreter, ZeroXInstanceKeys.ALLOCATIONS | ZeroXInstanceKeys.SMUSH_MANY | ZeroXInstanceKeys.SMUSH_PRIMITIVE_HOLDERS
//		        | ZeroXInstanceKeys.SMUSH_STRINGS | ZeroXInstanceKeys.SMUSH_THROWABLES);
		
		//separate constant value
		return IPAZeroXCFABuilder.make(l, cha, options, cache, customSelector, customInterpreter, 
				ZeroXInstanceKeys.ALLOCATIONS | ZeroXInstanceKeys.CONSTANT_SPECIFIC 
				| ZeroXInstanceKeys.SMUSH_THROWABLES);
	}
	
	
	/**
	 * make a {@link CallGraphBuilder} that uses call-site context sensitivity,
	 * with call-string length limited to n, and a context-sensitive
	 * allocation-site-based heap abstraction.
	 * @param java
	 */
	public static IPASSAPropagationCallGraphBuilder makeIPAkCFABuilder(int n, AnalysisOptions options, IAnalysisCacheView cache,
			IClassHierarchy cha, AnalysisScope scope) {
		if (options == null) {
			throw new IllegalArgumentException("options is null");
		}
		addDefaultSelectors(options, cha);
		addDefaultBypassLogic(options, scope, IPAUtil.class.getClassLoader(), cha);
		ContextSelector appSelector = null;
		SSAContextInterpreter appInterpreter = null;
		IPASSAPropagationCallGraphBuilder result = new IPAkCFABuilder(n, Language.JAVA.getFakeRootMethod(cha, options, cache), options, cache, appSelector, appInterpreter);

		// nCFABuilder uses type-based heap abstraction by default, but we want allocation sites with
		//not optimized: each allocation is handled separately.
		result.setInstanceKeys(new ZeroXInstanceKeys(options, cha, result.getContextInterpreter(), INSTANCE_POLICY_1));

		return result;
	}

	/**
	 * for plugin
	 * @param java
	 * @param options
	 * @param cache
	 * @param cha
	 * @param scope
	 * @return
	 */
	public static IPASSAPropagationCallGraphBuilder makeAstIPAZeroCFABuilder(AnalysisOptions options,
			IAnalysisCacheView cache, IClassHierarchy cha, AnalysisScope scope) {
		if (options == null) {
			throw new IllegalArgumentException("options is null");
		}
		addDefaultSelectors(options, cha);
		addDefaultBypassLogic(options, scope, IPAUtil.class.getClassLoader(), cha);
		
		IPASSAPropagationCallGraphBuilder result = new AstJavaIPAZeroXCFABuilder(cha, options, cache, null, null);
	    
		if(SEPARATE_CLASS_BASED_INSTANCE)
			result.setInstanceKeys(new IPAJavaScopeMappingInstanceKeys(result, new IPAZeroXInstanceKeys(options, cha, result.getContextInterpreter(),
			        INSTANCE_POLICY_2)));
		else
			result.setInstanceKeys(new IPAJavaScopeMappingInstanceKeys(result, new ZeroXInstanceKeys(options, cha, result.getContextInterpreter(),
			        INSTANCE_POLICY_1)));
		return result;
	}
	
	
	/**
	 * for plugin
	 * @param k
	 * @param options
	 * @param cache
	 * @param cha
	 * @param scope
	 * @return
	 */
	public static IPASSAPropagationCallGraphBuilder makeAstIPAkCFABuilder(int k, AnalysisOptions options,
			IAnalysisCacheView cache, IClassHierarchy cha, AnalysisScope scope) {
		if (options == null) {
			throw new IllegalArgumentException("options is null");
		}
		addDefaultSelectors(options, cha);
		addDefaultBypassLogic(options, scope, IPAUtil.class.getClassLoader(), cha);
		
		IPASSAPropagationCallGraphBuilder result = new AstJavaIPAkCFABuilder(k, cha, options, cache, null, null);
	    
		if(SEPARATE_CLASS_BASED_INSTANCE)
			result.setInstanceKeys(new IPAJavaScopeMappingInstanceKeys(result, new IPAZeroXInstanceKeys(options, cha, result.getContextInterpreter(),
			        INSTANCE_POLICY_2)));
		else
			result.setInstanceKeys(new IPAJavaScopeMappingInstanceKeys(result, new ZeroXInstanceKeys(options, cha, result.getContextInterpreter(),
			        INSTANCE_POLICY_1)));
		return result;
	}
	

	public static IPAPropagationCallGraphBuilder makeAstIPAZeroCFABuilder(AnalysisOptions options, IAnalysisCacheView cache,
			IClassHierarchy cha, AnalysisScope scope, boolean separate) {
		SEPARATE_CLASS_BASED_INSTANCE = separate;
		return makeAstIPAZeroCFABuilder(options, cache, cha, scope);
	}

	public static IPAPropagationCallGraphBuilder makeAstIPAkCFABuilder(int i, AnalysisOptions options, IAnalysisCacheView cache,
			IClassHierarchy cha, AnalysisScope scope, boolean separate) {
		SEPARATE_CLASS_BASED_INSTANCE = separate;
		return makeAstIPAkCFABuilder(i, options, cache, cha, scope);
	}
	

	public static Iterable<Entrypoint> findEntryPoints(IClassHierarchy classHierarchy, String mainClassName, boolean includeAll) {
		if(PRINT_CLASSES)
			System.out.println("ANALYZED CLASSES");
		
		final Set<Entrypoint> result = HashSetFactory.make();
		Iterator<IClass> classIterator = classHierarchy.iterator();
		while (classIterator.hasNext()) {
			IClass klass = classIterator.next();
			if(PRINT_CLASSES)
				System.out.println("... " + klass.toString());
			
			if (JavaUtil.isJDKClass(klass)) {
				continue;
			}
			//special code for tradebeans/tradesoap  :  && klass.toString().contains("Lorg/apache/geronimo/main/Bootstrapper")
			//org.apache.geronimo.main.Bootstrapper.execute  
			for (IMethod method : klass.getDeclaredMethods()) {
				try {
					if(PRINT_CLASSES)
						System.err.println("... " + method.toString());

					if(method.isStatic() 
							&& method.isPublic()
							&& method.getName().toString().contains("main") //  execute
							&& method.getDescriptor().toString().equals("([Ljava/lang/String;)V")
							){
						if(includeAll || klass.getName().toString().contains(mainClassName)) {
							result.add(new DefaultEntrypoint(method, classHierarchy));
						}
					} 
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
		}

		//show entry points
		for(Entrypoint entry:result){
			System.out.println("ENTRY : " + entry.getMethod().getSignature());
		}

		return new Iterable<Entrypoint>() {
			public Iterator<Entrypoint> iterator() {
				return result.iterator();
			}
		};
	}

	public static void printData(ClassHierarchy cha, CallGraph cg, AbstractPointerAnalysis pta) {
		boolean printed = false; // anything has been printed out from this method
		
		if(cha != null || (PRINT_PAG && pta != null) || (PRINT_CG && cg != null)) {
			System.out.println("statistics *****************************************************");
		}
		
		//ptg statistics
		if(cha != null) {
			int numofCGNodes = cg.getNumberOfNodes();
			int totalInstanceKey = pta.getInstanceKeys().size();
			int totalPointerKey = 0;
			int totalPointerEdge = 0;
			int totalClass = cha.getNumberOfClasses();
			Iterator<PointerKey> iter = pta.getPointerKeys().iterator();
			while(iter.hasNext()){
				PointerKey key = iter.next();
				totalPointerKey++;
				int size = pta.getPointsToSet(key).size();
				totalPointerEdge+=size;
			}

			System.out.println("Total Pointer Keys: "+totalPointerKey);
			System.out.println("Total Instance Keys: "+totalInstanceKey);
			System.out.println("Total Pointer Edges: "+totalPointerEdge);
			System.out.println("Total Classes: "+totalClass);
			System.out.println("Total Methods: "+numofCGNodes);
			System.out.println("Total Methods (without context): " + (cg instanceof IPABasicCallGraph ? ((IPABasicCallGraph) cg).getMr2Nodes().keySet().size() : "not available"));
			
			if(PRINT_CHA) {
				System.out.println("cha *******************************************************");
				for(TypeReference typ : cha.getMap().keySet()) {
					if(typ.isPrimitiveType()) 
						continue;
					
					System.out.println(typ.toString());
				}
			}
			
			printed = true;
		}
		
		if(PRINT_PAG && pta != null) {
			if(printed) 
				System.out.println();
			System.out.println("pta *******************************************************");
			((IPAPointerAnalysisImpl) pta).print();
			printed = true;
		}
		if(PRINT_CG && cg != null) {
			if(printed) 
				System.out.println();
			System.out.println("cg *******************************************************");
			System.out.println(cg.toString());
			printed = true;
		}
		
		if(printed)
			System.out.println("done *******************************************************");
	}


	

}
