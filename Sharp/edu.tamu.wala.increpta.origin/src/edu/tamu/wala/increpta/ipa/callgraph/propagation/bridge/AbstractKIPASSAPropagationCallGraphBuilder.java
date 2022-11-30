package edu.tamu.wala.increpta.ipa.callgraph.propagation.bridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.ibm.wala.analysis.reflection.JavaTypeContext;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.ContextItem;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.callgraph.propagation.AllocationSiteInNode;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.callgraph.propagation.SSAContextInterpreter;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.IRView;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAGetCaughtExceptionInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSALoadMetadataInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.ssa.SSACFG.BasicBlock;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.MonitorUtil;
import com.ibm.wala.util.intset.IntegerUnionFind;
import com.ibm.wala.util.warnings.Warnings;

import edu.tamu.wala.increpta.callgraph.impl.IPAExplicitCallGraph;
import edu.tamu.wala.increpta.callgraph.impl.IPAExplicitCallGraph.IPAExplicitNode;
import edu.tamu.wala.increpta.change.IRChangedSummary;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPointsToSetVariable;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationSystem;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPASSAPropagationCallGraphBuilder;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.MyInstructionVisitor;
import edu.tamu.wala.increpta.operators.IPAAbstractStatement;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationCallGraphBuilder.InstancePutFieldOperator;
import edu.tamu.wala.increpta.pointerkey.IPAPointerKeyFactory;

/**
 * 
 * includes comment methods for KCFAIPABuilder and KObjIPABuilder
 * 
 * @author bozhen
 *
 */
public abstract class AbstractKIPASSAPropagationCallGraphBuilder extends IPASSAPropagationCallGraphBuilder {
	
	public static boolean DEBUG_K = true;
	public static boolean PARALLEL = false;
	
	// pta data:
	// - size:
	public int total_Sctx = 0;
	public int total_Sinv = 0;
	public int total_delInvokes = 0; //only delete this constraints created by invoke statements
	public int total_invalids = 0; //invalidCGNodes.size()
	
	public void clearData() {
		total_Sctx = 0;
		total_Sinv = 0;
		total_delInvokes = 0;
		total_invalids = 0; 
	}
	
	public void printData(String commitID) {
		System.out.println("============== opt data for commit " + commitID + " ==============");
		System.out.println("#Sctx: " + total_Sctx);
		System.out.println("#Sinv: " + total_Sinv);
		System.out.println("#only del invoke stmts: " + total_delInvokes);
		System.out.println("#invalid cgnodes: " + total_invalids);
		System.out.println("=================================================================");
		
		clearData();
	}
	
	//for my use
	public HashMap<Integer, HashSet<Integer>> baseInstance2Targets = new HashMap<>();
	public Pair2Invokes pair2Invokes = new Pair2Invokes();
	public HashSet<CGNode> possibleDeleteCGNodes = new HashSet<>(); // for moreDeletion() 
	
	public HashSet<CGNode> invalidCGNodes = new HashSet<>(); //𝑁𝑐𝑔-
//	public ArrayList<CGNode> updateCgNodes; //𝑁𝑐𝑔*
	
	public void clear() {
		possibleDeleteCGNodes.clear();
		invalidCGNodes.clear();
		clearData();
	}
	
	//util
	private final IntegerUnionFind uf = new IntegerUnionFind();
	
	// a wrapper of callercallee2Invokes
	private class Pair2Invokes {
		private HashMap<CGNode, HashMap<CGNode, HashSet<SSAAbstractInvokeInstruction>>> callercallee2Invokes = new HashMap<>();
		
		public Pair2Invokes() {}
		
		protected HashSet<SSAAbstractInvokeInstruction> getInvokes(CGNode caller, CGNode callee) {
			HashMap<CGNode, HashSet<SSAAbstractInvokeInstruction>> callee2invokes = callercallee2Invokes.get(caller);
			if(callee2invokes == null) return null;
			return callee2invokes.get(callee);
		}
		
		/**
		 * add update, not delete update.
		 * @param caller
		 * @param callee
		 * @param invoke
		 */
		protected void update(CGNode caller, CGNode callee, SSAAbstractInvokeInstruction invoke) {
			HashMap<CGNode, HashSet<SSAAbstractInvokeInstruction>> callee2invokes = callercallee2Invokes.get(caller);
			if(callee2invokes == null) {
				callee2invokes = new HashMap<>();
				callercallee2Invokes.put(caller, callee2invokes);
			}
			HashSet<SSAAbstractInvokeInstruction> invokes = callee2invokes.get(callee);
			if(invokes == null) {
				invokes = new HashSet<>();
				callee2invokes.put(callee, invokes);
			}
			invokes.add(invoke);
		}

		public void removeInvokes(CGNode caller, CGNode callee) {
			HashMap<CGNode, HashSet<SSAAbstractInvokeInstruction>> callee2invokes = callercallee2Invokes.get(caller);
			assert callee2invokes != null;
			callee2invokes.remove(callee);
		}
	}

	/**
	 * includes comment methods for KCFAIPABuilder and KObjIPABuilder
	 * @param abstractRootMethod
	 * @param options
	 * @param cache
	 * @param pointerKeyFactory
	 */
	protected AbstractKIPASSAPropagationCallGraphBuilder(IMethod abstractRootMethod, AnalysisOptions options,
			IAnalysisCacheView cache, IPAPointerKeyFactory pointerKeyFactory) {
		super(abstractRootMethod, options, cache, pointerKeyFactory);
	}
	
	/**
	 * @param possibles
	 * @return if is new in possibleDeleteCGNodes
	 */
	public boolean updatePossibleDeleteCGNodes(Set<CGNode> possibles) {
		return possibleDeleteCGNodes.addAll(possibles);
	}
	
	/**
	 * @param invalid cgnode
	 * @return if is new in invalidCGNodes
	 */
	public boolean updateInvalidCGNodesAndRemoveIncomingEdges(CGNode invalid) {
		boolean isNew = invalidCGNodes.add(invalid);
		if(isNew) { //avoid dup
			//remove all cg edges NOW for them -> for futher moreDeletion() check 
			callGraph.removeIncomingEdges(invalid);
		}
		return isNew;
	}
	
	/**
	 * clear outgoing cg edges and all its targets in invalid cgnode
	 * @param invalid cgnode
	 * @return if exist in invalidCGNodes
	 */
	public void removeOutgoingEdgesAndTargets(CGNode invalid) {
		((IPAExplicitNode) invalid).clearAllTargets();
	}
	
	/**
	 * further check if target still has other callers except the ones in invalidCGNodes
	 */
	public void moreDeletion() { 
		Set<CGNode> checks = new HashSet<>(); //this iteration
		Set<CGNode> tmp = new HashSet<>(); //next iteration
		checks.addAll(possibleDeleteCGNodes);
		
		while(!checks.isEmpty()) {
			for(CGNode check : checks) {
				int size_callers = callGraph.getPredNodeCount(check);
				if(size_callers == 0) { //check if this is an previously confirmed invalid cgnode by sub-builders
					tmp.addAll(((IPAExplicitNode) check).getAllTargets());
					removeOutgoingEdgesAndTargets(check);
					invalidCGNodes.add(check);
				} else {
					//check if all preds are in invalidCGNodes
					boolean alsoInvalid = true;
					Iterator<CGNode> preds = callGraph.getPredNodes(check);
					while (preds.hasNext()) {
						CGNode pred = preds.next();
						if(!invalidCGNodes.contains(pred)) {
							alsoInvalid = false; 
							break;
						}
					}
					
					if(alsoInvalid) {
						// check its desendents
						tmp.addAll(((IPAExplicitNode) check).getAllTargets());
						//remove this node and cg edges 
						updateInvalidCGNodesAndRemoveIncomingEdges(check);
						removeOutgoingEdgesAndTargets(check);
						continue;
					}
					
					// check is still valid with other callers
					// -> this requires to delete this constraints created by invoke statements
					Iterator<CGNode> preds2 = callGraph.getPredNodes(check);
					while (preds2.hasNext()) {
						CGNode pred = preds2.next();
						if(invalidCGNodes.contains(pred)) {
							// remove this invalid cg edge
							callGraph.removeEdge(pred, check);
							
							// delete this invoke
							HashSet<SSAAbstractInvokeInstruction> invokes = pair2Invokes.getInvokes(pred, check);
							if(invokes == null || invokes.isEmpty()) continue;

							try {
								removeInvokeDeletedConstraintsForCGNode(pred, invokes);
							} catch (CancelException e) {
								e.printStackTrace();
							}
							
							// newly discovered invalid cgnodes from removeInvokeDeletedConstraintsForCGNode()
							// -> update
							Set<CGNode> invalids = getWillRemoveNodes();
							invalidCGNodes.addAll(invalids);
							// add their desendents to tmp for next round check
							for(CGNode invalid : invalids) {
								tmp.addAll(((IPAExplicitNode) invalid).getAllTargets());
							}
						    clearWillRemoveNodes(); // clear for next round

							// remove these from pair2Invokes
							pair2Invokes.removeInvokes(pred, check);
						}
					}
				}
			}
			
			//update
			checks.clear();
			checks.addAll(tmp);
			tmp.clear();
		}
	}
	
	/**
	 * refer to IPASSAPropagationCallGraphBuilder.removeDeletedConstraintsForCGNode()
	 * @param node
	 * @param invokes
	 * @throws CancelException
	 */
	private void removeInvokeDeletedConstraintsForCGNode(CGNode node, HashSet<SSAAbstractInvokeInstruction> invokes) throws CancelException {
		total_delInvokes += invokes.size();
		
		ContextItem ctx = node.getContext();
		if(ctx instanceof JavaTypeContext) {
			return; // skip this change, since this involves too many cgnodes and constraints
		}
		
		IR ir_old = node.getIR();
		DefUse du_old = new DefUse(ir_old);
		ConstraintVisitor v_old = this.makeVisitor(node);//node = cgnode_old
		v_old.setIR(ir_old);
		v_old.setDefUse(du_old);
		
		this.setDelete(true);
		for(SSAAbstractInvokeInstruction invoke : invokes) {
			ISSABasicBlock bb = ir_old.getBasicBlockForInstruction(invoke);
			v_old.setBasicBlock(bb);
			
			system.setFirstDel(true);
			invoke.visit(v_old);
			system.setFirstDel(false);

			do{ 
				system.solveDel(null);
			}while(!system.emptyWorkList());
		}

		this.setDelete(false);
	}
	
	/**
	 * we have to remember the relation between 'this' obj and its target,
	 * and maintain caller2callee2Invokes 
	 * otherwise we cannot locate so many things ...
	 */
	@Override
	protected void updateAddTargets(CGNode caller, InstanceKey[] actualParameters, SSAAbstractInvokeInstruction instruction, CGNode target) {
		boolean skipThis = instruction.isStatic()
				|| actualParameters == null || actualParameters.length == 0 || actualParameters[0] == null;
		
		if(!skipThis) {
			// maintain baseInstance2Targets 
			InstanceKey receiver = actualParameters[0];
			int base_idx = system.getInstanceIndex(receiver); 
			int target_idx = callGraph.getNumber(target);
		
			//unique int; just in case ...
			base_idx = uf.find(base_idx);
			target_idx = uf.find(target_idx);
			HashSet<Integer> exists = baseInstance2Targets.get(target_idx);
			if(exists == null ) {
				exists = new HashSet<>();
				baseInstance2Targets.put(base_idx, exists);
			}
			exists.add(target_idx);
		}
		
		// maintain caller2callee2Invokes
		pair2Invokes.update(caller, target, instruction);
	}
	
	@Override
	protected void updateDelTargets(CGNode caller, InstanceKey[] actualParameters, SSAAbstractInvokeInstruction instruction, CGNode target) {
		boolean skipThis = instruction.isStatic()
				|| actualParameters == null || actualParameters.length == 0 || actualParameters[0] == null;
		
		if (!skipThis) {
			// maintain baseInstance2Targets 
			InstanceKey receiver = actualParameters[0];
			int base_idx = system.getInstanceIndex(receiver); 
			int target_idx = callGraph.getNumber(target);

			//unique int; just in case ...
			base_idx = uf.find(base_idx);
			target_idx = uf.find(target_idx);
			HashSet<Integer> exists = baseInstance2Targets.get(target_idx);
			if (exists != null) {
				exists.remove(target_idx);
			}//else already deleted
		}
		
		// maintain caller2callee2Invokes
		pair2Invokes.removeInvokes(caller, target);
	}
	
	
	private InvalidConstraintVisitor makeInvalidVisitor(CGNode node) {
		return new InvalidConstraintVisitor(this, node, invalidCGNodes);
	}
	
	/**
	 * bz: reverse the order of Iterator
	 * @param <T>
	 * @param iter
	 * @return
	 */
	private static <T> Iterator<T> getReversedIterator(Iterator<T> iter) {
	    List<T> rev = new ArrayList<T>();
	    while (iter.hasNext()) {
	        rev.add (0, iter.next());
	    }
	    return rev.iterator();
	}
	
	/**
	 * DeleteCGNode
	 * @throws CancelException 
	 */
	public void deleteInvalidCGNode() throws CancelException {
		this.setDelete(true);
		system.setFirstDel(true);

		for(CGNode node : invalidCGNodes) {
			// remove all edges
			callGraph.removeIncomingEdges(node);
			removeOutgoingEdgesAndTargets(node);
			
			// for constraints
			if(node.getIR() == null || node.getIR().isEmptyIR() ) {
				// some cgnodes we have no binary for them, and so no ir for them -> skip them
				continue;
			}
			
			InvalidConstraintVisitor v = makeInvalidVisitor(node);
			IR ir = node.getIR();
			//we traverse in reverse order 
			for (Iterator<ISSABasicBlock> reverseBB = getReversedIterator(ir.getBlocks()); reverseBB.hasNext();) {
				BasicBlock b = (BasicBlock) reverseBB.next();
				// visit each instruction in the basic block.
				for (Iterator<SSAInstruction> it = b.iterator(); it.hasNext();) {
					MonitorUtil.throwExceptionIfCanceled(null);
					SSAInstruction s = it.next();
					if (s != null) {
						s.visit(v);
					}
				}
			}
		}
		
		// solve
		do{ 
			system.solveDel(null);
		}while(!system.emptyWorkList());
		
		this.setDelete(false);
	}
	
	// MyInstructionVisitor vs ConstraintVisitor -> ConstraintVisitor: want to use its functions
	private static class InvalidConstraintVisitor extends ConstraintVisitor { 
		
		protected final IPASSAPropagationCallGraphBuilder builder; //this
		protected final CGNode node;//bz
		protected final IPAExplicitCallGraph callGraph;
		protected IRView ir; 
		protected final IPAPropagationSystem system;
		protected ISSABasicBlock basicBlock;
		protected SymbolTable symbolTable;//bz
		protected DefUse du;//bz
		
		protected HashSet<CGNode> invalidCGNodes;

		public InvalidConstraintVisitor(IPASSAPropagationCallGraphBuilder builder, CGNode node, HashSet<CGNode> invalidCGNodes) {
			super(builder, node);
			this.builder = builder;
			this.node = node;
			this.callGraph = builder.getCallGraph();
			this.system = builder.getSystem();
			this.ir = node.getIR();
			this.symbolTable = this.ir.getSymbolTable();
			this.du = node.getDU();
			this.invalidCGNodes = invalidCGNodes;
			assert symbolTable != null;
		}

		public IPASSAPropagationCallGraphBuilder getBuilder() {
			return builder;
		}
		
		@Override
		public void setBasicBlock(ISSABasicBlock block) {
			basicBlock = block;
		}

		@Override
		public IRView getIR() {
			return ir;
		}
		
		protected IClassHierarchy getClassHierarchy() {
			return getBuilder().getClassHierarchy();
		}
		
		//only the following four types will let obj flow out of invalidCGNodes, 
		//invokes are handled before, its constraints will be handled later
		@Override
		public void visitGet(SSAGetInstruction instruction) {
			visitGetInternal(instruction.getDef(), instruction.getRef(), instruction.isStatic(), instruction.getDeclaredField());
		}
		@Override
		protected void visitGetInternal(int lval, int ref, boolean isStatic, FieldReference field) {
//			PointerKey def = builder.getPointerKeyForLocal(node, lval);
//			assert def != null;
//			
//			if (hasNoInterestingUses(lval)) {
//				system.derecordImplicitPointsToSet(def);
//			}else {
////				IPAPointsToSetVariable defV = system.findOrCreatePointsToSet(def);
////				Iterator<IPAAbstractStatement> uses = system.getStatementsThatUse(defV);
//				
//				//only PutField and ArrayStore can let data flow out
//				
//			}
		}
		
		@Override
		public void visitPut(SSAPutInstruction instruction) {
			visitPutInternal(instruction.getVal(), instruction.getRef(), instruction.isStatic(), instruction.getDeclaredField());
		}
		@Override
		public void visitPutInternal(int rval, int ref, boolean isStatic, FieldReference field) {
//			// skip putfields of primitive type
//			if (field.getFieldType().isPrimitiveType(true)) {
//				return;
//			} 
//			IField f = getClassHierarchy().resolveField(field);
//			if (f == null) {
//				if (DEBUG_K) {
//					System.err.println("Could not resolve field " + field);
//				}
//				Warnings.add(FieldResolutionFailure.create(field));
//				return;
//			}
//			assert f.getFieldTypeReference().getName().equals(field.getFieldType().getName()) :
//				"name clash of two fields with the same name but different type: " + f.getReference() + " <=> " + field;
//			assert isStatic || !symbolTable.isStringConstant(ref) : "put to string constant shouldn't be allowed?";
//			if (isStatic) {
//				processPutStatic(rval, field, f);
//			}else {
////				processPutField(rval, ref, f);
//			}
		}
		
		@Override
		public void processPutField(int rval, int ref, IField f) {
//			assert !f.getFieldTypeReference().isPrimitiveType(true);
//			PointerKey refKey = getPointerKeyForLocal(ref);
//			if (contentsAreInvariant(symbolTable, du, ref)) {
//				system.derecordImplicitPointsToSet(refKey);
//				InstanceKey[] refk = getInvariantContents(ref);
//				for (int j = 0; j < refk.length; j++) {
//					if (!representsNullType(refk[j])) {
//						if(refk[j] instanceof AllocationSiteInNode) {
//							AllocationSiteInNode inNode = (AllocationSiteInNode) refk[j];
//							CGNode site = inNode.getNode();
//							if(invalidCGNodes.contains(site)) {
//								// redundant propagation
//								continue;
//							}
//						}
//						
////						system.findOrCreateIndexForInstanceKey(refk[j]);
////						PointerKey p = getPointerKeyForInstanceField(refk[j], f);
////						for (int i = 0; i < ik.length; i++) {
////							system.delConstraint(p, ik[i]);
////						}
//					}
//				}
//			}else {
////				for (int i = 0; i < ik.length; i++) {
////					system.delSideEffect(getBuilder().new InstancePutFieldOperator(f, ik[i]), refKey);
////				}
//			}
		}
		
		@Override
		public void visitArrayLoad(SSAArrayLoadInstruction instruction) {
			// skip arrays of primitive type
			if (instruction.typeIsPrimitive()) {
				return;
			}
			doVisitArrayLoad(instruction.getDef(), instruction.getArrayRef());
		}
		
		@Override
		protected void doVisitArrayLoad(int def, int arrayRef) { 
//			PointerKey result = getPointerKeyForLocal(def);
//			if (hasNoInterestingUses(def)) {
//				system.derecordImplicitPointsToSet(result);
//			}else {
////				IPAPointsToSetVariable defV = system.findOrCreatePointsToSet(result);
////				Iterator<IPAAbstractStatement> uses = system.getStatementsThatUse(defV);
//			}
		}
		
		@Override
		public void visitArrayStore(SSAArrayStoreInstruction instruction) {
			// skip arrays of primitive type
			if (instruction.typeIsPrimitive()) {
				return;
			}
//			doVisitArrayStore(instruction.getArrayRef(), instruction.getValue());
		}
		@Override
		public void doVisitArrayStore(int arrayRef, int value) {
			
		}
		
		@Override
		public void visitReturn(SSAReturnInstruction instruction) {
		}
		
		//for the following visit, we already handled by previous opt steps
		@Override
		public void visitInvoke(SSAInvokeInstruction instruction) { 
		}
		
		@Override
		public void visitNew(SSANewInstruction instruction) {
		}
		
		//for the following visit, we only want to remove the pointer and all its records
		@Override
		public void visitCheckCast(SSACheckCastInstruction instruction) {
		}
		
		@Override
		public void visitGetCaughtException(SSAGetCaughtExceptionInstruction instruction) {
		}
		
		@Override
		public void visitPhi(SSAPhiInstruction instruction) {
		}
		
		@Override
		public void visitPi(SSAPiInstruction instruction) {
		}
		
		@Override
		public void visitLoadMetadata(SSALoadMetadataInstruction instruction) {
		}
		
		@Override
		public void updateOriginSensitiveObjectForNew(int type, CGNode ciCGNode, CGNode originCGNode,
				SSANewInstruction instruction) {
		}
		
	}
	

}
