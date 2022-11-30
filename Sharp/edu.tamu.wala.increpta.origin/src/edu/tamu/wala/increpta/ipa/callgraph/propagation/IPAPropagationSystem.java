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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import com.ibm.wala.classLoader.ArrayClass;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.fixpoint.IFixedPointSystem;
import com.ibm.wala.fixpoint.IVariable;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKeyFactory;
import com.ibm.wala.ipa.callgraph.propagation.LocalPointerKey;
import com.ibm.wala.ipa.callgraph.propagation.NullConstantKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyWarning;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.debug.Assertions;
import com.ibm.wala.util.debug.VerboseAction;
import com.ibm.wala.util.graph.Graph;
import com.ibm.wala.util.graph.NumberedGraph;
import com.ibm.wala.util.heapTrace.HeapTracer;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetAction;
//import com.ibm.wala.util.intset.IntSetUtil;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.MutableMapping;
//import com.ibm.wala.util.intset.MutableSharedBitVectorIntSet;
//import com.ibm.wala.util.intset.MutableSharedBitVectorIntSetFactory;
import com.ibm.wala.util.ref.ReferenceCleanser;
import com.ibm.wala.util.warnings.Warnings;

import edu.tamu.wala.increpta.callgraph.impl.IPAExplicitCallGraph;
import edu.tamu.wala.increpta.instancekey.ThreadNormalAllocationInNode;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationCallGraphBuilder.IPAFilterOperator;
import edu.tamu.wala.increpta.operators.IPAAbstractOperator;
import edu.tamu.wala.increpta.operators.IPAAbstractStatement;
import edu.tamu.wala.increpta.operators.IPAAssignOperator;
import edu.tamu.wala.increpta.operators.IPAUnaryOperator;
import edu.tamu.wala.increpta.operators.IPAUnaryStatement;
import edu.tamu.wala.increpta.parallel.ThreadHub;
import edu.tamu.wala.increpta.pointerkey.IPAFilteredPointerKey;
import edu.tamu.wala.increpta.pointerkey.IPAPointerKeyFactory;
import edu.tamu.wala.increpta.util.DeletionUtil;
import edu.tamu.wala.increpta.util.IPADefaultFixedPointSolver;
import edu.tamu.wala.increpta.util.IPAWorklist;
import edu.tamu.wala.increpta.util.intset.IPAIntSetUtil;
import edu.tamu.wala.increpta.util.intset.IPAMutableSharedBitVectorIntSet;
import edu.tamu.wala.increpta.util.intset.IPAMutableSharedBitVectorIntSetFactory;
//import edu.tamu.wala.increpta.wcc.WCC;
//import edu.tamu.wala.increpta.wcc.WCCEngine;
//import edu.tamu.wala.increpta.wcc.WCCFixedPointSolver;
//import edu.tamu.wala.increpta.scc.SCCEngine;
//import edu.tamu.wala.increpta.scc.SCCVariable;

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
	protected final IPAPointsToMap pointsToMap = new IPAPointsToMap();//

	/**
	 * Implementation of the underlying dataflow graph
	 */
	private final IPAPropagationGraph flowGraph = new IPAPropagationGraph();//

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
	 * Governing call graph;
	 */
	protected final CallGraph cg;

	private int verboseInterval = DEFAULT_VERBOSE_INTERVAL;

	private int periodicMaintainInterval = DEFAULT_PERIODIC_MAINTENANCE_INTERVAL;

	/**
	 * bz: used when translating to database
	 */
	private static boolean RECORD_ROOT = false;
	public void recordRootRelation(boolean b) {
		RECORD_ROOT = b;
	}
	private HashMap<PointerKey, Integer> root = new HashMap<>();
	public HashMap<PointerKey, Integer> getRoot() {
		return root;
	}

	/**
	 * bz: use akka or a thread pool
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
	

//	////bz: scc
//	public SCCEngine sccEngine;
//	
//	/**
//	 * use scc in analysis
//	 */
//	static boolean use_scc = false;
//	public static void setUseSCC(boolean b) {
//		use_scc = b;
//	}
//	
//
//	/**
//	 * run after the whole program analysis
//	 */
//	public void createSCCEngine(){
//		sccEngine = new SCCEngine(this, flowGraph);
//		flowGraph.setSCCEngine(sccEngine);
//	}
//    //////////
//	////bz: wcc + scc
//	public WCCEngine wccEngine;
//	public WCCFixedPointSolver wlSolver;
//	
//	public WCCFixedPointSolver getWLSolver() {
//		return wlSolver;
//	}
//
//	/**
//	 * run after the whole program analysis
//	 */
//	public void createWCCEngine(){
//		wccEngine = new WCCEngine(flowGraph);
//		flowGraph.setWCCEngine(wccEngine);
//		wlSolver = new WCCFixedPointSolver(this);
//	}
//	
//	public WCCEngine getWCCEngine() {
//		return wccEngine;
//	}
//
//	static boolean use_wcc = false;
//	public static void setUseWCC(boolean b) {
//		use_wcc = b;
//	}
//	
//	static boolean within_wcc = false;
//	public static void setInWCC(boolean b) {
//		within_wcc = b;
//	}

	public IPAPropagationSystem(CallGraph cg, IPAPointerKeyFactory pointerKeyFactory, InstanceKeyFactory instanceKeyFactory) {
		if (cg == null) {
			throw new IllegalArgumentException("null cg");
		}
		this.cg = cg;
		this.pointerKeyFactory = pointerKeyFactory;
		this.instanceKeyFactory = instanceKeyFactory;
		// when doing paranoid checking of points-to sets, code in IPAPointsToSetVariable needs to know about the instance key
		// mapping
//		if (IPAPointsToSetVariable.PARANOID) {
//			IPAPointsToSetVariable.instanceKeys = instanceKeys;
//		}
//		if(use_wcc) {
//			//assistant edges are constructed during the whole analysis
//			createWCCEngine();
//			flowGraph.setUseWCC(use_wcc);
//		}else if(use_scc) {
//			createSCCEngine();
//			flowGraph.setUseSCC(use_scc);
//		}
	}

	/**
	 * bz: 
	 * @param cg
	 * @param pointerKeyFactory
	 * @param instanceKeyFactory
	 * @param num: the number of parallel threads 
	 */
	public IPAPropagationSystem(CallGraph cg, IPAPointerKeyFactory pointerKeyFactory, InstanceKeyFactory instanceKeyFactory, int num, boolean maxparallel) {
		if (cg == null) {
			throw new IllegalArgumentException("null cg");
		}
		this.cg = cg;
		this.pointerKeyFactory = pointerKeyFactory;
		this.instanceKeyFactory = instanceKeyFactory;
		// when doing paranoid checking of points-to sets, code in IPAPointsToSetVariable needs to know about the instance key
		// mapping
//		if (IPAPointsToSetVariable.PARANOID) {
//			IPAPointsToSetVariable.instanceKeys = instanceKeys;
//		}
//		if(use_wcc) {
//			//assistant edges are constructed during the whole analysis
//			createWCCEngine();
//			flowGraph.setUseWCC(use_wcc);
//		}else if(use_scc) {
//			createSCCEngine();
//			flowGraph.setUseSCC(use_scc);
//		}
		initialParallelSystem(false, num, maxparallel);
	}

	/**bz:
	 * initialze parallel system
	 * parallel when nrOfWorkers > 1
	 * false: use the thread pool
	 * true: use akka
	 * @param i
	 */
	public void initialParallelSystem(boolean useAkka, int nrOfWorkers, boolean maxparallel){
		IPAPropagationSystem.useAkka = useAkka;
		//bz: parallel system
		if(useAkka){
			//bz: initialize akka system
			// startAkkaSys();
		}else{
			//bz: initialize the WorkStealingPool
//			System.err.println("WorkStealingPool initialized. ");
			threadHub = new ThreadHub(nrOfWorkers, maxparallel);
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


	/**
	 * Keep this method private .. this returns the actual backing set for the class, which we do not want to expose to clients.
	 */
	private MutableIntSet findOrCreateSparseSetForClass(IClass klass) {
		assert klass.getReference() != TypeReference.JavaLangObject;
		MutableIntSet result = class2InstanceKey.get(klass);
		if (result == null) {
			result = IPAIntSetUtil.getDefaultIntSetFactory().make();
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
			return IPAIntSetUtil.getDefaultIntSetFactory().make();
		} else {
			// return a copy.
			return IPAIntSetUtil.getDefaultIntSetFactory().makeCopy(set);
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
	 * de -- record that a particular points-to-set is represented implicitly.
	 */
	public void derecordImplicitPointsToSet(PointerKey key) {
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
				Assertions.UNREACHABLE("How can parameter be implicit and then deimplicit?");
			}
		}
		pointsToMap.derecordImplicit(key);
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
			/**
			 * bz: a key may need to be updated during changes
			 */
			if(isChange)
				pointsToMap.derecordImplicit(key);
//			else
////              Original code: not working if incremental				
//				System.err.println("Did not expect to findOrCreatePointsToSet for implicitly represented PointerKey");
////				System.err.println(key);
////				Assertions.UNREACHABLE();
		}
		
		IPAPointsToSetVariable result = pointsToMap.getPointsToSet(key);
		if (result == null) {
			result = new IPAPointsToSetVariable(key);
			pointsToMap.put(key, result);
//			System.out.println("IPAPointsToSetVariable is null... should not be null...");
		} else {
			// check that the filter for this variable remains unique
			if (key instanceof IPAFilteredPointerKey) {
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
				//TODO: comment it back 
//				if (!fpk.getTypeFilter().equals(((IPAFilteredPointerKey) key).getTypeFilter())) {
//					Assertions.UNREACHABLE("Cannot use filter " + ((IPAFilteredPointerKey) key).getTypeFilter() + " for " + key
//							+ ": previously created different filter " + fpk.getTypeFilter());
//				}
			}
		}
		return result;
	}

	public int findOrCreateIndexForInstanceKey(InstanceKey key) {
		int result = instanceKeys.getMappedIndex(key);
		if (result == -1) {
			result = instanceKeys.add(key);
		}
		else if(key instanceof ThreadNormalAllocationInNode) {
			if(((ThreadNormalAllocationInNode) key).getRunTarget() != null) {//bz: do not create new instances for this thread?? i forget why add this code .... 
				instanceKeys.put(result, key);
			}
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
		}catch(Exception e){System.err.println(e.toString());return false;}

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
			IPAPointsToSetVariable rhs, IPAPointsToSetVariable base) {
		if (op == null) {
			throw new IllegalArgumentException("operator is null");
		}
		//tell scc engine to perform incremental detection after deleting all these relations...
//		if(use_wcc) {
//			wccEngine.setGroupWork(true);
//		}else if(use_scc){
//			sccEngine.setGroupWork(true);
//		}
		//start delete 
		for (IPAPointsToSetVariable lhs : lhss) {
			IPAUnaryStatement s = op.makeEquation(lhs, rhs);
			if (!getFixedPointSystem().containsStatement(s)) {
				continue;
			}
			if(getFirstDel()){
				getFixedPointSystem().removeStatement(s);
			}
			//wcc
//			if(use_wcc)
//				wccEngine.removeAssistEdge(base, lhs);
		}
//		if(use_wcc) {
//			wccEngine.setGroupWork(false);
//		}else if(use_scc){
//			sccEngine.setGroupWork(false);
//		}
//		//tell scc engine to work now
//		if(use_wcc) {
//			wccEngine.removeMultiEdges();
//		}else if(use_scc){
//			sccEngine.removeMultiEdges();
//		}
	}


	private void addMultiStatementsToFlowGraph_Parallel(ArrayList<IPAPointsToSetVariable> lhss, IPAAssignOperator op,
			IPAPointsToSetVariable rhs, IPAPointsToSetVariable base) {
		if (op == null) {
			throw new IllegalArgumentException("operator is null");
		}
//		tell scc engine to perform incremental detection after adding all these relations...
//		if(use_wcc) {
//			wccEngine.setGroupWork(true);
//		}else  if(use_scc){
//			sccEngine.setGroupWork(true);
//		}
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
			//wcc
//			if(use_wcc)
//				wccEngine.addAssistEdge(base, lhs);
		}
//		if(use_wcc) {
//			wccEngine.setGroupWork(false);
//		}else  if(use_scc){
//			sccEngine.setGroupWork(false);
//		}
//		//tell scc engine to work now
//		if(use_wcc) {
//			wccEngine.addMultiEdges();
//		}else  if(use_scc){
//			sccEngine.addMultiEdges();
//		}
	}


	private void delMultiStatementsFromFlowGraph(IPAPointsToSetVariable lhs, IPAAssignOperator op,
			ArrayList<IPAPointsToSetVariable> rhss, IPAPointsToSetVariable base) {
		if (lhs == null){
			throw new IllegalArgumentException("null lhs");
		}
		if (op == null) {
			throw new IllegalArgumentException("operator is null");
		}
		//tell scc engine to perform incremental detection after deleting all these relations...
//		if(use_wcc) {
//			wccEngine.setGroupWork(true);
//		}else if(use_scc){
//			sccEngine.setGroupWork(true);
//		}
		//start delete
		for (IPAPointsToSetVariable rhs : rhss) {
			IPAUnaryStatement s = op.makeEquation(lhs, rhs);
			if (!getFixedPointSystem().containsStatement(s)) {
				continue;
			}
			if(getFirstDel()){
				getFixedPointSystem().removeStatement(s);
			}
			//wcc
//			if(use_wcc)
//				wccEngine.removeAssistEdge(base, rhs);
		}
//		if(use_wcc) {
//			wccEngine.setGroupWork(false);
//		}else if(use_scc){
//			sccEngine.setGroupWork(false);
//		}
		//tell scc engine to work now
//		if(use_wcc) {
//			wccEngine.removeMultiEdges();
//		}else if(use_scc){
//			sccEngine.removeMultiEdges();
//		}
		
		//tell wcc engine to do the same thing
	}


	/**
	 * bz: only add statement from flow graph without propagation
	 * @param lhs
	 * @param operator
	 * @param rhs
	 * @return
	 */
	private void addMultiStatementsToFlowGraph_Parallel(IPAPointsToSetVariable lhs, IPAAssignOperator op,
			ArrayList<IPAPointsToSetVariable> rhss,  IPAPointsToSetVariable base) {
		if(lhs == null){
			throw new IllegalArgumentException("null lhs");
		}
		if (op == null) {
			throw new IllegalArgumentException("operator is null");
		}
		//tell scc engine to perform incremental detection after adding all these relations...
//		if(use_wcc) {
//			wccEngine.setGroupWork(true);
//		}else if(use_scc){
//			sccEngine.setGroupWork(true);
//		}
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
			if(s.toString().contains("[Node: < Primordial, Ljava/lang/CharacterData02, digit(II)I > Context: CallStringContext: [ java.lang.Character.digit(II)I@6 java.lang.Character.digit(CI)I@2 ], v1]"))
				System.out.println(s.toString());
			//wcc
//			if(use_wcc)
//				wccEngine.addAssistEdge(base, rhs);
		}
//		if(use_wcc) {
//			wccEngine.setGroupWork(false);
//		}else if(use_scc){
//			sccEngine.setGroupWork(false);
//		}
		//tell scc engine to work now
//		if(use_wcc) {
//			wccEngine.addMultiEdges();
//		}else if(use_scc){
//			sccEngine.addMultiEdges();
//		}
	}


	/**
	 * bz: 1 lhs = multi rhs
	 * base --> rhss : assist edges
	 * @param base 
	 */
	public boolean delConstraintHasMultiR(IPAPointsToSetVariable lhs, IPAAssignOperator op,
			ArrayList<IPAPointsToSetVariable> rhss, MutableIntSet delset, IPAPointsToSetVariable base) {
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

		delMultiStatementsFromFlowGraph(lhs, op, rhss, base);

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
	 * @param base 
	 * @param rhs
	 * @return
	 */
	public boolean addConstraintHasMultiR_Parallel(IPAPointsToSetVariable lhs, IPAAssignOperator op, ArrayList<IPAPointsToSetVariable> rhss,
			MutableIntSet addset, IPAPointsToSetVariable base) {
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
		addMultiStatementsToFlowGraph_Parallel(lhs, op, rhss, base);

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
	public boolean addConstraintHasMultiL_Parallel(ArrayList<IPAPointsToSetVariable> lhss, IPAAssignOperator op, IPAPointsToSetVariable rhs,
			final MutableIntSet targets, IPAPointsToSetVariable base) {
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
		addMultiStatementsToFlowGraph_Parallel(lhss, op, rhs, base);

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
	 * @param base 
	 * @return
	 */
	public boolean delConstraintHasMultiL(ArrayList<IPAPointsToSetVariable> lhss, IPAAssignOperator op,
			IPAPointsToSetVariable rhs, final MutableIntSet targets, IPAPointsToSetVariable base) {
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
		delMultiStatementsFromFlowGraph(lhss, op, rhs, base);

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
		MutableIntSet delSet = IPAIntSetUtil.make();
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

	public IPAMutableSharedBitVectorIntSet computeRemaining(MutableIntSet delSet, IPAPointsToSetVariable L){
		//recompute L
		final IPAMutableSharedBitVectorIntSet remaining = new IPAMutableSharedBitVectorIntSetFactory().makeCopy(delSet);
		for (IPAPointsToSetVariable pv : flowGraph.getPointsToSetVariablesThatDefImplicitly(L)) {
			if(remaining.isEmpty())
				break;
//			if(pv instanceof SCCVariable){
//				((SCCVariable) pv).ifOthersCanProvide(L, remaining, delSet);
//			}else 
			if(pv.getValue() != null){
				if(remaining.size() == 0)
					break;
				DeletionUtil.removeSome(remaining, pv.getValue());
			}
		}
		return remaining;
	}
	
	/**
	 * bz: the main procedure to delete points-to constraints
	 * @param l
	 * @param delSet
	 */
	public void procedureToDelPointsToSet(IPAPointsToSetVariable L, final MutableIntSet delSet, boolean isRoot) {
		if(!isRoot){
			if(isTransitiveRoot(L.getPointerKey()))
				return;
		}
		
//		if(within_wcc) {
//			byte changed = wlSolver.singleProcedureToDelPointsToSet(L, delSet);
//			if(changed == CHANGED) {
//				wlSolver.changedVariable(L);
//			}
//			return;
//		}
		final IPAMutableSharedBitVectorIntSet remaining = computeRemaining(delSet, L);
		//schedule task if changes
		if(!remaining.isEmpty()){
			DeletionUtil.removeSome(L, remaining);
			if(L.getChange().size() > 0){
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
							threadHub.initialRRTasks(L.getChange(), firstUsers, this);
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
	protected void singleProcedureToDelPointsToSet(final IPAPointsToSetVariable L, final MutableIntSet targets){
		if(isTransitiveRoot(L.getPointerKey()))
			return;
		final IPAMutableSharedBitVectorIntSet remaining = computeRemaining(targets, L);
		//if not reachable, deleting, and continue for other nodes
		if(!remaining.isEmpty()){
			DeletionUtil.removeSome(L, remaining);
			if(L.getChange().size() > 0){
				if(!changes.contains(L)){
					changes.add(L);
				}
				classifyPointsToConstraints(L, L.getChange());
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
//		if(use_wcc) {
//			Integer lid = L.getGraphNodeId();
//			if(wccEngine.belongToWCC(lid)) {
//				WCC wcc = wccEngine.getCorrespondingWCC(lid);
//				//propagate inside wcc
//				HashSet<IPAPointsToSetVariable> delta = propagateChangeWithinWCC(L, targets, wcc);
//				changes.addAll(delta);
//				//propagate outside wcc
//				propagateChangeOutsideWCC(delta, wcc);
//				return;
//			}
//		}
		//when not use wcc || when L does not belong to any wcc
		for (Iterator it = flowGraph.getStatementsThatUse(L); it.hasNext();) {
			IPAAbstractStatement s = (IPAAbstractStatement) it.next();
			IPAAbstractOperator op = s.getOperator();
			if(op instanceof IPAAssignOperator){//|| op instanceof IPAFilterOperator
				if(checkSelfRecursive(s))
					continue;
				IPAPointsToSetVariable pv = (IPAPointsToSetVariable) s.getLHS();
				if(pv.getValue() != null)
					singleProcedureToDelPointsToSet(pv, targets);
			}
			else{// all other complex constraints
				addToWorkList(s);
			}
		}
	}

//	private void propagateChangeOutsideWCC(HashSet<IPAPointsToSetVariable> delta, WCC wcc) {
//		for (IPAPointsToSetVariable v : delta) {
//			//when not use wcc || when L does not belong to any wcc
//			for (Iterator it = flowGraph.getStatementsThatUseOutsideWCC(v, wcc); it.hasNext();) {
//				IPAAbstractStatement s = (IPAAbstractStatement) it.next();
//				IPAAbstractOperator op = s.getOperator();
//				if(op instanceof IPAAssignOperator){//|| op instanceof IPAFilterOperator
//					if(checkSelfRecursive(s))
//						continue;
//					IPAPointsToSetVariable pv = (IPAPointsToSetVariable) s.getLHS();
//					if(pv.getValue() != null)
//						singleProcedureToDelPointsToSet(pv, v.getChange());
//				}
//				else{// all other complex constraints
//					addToWorkList(s);
//				}
//			}
//		}
//	}

	/**
	 * propagation when considering wcc
	 * @param l
	 * @param targets
	 * @param wcc
	 * @return
	 */
//	private HashSet<IPAPointsToSetVariable> propagateChangeWithinWCC(IPAPointsToSetVariable L, MutableIntSet change,
//			WCC wcc) {
//		HashSet<IPAPointsToSetVariable> delta = new HashSet<>();
//		//sequentially use worklist algorithm
//		wlSolver.initial(L, (IPAMutableSharedBitVectorIntSet) change, wcc);
//		wlSolver.solve();
//		return wlSolver.getDeltas();
//	}

	/**
	 * bz
	 * @param L
	 * @return get statements/variables that use L
	 */
	ArrayList<IPAPointsToSetVariable> findFirstUsers(IPAPointsToSetVariable L) {
		ArrayList<IPAPointsToSetVariable> results = new ArrayList<>();
		Iterator it = flowGraph.getStatementsThatUse(L);
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
	
	


	public boolean newFieldReadGroup(PointerKey lhs, IPAAssignOperator assignoperator, HashSet<PointerKey> rhss) {
		if (DEBUG) {
			System.err.println("Add constraint A: " + lhs + " " + assignoperator + " " + rhss);
		}
		IPAPointsToSetVariable L = findOrCreatePointsToSet(lhs);
		IPAMutableSharedBitVectorIntSet diff = new IPAMutableSharedBitVectorIntSet();
		for (PointerKey rhs : rhss) {
			IPAPointsToSetVariable R = findOrCreatePointsToSet(rhs);
			newStatementOnly(L, assignoperator, R);
			if(R.getValue() != null) {
				diff.addAll(R.getValue());
			}
		}
		return incorporateNewStatements(L, diff, true, true);
	}
	

	public boolean newFieldWriteGroup(HashSet<PointerKey> lhss, IPAAssignOperator assignoperator,
			PointerKey rhs) {
		if (DEBUG) {
			System.err.println("Add constraint A: " + lhss + " " + assignoperator + " " + rhs);
		}
		IPAPointsToSetVariable R = findOrCreatePointsToSet(rhs);
		ArrayList<IPAPointsToSetVariable> Ls = new ArrayList<>();
		for (PointerKey lhs : lhss) {
			IPAPointsToSetVariable L = findOrCreatePointsToSet(lhs);
			newStatementOnly(L, assignoperator, R);
			Ls.add(L);
		}
		if(R.getValue() != null) {
			addOrDelASetFromMultiLhs(Ls, R.getValue(), true);
			return true;
		}else
			return false;
	}



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
		IPAPointsToSetVariable L = findOrCreatePointsToSet(lhs);
		IPAPointsToSetVariable R1 = findOrCreatePointsToSet(rhs1);
		IPAPointsToSetVariable R2 = findOrCreatePointsToSet(rhs2);
		return newStatement(L, op, R1, R2, true, true);
	}

	/**
	 * base -> lhs : assist edge
	 * @return true iff the system changes
	 */
	public boolean newFieldWrite(PointerKey lhs, IPAUnaryOperator<IPAPointsToSetVariable> op, PointerKey rhs, IPAPointsToSetVariable base) {
//		if(use_wcc) {
//			if(isChange) {
//				IPAPointsToSetVariable L = findOrCreatePointsToSet(lhs);
//				wccEngine.addAssistEdge(base, L);
//				return newConstraint(lhs, op, rhs);
//			}else {
//				boolean change = newConstraint(lhs, op, rhs);//obtain id for L
//				IPAPointsToSetVariable L = findOrCreatePointsToSet(lhs);
//				wccEngine.addAssistEdge(base, L);
//				return change;
//			}
//		}else {
			return newConstraint(lhs, op, rhs);
//		}
	}
	
	/**
	 * base -> lhs: assist edge
	 * @param lhs
	 * @param op
	 * @param rhs
	 * @param base
	 * @return
	 */
	public boolean delFieldWrite(PointerKey lhs, IPAUnaryOperator<IPAPointsToSetVariable> op, PointerKey rhs, IPAPointsToSetVariable base) {
//		if(use_wcc) {
//			//first remove wcc edge
//			IPAPointsToSetVariable L = findOrCreatePointsToSet(lhs);
//			wccEngine.removeAssistEdge(base, L);
//		}
		//solve
		return delConstraint(lhs, op, rhs);
	}

	/**
	 * base -> rhs : assist edge
	 * @return true iff the system changes
	 */
	public boolean newFieldRead(PointerKey lhs, IPAUnaryOperator<IPAPointsToSetVariable> op, PointerKey rhs, IPAPointsToSetVariable base) {
//		if(use_wcc) {
//			if(isChange) {
//				IPAPointsToSetVariable R = findOrCreatePointsToSet(rhs);
//				wccEngine.addAssistEdge(base, R);
//				return newConstraint(lhs, op, rhs);
//			}else {
//				boolean change = newConstraint(lhs, op, rhs);
//				IPAPointsToSetVariable R = findOrCreatePointsToSet(rhs);
//				wccEngine.addAssistEdge(base, R);
//				return change;
//			}
//		}else {
			return newConstraint(lhs, op, rhs);
//		}
	}
	
	@SuppressWarnings("unused") //arrayload and getfield op do not need this : not differentiate filter/assign
	public boolean delFieldRead(PointerKey lhs, IPAUnaryOperator<IPAPointsToSetVariable> op, PointerKey rhs) {
		return delConstraint(lhs, op, rhs);
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
		}
		
		L.add(index);

		if(RECORD_ROOT) { //bz: only once after running the whole program analysis
			root.putIfAbsent(lhs, index);
		}
		
		if(!(value instanceof NullConstantKey)) {//bz: path
			// also register that we have an instanceKey for the klass
			assert value.getConcreteType() != null;

			if (!value.getConcreteType().getReference().equals(TypeReference.JavaLangObject)) {
				registerInstanceOfClass(value.getConcreteType(), index);
			}
		}
		// we'd better update the worklist appropriately
		// if graphNodeId == -1, then there are no equations that use this
		// variable.
		if (L.getGraphNodeId() > -1) {
			changedVariable(L);
		}
		return true;
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
	@SuppressWarnings("unused")
	public int getNumberOfPointerKeys() {
		return pointsToMap.getNumberOfPointerKeys();
	}

	/**
	 * Use with care.
	 */
	IPAWorklist getWorklist() {
		return workList;
	}
	
	@SuppressWarnings("unused")
	public Iterator<IPAAbstractStatement> getStatementsThatUse(IPAPointsToSetVariable v) {
		return flowGraph.getStatementsThatUse(v);
	}
	@SuppressWarnings("unused")
	public Iterator<IPAAbstractStatement> getStatementsThatDef(IPAPointsToSetVariable v) {
		return flowGraph.getStatementsThatDef(v);
	}
	@SuppressWarnings("unused")
	public NumberedGraph<IPAPointsToSetVariable> getAssignmentGraph() {
		return flowGraph.getAssignmentGraph();
	}
	@SuppressWarnings("unused")
	public Graph<IPAPointsToSetVariable> getFilterAsssignmentGraph() {
		return flowGraph.getFilterAssignmentGraph();
	}

	/**
	 * NOTE: do not use this method unless you really know what you are doing. Functionality is fragile and may not work in the
	 * future.
	 */
	@SuppressWarnings("unused")
	public Graph<IPAPointsToSetVariable> getFlowGraphIncludingImplicitConstraints() {
		return flowGraph.getFlowGraphIncludingImplicitConstraints();
	}
	
	@SuppressWarnings("unused")
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

	@SuppressWarnings("unused")
	public int getNumber(PointerKey p) {
		return pointsToMap.getIndex(p);
	}

	@Override
	protected IPAPointsToSetVariable[] makeStmtRHS(int size) {
		return new IPAPointsToSetVariable[size];
	}


	public MutableMapping<InstanceKey> getInstanceKeys() {
		return instanceKeys;
	}


}
