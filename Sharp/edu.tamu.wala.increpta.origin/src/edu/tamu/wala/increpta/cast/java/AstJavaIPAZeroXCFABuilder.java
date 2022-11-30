package edu.tamu.wala.increpta.cast.java;

import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.ContextSelector;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.callgraph.impl.DefaultContextSelector;
import com.ibm.wala.ipa.callgraph.impl.DelegatingContextSelector;
import com.ibm.wala.ipa.callgraph.propagation.SSAContextInterpreter;
import com.ibm.wala.ipa.callgraph.propagation.cfa.ZeroXInstanceKeys;
import com.ibm.wala.ipa.cha.IClassHierarchy;


public class AstJavaIPAZeroXCFABuilder extends AstJavaIPACFABuilder{

	public AstJavaIPAZeroXCFABuilder(IClassHierarchy cha, AnalysisOptions options, IAnalysisCacheView cache,
		      ContextSelector appContextSelector, SSAContextInterpreter appContextInterpreter) {
		super(cha, options, cache);

		SSAContextInterpreter contextInterpreter = makeDefaultContextInterpreters(appContextInterpreter, options, cha);
	    setContextInterpreter(contextInterpreter);

	    ContextSelector def = new DefaultContextSelector(options, cha);
	    ContextSelector contextSelector = appContextSelector == null ? def : new DelegatingContextSelector(appContextSelector, def);
	    setContextSelector(contextSelector);
	}




}
