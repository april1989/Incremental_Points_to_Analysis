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
		      ContextSelector appContextSelector, SSAContextInterpreter appContextInterpreter, int instancePolicy) {
		super(cha, options, cache);

		SSAContextInterpreter contextInterpreter = makeDefaultContextInterpreters(appContextInterpreter, options, cha);
	    setContextInterpreter(contextInterpreter);

	    ContextSelector def = new DefaultContextSelector(options, cha);
	    ContextSelector contextSelector = appContextSelector == null ? def : new DelegatingContextSelector(appContextSelector, def);

	    setContextSelector(contextSelector);

	    setInstanceKeys(new IPAJavaScopeMappingInstanceKeys(this, new ZeroXInstanceKeys(options, cha, contextInterpreter,
	        instancePolicy)));
	}

	public static AstJavaIPACFABuilder make(AnalysisOptions options, IAnalysisCacheView cache, IClassHierarchy cha, ClassLoader cl,
			AnalysisScope scope, String[] xmlFiles, byte instancePolicy) {

		com.ibm.wala.ipa.callgraph.impl.Util.addDefaultSelectors(options, cha);
		for (String xmlFile : xmlFiles) {
			com.ibm.wala.ipa.callgraph.impl.Util.addBypassLogic(options, scope, cl, xmlFile, cha);
		}

		return new AstJavaIPAZeroXCFABuilder(cha, options, cache, null, null, instancePolicy);
	}





}
