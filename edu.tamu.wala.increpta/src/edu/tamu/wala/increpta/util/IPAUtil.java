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

import com.ibm.wala.classLoader.JavaLanguage;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CallGraphBuilder;
import com.ibm.wala.ipa.callgraph.ContextSelector;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.SSAContextInterpreter;
import com.ibm.wala.ipa.callgraph.propagation.cfa.ZeroXInstanceKeys;
import com.ibm.wala.ipa.cha.IClassHierarchy;

import edu.tamu.wala.increpta.cast.java.AstJavaIPAZeroXCFABuilder;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPASSAPropagationCallGraphBuilder;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAZeroXCFABuilder;

public abstract class IPAUtil extends Util{

	/**
	 * @param options options that govern call graph construction
	 * @param cha governing class hierarchy
	 * @param scope representation of the analysis scope
	 * @return a 0-CFA Call Graph Builder.
	 */
	public static IPASSAPropagationCallGraphBuilder makeIPAZeroCFABuilder(Language l, AnalysisOptions options, IAnalysisCacheView cache,
			IClassHierarchy cha, AnalysisScope scope) {
		return makeIPAZeroCFABuilder(l, options, cache, cha, scope, null, null);
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
	public static IPASSAPropagationCallGraphBuilder makeIPAZeroCFABuilder(Language l, AnalysisOptions options, IAnalysisCacheView cache,
			IClassHierarchy cha, AnalysisScope scope, ContextSelector customSelector, SSAContextInterpreter customInterpreter) {

		if (options == null) {
			throw new IllegalArgumentException("options is null");
		}
		addDefaultSelectors(options, cha);
		addDefaultBypassLogic(options, scope, Util.class.getClassLoader(), cha);

		return IPAZeroXCFABuilder.make(l, cha, options, cache, customSelector, customInterpreter, ZeroXInstanceKeys.NONE);
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
	public static CallGraphBuilder makeIPAAstZeroCFABuilder(JavaLanguage java, AnalysisOptions options,
			IAnalysisCacheView cache, IClassHierarchy cha, AnalysisScope scope) {
		if (options == null) {
			throw new IllegalArgumentException("options is null");
		}
		addDefaultSelectors(options, cha);
		addDefaultBypassLogic(options, scope, Util.class.getClassLoader(), cha);
	    return new AstJavaIPAZeroXCFABuilder(cha, options, cache, null, null, ZeroXInstanceKeys.ALLOCATIONS  | ZeroXInstanceKeys.SMUSH_MANY | ZeroXInstanceKeys.SMUSH_THROWABLES);//JEFF ZeroXInstanceKeys.ALLOCATIONS ZeroXInstanceKeys.NONE
	}

}
