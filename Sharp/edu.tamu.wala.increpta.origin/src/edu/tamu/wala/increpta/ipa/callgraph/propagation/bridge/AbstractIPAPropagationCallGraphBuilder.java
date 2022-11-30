package edu.tamu.wala.increpta.ipa.callgraph.propagation.bridge;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.CallGraphBuilder;
import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.SSAContextInterpreter;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IRView;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSACFG.BasicBlock;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.MonitorUtil;
import com.ibm.wala.util.MonitorUtil.IProgressMonitor;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.ref.ReferenceCleanser;

import edu.tamu.wala.increpta.ipa.callgraph.propagation.MyInstructionVisitor;
import edu.tamu.wala.increpta.util.JavaUtil;


/**
 * 
 * just want an abstract class for both IPAPropagationCallGraphBuilder and IPAPropagationCallGraphBuilderByDB,
 * both IPASSAPropagationCallGraphBuilder and IPASSAPropagationCallGraphBuilderByDB
 * to skip duplicate code
 * 
 * @author bozhen
 *
 */
public abstract class AbstractIPAPropagationCallGraphBuilder implements CallGraphBuilder<InstanceKey> {
	
	////////////////////   original from IPASSAxxx  ////////////////////
	private final static boolean DEBUG = false;

	/**
	 * Should we periodically clear out soft reference caches in an attempt to help the GC?
	 */
	public final static boolean PERIODIC_WIPE_SOFT_CACHES = true;

	/**
	 * Interval which defines the period to clear soft reference caches
	 */
	public final static int WIPE_SOFT_CACHE_INTERVAL = 2500;

	/**
	 * Counter for wiping soft caches
	 */
	private static int wipeCount = 0;

	/**
	 * An optimization: if we can locally determine that a particular pointer p has exactly one use, then we don't actually create the
	 * points-to-set for p, but instead short-circuit by propagating the final solution to the unique use.
	 *
	 * Doesn't play well with pre-transitive solver; turning off for now.
	 */
	protected final static boolean SHORT_CIRCUIT_SINGLE_USES = false;
	
	/** 
	 * bz: flag for deleting constraints
	 */
	protected static boolean isDelete = false;
	
	public IProgressMonitor monitor;

	/**
	 * Set of nodes that have already been traversed for constraints
	 */
	final private Set<CGNode> alreadyVisited = HashSetFactory.make();

	/**
	 * At any given time, the set of nodes that have been discovered but not yet processed for constraints
	 */
	private Set<CGNode> discoveredNodes = HashSetFactory.make();
	
	/**
	 * bz: flags
	 */
	public boolean isChange = false;
	public void setChange(boolean p){
		isChange = p;
	}
	
	/**
	 * bz: for kcfa/kobj only: whether we are in opt section, 
	 * e.g., AbstractKIPASSAPropagationCallGraphBuilder.moreDeletion()
	 */
	protected boolean isOPT = false;
	protected void setOPT(boolean p){
		isOPT = p;
	}
	
	/**
	 * Incremental change only:
	 * a set of nodes that are newly discovered 
	 */
	private Set<CGNode> newlyDiscoveredNodes = HashSetFactory.make();
	
	/**
	 * Incremental change only:
	 * a set of thread nodes that will be removed, as well as its callee methods.
	 */
	private Set<CGNode> willRemoveNodes = HashSetFactory.make();

	/**
	 * Incremental change only:
	 * a set of thread nodes that have already been removed, as well as its callee methods.
	 */
	private Set<CGNode> alreadyRemoveNodes = HashSetFactory.make();

	
	public AbstractIPAPropagationCallGraphBuilder() {
	}
	
	public void initialDiscoveredNodes() {
		discoveredNodes = HashSetFactory.make();
	}
	
	public Set<CGNode> getDiscoveredNodes() {
		return discoveredNodes;
	}
	
	public boolean haveAlreadyVisited(CGNode node) {
		return alreadyVisited.contains(node);
	}

	protected void markAlreadyVisited(CGNode node) {
		alreadyVisited.add(node);
	}

	/**
	 * incremental: record that we've discovered a node 
	 */
	public void markDiscovered(CGNode node) {
		discoveredNodes.add(node);
		if(isChange)
			newlyDiscoveredNodes.add(node);//isChange == true
	}
	
	public boolean haveAlreadyRemoved(CGNode node) {
		return alreadyRemoveNodes.contains(node);
	}
	
	public void markAlreadyRemoved(CGNode node) {
		alreadyRemoveNodes.add(node);
		alreadyVisited.remove(node);
		willRemoveNodes.remove(node);
	}
	
	public boolean markWillRemove(CGNode node) {
		return willRemoveNodes.add(node);
	}
	
	public Set<CGNode> getWillRemoveNodes() {
		return willRemoveNodes;
	}
	
	public void clearWillRemoveNodes() {
		willRemoveNodes.clear();
	}
	
	public Set<CGNode> getAlreadyRemoved() {
		return alreadyRemoveNodes;
	}
	
	public Set<CGNode> getNewlyDiscoveredNodes() {
		return newlyDiscoveredNodes;
	}
	
	/**
	 * clear after each source code change
	 */
	public void clearNewlyDiscoveredNodes() {
		newlyDiscoveredNodes.clear();
	}

	public void clearAlreadyRemoved() {
		alreadyRemoveNodes.clear();
	}
	
	public void markChanged(CGNode node) {
		alreadyVisited.remove(node);
		discoveredNodes.add(node);
		if(isChange)
			newlyDiscoveredNodes.add(node);//isChange == true
	}

	protected boolean wasChanged(CGNode node) {
		return discoveredNodes.contains(node) && !alreadyVisited.contains(node);
	}
	
	protected boolean wasChanged2(CGNode node) {
		return willRemoveNodes.contains(node) && !alreadyRemoveNodes.contains(node);
	}
	
	@Override
	public CallGraph makeCallGraph(AnalysisOptions options, IProgressMonitor monitor)
			throws IllegalArgumentException, CallGraphBuilderCancelException {
		// TODO Auto-generated method stub
		return null;
	}
	
	public CallGraph makeCallGraph(AnalysisOptions options, IProgressMonitor monitor, int parallelNumThreads, boolean maxparallel)
			throws IllegalArgumentException, CallGraphBuilderCancelException {
		// TODO Auto-generated method stub
		return null;
	}
	
///////////////////////////// origin from IPAPropagationCallGraphBuilder  /////////////////////////////////
	/**
	 * bz: I removed the following abstract functions, since both implementations (IPASSAxxxx) are the same
	 * Added implementations to IPASSAxxxx part
	 */
//	protected abstract boolean addConstraintsFromNode(CGNode n, IProgressMonitor monitor) throws CancelException;
//	protected abstract boolean delConstraintsFromNode(CGNode n, IProgressMonitor monitor) throws CancelException;
//	protected abstract boolean unconditionallyAddConstraintsFromNode(CGNode node, IProgressMonitor monitor) throws CancelException;
//	protected abstract boolean unconditionallyDelConstraintsFromNode(CGNode node, IProgressMonitor monitor) throws CancelException;

	
	/**
	 * Add constraints from newly discovered nodes. Note: the act of adding constraints may discover new nodes, so this routine is
	 * iterative.
	 *
	 * @return true iff any new constraints are added.
	 * @throws CancelException
	 */
	public boolean addConstraintsFromNewNodes(IProgressMonitor monitor) throws CancelException {
		boolean result = false;
		while (!discoveredNodes.isEmpty()) {
			Iterator<CGNode> it = discoveredNodes.iterator();
			discoveredNodes = HashSetFactory.make();
			while (it.hasNext()) {
				CGNode n = it.next();
				result |= addConstraintsFromNode(n, monitor);
			}
		}
		return result;
	}


	public boolean delConstraintsFromWillRemoveNodes(IProgressMonitor monitor) throws CancelException {
		boolean result = false;
		while (!willRemoveNodes.isEmpty()) {
			Iterator<CGNode> it = willRemoveNodes.iterator();
			willRemoveNodes = HashSetFactory.make();
			while (it.hasNext()) {
				CGNode n = it.next();
				result |= delConstraintsFromNode(n, null);
			}
		}
		return result;		
	}
	
	
	
///////////////////////////// origin from IPASSAPropagationCallGraphBuilder  /////////////////////////////////

	public abstract void setDelete(boolean delete);
	public abstract void setFirstDel(boolean is);
	
	protected abstract void addPhiConstraints(CGNode node, ControlFlowGraph<SSAInstruction, ISSABasicBlock> controlFlowGraph, BasicBlock b,
			MyInstructionVisitor v);
	protected abstract void delPhiConstraints(CGNode node, ControlFlowGraph<SSAInstruction, ISSABasicBlock> controlFlowGraph, BasicBlock b,
			MyInstructionVisitor v);
	protected abstract void delPhiConstraint(CGNode node, MyInstructionVisitor vv, SSAPhiInstruction phi, 
			HashSet<Integer> keep_ids, HashSet<Integer> remove_ids);
	
	public abstract SSAContextInterpreter getCFAContextInterpreter();

	protected abstract void addNodeInstructionConstraints(CGNode node, IProgressMonitor monitor) throws CancelException;
	protected abstract void delNodeInstructionConstraints(CGNode node, IProgressMonitor monitor) throws CancelException;
	
	protected abstract void addNodePassthruExceptionConstraints(CGNode node, IRView ir, DefUse du);
	protected abstract void delNodePassthruExceptionConstraints(CGNode node, IRView ir, DefUse du, int iindex);

	
	
	

	/**
	 * Visit all instructions in a node, and add dataflow constraints induced by each statement in the SSA form.
	 * @throws CancelException
	 *
	 * @see com.ibm.wala.ipa.callgraph.propagation.PropagationCallGraphBuilder#addConstraintsFromNode(com.ibm.wala.ipa.callgraph.CGNode)
	 */
	protected boolean addConstraintsFromNode(CGNode node, IProgressMonitor monitor) throws CancelException {
		this.monitor = monitor;
		if (haveAlreadyVisited(node)) {
			return false;
		} else {
			markAlreadyVisited(node);
		}
		return unconditionallyAddConstraintsFromNode(node, monitor);
	}
	
	/**
	 * reverse to addConstraintsFromNode()
	 */
	protected boolean delConstraintsFromNode(CGNode node, IProgressMonitor monitor) throws CancelException {
		this.monitor = monitor;
		if (haveAlreadyRemoved(node)) {
			return false;
		} else {
			markAlreadyRemoved(node);
		}
		return unconditionallyDelConstraintsFromNode(node, monitor);
	}
	
	
	protected boolean unconditionallyAddConstraintsFromNode(CGNode node, IProgressMonitor monitor) throws CancelException {
		this.monitor = monitor;
		if (PERIODIC_WIPE_SOFT_CACHES) {
			wipeCount++;
			if (wipeCount >= WIPE_SOFT_CACHE_INTERVAL) {
				wipeCount = 0;
				ReferenceCleanser.clearSoftCaches();
			}
		}

		if (DEBUG) {
			System.err.println("\n\nAdd constraints from node " + node);
		}
		
		IRView ir = getCFAContextInterpreter().getIRView(node);
		if (DEBUG) {
			if (ir == null) {
				System.err.println("\n   No statements\n");
			} else {
				try {
					System.err.println(ir.toString());
				} catch (Error e) {
					e.printStackTrace();
				}
			}
		}

		if (ir == null) {
			return false;
		}

//		if(!JavaUtil.isJDKClass(node.getMethod().getDeclaringClass())) {
//			((IPAExplicitNode) node).IGNORE_BRANCH = true;
//		}
		
//		System.out.println("====> " + node.toString() + "\n" + node.getIR().toString());
		
 		addNodeInstructionConstraints(node, monitor);

        //addNodeValueConstraints(node, monitor);//unused -> comment off

		DefUse du = getCFAContextInterpreter().getDU(node);
		addNodePassthruExceptionConstraints(node, ir, du);
		return true;
	}
	
	protected boolean unconditionallyDelConstraintsFromNode(CGNode node, IProgressMonitor monitor) throws CancelException {
		this.monitor = monitor;
		if (PERIODIC_WIPE_SOFT_CACHES) {
			wipeCount++;
			if (wipeCount >= WIPE_SOFT_CACHE_INTERVAL) {
				wipeCount = 0;
				ReferenceCleanser.clearSoftCaches();
			}
		}

		if (DEBUG) {
			System.err.println("\n\nAdd constraints from node " + node);
		}
		IRView ir = getCFAContextInterpreter().getIRView(node);
		if (DEBUG) {
			if (ir == null) {
				System.err.println("\n   No statements\n");
			} else {
				try {
					System.err.println(ir.toString());
				} catch (Error e) {
					e.printStackTrace();
				}
			}
		}

		if (ir == null) {
			return false;
		}
		
		setDelete(true);
		setFirstDel(true);
		delNodeInstructionConstraints(node, monitor);

        //delNodeValueConstraints(node, monitor);//unused -> comment off

		//will handler later...
		DefUse du = getCFAContextInterpreter().getDU(node);
		delNodePassthruExceptionConstraints(node, ir, du, 0); //start from 1st stmt
		
//		setDelete(false);
//		system.setFirstDel(false);
		
		return true;
	}
	
	
	protected void _addNodeInstructionConstraints(CGNode node, MyInstructionVisitor v, IProgressMonitor monitor) throws CancelException {
		IRView ir = v.getIR(); 
		for (Iterator<ISSABasicBlock> x = ir.getBlocks(); x.hasNext();) {
			BasicBlock b = (BasicBlock) x.next();
			addBlockInstructionConstraints(node, ir, b, v, monitor);
			if (wasChanged(node)) {
				return;
			}
		}
	}
	
	
	protected void _delNodeInstructionConstraints(CGNode node, MyInstructionVisitor v, IProgressMonitor monitor) throws CancelException {
		IRView ir = v.getIR();
		for (Iterator<ISSABasicBlock> x = ir.getBlocks(); x.hasNext();) {
			BasicBlock b = (BasicBlock) x.next();
			delBlockInstructionConstraints(node, ir, b, v, monitor);
			if (wasChanged2(node)) {
				return;
			}
		}
	}
	
	
	/**
	 * Add constraints for a particular basic block.
	 * @throws CancelException
	 */
	protected void addBlockInstructionConstraints(CGNode node, IRView ir, BasicBlock b,
			MyInstructionVisitor v, IProgressMonitor monitor) throws CancelException {
		this.monitor = monitor;
		v.setBasicBlock(b);

		// visit each instruction in the basic block.
		for (Iterator<SSAInstruction> it = b.iterator(); it.hasNext();) {
			MonitorUtil.throwExceptionIfCanceled(monitor);
			SSAInstruction s = it.next();
			if (s != null) {
				s.visit(v);
				if (wasChanged(node)) {
					return;
				}
			}
		}

		addPhiConstraints(node, ir.getControlFlowGraph(), b, v);//lhs == rhs
	}
	
	protected void delBlockInstructionConstraints(CGNode node, IRView ir, BasicBlock b,
			MyInstructionVisitor v, IProgressMonitor monitor) throws CancelException {
		this.monitor = monitor;
		v.setBasicBlock(b);

		// visit each instruction in the basic block.
		for (Iterator<SSAInstruction> it = b.iterator(); it.hasNext();) {
			MonitorUtil.throwExceptionIfCanceled(monitor);
			SSAInstruction s = it.next();
			if (s != null) {
				s.visit(v);
				if (wasChanged2(node)) {
					return;
				}
			}
		}

		delPhiConstraints(node, ir.getControlFlowGraph(), b, v);//lhs == rhs
	}
	
}
