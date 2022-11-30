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
package edu.tamu.wala.increpta.callgraph.impl;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.callgraph.impl.FakeWorldClinitMethod;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.SSAContextInterpreter;
import com.ibm.wala.ipa.callgraph.propagation.cfa.CallString;
import com.ibm.wala.ipa.callgraph.propagation.cfa.CallStringContext;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.shrikeBT.BytecodeConstants;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.collections.EmptyIterator;
import com.ibm.wala.util.collections.FilterIterator;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.IntMapIterator;
import com.ibm.wala.util.collections.SparseVector;
import com.ibm.wala.util.debug.Assertions;
import com.ibm.wala.util.debug.UnimplementedError;
import com.ibm.wala.util.graph.NumberedEdgeManager;
import com.ibm.wala.util.intset.BasicNaturalRelation;
import com.ibm.wala.util.intset.IBinaryNaturalRelation;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetAction;
import com.ibm.wala.util.intset.MutableIntSet;
//import com.ibm.wala.util.intset.MutableSharedBitVectorIntSet;
import com.ibm.wala.util.intset.SparseIntSet;

import edu.tamu.wala.increpta.instancekey.ThreadNormalAllocationInNode;
import edu.tamu.wala.increpta.util.intset.IPAMutableSharedBitVectorIntSet;

public class IPAExplicitCallGraph extends IPABasicCallGraph<SSAContextInterpreter> implements BytecodeConstants{

	protected final IClassHierarchy cha;

	protected final AnalysisOptions options;

	private final IAnalysisCacheView cache;

	private final long maxNumberOfNodes;

	private final IMethod fakeRootMethod;

	/**
	 * special object to track call graph edges
	 */
	private final ExplicitEdgeManager edgeManager = makeEdgeManger();

	public IPAExplicitCallGraph(IMethod fakeRootMethod, AnalysisOptions options, IAnalysisCacheView cache) {
		super();
		if (options == null) {
			throw new IllegalArgumentException("null options");
		}
		if (cache == null) {
			throw new IllegalArgumentException("null cache");
		}
		this.cha = fakeRootMethod.getClassHierarchy();
		this.options = options;
		this.cache = cache;
		this.maxNumberOfNodes = options.getMaxNumberOfNodes();
		this.fakeRootMethod = fakeRootMethod;
	}

	/**
	 * subclasses may wish to override!
	 */
	protected IPAExplicitNode makeNode(IMethod method, Context context) {
		return new IPAExplicitNode(method, context);
	}

	/**
	 * subclasses may wish to override!
	 *
	 * @throws CancelException
	 */
	@Override
	protected CGNode makeFakeRootNode() throws CancelException {
		return findOrCreateNode(fakeRootMethod, Everywhere.EVERYWHERE);
	}

	/**
	 * subclasses may wish to override!
	 *
	 * @throws CancelException
	 */
	@Override
	protected CGNode makeFakeWorldClinitNode() throws CancelException {
		return findOrCreateNode(new FakeWorldClinitMethod(fakeRootMethod.getDeclaringClass(), options, cache), Everywhere.EVERYWHERE);
	}


	/**
	 * bz: added
	 */
	@Override
	public void removeNodeAndEdges(CGNode node) {
		//first remove incoming edges; outgoing have already been removed when deleting stmts
		edgeManager.removeIncomingEdges(node);
		//then
		getNodeManager().removeNode(node);
	}

	@Override
	public CGNode findOrCreateNode(IMethod method, Context context) throws CancelException {
		if (method == null) {
			throw new IllegalArgumentException("null method");
		}
		if (context == null) {
			throw new IllegalArgumentException("null context");
		}
		Key k = new Key(method, context);
		CGNode result = getNode(k);
		if (result == null) {
			if (maxNumberOfNodes == -1 || getNumberOfNodes() < maxNumberOfNodes) {
				result = makeNode(method, context);
				registerNode(k, result);
			} else {
				throw CancelException.make("Too many nodes");
			}
		}
		return result;
	}

	/**
	 * 
	 * @param method -> no input for context from source code ....
	 * @return all methods with different contexts; can be null
	 */
	public Set<CGNode> findExistingNode(IMethod method) {
		if (method == null) {
			throw new IllegalArgumentException("null method");
		}
		MethodReference ref = method.getReference();
		return mr2Nodes.get(ref);
	}

	/**
	 * bz: to precisely get the size of callgraph during incremental changes
	 * @return  com.ibm.wala.util.graph.impl.DelegatingNumberedNodeManager.maxNumber
	 */
	public int getCurrentMaxNumberOfNodes() {
		return getNodeManager().getMaxNumber();
	}

	/**
	 * bz: to maintain the size of callgraph dynamically to avoid one id mapping to two CGNodes
	 * @param copy -> should be astcgnodecopy
	 */
	public void addAstCGNode(CGNode copy) {
		addNode(copy);
	}


	public class IPAExplicitNode extends IPANodeImpl {
		/**
		 * A Mapping from call site program counter (int) -> Object, where Object is a CGNode if we've discovered exactly one target for
		 * the site, or an IntSet of node numbers if we've discovered more than one target for the site.
		 */
		protected final SparseVector<Object> targets = new SparseVector<Object>();

		private final IPAMutableSharedBitVectorIntSet allTargets = new IPAMutableSharedBitVectorIntSet();

		private WeakReference<IR> ir = new WeakReference<IR>(null);
		private WeakReference<DefUse> du = new WeakReference<DefUse>(null);

		/**
		 * @param method
		 */
		protected IPAExplicitNode(IMethod method, Context C) {
			super(method, C);
			this.hashcode = getMethod().hashCode() * 8581 + getContext().hashCode();
		}

		protected Set<CGNode> getPossibleTargets(CallSiteReference site) {
			Object result = targets.get(site.getProgramCounter());

			if (result == null) {
				return Collections.emptySet();
			} else if (result instanceof CGNode) {
				Set<CGNode> s = Collections.singleton((CGNode) result);
				return s;
			} else {
				IntSet s = (IntSet) result;
				HashSet<CGNode> h = HashSetFactory.make(s.size());
				for (IntIterator it = s.intIterator(); it.hasNext();) {
					h.add(getCallGraph().getNode(it.next()));
				}
				return h;
			}
		}

		protected IntSet getPossibleTargetNumbers(CallSiteReference site) {
			Object t = targets.get(site.getProgramCounter());

			if (t == null) {
				return null;
			} else if (t instanceof CGNode) {
				return SparseIntSet.singleton(getCallGraph().getNumber((CGNode) t));
			} else {
				return (IntSet) t;
			}
		}

		/*
		 * @see com.ibm.wala.ipa.callgraph.CGNode#getPossibleSites(com.ibm.wala.ipa.callgraph.CGNode)
		 */
		protected Iterator<CallSiteReference> getPossibleSites(final CGNode to) {
			final int n = getCallGraph().getNumber(to);
			return new FilterIterator<CallSiteReference>(iterateCallSites(), new Predicate() {
				@Override public boolean test(Object o) {
					IntSet s = getPossibleTargetNumbers((CallSiteReference) o);
					return s == null ? false : s.contains(n);
				}
			});
		}

		protected int getNumberOfTargets(CallSiteReference site) {
			Object result = targets.get(site.getProgramCounter());

			if (result == null) {
				return 0;
			} else if (result instanceof CGNode) {
				return 1;
			} else {
				return ((IntSet) result).size();
			}
		}
		
		/**
		 * @return allTargets
		 */
		public Set<CGNode> getAllTargets() {
			HashSet<CGNode> h = HashSetFactory.make(allTargets.size());
			for (IntIterator it = allTargets.intIterator(); it.hasNext();) {
				h.add(getCallGraph().getNode(it.next()));
			}
			return h;
		}


		@Override
		public boolean addTarget(CallSiteReference site, CGNode tNode) {
			return addTarget(site.getProgramCounter(), tNode);
		}

		protected boolean addTarget(int pc, CGNode tNode) {
			allTargets.add(getCallGraph().getNumber(tNode));
			Object S = targets.get(pc);
			if (S == null) {
				S = tNode;
				targets.set(pc, S);
				getCallGraph().addEdge(this, tNode);
				return true;
			}
			if (S instanceof CGNode) {
				if (S.equals(tNode)) {
					return false;
				}
				IPAMutableSharedBitVectorIntSet s = new IPAMutableSharedBitVectorIntSet();
				s.add(getCallGraph().getNumber((CGNode) S));
				s.add(getCallGraph().getNumber(tNode));
				getCallGraph().addEdge(this, tNode);
				targets.set(pc, s);
				return true;
			}
			MutableIntSet s = (MutableIntSet) S;
			int n = getCallGraph().getNumber(tNode);
			if (!s.contains(n)) {
				s.add(n);
				getCallGraph().addEdge(this, tNode);
				return true;
			}
			return false;
		}


		/**
		 */
		@Override
		public boolean removeTarget(CallSiteReference callSite, CGNode target) {
			return removeTarget(callSite.getProgramCounter(), target);
		}


		/**
		 * remove tNode from targets and allTargets from IPAExplicitNode;
		 * and remove the call graph edges ...
		 * @param pc
		 * @param tNode
		 * @return
		 */
		protected boolean removeTarget(int pc, CGNode tNode) {
			allTargets.remove(getCallGraph().getNumber(tNode));
			Object S = targets.get(pc);
			if (S == null) {
				return true;//this target does not exist
			} else {
				if (S instanceof CGNode) {
					IPAMutableSharedBitVectorIntSet s = new IPAMutableSharedBitVectorIntSet();
					if (S.equals(tNode)) { //only one target
						getCallGraph().removeEdge(this, tNode);
						targets.remove(pc);
						return true;
					} 
					return false; //no such target
				} else {
					MutableIntSet s = (MutableIntSet) S;
					int n = getCallGraph().getNumber(tNode);
					if (s.contains(n)) {
						s.remove(n);
						getCallGraph().removeEdge(this, tNode);
						return true;
					}
					return false; //no such target
				}
			}
		}

		/**
		 * remove allTargets from IPAExplicitNode;
		 * and remove all the call graph edges ...
		 */
		public void clearAllTargets() {
			IntIterator iter = targets.iterateIndices();
			while(iter.hasNext()) {
				int pc = iter.next();
				Object S = targets.get(pc);
				if (S == null) { //this target does not exist
					continue;
				} 
				if (S instanceof CGNode) {
					getCallGraph().removeEdge(this, (CGNode) S);
					targets.remove(pc);
				} else {
					MutableIntSet s = (MutableIntSet) S;
					IntIterator iter2 = s.intIterator();
					while (iter2.hasNext()) {
						int n = iter2.next();
						CGNode tNode = getCallGraph().getNode(n);
						getCallGraph().removeEdge(this, tNode);
						
					}
				}
			}
			
			targets.clear();
			allTargets.clear();
		}


		@Override
		public boolean equals(Object obj) {
			// WALA: we can use object equality since these objects are canonical as created
			// by the governing ExplicitCallGraph
			return this == obj;
			//bz:but we cannot use this... we may replace method....
//	    	return this.hashCode() == obj.hashCode();//bz: currently the best, maybe have duplicate hashcode problem
		}

		private int hashcode = -1;

		@Override
		public int hashCode() {//!k-cfa: change 8681 to 8581, otherwise same hashcode for pointerkey
			return hashcode;//move computation to constructor
//	      return getMethod().hashCode() * 8581 + getContext().hashCode();//wala original 
		}

		protected IPAMutableSharedBitVectorIntSet getAllTargetNumbers() {
			return allTargets;
		}


		@Override
		public IR getIR() { 
			if (getMethod().isSynthetic()) {
				// disable local cache in this case, as context interpreters
				// do weird things like mutate IRs
				return getCallGraph().getInterpreter(this).getIR(this);
			}
			IR ir = this.ir.get();
			if (ir == null) {
				ir = getCallGraph().getInterpreter(this).getIR(this);
				this.ir = new WeakReference<IR>(ir);
			}
			return ir;
		}

		@Override
		public DefUse getDU() {
			if (getMethod().isSynthetic()) {
				// disable local cache in this case, as context interpreters
				// do weird things like mutate IRs
				return getCallGraph().getInterpreter(this).getDU(this);
			}
			DefUse du = this.du.get();
			if (du == null) {
				du = getCallGraph().getInterpreter(this).getDU(this);
				this.du = new WeakReference<DefUse>(du);
			}
			return du;
		}

		public IPAExplicitCallGraph getCallGraph() {
			return IPAExplicitCallGraph.this;
		}

		@Override
		public Iterator<CallSiteReference> iterateCallSites() {
			return getCallGraph().getInterpreter(this).iterateCallSites(this);
		}

		@Override
		public Iterator<NewSiteReference> iterateNewSites() {
			return getCallGraph().getInterpreter(this).iterateNewSites(this);
		}

		public ControlFlowGraph<SSAInstruction, ISSABasicBlock> getCFG() {
			return getCallGraph().getInterpreter(this).getCFG(this);
		}

		/**
		 * for plugin
		 * @param m
		 * @param ir
		 */
		public void updateMethod(IMethod m, IR ir) {
			this.method = m;
			//if changed, may not be able to find it
			this.ir = new WeakReference<IR>(ir);
//			this.du = new WeakReference<DefUse>(getCallGraph().getInterpreter(this).getDU(this));
			this.du = new WeakReference<DefUse>(new DefUse(this.ir.get()));
		}


	}

	/*
	 * @see com.ibm.wala.ipa.callgraph.CallGraph#getClassHierarchy()
	 */
	@Override
	public IClassHierarchy getClassHierarchy() {
		return cha;
	}

	protected class ExplicitEdgeManager implements NumberedEdgeManager<CGNode> {

		final IntFunction<CGNode> toNode = new IntFunction<CGNode>() {
			@Override
			public CGNode apply(int i) {
				CGNode result = getNode(i);
				return result;
			}
		};

		/**
		 * for each y, the {x | (x,y) is an edge) == store incoming edges of y
		 */
		final IBinaryNaturalRelation predecessors = new BasicNaturalRelation(new byte[] { BasicNaturalRelation.SIMPLE_SPACE_STINGY },
				BasicNaturalRelation.SIMPLE);

		@Override
		public IntSet getSuccNodeNumbers(CGNode node) {
			IPAExplicitNode n = (IPAExplicitNode) node;
			return n.getAllTargetNumbers();
		}

		@Override
		public IntSet getPredNodeNumbers(CGNode node) {
			IPAExplicitNode n = (IPAExplicitNode) node;
			int y = getNumber(n);//
			return predecessors.getRelated(y);
		}

		@Override
		public Iterator<CGNode> getPredNodes(CGNode N) {
			IntSet s = getPredNodeNumbers(N);
			if (s == null) {
				return EmptyIterator.instance();
			} else {
				return new IntMapIterator<CGNode>(s.intIterator(), toNode);
			}
		}

		@Override
		public int getPredNodeCount(CGNode N) {
			IPAExplicitNode n = (IPAExplicitNode) N;
			int y = getNumber(n);
			return predecessors.getRelatedCount(y);
		}

		@Override
		public Iterator<CGNode> getSuccNodes(CGNode N) {
			IPAExplicitNode n = (IPAExplicitNode) N;
			return new IntMapIterator<CGNode>(n.getAllTargetNumbers().intIterator(), toNode);
		}

		@Override
		public int getSuccNodeCount(CGNode N) {
			IPAExplicitNode n = (IPAExplicitNode) N;
			return n.getAllTargetNumbers().size();
		}

		@Override
		public void addEdge(CGNode src, CGNode dst) {
			// we assume that this is called from IPAExplicitNode.addTarget().
			// so we only have to track the inverse edge.
			int x = getNumber(src);
			int y = getNumber(dst);
			predecessors.add(y, x);
		}

		@Override
		public void removeEdge(CGNode src, CGNode dst) {
			int x = getNumber(src);
			int y = getNumber(dst);
			predecessors.remove(y, x);
		}

		protected void addEdge(int x, int y) {
			// we only have to track the inverse edge.
			predecessors.add(y, x);
		}

		@Override
		public void removeAllIncidentEdges(CGNode node) {
			Assertions.UNREACHABLE();
		}

		@Override
		public void removeIncomingEdges(CGNode node) {
			//bz: node == y : for each y, the {x | (x,y) is an edge)
			// x -> y == incoming edge of y
			IntSet xs = getPredNodeNumbers(node);
			if (xs == null) // already removed 
				return;
			int y = getNumber(node);
			IntIterator iter = xs.intIterator();
			while (iter.hasNext()) {
				int x = (int) iter.next();
				predecessors.remove(y, x); // x -> y for y
			}
		}

		@Override
		public void removeOutgoingEdges(CGNode node) {
			//bz: node = x : for each y, the {x | (x,y) is an edge)
			// x -> y == outgoing edge of x
			IntSet ys = getSuccNodeNumbers(node);
			if (ys == null) // already removed 
				return;
			int x = getNumber(node);
			IntIterator iter = ys.intIterator();
			while (iter.hasNext()) {
				int y = (int) iter.next();
				predecessors.remove(y, x); //x -> y for y
			}
		}

		@Override
		public boolean hasEdge(CGNode src, CGNode dst) {
			int x = getNumber(src);
			int y = getNumber(dst);
			return predecessors.contains(y, x);
		}
	}

	/**
	 * @return Returns the edgeManger.
	 */
	@Override
	public NumberedEdgeManager<CGNode> getEdgeManager() {
		return edgeManager;
	}

	protected ExplicitEdgeManager makeEdgeManger() {
		return new ExplicitEdgeManager();
	}

	@Override
	public int getNumberOfTargets(CGNode node, CallSiteReference site) {
		if (!containsNode(node)) {
			throw new IllegalArgumentException("node not in callgraph " + node);
		}
		assert (node instanceof IPAExplicitNode);
		IPAExplicitNode n = (IPAExplicitNode) node;
		return n.getNumberOfTargets(site);
	}

	@Override
	public Iterator<CallSiteReference> getPossibleSites(CGNode src, CGNode target) {
		if (!containsNode(src)) {
			throw new IllegalArgumentException("node not in callgraph " + src);
		}
		if (!containsNode(target)) {
			throw new IllegalArgumentException("node not in callgraph " + target);
		}
		assert (src instanceof IPAExplicitNode);
		IPAExplicitNode n = (IPAExplicitNode) src;
		return n.getPossibleSites(target);
	}

	@Override
	public Set<CGNode> getPossibleTargets(CGNode node, CallSiteReference site) {
		if (!containsNode(node)) {
			throw new IllegalArgumentException("node not in callgraph " + node);
		}
		assert (node instanceof IPAExplicitNode);
		IPAExplicitNode n = (IPAExplicitNode) node;
		return n.getPossibleTargets(site);
	}

	public IntSet getPossibleTargetNumbers(CGNode node, CallSiteReference site) {
		if (!containsNode(node)) {
			throw new IllegalArgumentException("node not in callgraph " + node + " Site: " + site);
		}
		assert (node instanceof IPAExplicitNode);
		IPAExplicitNode n = (IPAExplicitNode) node;
		return n.getPossibleTargetNumbers(site);
	}

	public IAnalysisCacheView getAnalysisCache() {
		return cache;
	}

	/**
	 * thread-sensitive and k-cfa: return a zomby cgnode, but dont put it in the cg
	 * @param method
	 * @param everywhere
	 * @return
	 */
	public CGNode createZombieNode(IMethod method, Everywhere everywhere) {
		if (method == null) {
			throw new IllegalArgumentException("null method");
		}
		if (everywhere == null) {
			throw new IllegalArgumentException("null context");
		}
		CGNode result = makeNode(method, everywhere);
		return result;
	}

	/**
	 * bz: path-sensitive
	 * @param caller 
	 */
	public CGNode createNewCopyNode(CGNode method, CGNode caller, CallSiteReference callSite) {
		if (method == null) {
			throw new IllegalArgumentException("null method");
		}
		if (callSite == null) {
			throw new IllegalArgumentException("null context");
		}
		CGNode result = null;
		try {
			result = findOrCreateNode(method.getMethod(), new CallStringContext(new CallString(callSite, caller.getMethod())));
		} catch (CancelException e) {
			e.printStackTrace();
		}
		return result;
	}


}
