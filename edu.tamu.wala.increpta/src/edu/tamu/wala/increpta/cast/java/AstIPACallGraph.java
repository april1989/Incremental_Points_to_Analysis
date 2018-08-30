package edu.tamu.wala.increpta.cast.java;

import java.util.ConcurrentModificationException;
import java.util.Set;
import java.util.function.Function;

import com.ibm.wala.cast.ir.cfg.AstInducedCFG;
import com.ibm.wala.cast.ir.ssa.AstLexicalRead;
import com.ibm.wala.cfg.InducedCFG;
import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.callgraph.impl.AbstractRootMethod;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.Iterator2Iterable;

import edu.tamu.wala.increpta.callgraph.impl.IPAExplicitCallGraph;
import edu.tamu.wala.increpta.callgraph.impl.IPAExplicitCallGraph.IPAExplicitNode;

public class AstIPACallGraph extends IPAExplicitCallGraph{

	public AstIPACallGraph(IMethod fakeRootMethod, AnalysisOptions options, IAnalysisCacheView cache) {
		super(fakeRootMethod, options, cache);
	}

	public static class AstFakeRoot extends AbstractRootMethod {

		public AstFakeRoot(MethodReference rootMethod, IClass declaringClass, IClassHierarchy cha, AnalysisOptions options, IAnalysisCacheView cache) {
			super(rootMethod, declaringClass, cha, options, cache);
		}

		public AstFakeRoot(MethodReference rootMethod, IClassHierarchy cha, AnalysisOptions options, IAnalysisCacheView cache) {
			super(rootMethod, cha, options, cache);
		}

		@Override
		public InducedCFG makeControlFlowGraph(SSAInstruction[] statements) {
			return new AstInducedCFG(statements, this, Everywhere.EVERYWHERE);
		}

		public AstLexicalRead addGlobalRead(TypeReference type, String name) {
			AstLexicalRead s = new AstLexicalRead(statements.size(), nextLocal++, null, name, type);
			statements.add(s);
			return s;
		}
	}

	public static abstract class ScriptFakeRoot extends AstFakeRoot {

		public ScriptFakeRoot(MethodReference rootMethod, IClass declaringClass, IClassHierarchy cha, AnalysisOptions options, IAnalysisCacheView cache) {
			super(rootMethod, declaringClass, cha, options, cache);
		}

		public ScriptFakeRoot(MethodReference rootMethod, IClassHierarchy cha, AnalysisOptions options, IAnalysisCacheView cache) {
			super(rootMethod, cha, options, cache);
		}

		public abstract SSAAbstractInvokeInstruction addDirectCall(int functionVn, int[] argVns, CallSiteReference callSite);

		@Override
		public SSANewInstruction addAllocation(TypeReference T) {
			if (cha.isSubclassOf(cha.lookupClass(T), cha.lookupClass(declaringClass.getClassLoader().getLanguage().getRootType()))) {
				int instance = nextLocal++;
				NewSiteReference ref = NewSiteReference.make(statements.size(), T);
				SSANewInstruction result = getDeclaringClass().getClassLoader().getInstructionFactory().NewInstruction(statements.size(), instance, ref);
				statements.add(result);
				return result;
			} else {
				return super.addAllocation(T);
			}
		}

	}

	public class AstIPACGNode extends IPAExplicitNode{

		private Set<Function<Object, Object>> callbacks;

		protected AstIPACGNode(IMethod method, Context C) {
			super(method, C);
		}

		private void fireCallbacks() {
			if (callbacks != null) {
				boolean done = false;
				while (!done) {
					try {
						for (Function<Object, Object> function : callbacks) {
							function.apply(null);
						}
					} catch (ConcurrentModificationException e) {
						done = false;
						continue;
					}
					done = true;
				}
			}
		}

		private boolean hasCallback(Function<Object, Object> callback) {
			return callbacks != null && callbacks.contains(callback);
		}

		private boolean hasAllCallbacks(Set<Function<Object, Object>> callbacks) {
			return callbacks != null && callbacks.containsAll(callbacks);
		}

		public void addCallback(Function<Object, Object> callback) {
			if (!hasCallback(callback)) {
				if (callbacks == null) {
					callbacks = HashSetFactory.make(1);
				}

				callbacks.add(callback);

				for (CGNode p : Iterator2Iterable.make(getCallGraph().getPredNodes(this))) {
					((AstIPACGNode) p).addCallback(callback);
				}
			}
		}

		public void addAllCallbacks(Set<Function<Object, Object>> callback) {
			if (!hasAllCallbacks(callbacks)) {
				if (callbacks == null) {
					callbacks = HashSetFactory.make(1);
				}

				callbacks.addAll(callback);

				for (CGNode p : Iterator2Iterable.make(getCallGraph().getPredNodes(this))) {
					((AstIPACGNode) p).addAllCallbacks(callback);
				}
			}
		}

		/**
		 * for incremental
		 * @param callback
		 */
		private void delAllCallbacks(Set<Function<Object, Object>> callback) {
			if (!hasAllCallbacks(callbacks)) {
				if(callbacks != null){
					callbacks.removeAll(callback);
					for (CGNode p : Iterator2Iterable.make(getCallGraph().getPredNodes(this))) {
						((AstIPACGNode) p).delAllCallbacks(callback);
					}
				}
			}
		}

		public void clearMutatedCache(CallSiteReference cs) {
			targets.remove(cs.getProgramCounter());
		}

		@Override
		public boolean addTarget(CallSiteReference site, CGNode node) {
			if (super.addTarget(site, node)) {
				if (((AstIPACGNode) node).callbacks != null) {
					((AstIPACGNode) node).fireCallbacks();
					addAllCallbacks(((AstIPACGNode) node).callbacks);
				}
				return true;
			} else {
				return false;
			}
		}

		@Override
		public boolean delTarget(CallSiteReference callSite, CGNode node) {
			if(super.delTarget(callSite, node)){
				if(((AstIPACGNode) node).callbacks != null){
					delAllCallbacks(((AstIPACGNode) node).callbacks);
				}
				return true;
			}else{
				return false;
			}
		}

	}


	@Override
	protected IPAExplicitNode makeNode(IMethod method, Context context) {
		return new AstIPACGNode(method, context);
	}
}