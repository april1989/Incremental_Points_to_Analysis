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
package edu.tamu.wala.increpta.ipa.callgraph.propagation;


import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.ibm.wala.fixpoint.IFixedPointStatement;
import com.ibm.wala.fixpoint.IFixedPointSystem;
import com.ibm.wala.fixpoint.IVariable;
import com.ibm.wala.util.collections.CompoundIterator;
import com.ibm.wala.util.collections.EmptyIterator;
import com.ibm.wala.util.collections.FilterIterator;
import com.ibm.wala.util.collections.SmallMap;
import com.ibm.wala.util.debug.Assertions;
import com.ibm.wala.util.debug.UnimplementedError;
import com.ibm.wala.util.graph.AbstractNumberedGraph;
import com.ibm.wala.util.graph.Graph;
import com.ibm.wala.util.graph.INodeWithNumber;
import com.ibm.wala.util.graph.NumberedEdgeManager;
import com.ibm.wala.util.graph.NumberedGraph;
import com.ibm.wala.util.graph.NumberedNodeManager;
import com.ibm.wala.util.graph.impl.DelegatingNumberedNodeManager;
import com.ibm.wala.util.graph.impl.SparseNumberedEdgeManager;
import com.ibm.wala.util.graph.traverse.Topological;
import com.ibm.wala.util.heapTrace.HeapTracer;
import com.ibm.wala.util.intset.BasicNaturalRelation;
import com.ibm.wala.util.intset.IBinaryNaturalRelation;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntPair;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetAction;

import edu.tamu.wala.increpta.operators.IPAAbstractOperator;
import edu.tamu.wala.increpta.operators.IPAAbstractStatement;
import edu.tamu.wala.increpta.operators.IPAAssignEquation;
import edu.tamu.wala.increpta.operators.IPAAssignOperator;
import edu.tamu.wala.increpta.operators.IPAGeneralStatement;
import edu.tamu.wala.increpta.operators.IPAUnaryOperator;
import edu.tamu.wala.increpta.operators.IPAUnaryStatement;
//import edu.tamu.wala.increpta.scc.SCCEngine;
//import edu.tamu.wala.increpta.scc.SCCVariable;
//import edu.tamu.wala.increpta.wcc.WCC;
//import edu.tamu.wala.increpta.wcc.WCCEngine;

/**
 * A dataflow graph implementation specialized for propagation-based pointer analysis
 */
public class IPAPropagationGraph implements IFixedPointSystem<IPAPointsToSetVariable> {

	private final static boolean DEBUG = false;

	private final static boolean VERBOSE = false;

	/**
	 * Track nodes (PointsToSet Variables and AbstractEquations)
	 */
	private final NumberedNodeManager<INodeWithNumber> nodeManager = new DelegatingNumberedNodeManager<INodeWithNumber>();

	/**
	 * Track edges (equations) that are not represented implicitly
	 */
	private final NumberedEdgeManager<INodeWithNumber> edgeManager = new SparseNumberedEdgeManager<INodeWithNumber>(nodeManager, 2,
			BasicNaturalRelation.SIMPLE);

	private final DelegateGraph delegateGraph = new DelegateGraph();

	/**
	 * TODO: bz: identify existing statements, replace with hashmap
	 */
	@SuppressWarnings("rawtypes")
	private final HashMap<Integer, IPAAbstractStatement> delegateStatements = new HashMap<Integer, IPAAbstractStatement>();

	/**
	 * special representation for implicitly represented unary equations. This is a map from IPAUnaryOperator ->
	 * IBinaryNonNegativeIntRelation.
	 *
	 * for IPAUnaryOperator op, let R be implicitMap.get(op) then (i,j) \in R implies i op j is an equation in the graph
	 *
	 */
	private final SmallMap<IPAUnaryOperator<IPAPointsToSetVariable>, IBinaryNaturalRelation> implicitUnaryMap = new SmallMap<IPAUnaryOperator<IPAPointsToSetVariable>, IBinaryNaturalRelation>();

	/**
	 * The inverse of relations in the implicit map
	 *
	 * for IPAUnaryOperator op, let R be invImplicitMap.get(op) then (i,j) \in R implies j op i is an equation in the graph
	 */
	private final SmallMap<IPAUnaryOperator<IPAPointsToSetVariable>, IBinaryNaturalRelation> invImplicitUnaryMap = new SmallMap<IPAUnaryOperator<IPAPointsToSetVariable>, IBinaryNaturalRelation>();

	/**
	 * Number of implicit unary equations registered
	 */
	private int implicitUnaryCount = 0;

	
//	//////scc
//	public SCCEngine sccEngine;
//	public void setSCCEngine(SCCEngine engine) {
//		this.sccEngine = engine;
//	}
//
//	public SCCEngine getSCCEngine() {
//		return sccEngine;
//	}
//	
//	boolean use_scc = false;
//	public void setUseSCC(boolean b) {
//		this.use_scc = b;
//	}
//
//	/**
//	 * run after the whole program analysis
//	 */
//	public void initialRunSCCEngine(){
//		sccEngine.initialSCCDetection();
//	}
//	////////
//	
//	//////wcc + scc
//	public WCCEngine wccEngine;
//	public void setWCCEngine(WCCEngine engine) {
//		this.wccEngine = engine;
//	}
//
//	public WCCEngine getWCCEngine() {
//		return wccEngine;
//	}
//
//	/**
//	 * run after the whole program analysis
//	 */
//	public void initialRunWCCEngine(){
//		wccEngine.initialBothCCDetection();
//	}
//	
//	boolean use_wcc = false;
//	public void setUseWCC(boolean b) {
//		this.use_wcc = b;
//	}
//	////////
	
	////incremental
	private boolean change = false;
	/**
	 * start to incremental change program
	 * @param change
	 */
	public void setChange(boolean change){
		this.change = change;
//		if(use_wcc)
//			wccEngine.setChange(change);
	}
	////////

	
	public DelegateGraph getDelegateGraph(){
		return delegateGraph;
	}

	public NumberedNodeManager<INodeWithNumber> getNodeManager(){
		return nodeManager;
	}

	/**
	 * @return a relation in map m corresponding to a key
	 */
	private static IBinaryNaturalRelation findOrCreateRelation(Map<IPAUnaryOperator<IPAPointsToSetVariable>, IBinaryNaturalRelation> m,
			IPAUnaryOperator<IPAPointsToSetVariable> key) {
		IBinaryNaturalRelation result = m.get(key);
		if (result == null) {
			result = makeRelation(key);
			m.put(key, result);
		}
		return result;
	}

	/**
	 * @return a Relation object to track implicit equations using the operator
	 */
	private static IBinaryNaturalRelation makeRelation(IPAAbstractOperator op) {
		byte[] implementation = null;
		if (op instanceof IPAAssignOperator) {
			// lots of assignments.
			implementation = new byte[] { BasicNaturalRelation.SIMPLE_SPACE_STINGY, BasicNaturalRelation.SIMPLE_SPACE_STINGY };
		} else {
			// assume sparse assignments with any other operator.
			implementation = new byte[] { BasicNaturalRelation.SIMPLE_SPACE_STINGY };
		}
		return new BasicNaturalRelation(implementation, BasicNaturalRelation.SIMPLE);
	}

	/**
	 * @author sfink
	 *
	 *         A graph which tracks explicit equations.
	 *
	 *         use this with care ...
	 */
	 class DelegateGraph extends AbstractNumberedGraph<INodeWithNumber> {

		private int equationCount = 0;

		private int varCount = 0;

		@Override
		public void addNode(INodeWithNumber o) {
			Assertions.UNREACHABLE("Don't call me");
		}

		public void addEquation(IPAAbstractStatement<IPAPointsToSetVariable, ?> eq) {
			assert !containsStatement(eq);
			equationCount++;
			super.addNode(eq);
		}

		public void addVariable(IPAPointsToSetVariable v) {
			if (!containsVariable(v)) {
				varCount++;
				super.addNode(v);
			}
		}

		/*
		 * @see com.ibm.wala.util.graph.AbstractGraph#getNodeManager()
		 */
		@Override
		protected NumberedNodeManager<INodeWithNumber> getNodeManager() {
			return nodeManager;
		}

		/*
		 * @see com.ibm.wala.util.graph.AbstractGraph#getEdgeManager()
		 */
		@Override
		protected NumberedEdgeManager<INodeWithNumber> getEdgeManager() {
			return edgeManager;
		}

		protected int getEquationCount() {
			return equationCount;
		}

		protected int getVarCount() {
			return varCount;
		}

	}

	/**
	 * bz:
	 * @param eq
	 */
	@SuppressWarnings("unchecked")
	public void removeStatement(IPAGeneralStatement<IPAPointsToSetVariable> eq){
		if (eq == null) {
			throw new IllegalArgumentException("eq == null");
		}
		if(DEBUG)
			System.err.println("--- Del Statement2: "+eq.toString());

		IPAAbstractStatement tmp = delegateStatements.remove(eq.hashCode());
		if(!(tmp instanceof IPAGeneralStatement)) {
			System.out.println("Want IPAGeneralStatement " + eq.toString() + " \n\tGet IPABasicUnaryStatement " + tmp.toString());
			return;
		}
		
		eq = (IPAGeneralStatement<IPAPointsToSetVariable>) tmp;

		IPAPointsToSetVariable lhs = eq.getLHS();
		if (lhs != null) {
			delegateGraph.removeEdge(eq, lhs);
//			checkIfDeleteNode(lhs);
		}
		for (int i = 0; i < eq.getRHS().length; i++){
			IPAPointsToSetVariable rhs = eq.getRHS()[i];
			if(rhs!=null){
				delegateGraph.removeEdge(rhs, eq);
//				checkIfDeleteNode(rhs);
			}
		}
	}

	/**
	 * bz:
	 * @param eq
	 * @throws IllegalArgumentException
	 */
	@SuppressWarnings("unchecked")
	public void removeStatement(IPAUnaryStatement<IPAPointsToSetVariable> eq) throws IllegalArgumentException {
		if (eq == null) {
			throw new IllegalArgumentException("eq == null");
		}
		if(DEBUG)
			System.err.println("--- Del Statement: "+eq.toString());
		if (useImplicitRepresentation(eq)) {
			removeImplicitStatement(eq);
		} else {
			eq = (IPAUnaryStatement<IPAPointsToSetVariable>) delegateStatements.remove(eq.hashCode());
			IPAPointsToSetVariable lhs = eq.getLHS();
			IPAPointsToSetVariable rhs = eq.getRightHandSide();
			if (lhs != null) {
				delegateGraph.removeEdge(eq, lhs);
//				checkIfDeleteNode(lhs);
			}
			try{
				delegateGraph.removeEdge(rhs, eq);
//				checkIfDeleteNode(rhs);
			}catch(Exception e){
				e.printStackTrace();
			}

		}
	}

	@SuppressWarnings("unused")
	private void checkIfDeleteNode(IPAPointsToSetVariable v) {
//		int num_of_use = getNumberOfStatementsThatUse(v);
//		int num_of_def = getNumberOfStatementsThatDef(v);
//		if(num_of_def == 0 && num_of_use == 0){
////			delegateGraph.removeNode(v);//TODO: should be removed to maintain a small graph, but the graph implementation won't support
//			if(use_wcc) {
//				wccEngine.removeSCCNode(v.getGraphNodeId());
//			}else if(use_scc){
//				sccEngine.removeNode(v.getGraphNodeId());
//			}
////			System.err.println("delete: " + v.getGraphNodeId());
//		}
	}

	/**
	 * @throws IllegalArgumentException if eq is null
	 * rhs -> eq(op) -> lhs
	 */
	public void addStatement(IPAGeneralStatement<IPAPointsToSetVariable> eq) {
		if (eq == null) {
			throw new IllegalArgumentException("eq is null");
		}
		IPAPointsToSetVariable lhs = eq.getLHS();
		delegateGraph.addEquation(eq);
		delegateStatements.put(eq.hashCode(),eq);//bz

		if (lhs != null) {
			delegateGraph.addVariable(lhs);
			delegateGraph.addEdge(eq, lhs);
		}
		for (int i = 0; i < eq.getRHS().length; i++) {
			IPAPointsToSetVariable v = eq.getRHS()[i];
			if (v != null) {
				delegateGraph.addVariable(v);
				delegateGraph.addEdge(v, eq); 
			}
		}
	}

	/**
	 *  rhs -> eq(op) -> lhs
	 * @param eq
	 * @throws IllegalArgumentException
	 */
	public void addStatement(IPAUnaryStatement<IPAPointsToSetVariable> eq) throws IllegalArgumentException {
		if (eq == null) {
			throw new IllegalArgumentException("eq == null");
		}
		if (useImplicitRepresentation(eq)) {
			addImplicitStatement(eq);//add to scc
		} else {
			IPAPointsToSetVariable lhs = eq.getLHS();
			IPAPointsToSetVariable rhs = eq.getRightHandSide();
			delegateGraph.addEquation(eq);
			//bz
			delegateStatements.put(eq.hashCode(),eq);

			if (lhs != null) {
				delegateGraph.addVariable(lhs);
				delegateGraph.addEdge(eq, lhs);
			}
			delegateGraph.addVariable(rhs);
			delegateGraph.addEdge(rhs, eq);
		}
	}

	/**
	 * @return true iff this equation should be represented implicitly in this data structure
	 * private -> public
	 */
	public static boolean useImplicitRepresentation(IFixedPointStatement s) {
		IPAAbstractStatement eq = (IPAAbstractStatement) s;
		IPAAbstractOperator op = eq.getOperator();
		return (op instanceof IPAAssignOperator || op instanceof IPAPropagationCallGraphBuilder.IPAFilterOperator);
	}

	public void removeVariable(IPAPointsToSetVariable p) {
		assert getNumberOfStatementsThatDef(p) == 0;
		assert getNumberOfStatementsThatUse(p) == 0;
		delegateGraph.removeNode(p);
	}

	private void addImplicitStatement(IPAUnaryStatement<IPAPointsToSetVariable> eq) {
		if (DEBUG) {
			System.err.println(("addImplicitStatement " + eq));
		}
		delegateGraph.addVariable(eq.getLHS());
		delegateGraph.addVariable(eq.getRightHandSide());
		int lhs = eq.getLHS().getGraphNodeId();
		int rhs = eq.getRightHandSide().getGraphNodeId();
		if (DEBUG) {
			System.err.println(("lhs rhs " + lhs + " " + rhs));
		}
		IBinaryNaturalRelation R = findOrCreateRelation(implicitUnaryMap, eq.getOperator());
		boolean b = R.add(lhs, rhs);
		if (b) {
			implicitUnaryCount++;
			IBinaryNaturalRelation iR = findOrCreateRelation(invImplicitUnaryMap, eq.getOperator());
			iR.add(rhs, lhs);
		}
//		if(change){
//			//process scc
//			if(use_wcc) {
//				wccEngine.addSCCEdge(lhs, rhs);
//			}else if(use_scc) {
//				sccEngine.addEdge(lhs, rhs);
//			}
//		}
	}

	private void removeImplicitStatement(IPAUnaryStatement<IPAPointsToSetVariable> eq) {
		if (DEBUG) {
			System.err.println(("removeImplicitStatement " + eq));
		}
		IPAPointsToSetVariable LHS = eq.getLHS();
		IPAPointsToSetVariable RHS = eq.getRightHandSide();
		int lhs = LHS.getGraphNodeId();
		int rhs = RHS.getGraphNodeId();
		if (DEBUG) {
			System.err.println(("lhs rhs " + lhs + " " + rhs));
		}
		IBinaryNaturalRelation R = findOrCreateRelation(implicitUnaryMap, eq.getOperator());
		R.remove(lhs, rhs);
		IBinaryNaturalRelation iR = findOrCreateRelation(invImplicitUnaryMap, eq.getOperator());
		iR.remove(rhs, lhs);
		implicitUnaryCount--;
//		if(change){
//			//process scc
//			if(use_wcc) {
//				wccEngine.removeSCCEdge(lhs, rhs);
//			}else if(use_scc){
//				sccEngine.removeEdge(lhs, rhs);
//			}
////			checkIfDeleteNode(LHS);
////			checkIfDeleteNode(RHS);
//		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public Iterator<IPAAbstractStatement> getStatements() {
		Iterator<IPAAbstractStatement> it = new FilterIterator(delegateGraph.iterator(), new Predicate() {
			@Override public boolean test(Object x) {
				return x instanceof IPAAbstractStatement;
			}
		});
		return new CompoundIterator<IPAAbstractStatement>(it, new GlobalImplicitIterator());
	}

	/**
	 * Iterator of implicit equations that use a particular variable.
	 */
	private final class ImplicitUseIterator implements Iterator<IPAAbstractStatement> {

		final IPAPointsToSetVariable use;

		final IntIterator defs;

		final IPAUnaryOperator<IPAPointsToSetVariable> op;

		ImplicitUseIterator(IPAUnaryOperator<IPAPointsToSetVariable> op, IPAPointsToSetVariable use, IntSet defs) {
			this.op = op;
			this.use = use;
			this.defs = defs.intIterator();
		}

		@Override
		public boolean hasNext() {
			return defs.hasNext();
		}

		@Override
		public IPAAbstractStatement next() {
			int l = defs.next();
			IPAPointsToSetVariable lhs = (IPAPointsToSetVariable) delegateGraph.getNode(l);
			IPAUnaryStatement temp = op.makeEquation(lhs, use);
			if (DEBUG) {
				System.err.print(("XX Return temp: " + temp));
				System.err.println(("lhs rhs " + l + " " + use.getGraphNodeId()));
			}
			return temp;
		}

		@Override
		public void remove() {
			// TODO Auto-generated method stub
			Assertions.UNREACHABLE();
		}
	}

	/**
	 * Iterator of implicit equations that def a particular variable.
	 */
	private final class ImplicitDefIterator implements Iterator<IPAAbstractStatement> {

		final IPAPointsToSetVariable def;

		final IntIterator uses;

		final IPAUnaryOperator<IPAPointsToSetVariable> op;

		ImplicitDefIterator(IPAUnaryOperator<IPAPointsToSetVariable> op, IntSet uses, IPAPointsToSetVariable def) {
			this.op = op;
			this.def = def;
			this.uses = uses.intIterator();
		}

		@Override
		public boolean hasNext() {
			return uses.hasNext();
		}

		@Override
		public IPAAbstractStatement next() {
			int r = uses.next();
			IPAPointsToSetVariable rhs = (IPAPointsToSetVariable) delegateGraph.getNode(r);
			IPAUnaryStatement temp = op.makeEquation(def, rhs);
			if (DEBUG) {
				System.err.print(("YY Return temp: " + temp));
			}
			return temp;
		}

		@Override
		public void remove() {
			// TODO Auto-generated method stub
			Assertions.UNREACHABLE();
		}
	}

	/**
	 * Iterator of all implicit equations
	 */
	private class GlobalImplicitIterator implements Iterator<IPAAbstractStatement> {

		private final Iterator<IPAUnaryOperator<IPAPointsToSetVariable>> outerKeyDelegate = implicitUnaryMap.keySet().iterator();

		private Iterator innerDelegate;

		private IPAUnaryOperator<IPAPointsToSetVariable> currentOperator;

		GlobalImplicitIterator() {
			advanceOuter();
		}

		/**
		 * advance to the next operator
		 */
		private void advanceOuter() {
			innerDelegate = null;
			while (outerKeyDelegate.hasNext()) {
				currentOperator = outerKeyDelegate.next();
				IBinaryNaturalRelation R = implicitUnaryMap.get(currentOperator);
				Iterator it = R.iterator();
				if (it.hasNext()) {
					innerDelegate = it;
					return;
				}
			}
		}

		@Override
		public boolean hasNext() {
			return innerDelegate != null;
		}

		@Override
		public IPAAbstractStatement next() {
			IntPair p = (IntPair) innerDelegate.next();
			int lhs = p.getX();
			int rhs = p.getY();
			IPAUnaryStatement result = currentOperator.makeEquation((IPAPointsToSetVariable) delegateGraph.getNode(lhs),
					(IPAPointsToSetVariable) delegateGraph.getNode(rhs));
			if (!innerDelegate.hasNext()) {
				advanceOuter();
			}
			return result;
		}

		@Override
		public void remove() {
			// TODO Auto-generated method stub
			Assertions.UNREACHABLE();

		}
	}

	/**
	 * bz: override to consider deletion. (delStatement)
	 */
	@Override
	public void removeStatement(IFixedPointStatement<IPAPointsToSetVariable> eq) throws IllegalArgumentException {
		if (eq == null) {
			throw new IllegalArgumentException("statement == null");
		}
		if (eq instanceof IPAUnaryStatement) {
			removeStatement((IPAUnaryStatement<IPAPointsToSetVariable>) eq);
		} else if (eq instanceof IPAGeneralStatement) {
			removeStatement((IPAGeneralStatement<IPAPointsToSetVariable>) eq);
		} else {
			Assertions.UNREACHABLE("unexpected : " + eq.getClass());
		}
	}

	@Override
	public void reorder() {
		VariableGraphView graph = new VariableGraphView();

		Iterator<IPAPointsToSetVariable> order = Topological.makeTopologicalIter(graph).iterator();
		//bz: this is time consuming if sorting the whole pag !!
		int number = 0;
		while (order.hasNext()) {
			Object elt = order.next();
			if (elt instanceof IVariable) {
				IVariable v = (IVariable) elt;
				v.setOrderNumber(number++);
			}
		}
	}

	/**
	 * A graph of just the variables in the system. v1 -> v2 iff there exists equation e s.t. e uses v1 and e defs v2.
	 *
	 * Note that this graph trickily and fragilely reuses the nodeManager from the delegateGraph, above. This will work ok as long as
	 * every variable is inserted in the delegateGraph.
	 */
	private class VariableGraphView extends AbstractNumberedGraph<IPAPointsToSetVariable> {

		/*
		 * @see com.ibm.wala.util.graph.Graph#removeNodeAndEdges(java.lang.Object)
		 */
		@Override
		public void removeNodeAndEdges(IPAPointsToSetVariable N) {
			Assertions.UNREACHABLE();
		}

		/*
		 * @see com.ibm.wala.util.graph.NodeManager#iterateNodes()
		 */
		@Override
		public Iterator<IPAPointsToSetVariable> iterator() {
			return getVariables();
		}

		/*
		 * @see com.ibm.wala.util.graph.NodeManager#getNumberOfNodes()
		 */
		@Override
		public int getNumberOfNodes() {
			return delegateGraph.getVarCount();
		}

		/*
		 * @see com.ibm.wala.util.graph.NodeManager#addNode(java.lang.Object)
		 */
		@Override
		public void addNode(IPAPointsToSetVariable n) {
			Assertions.UNREACHABLE();

		}

		/*
		 * @see com.ibm.wala.util.graph.NodeManager#removeNode(java.lang.Object)
		 */
		@Override
		public void removeNode(IPAPointsToSetVariable n) {
			Assertions.UNREACHABLE();

		}

		/*
		 * @see com.ibm.wala.util.graph.NodeManager#containsNode(java.lang.Object)
		 */
		@Override
		public boolean containsNode(IPAPointsToSetVariable N) {
			return delegateGraph.containsNode(N);
		}

		/*
		 * @see com.ibm.wala.util.graph.EdgeManager#getPredNodes(java.lang.Object)
		 */
		@Override
		public Iterator<IPAPointsToSetVariable> getPredNodes(IPAPointsToSetVariable v) {
			final Iterator eqs = getStatementsThatDef(v);
			return new Iterator<IPAPointsToSetVariable>() {
				Iterator<INodeWithNumber> inner;

				@Override
				public boolean hasNext() {
					return eqs.hasNext() || (inner != null);
				}

				@Override
				public IPAPointsToSetVariable next() {
					if (inner != null) {
						IPAPointsToSetVariable result = (IPAPointsToSetVariable)inner.next();
						if (!inner.hasNext()) {
							inner = null;
						}
						return result;
					} else {
						IPAAbstractStatement eq = (IPAAbstractStatement) eqs.next();
						if (useImplicitRepresentation(eq)) {
							return (IPAPointsToSetVariable) ((IPAUnaryStatement) eq).getRightHandSide();
						} else {
							inner = delegateGraph.getPredNodes(eq);
							return next();
						}
					}
				}

				@Override
				public void remove() {
					// TODO Auto-generated method stub
					Assertions.UNREACHABLE();
				}
			};
		}

		/*
		 * @see com.ibm.wala.util.graph.EdgeManager#getPredNodeCount(java.lang.Object)
		 */
		@SuppressWarnings("unused")
		public int getPredNodeCount(INodeWithNumber N) {
			IPAPointsToSetVariable v = (IPAPointsToSetVariable) N;
			int result = 0;
			for (Iterator eqs = getStatementsThatDef(v); eqs.hasNext();) {
				IPAAbstractStatement eq = (IPAAbstractStatement) eqs.next();
				if (useImplicitRepresentation(eq)) {
					result++;
				} else {
					result += delegateGraph.getPredNodeCount(N);
				}
			}
			return result;
		}

		/*
		 * @see com.ibm.wala.util.graph.EdgeManager#getSuccNodes(java.lang.Object)
		 */
		@Override
		public Iterator<IPAPointsToSetVariable> getSuccNodes(IPAPointsToSetVariable v) {
			final Iterator eqs = getStatementsThatUse(v);
			return new Iterator<IPAPointsToSetVariable>() {
				IPAPointsToSetVariable nextResult;
				{
					advance();
				}

				@Override
				public boolean hasNext() {
					return nextResult != null;
				}

				@Override
				public IPAPointsToSetVariable next() {
					IPAPointsToSetVariable result = nextResult;
					advance();
					return result;
				}

				private void advance() {
					nextResult = null;
					while (eqs.hasNext() && nextResult == null) {
						IPAAbstractStatement eq = (IPAAbstractStatement) eqs.next();
						nextResult = (IPAPointsToSetVariable) eq.getLHS();
					}
				}

				@Override
				public void remove() {
					// TODO Auto-generated method stub
					Assertions.UNREACHABLE();
				}
			};
		}

		/*
		 * @see com.ibm.wala.util.graph.EdgeManager#getSuccNodeCount(java.lang.Object)
		 */
		@Override
		public int getSuccNodeCount(IPAPointsToSetVariable v) {
			int result = 0;
			for (Iterator eqs = getStatementsThatUse(v); eqs.hasNext();) {
				IPAAbstractStatement eq = (IPAAbstractStatement) eqs.next();
				IVariable lhs = eq.getLHS();
				if (lhs != null) {
					result++;
				}
			}
			return result;
		}

		/*
		 * @see com.ibm.wala.util.graph.EdgeManager#addEdge(java.lang.Object, java.lang.Object)
		 */
		@Override
		public void addEdge(IPAPointsToSetVariable src, IPAPointsToSetVariable dst) {
			Assertions.UNREACHABLE();
		}

		/*
		 * @see com.ibm.wala.util.graph.EdgeManager#removeAllIncidentEdges(java.lang.Object)
		 */
		@Override
		public void removeAllIncidentEdges(IPAPointsToSetVariable node) {
			Assertions.UNREACHABLE();
		}

		/*
		 * @see com.ibm.wala.util.graph.AbstractGraph#getNodeManager()
		 */
		@Override
		@SuppressWarnings("unchecked")
		protected NumberedNodeManager getNodeManager() {
			return nodeManager;
		}

		/*
		 * @see com.ibm.wala.util.graph.AbstractGraph#getEdgeManager()
		 */
		@Override
		@SuppressWarnings("unchecked")
		protected NumberedEdgeManager getEdgeManager() {
			// TODO Auto-generated method stub
			Assertions.UNREACHABLE();
			return null;
		}

	}
	
//	public Iterator<IPAAbstractStatement> getStatementsThatUseWithinWCC(IPAPointsToSetVariable v, WCC wcc) {
//		if (v == null) {
//			throw new IllegalArgumentException("v is null");
//		}
//		int number = v.getGraphNodeId();
//		if (number == -1) {
//			return EmptyIterator.instance();
//		}
//		Iterator<IPAAbstractStatement> result = null;
//		for (int i = 0; i < invImplicitUnaryMap.size(); i++) {
//			IPAUnaryOperator op = invImplicitUnaryMap.getKey(i);
//			IBinaryNaturalRelation R = (IBinaryNaturalRelation) invImplicitUnaryMap.getValue(i);
//			IntSet s = R.getRelated(number);
//			if (s != null) {
//				//only consider the ones insides wcc
//				MutableSharedBitVectorIntSet intersection = new MutableSharedBitVectorIntSet();
//				s.foreach(new IntSetAction() {
//					@Override
//					public void act(int x) {
//						if(wcc.contains(x))
//							intersection.add(x);
//					}
//				});
//				result = new ImplicitUseIterator(op, v, intersection);
//			}
//		}
//		List<IPAAbstractStatement> list = new ArrayList<IPAAbstractStatement>();
//		if(result != null) {
//			while (result.hasNext()) {
//				list.add((IPAAbstractStatement) result.next());
//			}
//		}
//		return list.iterator();
//	}
//	
//	public Iterator<IPAAbstractStatement> getStatementsThatUseOutsideWCC(IPAPointsToSetVariable v, WCC wcc) {
//		if (v == null) {
//			throw new IllegalArgumentException("v is null");
//		}
//		int number = v.getGraphNodeId();
//		if (number == -1) {
//			return EmptyIterator.instance();
//		}
//		Iterator<INodeWithNumber> result = delegateGraph.getSuccNodes(v);
//		for (int i = 0; i < invImplicitUnaryMap.size(); i++) {
//			IPAUnaryOperator op = invImplicitUnaryMap.getKey(i);
//			IBinaryNaturalRelation R = (IBinaryNaturalRelation) invImplicitUnaryMap.getValue(i);
//			IntSet s = R.getRelated(number);
//			if (s != null) {
//				//only consider the ones insides wcc
//				final MutableSharedBitVectorIntSet diff = new MutableSharedBitVectorIntSetFactory().makeCopy(s);
//				s.foreach(new IntSetAction() {
//					@Override
//					public void act(int x) {
//						if(wcc.contains(x))
//							diff.remove(x);
//					}
//				});
//				result = new CompoundIterator<INodeWithNumber>(new ImplicitUseIterator(op, v, diff), result);
//			}
//		}
//		List<IPAAbstractStatement> list = new ArrayList<IPAAbstractStatement>();
//		while (result.hasNext()) {
//			list.add((IPAAbstractStatement) result.next());
//		}
//		return list.iterator();
//	}
	

	@Override
	@SuppressWarnings("unchecked")
	public Iterator<IPAAbstractStatement> getStatementsThatUse(IPAPointsToSetVariable v) {
		if (v == null) {
			throw new IllegalArgumentException("v is null");
		}
		int number = v.getGraphNodeId();
		if (number == -1) {
			return EmptyIterator.instance();
		}
		Iterator<INodeWithNumber> result = delegateGraph.getSuccNodes(v);
		for (int i = 0; i < invImplicitUnaryMap.size(); i++) {
			IPAUnaryOperator op = invImplicitUnaryMap.getKey(i);
			IBinaryNaturalRelation R = (IBinaryNaturalRelation) invImplicitUnaryMap.getValue(i);
			IntSet s = R.getRelated(number);
			if (s != null) {
				result = new CompoundIterator<INodeWithNumber>(new ImplicitUseIterator(op, v, s), result);
			}
		}
		List<IPAAbstractStatement> list = new ArrayList<IPAAbstractStatement>();
		while (result.hasNext()) {
			list.add((IPAAbstractStatement) result.next());
		}
		return list.iterator();
	}
	
	/**
	 * only return the implicit unary ids
	 */
	public ArrayList<IPAAbstractStatement> getImplicitStatementsThatUse(IPAPointsToSetVariable v) {
		if (v == null) {
			throw new IllegalArgumentException("v is null");
		}
		ArrayList<IPAAbstractStatement> list = new ArrayList<IPAAbstractStatement>();
		int number = v.getGraphNodeId();
		if (number == -1) {
			return list;
		}
		Iterator<INodeWithNumber> result = EmptyIterator.instance();
		for (int i = 0; i < invImplicitUnaryMap.size(); i++) {
			IPAUnaryOperator op = invImplicitUnaryMap.getKey(i);
			IBinaryNaturalRelation R = (IBinaryNaturalRelation) invImplicitUnaryMap.getValue(i);
			IntSet s = R.getRelated(number);
			if (s != null) {
				result = new CompoundIterator<INodeWithNumber>(new ImplicitUseIterator(op, v, s), result);
			}
		}
		while (result.hasNext()) {
			list.add((IPAAbstractStatement) result.next());
		}
		return list;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Iterator<IPAAbstractStatement> getStatementsThatDef(IPAPointsToSetVariable v) {
		if (v == null) {
			throw new IllegalArgumentException("v is null");
		}
		int number = v.getGraphNodeId();
		if (number == -1) {
			return EmptyIterator.instance();
		}
		Iterator<INodeWithNumber> result = delegateGraph.getPredNodes(v);
		for (int i = 0; i < implicitUnaryMap.size(); i++) {
			IPAUnaryOperator op = implicitUnaryMap.getKey(i);
			IBinaryNaturalRelation R = (IBinaryNaturalRelation) implicitUnaryMap.getValue(i);
			IntSet s = R.getRelated(number);
			if (s != null) {
				result = new CompoundIterator<INodeWithNumber>(new ImplicitDefIterator(op, s, v), result);
			}
		}
		List<IPAAbstractStatement> list = new ArrayList<IPAAbstractStatement>();
		while (result.hasNext()) {
			list.add((IPAAbstractStatement) result.next());
		}
		return list.iterator();
	}

	/**
	 * only return the implicit unary statatements, do not consider scc
	 */
	public ArrayList<IPAAbstractStatement> getImplicitStatementsThatDef(IPAPointsToSetVariable v) {
		if (v == null) {
			throw new IllegalArgumentException("v is null");
		}
		ArrayList<IPAAbstractStatement> list = new ArrayList<IPAAbstractStatement>();
		int number = v.getGraphNodeId();
		if (number == -1) {
			return list;
		}
		Iterator<INodeWithNumber> result = EmptyIterator.instance();
		for (int i = 0; i < implicitUnaryMap.size(); i++) {
			IPAUnaryOperator op = implicitUnaryMap.getKey(i);
			IBinaryNaturalRelation R = (IBinaryNaturalRelation) implicitUnaryMap.getValue(i);
			IntSet s = R.getRelated(number);
			if (s != null) {
				result = new CompoundIterator<INodeWithNumber>(new ImplicitDefIterator(op, s, v), result);
			}
		}
		while (result.hasNext()) {
			list.add((IPAAbstractStatement) result.next());
		}
		return list;
	}

	/**
	 * only return the PointsToSetVariables/SCCVariables in its defined implicit unary statatements
	 * only used for deletion after the initial run, to determine incoming neighbours
	 */
	public HashSet<IPAPointsToSetVariable> getPointsToSetVariablesThatDefImplicitly(IPAPointsToSetVariable v) {
		if (v == null) {
			throw new IllegalArgumentException("v is null");
		}
		HashSet<IPAPointsToSetVariable> list = new HashSet<>();
		int number = v.getGraphNodeId();
		if (number == -1) {
			return list;
		}
		for (int i = 0; i < implicitUnaryMap.size(); i++) {
			IBinaryNaturalRelation R = (IBinaryNaturalRelation) implicitUnaryMap.getValue(i);
			IntSet s = R.getRelated(number);
			if (s != null) {
				s.foreach(new IntSetAction() {
					@Override
					public void act(int id) {
//						boolean cc = false;
//						if(use_wcc) {
//							if(wccEngine.belongToSCC(id)){
//								SCCVariable scc_v = wccEngine.getCorrespondingSCCVariable(id);
//								if(scc_v != null){
//									cc = true;
//									list.add(scc_v);
//								}else
//									throw new RuntimeException("Cannot locate corresponding SCCVariable for " + id);
//							}
//						}else if(use_scc){
//							if(sccEngine.belongToSCC(id)){
//								SCCVariable scc_v = sccEngine.getCorrespondingSCCVariable(id);
//								if(scc_v != null){
//									cc = true;
//									list.add(scc_v);
//								}else
//									throw new RuntimeException("Cannot locate corresponding SCCVariable for " + id);
//							}
//						}
//						if(!cc) {
							INodeWithNumber p_v = nodeManager.getNode(id);
							if(p_v instanceof IPAPointsToSetVariable){
								list.add((IPAPointsToSetVariable)p_v);
							}
//						}
					}
				});
			}
		}
		return list;
	}

	/**
	 * Note that this implementation consults the implicit relation for each and every operator cached. This will be inefficient if
	 * there are many implicit operators.
	 *
	 * @throws IllegalArgumentException if v is null
	 *
	 */
	@Override
	public int getNumberOfStatementsThatUse(IPAPointsToSetVariable v) {
		if (v == null) {
			throw new IllegalArgumentException("v is null");
		}
		int number = v.getGraphNodeId();
		if (number == -1) {
			return 0;
		}
		int result = delegateGraph.getSuccNodeCount(v);
		for (Iterator it = invImplicitUnaryMap.keySet().iterator(); it.hasNext();) {
			IPAUnaryOperator op = (IPAUnaryOperator) it.next();
			IBinaryNaturalRelation R = invImplicitUnaryMap.get(op);
			IntSet s = R.getRelated(number);
			if (s != null) {
				result += s.size();
			}
		}
		return result;
	}

	@Override
	public int getNumberOfStatementsThatDef(IPAPointsToSetVariable v) {
		if (v == null) {
			throw new IllegalArgumentException("v is null");
		}
		int number = v.getGraphNodeId();
		if (number == -1) {
			return 0;
		}
		int result = delegateGraph.getPredNodeCount(v);
		for (Iterator it = implicitUnaryMap.keySet().iterator(); it.hasNext();) {
			IPAUnaryOperator op = (IPAUnaryOperator) it.next();
			IBinaryNaturalRelation R = implicitUnaryMap.get(op);
			IntSet s = R.getRelated(number);
			if (s != null) {
				result += s.size();
			}
		}
		return result;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Iterator<IPAPointsToSetVariable> getVariables() {
		Iterator<IPAPointsToSetVariable> it = new FilterIterator(delegateGraph.iterator(), new Predicate() {
			@Override public boolean test(Object x) {
				return x instanceof IVariable;
			}
		});
		return it;
	}

	/*
	 * @see com.ibm.wala.util.debug.VerboseAction#performVerboseAction()
	 */
	public void performVerboseAction() {
		if (VERBOSE) {
			System.err.println(("stats for " + getClass()));
			System.err.println(("number of variables: " + delegateGraph.getVarCount()));
			System.err.println(("implicit equations: " + (implicitUnaryCount)));
			System.err.println(("explicit equations: " + delegateGraph.getEquationCount()));
			System.err.println("implicit map:");
			int count = 0;
			int totalBytes = 0;
			for (Iterator it = implicitUnaryMap.entrySet().iterator(); it.hasNext();) {
				count++;
				Map.Entry e = (Map.Entry) it.next();
				IBinaryNaturalRelation R = (IBinaryNaturalRelation) e.getValue();
				System.err.println(("entry " + count));
				R.performVerboseAction();
				HeapTracer.Result result = HeapTracer.traceHeap(Collections.singleton(R), false);
				totalBytes += result.getTotalSize();
			}
			System.err.println(("bytes in implicit map: " + totalBytes));
		}
	}

	@Override
	public boolean containsStatement(IFixedPointStatement<IPAPointsToSetVariable> eq) throws IllegalArgumentException {
		if (eq == null) {
			throw new IllegalArgumentException("eq == null");
		}
		if (useImplicitRepresentation(eq)) {
			IPAUnaryStatement<IPAPointsToSetVariable> ueq = (IPAUnaryStatement<IPAPointsToSetVariable>) eq;
			return containsImplicitStatement(ueq);
		} else {
			//bz
			return delegateStatements.containsKey(eq.hashCode());
		}
	}

	/**
	 * @return true iff the graph already contains this equation
	 */
	private boolean containsImplicitStatement(IPAUnaryStatement<IPAPointsToSetVariable> eq) {
		if (!containsVariable(eq.getLHS())) {
			return false;
		}
		if (!containsVariable(eq.getRightHandSide())) {
			return false;
		}
		int lhs = eq.getLHS().getGraphNodeId();
		int rhs = eq.getRightHandSide().getGraphNodeId();
		IPAUnaryOperator op = eq.getOperator();
		IBinaryNaturalRelation R = implicitUnaryMap.get(op);
		if (R != null) {
			return R.contains(lhs, rhs);
		} else {
			return false;
		}
	}

	@Override
	public boolean containsVariable(IPAPointsToSetVariable v) {
		return delegateGraph.containsNode(v);
	}

	@Override
	public void addStatement(IFixedPointStatement<IPAPointsToSetVariable> statement) throws IllegalArgumentException, UnimplementedError {
		if (statement == null) {
			throw new IllegalArgumentException("statement == null");
		}
		if (statement instanceof IPAUnaryStatement) {
			addStatement((IPAUnaryStatement<IPAPointsToSetVariable>) statement);
		} else if (statement instanceof IPAGeneralStatement) {
			addStatement((IPAGeneralStatement<IPAPointsToSetVariable>) statement);
		} else {
			Assertions.UNREACHABLE("unexpected: " + statement.getClass());
		}
	}

	/**
	 * A graph of just the variables in the system. v1 -> v2 iff there exists an assignment equation e s.t. e uses v1 and e defs v2.
	 *
	 */
	public NumberedGraph<IPAPointsToSetVariable> getAssignmentGraph() {
		return new FilteredConstraintGraphView() {

			@Override
			boolean isInteresting(IPAAbstractStatement eq) {
				return eq instanceof IPAAssignEquation;
			}
		};
	}

	/**
	 * A graph of just the variables in the system. v1 -> v2 iff there exists an Assingnment or Filter equation e s.t. e uses v1 and e
	 * defs v2.
	 *
	 */
	public Graph<IPAPointsToSetVariable> getFilterAssignmentGraph() {
		return new FilteredConstraintGraphView() {
			@Override
			boolean isInteresting(IPAAbstractStatement eq) {
				return eq instanceof IPAAssignEquation || eq.getOperator() instanceof IPAPropagationCallGraphBuilder.IPAFilterOperator;
			}
		};
	}

	/**
	 * NOTE: do not use this method unless you really know what you are doing. Functionality is fragile and may not work in the
	 * future.
	 */
	public Graph<IPAPointsToSetVariable> getFlowGraphIncludingImplicitConstraints() {
		return new VariableGraphView();
	}

	/**
	 * A graph of just the variables in the system. v1 -> v2 that are related by def-use with "interesting" operators
	 *
	 */
	private abstract class FilteredConstraintGraphView extends AbstractNumberedGraph<IPAPointsToSetVariable> {

		abstract boolean isInteresting(IPAAbstractStatement eq);

		/*
		 * @see com.ibm.wala.util.graph.Graph#removeNodeAndEdges(java.lang.Object)
		 */
		@Override
		public void removeNodeAndEdges(IPAPointsToSetVariable N) {
			Assertions.UNREACHABLE();
		}

		/*
		 * @see com.ibm.wala.util.graph.NodeManager#iterateNodes()
		 */
		@Override
		public Iterator<IPAPointsToSetVariable> iterator() {
			return getVariables();
		}

		/*
		 * @see com.ibm.wala.util.graph.NodeManager#getNumberOfNodes()
		 */
		@Override
		public int getNumberOfNodes() {
			return delegateGraph.getVarCount();
		}

		/*
		 * @see com.ibm.wala.util.graph.NodeManager#addNode(java.lang.Object)
		 */
		@Override
		public void addNode(IPAPointsToSetVariable n) {
			Assertions.UNREACHABLE();

		}

		/*
		 * @see com.ibm.wala.util.graph.NodeManager#removeNode(java.lang.Object)
		 */
		@Override
		public void removeNode(IPAPointsToSetVariable n) {
			Assertions.UNREACHABLE();

		}

		/*
		 * @see com.ibm.wala.util.graph.NodeManager#containsNode(java.lang.Object)
		 */
		@Override
		public boolean containsNode(IPAPointsToSetVariable N) {
			Assertions.UNREACHABLE();
			return false;
		}

		/*
		 * @see com.ibm.wala.util.graph.EdgeManager#getPredNodes(java.lang.Object)
		 */
		@Override
		public Iterator<IPAPointsToSetVariable> getPredNodes(IPAPointsToSetVariable v) {
			final Iterator eqs = getStatementsThatDef(v);
			return new Iterator<IPAPointsToSetVariable>() {
				IPAPointsToSetVariable nextResult;
				{
					advance();
				}

				@Override
				public boolean hasNext() {
					return nextResult != null;
				}

				@Override
				public IPAPointsToSetVariable next() {
					IPAPointsToSetVariable result = nextResult;
					advance();
					return result;
				}

				private void advance() {
					nextResult = null;
					while (eqs.hasNext() && nextResult == null) {
						IPAAbstractStatement eq = (IPAAbstractStatement) eqs.next();
						if (isInteresting(eq)) {
							nextResult = (IPAPointsToSetVariable) ((IPAUnaryStatement) eq).getRightHandSide();
						}
					}
				}

				@Override
				public void remove() {
					Assertions.UNREACHABLE();
				}
			};
		}

		/*
		 * @see com.ibm.wala.util.graph.EdgeManager#getPredNodeCount(java.lang.Object)
		 */
		@Override
		public int getPredNodeCount(IPAPointsToSetVariable v) {
			int result = 0;
			for (Iterator eqs = getStatementsThatDef(v); eqs.hasNext();) {
				IPAAbstractStatement eq = (IPAAbstractStatement) eqs.next();
				if (isInteresting(eq)) {
					result++;
				}
			}
			return result;
		}

		/*
		 * @see com.ibm.wala.util.graph.EdgeManager#getSuccNodes(java.lang.Object)
		 */
		@Override
		public Iterator<IPAPointsToSetVariable> getSuccNodes(IPAPointsToSetVariable v) {
			final Iterator eqs = getStatementsThatUse(v);
			return new Iterator<IPAPointsToSetVariable>() {
				IPAPointsToSetVariable nextResult;
				{
					advance();
				}

				@Override
				public boolean hasNext() {
					return nextResult != null;
				}

				@Override
				public IPAPointsToSetVariable next() {
					IPAPointsToSetVariable result = nextResult;
					advance();
					return result;
				}

				private void advance() {
					nextResult = null;
					while (eqs.hasNext() && nextResult == null) {
						IPAAbstractStatement eq = (IPAAbstractStatement) eqs.next();
						if (isInteresting(eq)) {
							nextResult = (IPAPointsToSetVariable) ((IPAUnaryStatement) eq).getLHS();
						}
					}
				}

				@Override
				public void remove() {
					// TODO Auto-generated method stub
					Assertions.UNREACHABLE();
				}
			};
		}

		/*
		 * @see com.ibm.wala.util.graph.EdgeManager#getSuccNodeCount(java.lang.Object)
		 */
		@Override
		public int getSuccNodeCount(IPAPointsToSetVariable v) {
			int result = 0;
			for (Iterator eqs = getStatementsThatUse(v); eqs.hasNext();) {
				IPAAbstractStatement eq = (IPAAbstractStatement) eqs.next();
				if (isInteresting(eq)) {
					result++;
				}
			}
			return result;
		}

		/*
		 * @see com.ibm.wala.util.graph.EdgeManager#addEdge(java.lang.Object, java.lang.Object)
		 */
		@Override
		public void addEdge(IPAPointsToSetVariable src, IPAPointsToSetVariable dst) {
			Assertions.UNREACHABLE();
		}

		/*
		 * @see com.ibm.wala.util.graph.EdgeManager#removeAllIncidentEdges(java.lang.Object)
		 */
		@Override
		public void removeAllIncidentEdges(IPAPointsToSetVariable node) {
			Assertions.UNREACHABLE();
		}

		/*
		 * @see com.ibm.wala.util.graph.AbstractGraph#getNodeManager()
		 */
		@Override
		@SuppressWarnings("unchecked")
		protected NumberedNodeManager getNodeManager() {
			return nodeManager;
		}

		/*
		 * @see com.ibm.wala.util.graph.AbstractGraph#getEdgeManager()
		 */
		@Override
		protected NumberedEdgeManager<IPAPointsToSetVariable> getEdgeManager() {
			Assertions.UNREACHABLE();
			return null;
		}
	}

	public String spaceReport() {
		StringBuffer result = new StringBuffer("IPAPropagationGraph\n");
		result.append("ImplicitEdges:" + countImplicitEdges() + "\n");
		// for (Iterator it = implicitUnaryMap.values().iterator(); it.hasNext(); )
		// {
		// result.append(it.next() + "\n");
		// }
		return result.toString();
	}

	private int countImplicitEdges() {
		int result = 0;
		for (Iterator it = new GlobalImplicitIterator(); it.hasNext();) {
			it.next();
			result++;
		}
		return result;
	}

}
