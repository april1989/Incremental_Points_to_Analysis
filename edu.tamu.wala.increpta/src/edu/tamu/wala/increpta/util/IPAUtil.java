package edu.tamu.wala.increpta.util;

import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.ContextSelector;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.callgraph.propagation.SSAContextInterpreter;
import com.ibm.wala.ipa.callgraph.propagation.cfa.ZeroXInstanceKeys;
import com.ibm.wala.ipa.cha.IClassHierarchy;

import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPASSAPropagationCallGraphBuilder;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAZeroXCFABuilder;

import com.ibm.wala.ipa.callgraph.impl.Util;

public class IPAUtil extends Util{

	/**
	 * @param options options that govern call graph construction
	 * @param cha governing class hierarchy
	 * @param scope representation of the analysis scope
	 * @return a 0-CFA Call Graph Builder.
	 */
	public static IPASSAPropagationCallGraphBuilder makeIPAZeroCFABuilder(AnalysisOptions options, IAnalysisCacheView cache,
			IClassHierarchy cha, AnalysisScope scope) {
		return makeIPAZeroCFABuilder(options, cache, cha, scope, null, null);
	}

	/**
	 * @param options options that govern call graph construction
	 * @param cha governing class hierarchy
	 * @param scope representation of the analysis scope
	 * @param customSelector user-defined context selector, or null if none
	 * @param customInterpreter user-defined context interpreter, or null if none
	 * @return a 0-CFA Call Graph Builder.
	 * @throws IllegalArgumentException if options is null
	 */
	public static IPASSAPropagationCallGraphBuilder makeIPAZeroCFABuilder(AnalysisOptions options, IAnalysisCacheView cache,
			IClassHierarchy cha, AnalysisScope scope, ContextSelector customSelector, SSAContextInterpreter customInterpreter) {

		if (options == null) {
			throw new IllegalArgumentException("options is null");
		}
		addDefaultSelectors(options, cha);
		addDefaultBypassLogic(options, scope, Util.class.getClassLoader(), cha);

		return IPAZeroXCFABuilder.make(cha, options, cache, customSelector, customInterpreter, ZeroXInstanceKeys.NONE);
	}

}
