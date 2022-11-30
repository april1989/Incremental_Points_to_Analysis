package edu.tamu.wala.increpta.ipa.callgraph.propagation;

import com.ibm.wala.analysis.reflection.ReflectionContextInterpreter;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.ContextSelector;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.callgraph.impl.AbstractRootMethod;
import com.ibm.wala.ipa.callgraph.impl.DefaultContextSelector;
import com.ibm.wala.ipa.callgraph.impl.DelegatingContextSelector;
import com.ibm.wala.ipa.callgraph.propagation.SSAContextInterpreter;
import com.ibm.wala.ipa.callgraph.propagation.cfa.DefaultSSAInterpreter;
import com.ibm.wala.ipa.callgraph.propagation.cfa.DelegatingSSAContextInterpreter;
import com.ibm.wala.ipa.callgraph.propagation.cfa.nCFAContextSelector;

import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPASSAPropagationCallGraphBuilder;
import edu.tamu.wala.increpta.pointerkey.IPADefaultPointerKeyFactory;
import edu.tamu.wala.increpta.pointerkey.IPAPointerKeyFactory;

public class IPAkCFABuilder extends IPASSAPropagationCallGraphBuilder {

	public IPAkCFABuilder(int n, AbstractRootMethod fakeRootMethod, AnalysisOptions options, IAnalysisCacheView cache,
			ContextSelector appContextSelector, SSAContextInterpreter appContextInterpreter) {
	    super(fakeRootMethod, options, cache, (IPAPointerKeyFactory) new IPADefaultPointerKeyFactory());
	    if (options == null) {
	      throw new IllegalArgumentException("options is null");
	    }

	    ContextSelector def = new DefaultContextSelector(options, cha);
	    ContextSelector contextSelector = appContextSelector == null ? def : new DelegatingContextSelector(appContextSelector, def);
	    contextSelector = new nCFAContextSelector(n, contextSelector);
	    setContextSelector(contextSelector);

	    SSAContextInterpreter defI = new DefaultSSAInterpreter(options, cache);
	    defI = new DelegatingSSAContextInterpreter(ReflectionContextInterpreter.createReflectionContextInterpreter(cha, options, getAnalysisCache()), defI);
	    SSAContextInterpreter contextInterpreter = appContextInterpreter == null ? defI : new DelegatingSSAContextInterpreter(appContextInterpreter, defI);
	    setContextInterpreter(contextInterpreter);
	}

}
