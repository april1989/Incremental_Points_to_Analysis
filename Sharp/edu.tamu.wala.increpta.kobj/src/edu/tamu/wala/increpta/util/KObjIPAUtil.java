package edu.tamu.wala.increpta.util;

import com.ibm.wala.classLoader.JavaLanguage;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.ContextSelector;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.SSAContextInterpreter;
import com.ibm.wala.ipa.callgraph.propagation.cfa.ZeroXInstanceKeys;
import com.ibm.wala.ipa.cha.IClassHierarchy;

import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPASSAPropagationCallGraphBuilder;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.bridge.AbstractKIPASSAPropagationCallGraphBuilder;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.objsens.KObjIPABuilder;

public class KObjIPAUtil extends IPAUtil{

	public static AbstractKIPASSAPropagationCallGraphBuilder makeKObjBuilder(JavaLanguage java, int n, AnalysisOptions options, IAnalysisCacheView cache,
			IClassHierarchy cha, AnalysisScope scope) {
		if (options == null) {
			throw new IllegalArgumentException("options is null");
		}
		addDefaultSelectors(options, cha);
		addDefaultBypassLogic(options, scope, KObjIPAUtil.class.getClassLoader(), cha);
		ContextSelector appSelector = null;
		SSAContextInterpreter appInterpreter = null;
		AbstractKIPASSAPropagationCallGraphBuilder result = new KObjIPABuilder(n, Language.JAVA.getFakeRootMethod(cha, options, cache), options, cache, appSelector, appInterpreter);

		// nCFABuilder uses type-based heap abstraction by default, but we want allocation sites with
		//not optimized: each allocation is handled separately.
		result.setInstanceKeys(new ZeroXInstanceKeys(options, cha, result.getContextInterpreter(), ZeroXInstanceKeys.ALLOCATIONS | ZeroXInstanceKeys.CONSTANT_SPECIFIC 
				| ZeroXInstanceKeys.SMUSH_THROWABLES));

		//optimized:
		//SMUSH_STRINGS: individual String or StringBuffer allocation sites are not disambiguated. A single InstanceKey represents all String objects, and a single InstanceKey represents all StringBuffer objects.
		//SMUSH_THROWABLES: individual exception objects are disambiguated by type, and not by allocation site.
		//SMUSH_PRIMITIVE_HOLDERS: if a class has no reference fields, then all objects of that type are represented by a single InstanceKey
		//SMUSH_MANY: if there are more than k (current k=25) allocation sites of a particular type in a single method, then all these sites are represented by a single InstanceKey. For example, a library class initializer method might allocate 10,000 Font objects; this optimization will not disambiguate the 10,000 separate allocation sites.
		//		    result.setInstanceKeys(new ZeroXInstanceKeys(options, cha, result.getContextInterpreter(), ZeroXInstanceKeys.ALLOCATIONS
		//		        | ZeroXInstanceKeys.SMUSH_MANY | ZeroXInstanceKeys.SMUSH_PRIMITIVE_HOLDERS | ZeroXInstanceKeys.SMUSH_STRINGS
		//		        | ZeroXInstanceKeys.SMUSH_THROWABLES));
		return result;
	}

//	public static IPASSAPropagationCallGraphBuilder makekTypesensIPABuilder(JavaLanguage java, int n, AnalysisOptions options, IAnalysisCacheView cache,
//			IClassHierarchy cha, AnalysisScope scope) {
//		if (options == null) {
//			throw new IllegalArgumentException("options is null");
//		}
//		addDefaultSelectors(options, cha);
//		addDefaultBypassLogic(options, scope, Util.class.getClassLoader(), cha);
//		ContextSelector appSelector = null;
//		SSAContextInterpreter appInterpreter = null;
//		IPASSAPropagationCallGraphBuilder result = new kTypesensIPABuilder(n, Language.JAVA.getFakeRootMethod(cha, options, cache), options, cache, appSelector, appInterpreter);
//
//		// nCFABuilder uses type-based heap abstraction by default, but we want allocation sites with
//		//not optimized: each allocation is handled separately.
//		result.setInstanceKeys(new ZeroXInstanceKeys(options, cha, result.getContextInterpreter(), ZeroXInstanceKeys.ALLOCATIONS
//				| ZeroXInstanceKeys.SMUSH_MANY));
//
//		//optimized:
//		//SMUSH_STRINGS: individual String or StringBuffer allocation sites are not disambiguated. A single InstanceKey represents all String objects, and a single InstanceKey represents all StringBuffer objects.
//		//SMUSH_THROWABLES: individual exception objects are disambiguated by type, and not by allocation site.
//		//SMUSH_PRIMITIVE_HOLDERS: if a class has no reference fields, then all objects of that type are represented by a single InstanceKey
//		//SMUSH_MANY: if there are more than k (current k=25) allocation sites of a particular type in a single method, then all these sites are represented by a single InstanceKey. For example, a library class initializer method might allocate 10,000 Font objects; this optimization will not disambiguate the 10,000 separate allocation sites.
//		//		    result.setInstanceKeys(new ZeroXInstanceKeys(options, cha, result.getContextInterpreter(), ZeroXInstanceKeys.ALLOCATIONS
//		//		        | ZeroXInstanceKeys.SMUSH_MANY | ZeroXInstanceKeys.SMUSH_PRIMITIVE_HOLDERS | ZeroXInstanceKeys.SMUSH_STRINGS
//		//		        | ZeroXInstanceKeys.SMUSH_THROWABLES));
//		return result;
//	}


}
