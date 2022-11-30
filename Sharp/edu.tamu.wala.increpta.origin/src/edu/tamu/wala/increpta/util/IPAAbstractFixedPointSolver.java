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

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;

import com.ibm.wala.fixpoint.FixedPointConstants;
import com.ibm.wala.fixpoint.IFixedPointSolver;
import com.ibm.wala.fixpoint.IVariable;
import com.ibm.wala.ipa.callgraph.propagation.LocalPointerKey;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.MonitorUtil;
import com.ibm.wala.util.MonitorUtil.IProgressMonitor;
import com.ibm.wala.util.debug.VerboseAction;
import com.ibm.wala.util.perf.Stopwatch;

import edu.tamu.wala.increpta.callgraph.impl.IPAExplicitCallGraph.IPAExplicitNode;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPointsToSetVariable;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationCallGraphBuilder.IPAFilterOperator;
import edu.tamu.wala.increpta.operators.IPAAbstractOperator;
import edu.tamu.wala.increpta.operators.IPAAbstractStatement;
import edu.tamu.wala.increpta.operators.IPAAssignOperator;
import edu.tamu.wala.increpta.operators.IPABasicUnaryStatement;
import edu.tamu.wala.increpta.operators.IPAGeneralStatement;
import edu.tamu.wala.increpta.operators.IPAUnaryOperator;
import edu.tamu.wala.increpta.operators.IPAUnaryStatement;
import edu.tamu.wala.increpta.util.intset.IPAMutableSharedBitVectorIntSet;

public abstract class IPAAbstractFixedPointSolver<T extends IVariable<T>> implements IFixedPointSolver<T>, FixedPointConstants, VerboseAction {
	
	public static boolean DEBUG = false;

	static public final boolean verbose = false; // "true".equals(System.getProperty("com.ibm.wala.fixedpoint.impl.verbose"));

	static public final int DEFAULT_VERBOSE_INTERVAL = 100000;

	static final boolean MORE_VERBOSE = true;

	static public final int DEFAULT_PERIODIC_MAINTENANCE_INTERVAL = 100000;

	/**
	 * A tuning parameter; how may new IStatementDefinitionss must be added before doing a new topological sort? TODO: Tune this
	 * empirically.
	 */
	private int minSizeForTopSort = 0;

	/**
	 * A tuning parameter; by what percentage must the number of equations grow before we perform a topological sort?
	 */
	private double topologicalGrowthFactor = 0.1;

	/**
	 * A tuning parameter: how many evaluations are allowed to take place between topological re-orderings. The idea is that many
	 * evaluations may be a sign of a bad ordering, even when few new equations are being added.
	 *
	 * A number less than zero mean infinite.
	 */
	private int maxEvalBetweenTopo = 500000;

	private int evaluationsAtLastOrdering = 0;

	/**
	 * How many equations have been added since the last topological sort?
	 */
	int topologicalCounter = 0;

	/**
	 * The next order number to assign to a new equation
	 */
	protected int nextOrderNumber = 1;

	/**
	 * During verbose evaluation, holds the number of dataflow equations evaluated
	 */
	private int nEvaluated = 0;

	/**
	 * During verbose evaluation, holds the number of dataflow equations created
	 */
	private int nCreated = 0;

	/**
	 * for d4 plugin
	 */
	public boolean updatechange = false;
	public void setUpdateChange(boolean p){
		updatechange = p;
	}
	
	/**
	 * bz: flags
	 */
	public boolean isChange = false;
	public void setChange(boolean p){
		isChange = p;
	}

	/**
	 * bz: if this is the deleted stmt from commit/change
	 */
	private boolean isFirstDelete = true;
	public void setFirstDel(boolean isFirstDelete){ //--- do  delete statements
		this.isFirstDelete = isFirstDelete;
	}
	public boolean getFirstDel(){
		return this.isFirstDelete;
	}
	
	/**
	 * bz: is using k-cfa (SHARP): ?
	 */
	public boolean kcfa = false;
	public void setKCFA(boolean p){
		kcfa = p;
	}
	
	/**
	 * bz: is using k-obj (SHARP): ?
	 */
	public boolean kobj = false;
	public void setKObj(boolean p){
		kobj = p;
	}

	/**
	 * ONLY FOR ADDITION STMT (SHARP): to store the stmts created inside a cgnode
	 */
	public HashSet<IPAAbstractStatement> collectedStmts = new HashSet<>();

	/**
	 * ONLY FOR DELETION STMT (SHARP):
	 * to remove relative stmts from curCGNode for context-sensitive
	 */
	public IPAExplicitNode curCGNode = null;
	public void setCurCGNode(IPAExplicitNode node) {
		this.curCGNode = node;
	}

	/**
	 * bz: record changed points-to sets
	 */
	public static HashSet<IVariable> changes = new HashSet<IVariable>();
	public void clearChanges(){
		changes.clear();
	}

	public static void addToChanges(IPAPointsToSetVariable tar){
		synchronized(changes){
			if(!changes.contains(tar))
				changes.add(tar);
		}
	}

	public static void addSetToChanges(HashSet<IPAPointsToSetVariable> delta){
		synchronized(changes){
			changes.addAll(delta);
		}
	}

	/**
	 * worklist for the iterative solver
	 */
	protected IPAWorklist workList = new IPAWorklist();

	/**
	 * A boolean which is initially true, but set to false after the first call to solve();
	 */
	private boolean firstSolve = true;

	protected abstract T[] makeStmtRHS(int size);

	/**
	 * Some setup which occurs only before the first solve
	 */
	public void initForFirstSolve() {
		orderStatements();
		initializeVariables();
		initializeWorkList();
		firstSolve = false;
	}

	/**
	 * @return true iff work list is empty
	 */
	public boolean emptyWorkList() {
		return workList.isEmpty();
	}

	//for DEBUG
	static int i = 0;
	static int j = 1;
	
	private static Stopwatch watch = new Stopwatch();

	/**
	 * Solve the set of dataflow graph.
	 * <p>
	 * PRECONDITION: graph is set up
	 *
	 * @return true iff the evaluation of some equation caused a change in the value of some variable.
	 */
	@Override
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public boolean solve(IProgressMonitor monitor) throws CancelException {

		boolean globalChange = false;
		watch.start();

		if (firstSolve) {
			initForFirstSolve();
		}

//		if(DEBUG) {
//			System.out.println("\n==> " + workList.size());
//		}else {
//			System.out.println("==> " + workList.size());
//		}
		
//		int tmp = workList.size();
//		System.out.println("> worklist size: " + tmp);
		
		while (!workList.isEmpty()) {
			MonitorUtil.throwExceptionIfCanceled(monitor);
			if(DEBUG) 
				System.out.print(" /ordering ... " + workList.size());
			orderStatements();
			if(DEBUG) 
				System.out.print("/ ");

			// duplicate insertion detection
			IPAAbstractStatement s = workList.takeStatement();
//			if(s.toString().contains("[Node: < Application, Lorg/elasticsearch/common/inject/ModulesBuilder, add([Lorg/elasticsearch/common/inject/Module;)Lorg/elasticsearch/common/inject/ModulesBuilder; > Context: ThreadContext: [Fake Main Thread], v2]")
//					|| s.toString().contains("[SITE_IN_NODE{< Application, Lorg/elasticsearch/node/Node, <init>(Lorg/elasticsearch/env/Environment;Ljava/util/Collection;Z)V >:NEW <Application,[Lorg/elasticsearch/common/inject/Module>@2634 in ThreadContext: [Fake Main Thread]}[]]")
//					|| s.toString().contains("[Node: < Application, Lorg/elasticsearch/node/Node, <init>(Lorg/elasticsearch/env/Environment;Ljava/util/Collection;Z)V > Context: ThreadContext: [Fake Main Thread], v779]")
//					|| s.toString().contains("[Node: < Application, Lorg/elasticsearch/node/Node, <init>(Lorg/elasticsearch/env/Environment;Ljava/util/Collection;Z)V > Context: ThreadContext: [Fake Main Thread], v777]")
//					|| s.toString().contains("[Node: < Application, Lorg/elasticsearch/common/inject/ModulesBuilder, add([Lorg/elasticsearch/common/inject/Module;)Lorg/elasticsearch/common/inject/ModulesBuilder; > Context: ThreadContext: [Fake Main Thread], v4]")
//					|| s.toString().contains("[Node: < Primordial, Ljava/util/Collections, addAll(Ljava/util/Collection;[Ljava/lang/Object;)Z > Context: ThreadContext: [Fake Main Thread], v6]")
////					|| s.toString().contains("")
//					)
//				System.out.println(" ** " + s.toString());
			
			byte code = s.evaluate();
			if(DEBUG) {
				System.out.print(++i + " ");
				if(i >= 50*j) {
					System.out.print("\n");
					j++;
				}
			}
			nEvaluated++;
			if (verbose) {
				if (nEvaluated % getVerboseInterval() == 0) {
					performVerboseAction();
				}
				if (nEvaluated % getPeriodicMaintainInterval() == 0) {
					periodicMaintenance();
				}

			}
			if (isChanged(code)) {
				globalChange = true;
				int size = workList.size();
				if(DEBUG) {
					System.out.print(" |wl ... ");
				}
				updateWorkList(s);
				if(DEBUG) {
					System.out.print(workList.size() - size + "| ");
				}
			}
		}
		if(DEBUG) {
			System.out.print("\n");
			i = 0; j = 1;//restore
		}
//		watch.stop();
//		if(tmp > 0)
//			System.out.println("--> total solving time: " + watch.getElapsedMillis() + "ms; avg. solving time: " + ((long) watch.getElapsedMillis()/tmp));

		return globalChange;
	}

	public boolean checkSelfRecursive(IPAAbstractStatement s){
		IVariable lhs = s.getLHS();
		if(lhs != null){
			if(s instanceof IPAUnaryStatement){
				int lhs_id = lhs.getGraphNodeId();
				int rhs_id = ((IPAUnaryStatement) s).getRightHandSide().getGraphNodeId();
				if(lhs_id == rhs_id){
					//bz: **probably from phi instruction => lhs == rhs
					//propagation following this edge will generate wrong result
					//do not consider it in this adjlist
					return true;
				}
			}
		}
		return false;
	}


	@SuppressWarnings("unchecked")
	public boolean solveDel(IProgressMonitor monitor) throws CancelException {
		boolean globalChange = false;

		while (!workList.isEmpty()) {
			MonitorUtil.throwExceptionIfCanceled(monitor);
			orderStatements();

			// duplicate insertion detection
			IPAAbstractStatement s = workList.takeStatement();
			if(checkSelfRecursive(s))
				continue;
			
//			if(s.toString().contains("GetField field my_field,[Node: <src-method: < Source, Lloop/MyThread, run()V >> Context: ThreadContext: [LoopID:1, <Lloop/TestThreadLoop, main>; ThreadID:0, <Lcom/ibm/wala/FakeRootClass, fakeRootMethod>], v22] [Node: <src-method: < Source, Lloop/MyThread, run()V >> Context: ThreadContext: [LoopID:1, <Lloop/TestThreadLoop, main>; ThreadID:0, <Lcom/ibm/wala/FakeRootClass, fakeRootMethod>], v1]"))
//				System.out.println( );
			
			byte code = s.evaluateDel();
			if (verbose) {
				nEvaluated++;
				if (nEvaluated % getVerboseInterval() == 0) {
					performVerboseAction();
				}
				if (nEvaluated % getPeriodicMaintainInterval() == 0) {
					periodicMaintenance();
				}
			}
			if (isChanged(code)) {
				if(isChange){
					IVariable lhs = s.getLHS();
					if(lhs != null){
						if(!changes.contains(lhs))
							changes.add(s.getLHS());
					}
					updateWorkList(s);
				}
				globalChange = true;
			}
		}
		return globalChange;
	}


	@SuppressWarnings("unchecked")
	public boolean solveAdd(IProgressMonitor monitor) throws CancelException {
		boolean globalChange = false;
		while (!workList.isEmpty()) {
			MonitorUtil.throwExceptionIfCanceled(monitor);
			orderStatements();

			// duplicate insertion detection
			IPAAbstractStatement s = workList.takeStatement();
			
//			if(s.toString().contains("null Dispatch to 36 = invokevirtual < Source, Ljava/lang/Thread, getId()J > 37 @29 exception:39"))
//				System.out.println(s.toString());
			
			byte code = s.evaluate();
			if (verbose) {
				nEvaluated++;
				if (nEvaluated % getVerboseInterval() == 0) {
					performVerboseAction();
				}
				if (nEvaluated % getPeriodicMaintainInterval() == 0) {
					periodicMaintenance();
				}

			}
			if (isChanged(code)) {
				if(isChange){
					IVariable lhs = s.getLHS();
					if(lhs != null){
						if(!changes.contains(lhs))
							changes.add(s.getLHS());
					}
					updateWorkList(s);
				}
				globalChange = true;
			}
		}
		return globalChange;
	}


	@Override
	public void performVerboseAction() {
		System.err.println("Evaluated " + nEvaluated);
		System.err.println("Created   " + nCreated);
		System.err.println("Worklist  " + workList.size());
		if (MORE_VERBOSE) {
			if (!workList.isEmpty()) {
				IPAAbstractStatement s = workList.takeStatement();
				System.err.println("Peek      " + lineBreak(s.toString(), 132));
				if (s instanceof VerboseAction) {
					((VerboseAction) s).performVerboseAction();
				}
				workList.insertStatement(s);
			}
		}
	}

	public static String lineBreak(String string, int wrap) {
		if (string == null) {
			throw new IllegalArgumentException("string is null");
		}
		if (string.length() > wrap) {
			StringBuffer result = new StringBuffer();
			int start = 0;
			while (start < string.length()) {
				int end = Math.min(start + wrap, string.length());
				result.append(string.substring(start, end));
				result.append("\n  ");
				start = end;
			}
			return result.toString();
		}
		return string;
	}

	public void removeStatement(IPAAbstractStatement<T, ?> s) {
		getFixedPointSystem().removeStatement(s);
	}

	@Override
	public String toString() {
		StringBuffer result = new StringBuffer("Fixed Point System:\n");
		for (Iterator it = getStatements(); it.hasNext();) {
			result.append(it.next()).append("\n");
		}
		return result.toString();
	}

	public Iterator getStatements() {
		return getFixedPointSystem().getStatements();
	}

	/**
	 * Add a step to the work list.
	 *
	 * @param s the step to add
	 */
	public void addToWorkList(IPAAbstractStatement s) {
		workList.insertStatement(s);
	}

	/**bz: sync*/
	public void addToWorkListSync(IPAAbstractStatement s) {
		synchronized (workList) {
			workList.insertStatement(s);
		}
	}

	/**
	 * Add all to the work list.
	 */
	public void addAllStatementsToWorkList() {
		for (Iterator i = getStatements(); i.hasNext();) {
			IPAAbstractStatement eq = (IPAAbstractStatement) i.next();
			addToWorkList(eq);
		}
	}

	/**
	 * Call this method when the contents of a variable changes. This routine adds all graph using this variable to the set of new
	 * graph.
	 *
	 * @param v the variable that has changed
	 */
	public void changedVariable(T v) {
		for (Iterator it = getFixedPointSystem().getStatementsThatUse(v); it.hasNext();) {
			IPAAbstractStatement s = (IPAAbstractStatement) it.next();
//			if(PATH_SENSITIVE) {
//				//should only process static/instance field, array, 
//				//and local variable that are from callee method after rhs ...
//				//assume call graph has no loop...
//				//however, cannot implement due to multiple caller methods share the same copy of callee method.
//				IPAAbstractOperator op = s.getOperator();
//				if(op instanceof IPAAssignOperator || op instanceof IPAFilterOperator) {
//					IPAPointsToSetVariable lhs = (IPAPointsToSetVariable) s.getLHS();
//					if(lhs.getPointerKey() instanceof LocalPointerKey) {
//						System.out.println("    SKIP  -- " + s.toString());
//						continue;
//					} 
//				}
//			}
			addToWorkList(s);
		}
	}

	/**
	 * Add a step with zero operands on the right-hand side.
	 *
	 * TODO: this is a little odd, in that this equation will never fire unless explicitly added to a work list. I think in most cases
	 * we shouldn't be creating this nullary form.
	 *
	 * @param lhs the variable set by this equation
	 * @param operator the step operator
	 * @throws IllegalArgumentException if lhs is null
	 */
	//	public boolean newStatement(final T lhs, final NullaryOperator<T> operator, final boolean toWorkList, final boolean eager) {
	//		if (lhs == null) {
	//			throw new IllegalArgumentException("lhs is null");
	//		}
	//		// add to the list of graph
	//		lhs.setOrderNumber(nextOrderNumber++);
	//		final NullaryStatement<T> s = new BasicNullaryStatement<>(lhs, operator);
	//		if (getFixedPointSystem().containsStatement(s)) {
	//			return false;
	//		}
	//		nCreated++;
	//		getFixedPointSystem().addStatement(s);
	//		incorporateNewStatement(toWorkList, eager, s);
	//		topologicalCounter++;
	//		return true;
	//	}


	public void incorporateNewStatement(IPAAbstractStatement s){
		incorporateNewStatement(true, true, s);
	}


	@SuppressWarnings("unchecked")
	private void incorporateNewStatement(boolean toWorkList, boolean eager, IPAAbstractStatement s) {
		if (eager) {
			//			System.out.println("  " + s.toString());
			byte code = s.evaluate();
			if (verbose) {
				nEvaluated++;
				if (nEvaluated % getVerboseInterval() == 0) {
					performVerboseAction();
				}
				if (nEvaluated % getPeriodicMaintainInterval() == 0) {
					periodicMaintenance();
				}
			}
			if (isChanged(code)) {
				updateWorkList(s);
			}
//			if(PATH_SENSITIVE) {
//				if(workList.size() > 0) {
//					System.out.println(" > Clean Solver: > worklist size: " + workList.size());
//					//bz: ???? solve all related constraints until this point.
//					try {
//						solve(null);
//					} catch (CancelException e) {
//						e.printStackTrace();
//					}
//				}
//			}
			
			//			if (isFixed(code)) {
			//				removeStatement(s);
			//			}
		} else if (toWorkList) {
			addToWorkList(s);
		}
	}
	
	@SuppressWarnings("unchecked")
	protected boolean incorporateNewStatements(IPAPointsToSetVariable L, IPAMutableSharedBitVectorIntSet diff, boolean toWorkList, boolean eager) {
		if (eager) {
			//			System.out.println("  " + s.toString());
			boolean changed = L.addAll(diff);
			if (verbose) {
				nEvaluated++;
				if (nEvaluated % getVerboseInterval() == 0) {
					performVerboseAction();
				}
				if (nEvaluated % getPeriodicMaintainInterval() == 0) {
					periodicMaintenance();
				}
			}
			if (changed) {
				changedVariable((T) L);
			}
			return changed;
		}
		return false;
	}


	public void incorporateDelStatement(IPAAbstractStatement s){
		incorporateDelStatement(true, true, s);
	}

	/**
	 * bz: reverse
	 * @param toWorkList
	 * @param eager
	 * @param delS
	 */
	@SuppressWarnings("unchecked")
	private void incorporateDelStatement(boolean toWorkList, boolean eager, IPAAbstractStatement delS) {
		if (eager) {
			if(checkSelfRecursive(delS))
				return;
			byte code = delS.evaluateDel();
			if(isChanged(code)){
				IVariable lhs = delS.getLHS();
				if(lhs != null){
					if(!changes.contains(lhs))
						changes.add(delS.getLHS());
				}
				updateWorkList(delS);
			}
		}
	}

	/**
	 * Add a step with one operand on the right-hand side.
	 *
	 * @param lhs the lattice variable set by this equation
	 * @param operator the step's operator
	 * @param rhs first operand on the rhs
	 * @return true iff the system changes
	 * @throws IllegalArgumentException if operator is null
	 */
	public boolean newStatement(T lhs, IPAUnaryOperator<T> operator, T rhs, boolean toWorkList, boolean eager) {
		if (operator == null) {
			throw new IllegalArgumentException("operator is null");
		}
		
		IPAUnaryStatement<T> s = operator.makeEquation(lhs, rhs);
		// add to the list of graph
		if (getFixedPointSystem().containsStatement(s)) {
			return false;//should not contain a new statement
		}
		if (lhs != null) {
			lhs.setOrderNumber(nextOrderNumber++);
		}
		nCreated++;
		getFixedPointSystem().addStatement(s);
		incorporateNewStatement(toWorkList, eager, s);
		topologicalCounter++;
		return true;
	}

	public boolean newStatementChange(T lhs, IPAUnaryOperator<T> operator, T rhs, boolean toWorkList, boolean eager) {
		if (operator == null) {
			throw new IllegalArgumentException("operator is null");
		}

		// add to the list of graph
		IPAUnaryStatement<T> s = operator.makeEquation(lhs, rhs);
		if (getFixedPointSystem().containsStatement(s)) {
			return false;//should not contain a new statement
		}
		
		if (lhs != null) {
			if(lhs.getOrderNumber() == -1)
				lhs.setOrderNumber(nextOrderNumber++);
		}
		
		getFixedPointSystem().addStatement(s);
		nCreated++;
//		System.out.println("ADD "  + s.toString());
		incorporateNewStatement(toWorkList, eager, s);
		topologicalCounter++;
		return true;
	}

	/**
	 * only add stmt to flowgraph, no evaluation/propagation
	 * @param lhs
	 * @param operator
	 * @param rhs
	 * @return
	 */
	public boolean newStatementOnly(T lhs, IPAUnaryOperator<T> operator, T rhs) {
		if (operator == null) {
			throw new IllegalArgumentException("operator is null");
		}
		// add to the list of graph
		IPAUnaryStatement<T> s = operator.makeEquation(lhs, rhs);
		if (getFixedPointSystem().containsStatement(s)) {
			return false;//should not contain a new statement
		}

		if (lhs != null) {
			if(lhs.getOrderNumber() == -1)
				lhs.setOrderNumber(nextOrderNumber++);
		}
		nCreated++;
		getFixedPointSystem().addStatement(s);
		topologicalCounter++;
		return true;
	}

	/**
	 * only remove stmt from flow graph, do not propagate
	 * @param lhs
	 * @param operator
	 * @param rhs
	 */
	public boolean delStatementOnly(T lhs, IPAUnaryOperator<T> operator, T rhs) {
		if (operator == null) {
			throw new IllegalArgumentException("operator is null");
		}
		IPAUnaryStatement<T> s = operator.makeEquation(lhs, rhs);
		if (!getFixedPointSystem().containsStatement(s)) {
			return false;
		}
		if(isFirstDelete){
			getFixedPointSystem().removeStatement(s);
			return true;
		}
		return false;
	}

	/**
	 * only remove stmt from flow graph, do not propagate
	 * @param lhs
	 * @param operator
	 * @param rhs
	 */
	public boolean delStatementOnly(T lhs, IPAAbstractOperator<T> operator, T[] rhs) {
		IPAGeneralStatement<T> s = new Statement(lhs, operator, rhs);
		if (!getFixedPointSystem().containsStatement(s)) {
			return false;
		}
		if(isFirstDelete){
			getFixedPointSystem().removeStatement(s);
			return true;
		}
		return false;
	}


	/**
	 * bz: reverse
	 * @param lhs
	 * @param operator
	 * @param rhs
	 * @param toWorkList
	 * @param eager
	 * @return
	 */
	public boolean delStatement(T lhs, IPAUnaryOperator<T> operator, T rhs, boolean toWorkList, boolean eager) {
		if (operator == null) {
			throw new IllegalArgumentException("operator is null");
		}
		// add to the list of graph
		IPAUnaryStatement<T> s = operator.makeEquation(lhs, rhs);
		if (!getFixedPointSystem().containsStatement(s)) {
//			System.out.println("return " + s.toString());
			return false;
		}

		if(isFirstDelete){
			getFixedPointSystem().removeStatement(s);
//			System.out.println("REMOVE " + s.toString());
		}

		incorporateDelStatement(toWorkList, eager, s);
		return true;
	}

	/**
	 * bz: reverse
	 * @param lhs
	 * @param operator
	 * @param rhs
	 * @param toWorkList
	 * @param eager
	 * @return
	 */
	public boolean delStatement(T lhs, IPAAbstractOperator<T> operator, T[] rhs, boolean toWorkList, boolean eager) {
		if (operator == null) {
			throw new IllegalArgumentException("operator is null");
		}
		IPAGeneralStatement<T> s = new Statement(lhs, operator, rhs);
		if (!getFixedPointSystem().containsStatement(s)) {
//			System.out.println("return* " + s.toString());
			return false;
		}

		if(isFirstDelete){
			getFixedPointSystem().removeStatement(s);
//			System.out.println("REMOVE " + s.toString());
		}
		incorporateDelStatement(toWorkList, eager, s);
		return true;
	}

	protected class Statement extends IPAGeneralStatement<T> {

		public Statement(T lhs, IPAAbstractOperator<T> operator, T op1, T op2, T op3) {
			super(lhs, operator, op1, op2, op3);
		}

		public Statement(T lhs, IPAAbstractOperator<T> operator, T op1, T op2) {
			super(lhs, operator, op1, op2);
		}

		public Statement(T lhs, IPAAbstractOperator<T> operator, T[] rhs) {
			super(lhs, operator, rhs);
		}

		public Statement(T lhs, IPAAbstractOperator<T> operator) {
			super(lhs, operator);
		}

		@Override
		protected T[] makeRHS(int size) {
			return makeStmtRHS(size);
		}

	}

	/**
	 * Add an equation with two operands on the right-hand side.
	 *
	 * @param lhs the lattice variable set by this equation
	 * @param operator the equation operator
	 * @param op1 first operand on the rhs
	 * @param op2 second operand on the rhs
	 */
	public boolean newStatement(T lhs, IPAAbstractOperator<T> operator, T op1, T op2, boolean toWorkList, boolean eager) {
		// add to the list of graph
		IPAGeneralStatement<T> s = new Statement(lhs, operator, op1, op2);
		if (getFixedPointSystem().containsStatement(s)) {
			return false;
		}
		if (lhs != null) {
			lhs.setOrderNumber(nextOrderNumber++);
		}
		nCreated++;
		getFixedPointSystem().addStatement(s);
		incorporateNewStatement(toWorkList, eager, s);
		topologicalCounter++;
		return true;
	}

	/**
	 * Add a step with three operands on the right-hand side.
	 *
	 * @param lhs the lattice variable set by this equation
	 * @param operator the equation operator
	 * @param op1 first operand on the rhs
	 * @param op2 second operand on the rhs
	 * @param op3 third operand on the rhs
	 * @throws IllegalArgumentException if lhs is null
	 */
	public boolean newStatement(T lhs, IPAAbstractOperator<T> operator, T op1, T op2, T op3, boolean toWorkList, boolean eager) {
		if (lhs == null) {
			throw new IllegalArgumentException("lhs is null");
		}
		// add to the list of graph
		lhs.setOrderNumber(nextOrderNumber++);
		IPAGeneralStatement<T> s = new Statement(lhs, operator, op1, op2, op3);
		if (getFixedPointSystem().containsStatement(s)) {
			nextOrderNumber--;
			return false;
		}
		nCreated++;
		getFixedPointSystem().addStatement(s);

		incorporateNewStatement(toWorkList, eager, s);
		topologicalCounter++;
		return true;
	}

	/**
	 * Add a step to the system with an arbitrary number of operands on the right-hand side.
	 *
	 * @param lhs lattice variable set by this equation
	 * @param operator the operator
	 * @param rhs the operands on the rhs
	 */
	public boolean newStatement(T lhs, IPAAbstractOperator<T> operator, T[] rhs, boolean toWorkList, boolean eager) {
		// add to the list of graph
		if (lhs != null)
			lhs.setOrderNumber(nextOrderNumber++);
		IPAGeneralStatement<T> s = new Statement(lhs, operator, rhs);
		if (getFixedPointSystem().containsStatement(s)) {
			nextOrderNumber--;
			return false;
		}
		nCreated++;
		getFixedPointSystem().addStatement(s);
		incorporateNewStatement(toWorkList, eager, s);
		topologicalCounter++;
		return true;
	}

	public boolean newStatementChange(T lhs, IPAAbstractOperator<T> operator, T[] rhs, boolean toWorkList, boolean eager) {
		// add to the list of graph
		if (lhs != null) {
			if(lhs.getOrderNumber() == -1)
				lhs.setOrderNumber(nextOrderNumber++);
		}
		
		IPAGeneralStatement<T> s = new Statement(lhs, operator, rhs);
//		if(!updatechange){//for plugin : converthandler
//			if (!getFixedPointSystem().containsStatement(s)) {
//				getFixedPointSystem().addStatement(s);
//				nCreated++;
//			}
//		}
		if (getFixedPointSystem().containsStatement(s)) {
			nextOrderNumber--;
			return false;
		}
		nCreated++;
		getFixedPointSystem().addStatement(s);
		incorporateNewStatement(toWorkList, eager, s);
		topologicalCounter++;
		return true;
	}

	/**
	 * Initialize all lattice vars in the system.
	 */
	abstract protected void initializeVariables();

	/**
	 * Initialize the work list for iteration.j
	 */
	abstract protected void initializeWorkList();

	/**
	 * Update the worklist, assuming that a particular equation has been re-evaluated
	 *
	 * @param s the equation that has been re-evaluated.
	 */
	private void updateWorkList(IPAAbstractStatement<T, ?> s) {
		// find each equation which uses this lattice cell, and
		// add it to the work list
		T v = s.getLHS();
		if (v == null) {
			return;
		}
		changedVariable(v);
	}

	/**
	 * Number the graph in topological order.
	 */
	private void orderStatementsInternal() {
		if (verbose) {
			if (nEvaluated > 0) {
				System.err.println("Reorder " + nEvaluated + " " + nCreated);
			}
		}
		reorder();
		if (verbose) {
			if (nEvaluated > 0) {
				System.err.println("Reorder finished " + nEvaluated + " " + nCreated);
			}
		}
		topologicalCounter = 0;
		evaluationsAtLastOrdering = nEvaluated;
	}

	/**
	 *
	 */
	public void orderStatements() {

		if (nextOrderNumber > minSizeForTopSort) {
			if (((double) topologicalCounter / (double) nextOrderNumber) > topologicalGrowthFactor) {
				orderStatementsInternal();
				return;
			}
		}

		if ((nEvaluated - evaluationsAtLastOrdering) > maxEvalBetweenTopo) {
			orderStatementsInternal();
			return;
		}
	}

	/**
	 * Re-order the step definitions.
	 */
	private void reorder() {
		// drain the worklist
		LinkedList<IPAAbstractStatement> temp = new LinkedList<>();
		while (!workList.isEmpty()) {
			IPAAbstractStatement eq = workList.takeStatement();
			temp.add(eq);
		}
		workList = new IPAWorklist();

		// compute new ordering
		getFixedPointSystem().reorder();

		// re-populate worklist
		for (Iterator<IPAAbstractStatement> it = temp.iterator(); it.hasNext();) {
			IPAAbstractStatement s = it.next();
			workList.insertStatementWithoutCheck(s);//bz: no need to check
		}
	}

	public static boolean isChanged(byte code) {
		return (code & CHANGED_MASK) != 0;
	}

	public static boolean isSideEffect(byte code) {
		return (code & SIDE_EFFECT_MASK) != 0;
	}

	//	public static boolean isFixed(byte code) {
	//		return (code & FIXED_MASK) != 0;
	//	}

	public int getMinSizeForTopSort() {
		return minSizeForTopSort;
	}

	/**
	 * @param i
	 */
	public void setMinEquationsForTopSort(int i) {
		minSizeForTopSort = i;
	}

	public int getMaxEvalBetweenTopo() {
		return maxEvalBetweenTopo;
	}

	public double getTopologicalGrowthFactor() {
		return topologicalGrowthFactor;
	}

	/**
	 * @param i
	 */
	public void setMaxEvalBetweenTopo(int i) {
		maxEvalBetweenTopo = i;
	}

	/**
	 * @param d
	 */
	public void setTopologicalGrowthFactor(double d) {
		topologicalGrowthFactor = d;
	}

	public int getNumberOfEvaluations() {
		return nEvaluated;
	}

	public void incNumberOfEvaluations() {
		nEvaluated++;
	}

	/**
	 * a method that will be called every N evaluations. subclasses should override as desired.
	 */
	protected void periodicMaintenance() {
	}

	/**
	 * subclasses should override as desired.
	 */
	protected int getVerboseInterval() {
		return DEFAULT_VERBOSE_INTERVAL;
	}

	/**
	 * subclasses should override as desired.
	 */
	protected int getPeriodicMaintainInterval() {
		return DEFAULT_PERIODIC_MAINTENANCE_INTERVAL;
	}
}
