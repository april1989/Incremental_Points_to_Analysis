package edu.tamu.wala.increpta.cast.java;

import com.ibm.wala.analysis.reflection.ReflectionContextInterpreter;
import com.ibm.wala.cast.ipa.callgraph.AstContextInsensitiveSSAContextInterpreter;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.ContextSelector;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.callgraph.impl.DefaultContextSelector;
import com.ibm.wala.ipa.callgraph.impl.DelegatingContextSelector;
import com.ibm.wala.ipa.callgraph.propagation.SSAContextInterpreter;
import com.ibm.wala.ipa.callgraph.propagation.cfa.DefaultSSAInterpreter;
import com.ibm.wala.ipa.callgraph.propagation.cfa.DelegatingSSAContextInterpreter;
import com.ibm.wala.ipa.callgraph.propagation.cfa.nCFAContextSelector;
import com.ibm.wala.ipa.cha.IClassHierarchy;

import edu.tamu.wala.increpta.pointerkey.IPADefaultPointerKeyFactory;



public class AstJavaIPAkCFABuilder extends AstJavaIPASSAPropagationCallGraphBuilder{
	
	public AstJavaIPAkCFABuilder(int k, IClassHierarchy cha, AnalysisOptions options, IAnalysisCacheView cache,
		      ContextSelector appContextSelector, SSAContextInterpreter appContextInterpreter) {
		super(Language.JAVA.getFakeRootMethod(cha, options, cache), options, cache, new IPADefaultPointerKeyFactory());
		
	    ContextSelector def = new DefaultContextSelector(options, cha);
	    ContextSelector contextSelector = appContextSelector == null ? def : new DelegatingContextSelector(appContextSelector, def);
	    contextSelector = new nCFAContextSelector(k, contextSelector);
	    setContextSelector(contextSelector);
	    
	    SSAContextInterpreter defI = new DefaultSSAInterpreter(options, cache);
		defI = new DelegatingSSAContextInterpreter(new AstContextInsensitiveSSAContextInterpreter(options, cache), defI);
	    defI = new DelegatingSSAContextInterpreter(ReflectionContextInterpreter.createReflectionContextInterpreter(cha, options, getAnalysisCache()), defI);
	    SSAContextInterpreter contextInterpreter = appContextInterpreter == null ? defI : new DelegatingSSAContextInterpreter(appContextInterpreter, defI);
	    setContextInterpreter(contextInterpreter);

	}
	
}
