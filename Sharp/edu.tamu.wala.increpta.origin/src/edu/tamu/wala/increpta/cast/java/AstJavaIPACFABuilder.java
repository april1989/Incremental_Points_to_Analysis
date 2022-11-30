package edu.tamu.wala.increpta.cast.java;

import com.ibm.wala.classLoader.Language;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.cha.IClassHierarchy;

import edu.tamu.wala.increpta.pointerkey.IPADefaultPointerKeyFactory;

public class AstJavaIPACFABuilder extends AstJavaIPASSAPropagationCallGraphBuilder{

	public AstJavaIPACFABuilder(IClassHierarchy cha, AnalysisOptions options, IAnalysisCacheView cache) {
		super(Language.JAVA.getFakeRootMethod(cha, options, cache), options, cache, new IPADefaultPointerKeyFactory());
	}

}
