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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import com.ibm.wala.classLoader.ArrayClass;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.fixpoint.IFixedPointSystem;
import com.ibm.wala.fixpoint.IVariable;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKeyFactory;
import com.ibm.wala.ipa.callgraph.propagation.LocalPointerKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyWarning;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.Iterator2Collection;
import com.ibm.wala.util.collections.MapUtil;
import com.ibm.wala.util.debug.Assertions;
import com.ibm.wala.util.debug.VerboseAction;
import com.ibm.wala.util.graph.Graph;
import com.ibm.wala.util.graph.NumberedGraph;
import com.ibm.wala.util.heapTrace.HeapTracer;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetAction;
import com.ibm.wala.util.intset.IntSetUtil;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.MutableMapping;
import com.ibm.wala.util.intset.MutableSharedBitVectorIntSet;
import com.ibm.wala.util.intset.MutableSharedBitVectorIntSetFactory;
import com.ibm.wala.util.ref.ReferenceCleanser;
import com.ibm.wala.util.warnings.Warnings;

import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationCallGraphBuilder.IPAFilterOperator;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationCallGraphBuilder.PutFieldOperator;
import edu.tamu.wala.increpta.operators.IPAAbstractOperator;
import edu.tamu.wala.increpta.operators.IPAAbstractStatement;
import edu.tamu.wala.increpta.operators.IPAAssignEquation;
import edu.tamu.wala.increpta.operators.IPAAssignOperator;
import edu.tamu.wala.increpta.operators.IPAUnaryOperator;
import edu.tamu.wala.increpta.operators.IPAUnarySideEffect;
import edu.tamu.wala.increpta.operators.IPAUnaryStatement;
import edu.tamu.wala.increpta.parallel.ThreadHub;
import edu.tamu.wala.increpta.pointerkey.IPAFilteredPointerKey;
import edu.tamu.wala.increpta.pointerkey.IPAPointerKeyFactory;
import edu.tamu.wala.increpta.scc.SCCEngine;
import edu.tamu.wala.increpta.scc.SCCVariable;
import edu.tamu.wala.increpta.util.DeletionUtil;
import edu.tamu.wala.increpta.util.IPADefaultFixedPointSolver;
import edu.tamu.wala.increpta.util.Worklist;


/**
 * System of constraints that define propagation for call graph construction
 */
public class IPAPropagationSystem extends IPADefaultFixedPointSolver<IPAPointsToSetVariable> {

	private final static boolean DEBUG = false;

	private final static boolean DEBUG_MEMORY = false;

	private static int DEBUG_MEM_COUNTER = 0;

	private final static int DEBUG_MEM_INTERVAL = 5;

	/**
	 * object that tracks points-to sets
	 */
	protected final IPAPointsToMap pointsToMap = new IPAPointsToMap();

	/**
	 * Implementation of the underlying dataflow graph
	 */
	private final IPAPropagationGraph flowGraph = new IPAPropagationGraph();

	/**
	 * bijection from InstanceKey &lt;=&gt; Integer
	 */
	protected final MutableMapping<InstanceKey> instanceKeys = MutableMapping.make();

	/**
	 * A mapping from IClass -> MutableSharedBitVectorIntSet The range represents the instance keys that correspond to a given class.
	 * This mapping is used to filter sets based on declared types; e.g., in cast constraints
	 */
	final private Map<IClass, MutableIntSet> class2InstanceKey = HashMapFactory.make();

	/**
	 * An abstraction of the pointer analysis result
	 */
	private PointerAnalysis<InstanceKey> pointerAnalysis;

	/**
	 * Meta-data regarding how pointers are modelled.
	 */
	private final IPAPointerKeyFactory pointerKeyFactory;

	/**
	 * Meta-data regarding how instances are modelled.
	 */
	private final InstanceKeyFactory instanceKeyFactory;

	/**
	 * When doing unification, we must also updated the fixed sets in unary side effects.
	 *
	 * This maintains a map from IPAPointsToSetVariable -> Set<UnarySideEffect>
	 */
	final private Map<IPAPointsToSetVariable, Set<IPAUnarySideEffect>> fixedSetMap = HashMapFactory.make();

	/**
	 * Governing call graph;
	 */
	protected final CallGraph cg;

	private int verboseInterval = DEFAULT_VERBOSE_INTERVAL;

	private int periodicMaintainInterval = DEFAULT_PERIODIC_MAINTENANCE_INTERVAL;


	/**bz: use akka or a thread pool
	 * false: use the thread pool
	 * true: use akka
	 */
	public static boolean useAkka = false;

	/**
	 * nrOfWorkers: number of threads to do parallel work
	 */
	// public ActorSystem akkaSys;
	// public ActorRef hub;
	/**
	 * bz: thread pool system
	 */
	public ThreadHub threadHub;

	////bz: scc
	public SCCEngine sccEngine;

	/**
	 * run after the whole program analysis
	 */
	public void createSCCEngine(){
		sccEngine = new SCCEngine(this, flowGraph);
		flowGraph.setSCCEngine(sccEngine);
	}

    //////////

	public IPAPropagationSystem(CallGraph cg, IPAPointerKeyFactory pointerKeyFactory, InstanceKeyFactory instanceKeyFactory) {
		if (cg == null) {
			throw new IllegalArgumentException("null cg");
		}
		this.cg = cg;
		this.pointerKeyFactory = pointerKeyFactory;
		this.instanceKeyFactory = instanceKeyFactory;
		// when doing paranoid checking of points-to sets, code in IPAPointsToSetVariable needs to know about the instance key
		// mapping
		if (IPAPointsToSetVariable.PARANOID) {
			IPAPointsToSetVariable.instanceKeys = instanceKeys;
		}
	}

	/**bz:
	 * initialze parallel system
	 * parallel when nrOfWorkers > 1
	 * false: use the thread pool
	 * true: use akka
	 * @param i
	 */
	public void initialParallelSystem(boolean useAkka, int nrOfWorkers){
		IPAPropagationSystem.useAkka = useAkka;
		//bz: parallel system
		if(useAkka){
			//bz: initialize akka system
			// startAkkaSys();
		}else{
			//bz: initialize the WorkStealingPool
			System.err.println("WorkStealingPool initialized. ");
			threadHub = new ThreadHub(nrOfWorkers);
		}
	}

	/**
	 * bz: class loader for akka system, prevent from loading illegal class loader
	 */
	// private static ClassLoader ourClassLoader = ActorSystem.class.getClassLoader();

	/**
	 * bz: initialize akka system
	 */
	// private void startAkkaSys(){
	//   Thread.currentThread().setContextClassLoader(ourClassLoader);
	//   akkaSys = ActorSystem.create("pta");
	//   hub = akkaSys.actorOf(Props.create(Hub.class, nrOfWorkers), "hub");
	//   System.err.println("Akka sys initialized. ");
	// }


	/**
	 * bz
	 */
	public PointerAnalysis<InstanceKey> getPointerAnalysis(){
		return pointerAnalysis;
	}

	/**
	 * bz
	 */
	public IPAPropagationGraph getPropagationGraph(){
		return flowGraph;
	}

	/**
	 * bz
	 */
	public IPAPointsToMap getPointsToMap(){
		return pointsToMap;
	}

	/**
	 * @return an object which encapsulates the pointer analysis result
	 */
	public PointerAnalysis<InstanceKey> makePointerAnalysis(IPAPropagationCallGraphBuilder builder) {
		return new IPAPointerAnalysisImpl(builder, cg, pointsToMap, instanceKeys, pointerKeyFactory, instanceKeyFactory);
	}

	protected void registerFixedSet(IPAPointsToSetVariable p, IPAUnarySideEffect s) {
		Set<IPAUnarySideEffect> set = MapUtil.findOrCreateSet(fixedSetMap, p);
		set.add(s);
	}

	protected void updateSideEffects(IPAPointsToSetVariable p, IPAPointsToSetVariable rep) {
		Set<IPAUnarySideEffect> set = fixedSetMap.get(p);
		if (set != null) {
			for (Iterator it = set.iterator(); it.hasNext();) {
				IPAUnarySideEffect s = (IPAUnarySideEffect) it.next();
				s.replaceFixedSet(rep);
			}
			Set<IPAUnarySideEffect> s2 = MapUtil.findOrCreateSet(fixedSetMap, rep);
			s2.addAll(set);
			fixedSetMap.remove(p);
		}
	}

	/**
	 * Keep this method private .. this returns the actual backing set for the class, which we do not want to expose to clients.
	 */
	private MutableIntSet findOrCreateSparseSetForClass(IClass klass) {
		assert klass.getReference() != TypeReference.JavaLangObject;
		MutableIntSet result = class2InstanceKey.get(klass);
		if (result == null) {
			result = IntSetUtil.getDefaultIntSetFactory().make();
			class2InstanceKey.put(klass, result);
		}
		return result;
	}

	/**
	 * @return a set of integers representing the instance keys that correspond to a given class. This method creates a new set, which
	 *         the caller may bash at will.
	 */
	MutableIntSet cloneInstanceKeysForClass(IClass klass) {
		assert klass.getReference() != TypeReference.JavaLangObject;
		MutableIntSet set = class2InstanceKey.get(klass);
		if (set == null) {
			return IntSetUtil.getDefaultIntSetFactory().make();
		} else {
			// return a copy.
			return IntSetUtil.getDefaultIntSetFactory().makeCopy(set);
		}
	}

	/**
	 * @return a set of integers representing the instance keys that correspond to a given class, or null if there are none.
	 * @throws IllegalArgumentException if klass is null
	 */
	public IntSet getInstanceKeysForClass(IClass klass) {
		if (klass == null) {
			throw new IllegalArgumentException("klass is null");
		}
		assert klass != klass.getClassHierarchy().getRootClass();
		return class2InstanceKey.get(klass);
	}

	/**
	 * @return the instance key numbered with index i
	 */
	public InstanceKey getInstanceKey(int i) {
		return instanceKeys.getMappedObject(i);
	}

	public int getInstanceIndex(InstanceKey ik) {
		return instanceKeys.getMappedIndex(ik);
	}

	/**
	 * TODO: optimize; this may be inefficient;
	 *
	 * @return an List of instance keys corresponding to the integers in a set
	 */
	List<InstanceKey> getInstances(IntSet set) {
		LinkedList<InstanceKey> result = new LinkedList<InstanceKey>();
		for (IntIterator it = set.intIterator(); it.hasNext();) {
			int j = it.next();
			result.add(getInstanceKey(j));
		}
		return result;
	}

	@Override
	protected void initializeVariables() {
		// don't have to do anything; all variables initialized
		// by default to TOP (the empty set);
	}

	/**
	 * record that a particular points-to-set is represented implicitly.
	 */
	public void recordImplicitPointsToSet(PointerKey key) {
		if (key == null) {
			throw new IllegalArgumentException("null key");
		}
		if (key instanceof LocalPointerKey) {
			LocalPointerKey lpk = (LocalPointerKey) key;
			if (lpk.isParameter()) {
				System.err.println("------------------ ERROR:");
				System.err.println("LocalPointerKey: " + lpk);
				System.err.println("Constant? " + lpk.getNode().getIR().getSymbolTable().isConstant(lpk.getValueNumber()));
				System.err.println("   -- IR:");
				System.err.println(lpk.getNode().getIR());
				Assertions.UNREACHABLE("How can parameter be implicit?");
			}
		}
		pointsToMap.recordImplicit(key);
	}

	/**
	 * If key is unified, returns the representative
	 *
	 * @param key
	 * @return the dataflow variable that tracks the points-to set for key
	 */
	public IPAPointsToSetVariable findOrCreatePointsToSet(PointerKey key) {

		if (key == null) {
			throw new IllegalArgumentException("null key");
		}

		if (pointsToMap.isImplicit(key)) {
			System.err.println("Did not expect to findOrCreatePointsToSet for implicitly represented PointerKey");
			System.err.println(key);
			Assertions.UNREACHABLE();
		}
		IPAPointsToSetVariable result = pointsToMap.getPointsToSet(key);
		if (result == null) {
			result = new IPAPointsToSetVariable(key);
			pointsToMap.put(key, result);
		} else {
			// check that the filter for this variable remains unique
			if (!pointsToMap.isUnified(key) && key instanceof IPAFilteredPointerKey) {
				PointerKey pk = result.getPointerKey();
				if (!(pk instanceof IPAFilteredPointerKey)) {
					// add a filter for all future evaluations.
					// this is tricky, but the logic is OK .. any constraints that need
					// the filter will see it ...
					// CALLERS MUST BE EXTRA CAREFUL WHEN DEALING WITH UNIFICATION!
					result.setPointerKey(key);
					pk = key;
				}
				IPAFilteredPointerKey fpk = (IPAFilteredPointerKey) pk;
				assert fpk != null;
				assert key != null;
				if (fpk.getTypeFilter() == null) {
					Assertions.UNREACHABLE("fpk.getTypeFilter() is null");
				}
				if (!fpk.getTypeFilter().equals(((IPAFilteredPointerKey) key).getTypeFilter())) {
					Assertions.UNREACHABLE("Cannot use filter " + ((IPAFilteredPointerKey) key).getTypeFilter() + " for " + key
							+ ": previously created different filter " + fpk.getTypeFilter());
				}
			}
		}
		return result;
	}

	public int findOrCreateIndexForInstanceKey(InstanceKey key) {
		int result = instanceKeys.getMappedIndex(key);
		if (result == -1) {
			result = instanceKeys.add(key);
		}
		if (DEBUG) {
			System.err.println("getIndexForInstanceKey " + key + " " + result);
		}
		return result;
	}


	/**
	 * bz:
	 * @param lhs
	 * @param op
	 * @param rhs
	 * @return
	 */
	public boolean delConstraint(PointerKey lhs, IPAUnaryOperator<IPAPointsToSetVariable> op, PointerKey rhs) {
		if (lhs == null) {
			throw new IllegalArgumentException("null lhs");
		}
		if (op == null) {
			throw new IllegalArgumentException("op null");
		}
		if (DEBUG) {
			System.err.println("Delete constraint A: " + lhs + " " + op + " " + rhs);
		}
		IPAPointsToSetVariable L = findOrCreatePointsToSet(lhs);
		if(L == null)
			return false;

		IPAPointsToSetVariable R = findOrCreatePointsToSet(rhs);
		if (op instanceof IPAFilterOperator) {
			if (!(L.getPointerKey() instanceof IPAFilteredPointerKey)) {
				Assertions.UNREACHABLE("expected filtered lhs " + L.getPointerKey() + " " + L.getPointerKey().getClass() + " " + lhs + " "
						+ lhs.getClass());
			}
		}

		try{
			if(lhs instanceof LocalPointerKey){
				LocalPointerKey LocalPK = (LocalPointerKey)L.getPointerKey();
				if(LocalPK.getNode().getMethod().isInit() || LocalPK.getNode().getMethod().isClinit())
					return false;
			}
		}catch(Exception e){return false;}

		if(op instanceof IPAAssignOperator){
			IntSet delSet = null;
			if(getFirstDel()){
				delSet = R.getValue();
			}else{
				delSet = R.getChange();
			}
			if(delSet == null)
				return false;
			//remove the statement first
			delStatementFromFlowGraph(L, op, R, true, true);
			procedureToDelPointsToSet(L, (MutableIntSet) delSet, false);
			return true;
		}else{
			return delStatement(L, op, R, true, true);
		}
	}

	/**
	 * bz: only delete statement from flow graph without propagation
	 * @param lhs
	 * @param operator
	 * @param rhs
	 * @param toWorkList
	 * @param eager
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public boolean delStatementFromFlowGraph(IPAPointsToSetVariable lhs, IPAUnaryOperator operator, IPAPointsToSetVariable rhs, boolean toWorkList, boolean eager) {
		if (operator == null) {
			throw new IllegalArgumentException("operator is null");
		}
		IPAUnaryStatement s = operator.makeEquation(lhs, rhs);
		if (!getFixedPointSystem().containsStatement(s)) {
			return false;
		}
		if(getFirstDel()){
			getFixedPointSystem().removeStatement(s);
			return true;
		}
		return false;
	}

	/**
	 * to replace delStatementFromFlowGraph => too slow for incremental scc detection
	 * @param lhss
	 * @param op
	 * @param rhs
	 * @param b
	 * @param c
	 */
	private void delMultiStatementsFromFlowGraph(ArrayList<IPAPointsToSetVariable> lhss, IPAAssignOperator op,
			IPAPointsToSetVariable rhs, boolean b, boolean c) {
		if (op == null) {
			throw new IllegalArgumentException("operator is null");
		}
		//tell scc engine to perform incremental detection after deleting all these relations...
		sccEngine.setGroupWork(true);
		//start delete
		for (IPAPointsToSetVariable lhs : lhss) {
			IPAUnaryStatement s = op.makeEquation(lhs, rhs);
			if (!getFixedPointSystem().containsStatement(s)) {
				continue;
			}
			if(getFirstDel()){
				getFixedPointSystem().removeStatement(s);
			}
		}
		sccEngine.setGroupWork(false);
		//tell scc engine to work now
		sccEngine.removeMultiEdges();
	}


	private void addMultiStatementsFromFlowGraph(ArrayList<IPAPointsToSetVariable> lhss, IPAAssignOperator op,
			IPAPointsToSetVariable rhs, boolean b, boolean c) {
		if (op == null) {
			throw new IllegalArgumentException("operator is null");
		}
		//tell scc engine to perform incremental detection after adding all these relations...
		sccEngine.setGroupWork(true);
		//start add
		for (IPAPointsToSetVariable lhs : lhss) {
			IPAUnaryStatement s = op.makeEquation(lhs, rhs);
			if (getFixedPointSystem().containsStatement(s)) {
				continue;
			}
			if(lhs.getOrderNumber() <= 0){
				lhs.setOrderNumber(nextOrderNumber++);
			}
			getFixedPointSystem().addStatement(s);
		}
		sccEngine.setGroupWork(false);
		//tell scc engine to work now
		sccEngine.addMultiEdges();
	}


	private void delMultiStatementsFromFlowGraph(IPAPointsToSetVariable lhs, IPAAssignOperator op,
			ArrayList<IPAPointsToSetVariable> rhss, boolean b, boolean c) {
		if (lhs == null){
			throw new IllegalArgumentException("null lhs");
		}
		if (op == null) {
			throw new IllegalArgumentException("operator is null");
		}
		//tell scc engine to perform incremental detection after deleting all these relations...
		sccEngine.setGroupWork(true);
		//start delete
		for (IPAPointsToSetVariable rhs : rhss) {
			IPAUnaryStatement s = op.makeEquation(lhs, rhs);
			if (!getFixedPointSystem().containsStatement(s)) {
				continue;
			}
			if(getFirstDel()){
				getFixedPointSystem().removeStatement(s);
			}
		}
		sccEngine.setGroupWork(false);
		//tell scc engine to work now
		sccEngine.removeMultiEdges();
	}


	/**
	 * bz: only add statement from flow graph without propagation
	 * @param lhs
	 * @param operator
	 * @param rhs
	 * @return
	 */
	private void addMultiStatementsFromFlowGraph(IPAPointsToSetVariable lhs, IPAAssignOperator op,
			ArrayList<IPAPointsToSetVariable> rhss, boolean b, boolean c) {
		if(lhs == null){
			throw new IllegalArgumentException("null lhs");
		}
		if (op == null) {
			throw new IllegalArgumentException("operator is null");
		}
		//tell scc engine to perform incremental detection after adding all these relations...
		sccEngine.setGroupWork(true);
		//start add
		for (IPAPointsToSetVariable rhs : rhss) {
			IPAUnaryStatement s = op.makeEquation(lhs, rhs);
			if (getFixedPointSystem().containsStatement(s)) {
				continue;
			}
			if(lhs.getOrderNumber() <= 0){
				lhs.setOrderNumber(nextOrderNumber++);
			}
			getFixedPointSystem().addStatement(s);
		}
		sccEngine.setGroupWork(false);
		//tell scc engine to work now
		sccEngine.addMultiEdges();
	}


	/**
	 * bz: 1 lhs = multi rhs
	 */
	public boolean delConstraintHasMultiR(IPAPointsToSetVariable lhs, IPAAssignOperator op,
			ArrayList<IPAPointsToSetVariable> rhss, MutableIntSet delset) {
		if (lhs == null) {
			throw new IllegalArgumentException("null lhs");
		}
		if (op == null) {
			throw new IllegalArgumentException("op null");
		}

		try{
			if(lhs.getPointerKey() instanceof LocalPointerKey){
				LocalPointerKey LocalPK = (LocalPointerKey)lhs.getPointerKey();
				if(LocalPK.getNode().getMethod().isInit() || LocalPK.getNode().getMethod().isClinit())
					return false;
			}}catch(Exception e){return false;}

		/**
		 * bz: too slow
		 */
//		for (IPAPointsToSetVariable rhs : rhss) {
//			delStatementFromFlowGraph(L, op, rhs, true, true);
//		}
		delMultiStatementsFromFlowGraph(lhs, op, rhss, true, true);

		int nrOfWorks = rhss.size();
		if(nrOfWorks == 0){
			return false;
		}
		if(delset == null)
			return false;

		procedureToDelPointsToSet(lhs, delset, false);
		return true;
	}

	/**bz: 1 lhs <= multi rhs
	 * @param lhs
	 * @param op
	 * @param rhss
	 * @param addset
	 * @param rhs
	 * @return
	 */
	public boolean addConstraintHasMultiR(IPAPointsToSetVariable lhs, IPAAssignOperator op, ArrayList<IPAPointsToSetVariable> rhss,
			MutableIntSet addset) {
		if (lhs == null) {
			throw new IllegalArgumentException("null lhs");
		}
		if (op == null) {
			throw new IllegalArgumentException("null operator");
		}
		/**
		 * too slow
		 */
//		for (IPAPointsToSetVariable rhs : rhss) {
//			addStatementToFlowGraph(lhs, op, rhs);
//		}
		addMultiStatementsFromFlowGraph(lhs, op, rhss, true, true);

		int nrOfWorks = rhss.size();
		if(nrOfWorks == 0){
			return false;
		}
		if(addset == null)
			return false;

		ArrayList<IPAPointsToSetVariable> lhss = new ArrayList<>();
		lhss.add(lhs);

		addOrDelASetFromMultiLhs(lhss, addset, true);
		return true;
	}


	/**bz: multi lhs <= 1 rhs
	 * @param lhss
	 * @param assignoperator
	 * @param pVal
	 * @param addset
	 * @param pVal2
	 * @return
	 */
	public boolean addConstraintHasMultiL(ArrayList<IPAPointsToSetVariable> lhss, IPAAssignOperator op, IPAPointsToSetVariable rhs,
			final MutableIntSet targets) {
		if (rhs == null) {
			throw new IllegalArgumentException("null rhs");
		}
		if (op == null) {
			throw new IllegalArgumentException("null operator");
		}
		/**
		 * too slow
		 */
//		for (IPAPointsToSetVariable lhs : lhss) {
//			if(lhs == null){
//				throw new IllegalArgumentException("null lhs");
//			}
//			addStatementToFlowGraph(lhs, op, rhs);
//		}
		addMultiStatementsFromFlowGraph(lhss, op, rhs, true, true);

		int nrOfWorks = lhss.size();
		if(nrOfWorks == 0){
			return false;
		}
		if(targets.size() == 0)
			return false;

		addOrDelASetFromMultiLhs(lhss, targets, true);
		return true;
	}


	/**
	 * bz: multi lhs - 1 rhs
	 * @param lhss
	 * @param op
	 * @param rhs
	 * @param targets
	 * @return
	 */
	public boolean delConstraintHasMultiL(ArrayList<IPAPointsToSetVariable> lhss, IPAAssignOperator op,
			IPAPointsToSetVariable rhs, final MutableIntSet targets) {
		if(op == null)
			throw new IllegalArgumentException("null operator");
		if(rhs == null)
			throw new IllegalArgumentException("null rhs");
		/**
		 * bz: too slow
		 */
		//			for (IPAPointsToSetVariable lhs : lhss) {
		//				if(lhs != null)
		//					delStatementFromFlowGraph(lhs, op, rhs, true, true);
		//			}
		delMultiStatementsFromFlowGraph(lhss, op, rhs, true, true);

		int nrOfWorks = lhss.size();
		if(nrOfWorks == 0){
			return false;
		}
		if(targets.size() == 0)
			return false;

		//      System.out.println("Start AkkaSys for delConstraintHasMultiL: ---- nrOfWorks = " + lhss.size());
		addOrDelASetFromMultiLhs(lhss, targets, false);
		return true;
	}


	/**
	 * bz: multi instances - 1 rhs
	 * @param lhss
	 * @param targets
	 * @param notouch
	 * @return
	 */
	public boolean delConstraintHasMultiInstanceL(ArrayList<IPAPointsToSetVariable> lhss, MutableIntSet targets, IPAPointsToSetVariable notouch) {
		if(lhss.size() == 0)
			return false;

		if(targets == null)
			return false;

		//    System.out.println("Start AkkaSys for delConstraintMultiInstanceFromL ---- nrOfWorks = " + nrOfWorks);
		addOrDelASetFromMultiLhs(lhss, targets, false);
		return true;
	}

	/**
	 * bz: scheduler for special cases
	 * @param lhss
	 * @param targets
	 * @param isAddition
	 */
	private void addOrDelASetFromMultiLhs(ArrayList<IPAPointsToSetVariable> lhss, MutableIntSet targets, boolean isAddition){
		if(useAkka){
			// System.out.println("Start AkkaSys for multi l: ---- nrOfWorks = " + lhss.size());
			// hub.tell(new SchedulerForSpecialTasks(lhss, targets, isAddition, this), hub);
			// awaitHubComplete();
		}else{
			try {
				threadHub.initialSpecialTasks(lhss, targets, isAddition, this);
			} catch (InterruptedException | ExecutionException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * bz
	 */
	public boolean delConstraint(PointerKey lhs, InstanceKey value) {
		if (DEBUG) {
			System.err.println("Delete constraint B: " + lhs + " U= " + value);
		}
		//    pointsToMap.recordTransitiveRoot(lhs);

		IPAPointsToSetVariable L = findOrCreatePointsToSet(lhs);
		int index = findOrCreateIndexForInstanceKey(value);
		MutableIntSet delSet = IntSetUtil.make();
		delSet.add(index);

		procedureToDelPointsToSet(L, delSet, true);
		// deregister that we have an instanceKey for the klass?
		//    assert value.getConcreteType() != null;
		//    if (!value.getConcreteType().getReference().equals(TypeReference.JavaLangObject)) {
		//      registerInstanceOfClass(value.getConcreteType(), index);
		//    }
		return true;
	}

	/**
	 * bz
	 * @param lhs
	 * @param value
	 * @return
	 */
	public boolean delConstraint(PointerKey lhs, MutableIntSet delset) {
		if (DEBUG) {
			System.err.println("Delete constraint B: " + lhs + " U= " + delset);
		}
		IPAPointsToSetVariable L = findOrCreatePointsToSet(lhs);

		procedureToDelPointsToSet(L, delset, true);
		// deregister that we have an instanceKey for the klass?
		//    assert value.getConcreteType() != null;
		//    if (!value.getConcreteType().getReference().equals(TypeReference.JavaLangObject)) {
		//      registerInstanceOfClass(value.getConcreteType(), index);
		//    }
		return true;
	}


	/**
	 * bz: the main procedure to delete points-to constraints
	 * @param l
	 * @param delSet
	 */
	private void procedureToDelPointsToSet(IPAPointsToSetVariable L, final MutableIntSet delSet, boolean isRoot) {
		if(!isRoot){
			if(isTransitiveRoot(L.getPointerKey()))
				return;
		}
		//recompute L
		final MutableSharedBitVectorIntSet remaining = new MutableSharedBitVectorIntSetFactory().makeCopy(delSet);
		for (IPAPointsToSetVariable pv : flowGraph.getPointsToSetVariablesThatDefImplicitly(L)) {
			if(remaining.isEmpty())
				break;
			if(pv instanceof SCCVariable){
				((SCCVariable) pv).ifOthersCanProvide(L, remaining, delSet, flowGraph);
			}else if(pv.getValue() != null){
				IntSetAction action = new IntSetAction() {
					@Override
					public void act(int i) {
						if(remaining.isEmpty())
							return;
						if(delSet.contains(i)){
							remaining.remove(i);
						}
					}
				};
				pv.getValue().foreach(action);
			}
		}
		//schedule task if changes
		if(!remaining.isEmpty()){
			MutableSharedBitVectorIntSet removed = DeletionUtil.removeSome(L, remaining);
			if(removed.size() > 0){
				if(!changes.contains(L)){
					changes.add(L);
				}
				final ArrayList<IPAPointsToSetVariable> firstUsers = findFirstUsers(L);
				final int nrOfWorks = firstUsers.size();
				if(nrOfWorks == 0){//no need to propagate
					return;
				}else if(nrOfWorks == 1){//only one constraint to propagate
					IPAPointsToSetVariable first = firstUsers.get(0);
					singleProcedureToDelPointsToSet(first, delSet);
				}else{//use parallelization to propagete
					if(useAkka){
						// System.out.println("Start AkkaSys for Deleting (re & re) set ---- nrOfWorks = " + nrOfWorks);
						// hub.tell(new SchedulerForRRTasks(removed, firstUsers, this), hub);
						// awaitHubComplete();
					}else{
						try {
							threadHub.initialRRTasks(removed, firstUsers, this);
						} catch (InterruptedException | ExecutionException e) {
							e.printStackTrace();
						}
					}
				}
			}
		}else{
			return;
		}
	}

	/**bz:
	 * process of del constraint without using parallelization
	 * @param L
	 * @param targets
	 */
	private void singleProcedureToDelPointsToSet(final IPAPointsToSetVariable L, final MutableIntSet targets){
		if(isTransitiveRoot(L.getPointerKey()))
			return;
		final MutableSharedBitVectorIntSet remaining = new MutableSharedBitVectorIntSetFactory().makeCopy(targets);
		for (IPAPointsToSetVariable pv : flowGraph.getPointsToSetVariablesThatDefImplicitly(L)) {
			if(remaining.isEmpty())
				break;
			if(pv instanceof SCCVariable){
				((SCCVariable) pv).ifOthersCanProvide(L, remaining, targets, flowGraph);
			}else if(pv.getValue() != null){
				IntSetAction action = new IntSetAction() {
					@Override
					public void act(int i) {
						if(remaining.isEmpty())
							return;
						if(targets.contains(i)){
							remaining.remove(i);
						}
					}
				};
				pv.getValue().foreach(action);
			}
		}
		//if not reachable, deleting, and continue for other nodes
		if(!remaining.isEmpty()){
			MutableSharedBitVectorIntSet removed = DeletionUtil.removeSome(L, remaining);
			if(removed.size() > 0){
				if(!changes.contains(L)){
					changes.add(L);
				}
				classifyPointsToConstraints(L, removed);
			}
		}else{//all included, early return
			return;
		}
	}

	/**
	 * bz: classify different Points-To Constraints with different procedure
	 * @param L
	 * @param targets
	 */
	private void classifyPointsToConstraints(IPAPointsToSetVariable L, final MutableIntSet targets){
		for (Iterator it = flowGraph.getStatementsThatUse(L); it.hasNext();) {
			IPAAbstractStatement s = (IPAAbstractStatement) it.next();
			IPAAbstractOperator op = s.getOperator();
			if(op instanceof IPAAssignOperator || op instanceof IPAFilterOperator){
				if(checkSelfRecursive(s))
					continue;
				IPAPointsToSetVariable pv = (IPAPointsToSetVariable) s.getLHS();
				if(pv.getValue() != null)
					singleProcedureToDelPointsToSet(pv, targets);
			}
//			else if(op instanceof IPAFilterOperator){
//				if(checkSelfRecursive(s))
//					continue;
//				IPAFilterOperator filter = (IPAFilterOperator) op;
//				IPAPointsToSetVariable pv = (IPAPointsToSetVariable) s.getLHS();
//				byte mark = filter.evaluateDel(pv, L);
//				if(mark == 1){
//					if(!changes.contains(pv)){
//						changes.add(pv);
//					}
//					classifyPointsToConstraints(pv, targets);
//				}
//			}
			else{// all other complex constraints
				addToWorkList(s);
			}
		}
	}


	/**
	 * bz
	 * @param L
	 * @return get statements/variables that use L
	 */
	ArrayList<IPAPointsToSetVariable> findFirstUsers(IPAPointsToSetVariable L) {
		ArrayList<IPAPointsToSetVariable> results = new ArrayList<>();
		Iterator it = getFixedPointSystem().getStatementsThatUse(L);
		while(it.hasNext()){
			IPAAbstractStatement s = (IPAAbstractStatement) it.next();
			IPAAbstractOperator op = s.getOperator();
			if(op instanceof IPAAssignOperator){
				IVariable iv = s.getLHS();
				IPAPointsToSetVariable pv = (IPAPointsToSetVariable)iv;
				results.add(pv);
			}else
				addToWorkList(s);
		}
		return results;
	}

	/**
	 * bz: waiting for parallel tasks
	 */
	// public void awaitHubComplete(){
	//   boolean goon = true;
	//   while(goon){
	//     try {
	//       Thread.sleep(10);
	//     } catch (InterruptedException e) {
	//       e.printStackTrace();
	//     }
	//     goon = Hub.askstatus();
	//   }
	//   return;
	// }


	/**
	 * NB: this is idempotent ... if the given constraint exists, it will not be added to the system; however, this will be more
	 * expensive since it must check if the constraint pre-exits.
	 *
	 * @return true iff the system changes
	 */
	public boolean newConstraint(PointerKey lhs, IPAUnaryOperator<IPAPointsToSetVariable> op, PointerKey rhs) {
		if (lhs == null) {
			throw new IllegalArgumentException("null lhs");
		}
		if (op == null) {
			throw new IllegalArgumentException("op null");
		}
		if (rhs == null) {
			throw new IllegalArgumentException("rhs null");
		}
		if (DEBUG) {
			System.err.println("Add constraint A: " + lhs + " " + op + " " + rhs);
		}
		IPAPointsToSetVariable L = findOrCreatePointsToSet(lhs);
		IPAPointsToSetVariable R = findOrCreatePointsToSet(rhs);
		if (op instanceof IPAFilterOperator) {
			// we do not want to revert the lhs to pre-transitive form;
			// we instead want to check in the outer loop of the pre-transitive
			// solver if the value of L changes.
			pointsToMap.recordTransitiveRoot(L.getPointerKey());
			if (!(L.getPointerKey() instanceof IPAFilteredPointerKey)) {
				Assertions.UNREACHABLE("expected filtered lhs " + L.getPointerKey() + " " + L.getPointerKey().getClass() + " " + lhs + " "
						+ lhs.getClass());
			}
		}
		if(isChange)
			return newStatementChange(L, op, R, true, true);

		return newStatement(L, op, R, true, true);
	}

	public boolean newConstraint(PointerKey lhs, IPAAbstractOperator<IPAPointsToSetVariable> op, PointerKey rhs) {
		if (lhs == null) {
			throw new IllegalArgumentException("lhs null");
		}
		if (op == null) {
			throw new IllegalArgumentException("op null");
		}
		if (rhs == null) {
			throw new IllegalArgumentException("rhs null");
		}
		if (DEBUG) {
			System.err.println("Add constraint A: " + lhs + " " + op + " " + rhs);
		}
		assert !pointsToMap.isUnified(lhs);
		assert !pointsToMap.isUnified(rhs);
		IPAPointsToSetVariable L = findOrCreatePointsToSet(lhs);
		IPAPointsToSetVariable R = findOrCreatePointsToSet(rhs);
		if(isChange)
			return newStatementChange(L, op, new IPAPointsToSetVariable[] { R }, true, true);

		return newStatement(L, op, new IPAPointsToSetVariable[] { R }, true, true);
	}

	public boolean newConstraint(PointerKey lhs, IPAAbstractOperator<IPAPointsToSetVariable> op, PointerKey rhs1, PointerKey rhs2) {
		if (lhs == null) {
			throw new IllegalArgumentException("null lhs");
		}
		if (op == null) {
			throw new IllegalArgumentException("null op");
		}
		if (rhs1 == null) {
			throw new IllegalArgumentException("null rhs1");
		}
		if (rhs2 == null) {
			throw new IllegalArgumentException("null rhs2");
		}
		if (DEBUG) {
			System.err.println("Add constraint A: " + lhs + " " + op + " " + rhs1 + ", " + rhs2);
		}
		assert !pointsToMap.isUnified(lhs);
		assert !pointsToMap.isUnified(rhs1);
		assert !pointsToMap.isUnified(rhs2);
		IPAPointsToSetVariable L = findOrCreatePointsToSet(lhs);
		IPAPointsToSetVariable R1 = findOrCreatePointsToSet(rhs1);
		IPAPointsToSetVariable R2 = findOrCreatePointsToSet(rhs2);
		return newStatement(L, op, R1, R2, true, true);
	}

	/**
	 * @return true iff the system changes
	 */
	public boolean newFieldWrite(PointerKey lhs, IPAUnaryOperator<IPAPointsToSetVariable> op, PointerKey rhs) {
		return newConstraint(lhs, op, rhs);
	}

	/**
	 * @return true iff the system changes
	 */
	public boolean newFieldRead(PointerKey lhs, IPAUnaryOperator<IPAPointsToSetVariable> op, PointerKey rhs) {
		return newConstraint(lhs, op, rhs);
	}

	/**
	 * @return true iff the system changes
	 */
	public boolean newConstraint(PointerKey lhs, InstanceKey value) {
		if (DEBUG) {
			System.err.println("Add constraint B: " + lhs + " U= " + value);
		}
		pointsToMap.recordTransitiveRoot(lhs);

		// we don't actually add a constraint.
		// instead, we immediately add the value to the points-to set.
		// This works since the solver is monotonic with TOP = {}
		IPAPointsToSetVariable L = findOrCreatePointsToSet(lhs);
		int index = findOrCreateIndexForInstanceKey(value);
		if (L.contains(index)) {
			// a no-op
			return false;
		} else {
			L.add(index);

			// also register that we have an instanceKey for the klass
			assert value.getConcreteType() != null;

			if (!value.getConcreteType().getReference().equals(TypeReference.JavaLangObject)) {
				registerInstanceOfClass(value.getConcreteType(), index);
			}

			// we'd better update the worklist appropriately
			// if graphNodeId == -1, then there are no equations that use this
			// variable.
			if (L.getGraphNodeId() > -1) {
				changedVariable(L);
			}
			return true;
		}

	}

	/**
	 * Record that we have a new instanceKey for a given declared type.
	 */
	private void registerInstanceOfClass(IClass klass, int index) {

		if (DEBUG) {
			System.err.println("registerInstanceOfClass " + klass + " " + index);
		}

		assert !klass.getReference().equals(TypeReference.JavaLangObject);

		try {
			IClass T = klass;
			registerInstanceWithAllSuperclasses(index, T);
			registerInstanceWithAllInterfaces(klass, index);

			if (klass.isArrayClass()) {
				ArrayClass aClass = (ArrayClass) klass;
				int dim = aClass.getDimensionality();
				registerMultiDimArraysForArrayOfObjectTypes(dim, index, aClass);

				IClass elementClass = aClass.getInnermostElementClass();
				if (elementClass != null) {
					registerArrayInstanceWithAllSuperclassesOfElement(index, elementClass, dim);
					registerArrayInstanceWithAllInterfacesOfElement(index, elementClass, dim);
				}
			}
		} catch (ClassHierarchyException e) {
			Warnings.add(ClassHierarchyWarning.create(e.getMessage()));
		}
	}

	private int registerMultiDimArraysForArrayOfObjectTypes(int dim, int index, ArrayClass aClass) {

		for (int i = 1; i < dim; i++) {
			TypeReference jlo = makeArray(TypeReference.JavaLangObject, i);
			IClass jloClass = null;
			jloClass = aClass.getClassLoader().lookupClass(jlo.getName());
			MutableIntSet set = findOrCreateSparseSetForClass(jloClass);
			set.add(index);
		}
		return dim;
	}

	private void registerArrayInstanceWithAllInterfacesOfElement(int index, IClass elementClass, int dim) {
		Collection ifaces = null;
		ifaces = elementClass.getAllImplementedInterfaces();
		for (Iterator it = ifaces.iterator(); it.hasNext();) {
			IClass I = (IClass) it.next();
			TypeReference iArrayRef = makeArray(I.getReference(), dim);
			IClass iArrayClass = null;
			iArrayClass = I.getClassLoader().lookupClass(iArrayRef.getName());
			MutableIntSet set = findOrCreateSparseSetForClass(iArrayClass);
			set.add(index);
			if (DEBUG) {
				System.err.println("dense filter for interface " + iArrayClass + " " + set);
			}
		}
	}

	private static TypeReference makeArray(TypeReference element, int dim) {
		TypeReference iArrayRef = element;
		for (int i = 0; i < dim; i++) {
			iArrayRef = TypeReference.findOrCreateArrayOf(iArrayRef);
		}
		return iArrayRef;
	}

	private void registerArrayInstanceWithAllSuperclassesOfElement(int index, IClass elementClass, int dim) {
		IClass T;
		// register the array with each supertype of the element class
		T = elementClass.getSuperclass();
		while (T != null) {
			TypeReference tArrayRef = makeArray(T.getReference(), dim);
			IClass tArrayClass = null;
			tArrayClass = T.getClassLoader().lookupClass(tArrayRef.getName());
			MutableIntSet set = findOrCreateSparseSetForClass(tArrayClass);
			set.add(index);
			if (DEBUG) {
				System.err.println("dense filter for class " + tArrayClass + " " + set);
			}
			T = T.getSuperclass();
		}
	}

	/**
	 * @param klass
	 * @param index
	 * @throws ClassHierarchyException
	 */
	private void registerInstanceWithAllInterfaces(IClass klass, int index) throws ClassHierarchyException {
		Collection ifaces = klass.getAllImplementedInterfaces();
		for (Iterator it = ifaces.iterator(); it.hasNext();) {
			IClass I = (IClass) it.next();
			MutableIntSet set = findOrCreateSparseSetForClass(I);
			set.add(index);
			if (DEBUG) {
				System.err.println("dense filter for interface " + I + " " + set);
			}
		}
	}

	/**
	 * @param index
	 * @param T
	 * @throws ClassHierarchyException
	 */
	private void registerInstanceWithAllSuperclasses(int index, IClass T) throws ClassHierarchyException {
		while (T != null && !T.getReference().equals(TypeReference.JavaLangObject)) {
			MutableIntSet set = findOrCreateSparseSetForClass(T);
			set.add(index);
			if (DEBUG) {
				System.err.println("dense filter for class " + T + " " + set);
			}
			T = T.getSuperclass();
		}
	}

	//bz
	public void delSideEffect(IPAUnaryOperator<IPAPointsToSetVariable> op, PointerKey arg0) {
		if (arg0 == null) {
			throw new IllegalArgumentException("null arg0");
		}
		if (DEBUG) {
			System.err.println("delete constraint D: " + op + " --- to  " + arg0);
		}
		assert !pointsToMap.isUnified(arg0);
		IPAPointsToSetVariable v1 = findOrCreatePointsToSet(arg0);
		delStatement(null, op, v1, true, true);
	}

	//bz
	public void delSideEffect(IPAAbstractOperator<IPAPointsToSetVariable> op, PointerKey[] arg0) {
		if (arg0 == null) {
			throw new IllegalArgumentException("null arg0");
		}
		if (DEBUG) {
			System.err.println("delete constraint D: " + op + " " + arg0);
		}
		IPAPointsToSetVariable[] vs = new IPAPointsToSetVariable[ arg0.length ];
		for(int i = 0; i < arg0.length; i++) {
			assert !pointsToMap.isUnified(arg0[i]);
			vs[i] = findOrCreatePointsToSet(arg0[i]);
		}
		delStatement(null, op, vs, true, true);
	}

	public void newSideEffect(IPAUnaryOperator<IPAPointsToSetVariable> op, PointerKey arg0) {
		if (arg0 == null) {
			throw new IllegalArgumentException("null arg0");
		}
		if (DEBUG) {
			System.err.println("add constraint D: " + op + " " + arg0);
		}
		assert !pointsToMap.isUnified(arg0);
		IPAPointsToSetVariable v1 = findOrCreatePointsToSet(arg0);

		if(isChange){
			newStatementChange(null, op, v1, true, true);
			return;
		}

		newStatement(null, op, v1, true, true);
	}

	public void newSideEffect(IPAAbstractOperator<IPAPointsToSetVariable> op, PointerKey[] arg0) {
		if (arg0 == null) {
			throw new IllegalArgumentException("null arg0");
		}
		if (DEBUG) {
			System.err.println("add constraint D: " + op + " " + Arrays.toString(arg0));
		}
		IPAPointsToSetVariable[] vs = new IPAPointsToSetVariable[ arg0.length ];
		for(int i = 0; i < arg0.length; i++) {
			assert !pointsToMap.isUnified(arg0[i]);
			vs[i] = findOrCreatePointsToSet(arg0[i]);
		}

		if(isChange){
			newStatementChange(null, op, vs, true, true);
			return;
		}
		newStatement(null, op, vs, true, true);
	}

	public void newSideEffect(IPAAbstractOperator<IPAPointsToSetVariable> op, PointerKey arg0, PointerKey arg1) {
		if (DEBUG) {
			System.err.println("add constraint D: " + op + " " + arg0);
		}
		assert !pointsToMap.isUnified(arg0);
		assert !pointsToMap.isUnified(arg1);
		IPAPointsToSetVariable v1 = findOrCreatePointsToSet(arg0);
		IPAPointsToSetVariable v2 = findOrCreatePointsToSet(arg1);
		newStatement(null, op, v1, v2, true, true);
	}

	@Override
	protected void initializeWorkList() {
		addAllStatementsToWorkList();
	}

	/**
	 * @return an object that encapsulates the pointer analysis results
	 */
	public PointerAnalysis<InstanceKey> extractPointerAnalysis(IPAPropagationCallGraphBuilder builder) {
		if (pointerAnalysis == null) {
			pointerAnalysis = makePointerAnalysis(builder);
		}
		return pointerAnalysis;
	}

	@Override
	public void performVerboseAction() {
		super.performVerboseAction();
		if (DEBUG_MEMORY) {
			DEBUG_MEM_COUNTER++;
			if (DEBUG_MEM_COUNTER % DEBUG_MEM_INTERVAL == 0) {
				DEBUG_MEM_COUNTER = 0;
				ReferenceCleanser.clearSoftCaches();

				System.err.println(flowGraph.spaceReport());

				System.err.println("Analyze leaks..");
				HeapTracer.traceHeap(Collections.singleton(this), true);
				System.err.println("done analyzing leaks");
			}
		}
		if (getFixedPointSystem() instanceof VerboseAction) {
			((VerboseAction) getFixedPointSystem()).performVerboseAction();
		}
		if (!workList.isEmpty()) {
			IPAAbstractStatement s = workList.takeStatement();
			System.err.println(printRHSInstances(s));
			workList.insertStatement(s);
			System.err.println("CGNodes: " + cg.getNumberOfNodes());
		}

	}

	private String printRHSInstances(IPAAbstractStatement s) {
		if (s instanceof IPAUnaryStatement) {
			IPAUnaryStatement u = (IPAUnaryStatement) s;
			IPAPointsToSetVariable rhs = (IPAPointsToSetVariable) u.getRightHandSide();
			IntSet value = rhs.getValue();
			final int[] topFive = new int[5];
			value.foreach(new IntSetAction() {
				@Override
				public void act(int x) {
					for (int i = 0; i < 4; i++) {
						topFive[i] = topFive[i + 1];
					}
					topFive[4] = x;
				}
			});
			StringBuffer result = new StringBuffer();
			for (int i = 0; i < 5; i++) {
				int p = topFive[i];
				if (p != 0) {
					InstanceKey ik = getInstanceKey(p);
					result.append(p).append("  ").append(ik).append("\n");
				}
			}
			return result.toString();
		} else {
			return s.getClass().toString();
		}
	}

	@Override
	public IFixedPointSystem<IPAPointsToSetVariable> getFixedPointSystem() {
		return flowGraph;
	}

	/*
	 * @see com.ibm.wala.ipa.callgraph.propagation.HeapModel#iteratePointerKeys()
	 */
	public Iterator<PointerKey> iteratePointerKeys() {
		return pointsToMap.iterateKeys();
	}

	/**
	 * warning: this is _real_ slow; don't use it anywhere performance critical
	 */
	public int getNumberOfPointerKeys() {
		return pointsToMap.getNumberOfPointerKeys();
	}

	/**
	 * Use with care.
	 */
	Worklist getWorklist() {
		return workList;
	}

	public Iterator<IPAAbstractStatement> getStatementsThatUse(IPAPointsToSetVariable v) {
		return flowGraph.getStatementsThatUse(v);
	}

	public Iterator<IPAAbstractStatement> getStatementsThatDef(IPAPointsToSetVariable v) {
		return flowGraph.getStatementsThatDef(v);
	}

	public NumberedGraph<IPAPointsToSetVariable> getAssignmentGraph() {
		return flowGraph.getAssignmentGraph();
	}

	public Graph<IPAPointsToSetVariable> getFilterAsssignmentGraph() {
		return flowGraph.getFilterAssignmentGraph();
	}

	/**
	 * NOTE: do not use this method unless you really know what you are doing. Functionality is fragile and may not work in the
	 * future.
	 */
	public Graph<IPAPointsToSetVariable> getFlowGraphIncludingImplicitConstraints() {
		return flowGraph.getFlowGraphIncludingImplicitConstraints();
	}

	/**
	 *
	 */
	public void revertToPreTransitive() {
		pointsToMap.revertToPreTransitive();
	}

	public Iterator getTransitiveRoots() {
		return pointsToMap.getTransitiveRoots();
	}

	public boolean isTransitiveRoot(PointerKey key) {
		return pointsToMap.isTransitiveRoot(key);
	}

	@Override
	protected void periodicMaintenance() {
		super.periodicMaintenance();
		ReferenceCleanser.clearSoftCaches();
	}

	@Override
	public int getVerboseInterval() {
		return verboseInterval;
	}

	/**
	 * @param verboseInterval The verboseInterval to set.
	 */
	public void setVerboseInterval(int verboseInterval) {
		this.verboseInterval = verboseInterval;
	}

	@Override
	public int getPeriodicMaintainInterval() {
		return periodicMaintainInterval;
	}

	/**
	 * @param periodicMaintainInteval
	 */
	public void setPeriodicMaintainInterval(int periodicMaintainInteval) {
		this.periodicMaintainInterval = periodicMaintainInteval;
	}

	/**
	 * Unify the points-to-sets for the variables identified by the set s
	 *
	 * @param s numbers of points-to-set variables
	 * @throws IllegalArgumentException if s is null
	 */
	public void unify(IntSet s) {
		if (s == null) {
			throw new IllegalArgumentException("s is null");
		}
		// cache the variables represented
		HashSet<IPAPointsToSetVariable> cache = HashSetFactory.make(s.size());
		for (IntIterator it = s.intIterator(); it.hasNext();) {
			int i = it.next();
			cache.add(pointsToMap.getPointsToSet(i));
		}

		// unify the variables
		pointsToMap.unify(s);
		int rep = pointsToMap.getRepresentative(s.intIterator().next());

		// clean up the equations
		updateEquationsForUnification(cache, rep);

		// special logic to clean up side effects
		updateSideEffectsForUnification(cache, rep);
	}

	/**
	 * Update side effect after unification
	 *
	 * @param s set of PointsToSetVariables that have been unified
	 * @param rep number of the representative variable for the unified set.
	 */
	private void updateSideEffectsForUnification(HashSet<IPAPointsToSetVariable> s, int rep) {
		IPAPointsToSetVariable pRef = pointsToMap.getPointsToSet(rep);
		for (Iterator<IPAPointsToSetVariable> it = s.iterator(); it.hasNext();) {
			IPAPointsToSetVariable p = it.next();
			updateSideEffects(p, pRef);
		}
	}

	/**
	 * Update equation def/uses after unification
	 *
	 * @param s set of PointsToSetVariables that have been unified
	 * @param rep number of the representative variable for the unified set.
	 */
	@SuppressWarnings("unchecked")
	private void updateEquationsForUnification(HashSet<IPAPointsToSetVariable> s, int rep) {
		IPAPointsToSetVariable pRef = pointsToMap.getPointsToSet(rep);
		for (Iterator<IPAPointsToSetVariable> it = s.iterator(); it.hasNext();) {
			IPAPointsToSetVariable p = it.next();

			if (p != pRef) {
				// pRef is the representative for p.
				// be careful: cache the defs before mucking with the underlying system
				for (Iterator d = Iterator2Collection.toSet(getStatementsThatDef(p)).iterator(); d.hasNext();) {
					IPAAbstractStatement as = (IPAAbstractStatement) d.next();

					if (as instanceof IPAAssignEquation) {
						IPAAssignEquation assign = (IPAAssignEquation) as;
						IPAPointsToSetVariable rhs = assign.getRightHandSide();
						int rhsRep = pointsToMap.getRepresentative(pointsToMap.getIndex(rhs.getPointerKey()));
						if (rhsRep == rep) {
							flowGraph.removeStatement(as);
						} else {
							replaceLHS(pRef, p, as);
						}
					} else {
						replaceLHS(pRef, p, as);
					}
				}
				// be careful: cache the defs before mucking with the underlying system
				for (Iterator u = Iterator2Collection.toSet(getStatementsThatUse(p)).iterator(); u.hasNext();) {
					IPAAbstractStatement as = (IPAAbstractStatement) u.next();
					if (as instanceof IPAAssignEquation) {
						IPAAssignEquation assign = (IPAAssignEquation) as;
						IPAPointsToSetVariable lhs = assign.getLHS();
						int lhsRep = pointsToMap.getRepresentative(pointsToMap.getIndex(lhs.getPointerKey()));
						if (lhsRep == rep) {
							flowGraph.removeStatement(as);
						} else {
							replaceRHS(pRef, p, as);
						}
					} else {
						replaceRHS(pRef, p, as);
					}
				}
				if (flowGraph.getNumberOfStatementsThatDef(p) == 0 && flowGraph.getNumberOfStatementsThatUse(p) == 0) {
					flowGraph.removeVariable(p);
				}
			}
		}
	}

	/**
	 * replace all occurrences of p on the rhs of a statement with pRef
	 *
	 * @param as a statement that uses p in it's right-hand side
	 */
	private void replaceRHS(IPAPointsToSetVariable pRef, IPAPointsToSetVariable p,
			IPAAbstractStatement<IPAPointsToSetVariable, IPAAbstractOperator<IPAPointsToSetVariable>> as) {
		if (as instanceof IPAUnaryStatement) {
			assert ((IPAUnaryStatement) as).getRightHandSide() == p;
			newStatement(as.getLHS(), (IPAUnaryOperator<IPAPointsToSetVariable>) as.getOperator(), pRef, false, false);
		} else {
			IVariable[] rhs = as.getRHS();
			IPAPointsToSetVariable[] newRHS = new IPAPointsToSetVariable[rhs.length];
			for (int i = 0; i < rhs.length; i++) {
				if (rhs[i].equals(p)) {
					newRHS[i] = pRef;
				} else {
					newRHS[i] = (IPAPointsToSetVariable) rhs[i];
				}
			}
			newStatement(as.getLHS(), as.getOperator(), newRHS, false, false);
		}
		flowGraph.removeStatement(as);
	}

	/**
	 * replace all occurences of p on the lhs of a statement with pRef
	 *
	 * @param as a statement that defs p
	 */
	private void replaceLHS(IPAPointsToSetVariable pRef, IPAPointsToSetVariable p,
			IPAAbstractStatement<IPAPointsToSetVariable, IPAAbstractOperator<IPAPointsToSetVariable>> as) {
		assert as.getLHS() == p;
		if (as instanceof IPAUnaryStatement) {
			newStatement(pRef, (IPAUnaryOperator<IPAPointsToSetVariable>) as.getOperator(), (IPAPointsToSetVariable) ((IPAUnaryStatement) as)
					.getRightHandSide(), false, false);
		} else {
			newStatement(pRef, as.getOperator(), as.getRHS(), false, false);
		}
		flowGraph.removeStatement(as);
	}

	public boolean isUnified(PointerKey result) {
		return pointsToMap.isUnified(result);
	}

	public int getNumber(PointerKey p) {
		return pointsToMap.getIndex(p);
	}

	@Override
	protected IPAPointsToSetVariable[] makeStmtRHS(int size) {
		return new IPAPointsToSetVariable[size];
	}

}
