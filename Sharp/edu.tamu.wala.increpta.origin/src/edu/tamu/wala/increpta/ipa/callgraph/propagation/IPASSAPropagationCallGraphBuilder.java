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
import java.util.Set;
import java.util.Stack;
import java.util.function.Consumer;

import org.junit.Assert;

import com.ibm.wala.analysis.reflection.CloneInterpreter;
import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.cfg.IBasicBlock;
import com.ibm.wala.classLoader.ArrayClass;
import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.classLoader.ProgramCounter;
import com.ibm.wala.fixpoint.IVariable;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.ContextKey;
import com.ibm.wala.ipa.callgraph.ContextSelector;
import com.ibm.wala.ipa.callgraph.DelegatingContext;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.callgraph.impl.AbstractRootMethod;
import com.ibm.wala.ipa.callgraph.impl.DefaultEntrypoint;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.callgraph.propagation.ConcreteTypeKey;
import com.ibm.wala.ipa.callgraph.propagation.ConstantKey;
import com.ibm.wala.ipa.callgraph.propagation.FilteredPointerKey;
import com.ibm.wala.ipa.callgraph.propagation.IPointerOperator;
import com.ibm.wala.ipa.callgraph.propagation.IPointsToSolver;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKeyFactory;
import com.ibm.wala.ipa.callgraph.propagation.LocalPointerKey;
import com.ibm.wala.ipa.callgraph.propagation.NormalAllocationInNode;
import com.ibm.wala.ipa.callgraph.propagation.NullConstantKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.callgraph.propagation.SSAContextInterpreter;
import com.ibm.wala.ipa.callgraph.propagation.ZeroLengthArrayInNode;
import com.ibm.wala.ipa.cha.ClassHierarchy.Node;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.shrikeBT.ConditionalBranchInstruction;
import com.ibm.wala.shrikeBT.IInvokeInstruction;
import com.ibm.wala.shrikeBT.IConditionalBranchInstruction.IOperator;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.IRView;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAAbstractThrowInstruction;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSACFG.BasicBlock;
import com.ibm.wala.ssa.SSACFG.ExceptionHandlerBasicBlock;
import com.ibm.wala.ssa.SSACache;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAGetCaughtExceptionInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstanceofInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSALoadMetadataInstruction;
import com.ibm.wala.ssa.SSAMonitorInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SSAThrowInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.Selector;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.MonitorUtil;
import com.ibm.wala.util.MonitorUtil.IProgressMonitor;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.debug.Assertions;
import com.ibm.wala.util.debug.UnimplementedError;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetAction;
//import com.ibm.wala.util.intset.IntSetUtil;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.OrdinalSet;
import com.ibm.wala.util.ref.ReferenceCleanser;
import com.ibm.wala.util.warnings.Warning;
import com.ibm.wala.util.warnings.Warnings;

import edu.tamu.wala.increpta.callgraph.impl.DUForPath;
import edu.tamu.wala.increpta.callgraph.impl.IPACGNode;
import edu.tamu.wala.increpta.callgraph.impl.IPAExplicitCallGraph;
import edu.tamu.wala.increpta.callgraph.impl.IPAExplicitCallGraph.IPAExplicitNode;
import edu.tamu.wala.increpta.change.IRChangedSummary;
import edu.tamu.wala.increpta.change.IRDiff;
import edu.tamu.wala.increpta.instancekey.IPAConcreteTypeKey;
import edu.tamu.wala.increpta.instancekey.ThreadNormalAllocationInNode;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPASSAPropagationCallGraphBuilder.ConstraintVisitor;
import edu.tamu.wala.increpta.operators.IPAAbstractOperator;
import edu.tamu.wala.increpta.operators.IPAAbstractStatement;
import edu.tamu.wala.increpta.operators.IPAGeneralStatement;
import edu.tamu.wala.increpta.operators.IPAUnaryStatement;
import edu.tamu.wala.increpta.operators.OpVisitor;
import edu.tamu.wala.increpta.pointerkey.IPAFilteredPointerKey;
import edu.tamu.wala.increpta.pointerkey.IPAPointerKeyFactory;
import edu.tamu.wala.increpta.util.IPAAbstractFixedPointSolver;
import edu.tamu.wala.increpta.util.JavaUtil;
import edu.tamu.wala.increpta.util.intset.IPAIntSetUtil;


public abstract class IPASSAPropagationCallGraphBuilder extends IPAPropagationCallGraphBuilder implements IPAHeapModel {
	
	///////// original code ///////// 
	private final static boolean DEBUG = false;
	private final static boolean DEBUG_MULTINEWARRAY = DEBUG | false;

	/**
	 * use type inference to avoid unnecessary filter constraints?
	 */
	// private final static boolean OPTIMIZE_WITH_TYPE_INFERENCE = true;
	/**
	 * An optimization: if we can locally determine the final solution for a points-to set, then don't actually create the points-to
	 * set, but instead short circuit by propagating the final solution to all such uses.
	 *
	 * String constants are ALWAYS considered invariant, regardless of the value of this flag.
	 *
	 * However, if this flag is set, then the solver is more aggressive identifying invariants.
	 *
	 * Doesn't play well with pre-transitive solver; turning off for now.
	 */
	private final static boolean SHORT_CIRCUIT_INVARIANT_SETS = true;
	
	
	/**
	 * bz: InterestingVisitor guided the Implicit Points-to Set in pointer analysis
	 * true -> use implicit
	 */
	public static boolean USE_InterestingVisitor = false;

	/**
	 * Should we change calls to clone() to assignments?
	 */
	private final boolean clone2Assign = false;

	/**
	 * Cache for efficiency
	 */
	private final static Selector cloneSelector = CloneInterpreter.CLONE.getSelector();

	/**
	 * set of class whose clinits have already been processed
	 */
	private final Set<IClass> clinitVisited = HashSetFactory.make();
	
	public Set<IClass> getClinitVisited() {
		return clinitVisited;
	}

	private final Set<IClass> finalizeVisited = HashSetFactory.make();
	
	/**
	 * bz: to use in db
	 * @return
	 */
	public Set<IClass> getFinalizeVisited() {
		return finalizeVisited;
	}

	protected IPASSAPropagationCallGraphBuilder(IMethod abstractRootMethod, AnalysisOptions options, IAnalysisCacheView cache,
			IPAPointerKeyFactory pointerKeyFactory) {
		super(abstractRootMethod, options, cache, pointerKeyFactory);
		// this.usePreTransitiveSolver = options.usePreTransitiveSolver();
	}

	@Override
	public SSAContextInterpreter getCFAContextInterpreter() {
		return (SSAContextInterpreter) getContextInterpreter();
	}


	/**
	 * @param node
	 * @param x
	 * @param type
	 * @return the instance key that represents the exception of type _type_ thrown by a particular PEI.
	 * @throws IllegalArgumentException if ikFactory is null
	 */
	public static InstanceKey getInstanceKeyForPEI(CGNode node, ProgramCounter x, TypeReference type, InstanceKeyFactory ikFactory) {
		if (ikFactory == null) {
			throw new IllegalArgumentException("ikFactory is null");
		}
		return ikFactory.getInstanceKeyForPEI(node, x, type);
	}

	/**
	 * @return a visitor to examine instructions in the ir
	 */
	public ConstraintVisitor makeVisitor(CGNode node) {
		return new ConstraintVisitor(this, node);
	}

	/**
	 * Add pointer flow constraints based on instructions in a given node
	 * @throws CancelException
	 */
	protected void addNodeInstructionConstraints(CGNode node, IProgressMonitor monitor) throws CancelException {
		this.monitor = monitor;
		ConstraintVisitor v = makeVisitor(node);
		_addNodeInstructionConstraints(node, v, monitor);
	}
	
	protected void delNodeInstructionConstraints(CGNode node, IProgressMonitor monitor) throws CancelException {
		this.monitor = monitor;
		ConstraintVisitor v = makeVisitor(node);
		_delNodeInstructionConstraints(node, v, monitor);
	}

	/**
	 * Hook for subclasses to add pointer flow constraints based on values in a given node
	 * @throws CancelException
	 */
	@SuppressWarnings("unused")
	protected void addNodeValueConstraints(CGNode node, IProgressMonitor monitor) throws CancelException {

	}
	
	@SuppressWarnings("unused")
	protected void delNodeValueConstraints(CGNode node, IProgressMonitor monitor) throws CancelException {

	}
	
	/**
	 * only used for removing confirmed infeasible paths and their constraints.
	 * @param node
	 * @param vv
	 * @param phi
	 * @param keep_ids
	 */
	@Override
	protected void delPhiConstraint(CGNode node, MyInstructionVisitor vv, SSAPhiInstruction phi, 
			HashSet<Integer> keep_ids, HashSet<Integer> remove_ids) {
		if(!isDelete)
			return;
		ConstraintVisitor v = (ConstraintVisitor) vv;
		PointerKey def = getPointerKeyForLocal(node, phi.getDef());
		if (hasNoInterestingUses(node, phi.getDef(), v.du)) {
			system.derecordImplicitPointsToSet(def);
		} else {
			boolean HANDLE_THIS_PHI = false;
			for (int i = 0; i < phi.getNumberOfUses(); i++) {
				int use_id = phi.getUse(i);
				if(keep_ids.contains(use_id)) {
					HANDLE_THIS_PHI = true; 
					break;
				}
			}
			for (int i = 0; i < phi.getNumberOfUses(); i++) {
				int use_id = phi.getUse(i);
				if(HANDLE_THIS_PHI && !keep_ids.contains(use_id) 
						|| (remove_ids.contains(use_id) && !HANDLE_THIS_PHI)) {
					//remove specific constraint.
					PointerKey use = getPointerKeyForLocal(node, use_id);
					if (contentsAreInvariant(v.symbolTable, v.du, use_id)) {
						system.derecordImplicitPointsToSet(use);
						InstanceKey[] ik = getInvariantContents(v.symbolTable, v.du, node, use_id, this);
						for (int j = 0; j < ik.length; j++) {
							system.delConstraint(def, ik[j]);
						}
					} else {
						system.delConstraint(def, assignOperator, use);
					}
				}
			}
		}
	}
	
	@Override
	protected void delPhiConstraints(CGNode node, ControlFlowGraph<SSAInstruction, ISSABasicBlock> controlFlowGraph, BasicBlock b,
			MyInstructionVisitor vv) {
		ConstraintVisitor v = (ConstraintVisitor) vv;
		
		// visit each phi instruction in each successor block to add constraints
		//each phi instruction will be processed twice, from both branches.
		//bz: can be skipped? 
		for (Iterator sbs = controlFlowGraph.getSuccNodes(b); sbs.hasNext();) {
			BasicBlock sb = (BasicBlock) sbs.next();
			if (!sb.hasPhi()) {
				continue;
			}
			int n = 0;
			for (Iterator<? extends IBasicBlock> back = controlFlowGraph.getPredNodes(sb); back.hasNext(); n++) {
				if (back.next() == b) {
					break;
				}
			} 
			/**
			 * bz: assertion will break due to "==" in the above loop ... when creating new CGNodes for a path-sensitive.
			 */
			assert n < controlFlowGraph.getPredNodeCount(sb);
			for (Iterator<? extends SSAInstruction> phis = sb.iteratePhis(); phis.hasNext();) {
				SSAPhiInstruction phi = (SSAPhiInstruction) phis.next();
				if (phi == null) {
					continue;
				}
				
				PointerKey def = getPointerKeyForLocal(node, phi.getDef());
				if (hasNoInterestingUses(node, phi.getDef(), v.du)) {
					system.derecordImplicitPointsToSet(def);
				} else {
					// the following test restricts the constraints to reachable
					// paths, according to verification constraints
					if (phi.getUse(n) > 0) {
						PointerKey use = getPointerKeyForLocal(node, phi.getUse(n));
						if (contentsAreInvariant(v.symbolTable, v.du, phi.getUse(n))) {
							system.derecordImplicitPointsToSet(use);
							InstanceKey[] ik = getInvariantContents(v.symbolTable, v.du, node, phi.getUse(n), this);
							for (int i = 0; i < ik.length; i++) {
								system.delConstraint(def, ik[i]);
							}
						} else {
							system.delConstraint(def, assignOperator, use);
						}
					}
				}
			}
		}
	}
	
	@Override
	protected void addPhiConstraints(CGNode node, ControlFlowGraph<SSAInstruction, ISSABasicBlock> controlFlowGraph, BasicBlock b,
			MyInstructionVisitor vv) {
		ConstraintVisitor v = (ConstraintVisitor) vv;
		
		// visit each phi instruction in each successor block to add constraints
		//each phi instruction will be processed twice, from both branches.
		//bz: can be skipped? 
		for (Iterator sbs = controlFlowGraph.getSuccNodes(b); sbs.hasNext();) {
			BasicBlock sb = (BasicBlock) sbs.next();
			if (!sb.hasPhi()) {
				continue;
			}
			int n = 0;
			for (Iterator<? extends IBasicBlock> back = controlFlowGraph.getPredNodes(sb); back.hasNext(); n++) {
				if (back.next() == b) {
					break;
				}
			} 
			/**
			 * bz: assertion will break due to "==" in the above loop ... when creating new CGNodes for a path-sensitive.
			 */
			assert n < controlFlowGraph.getPredNodeCount(sb);
			for (Iterator<? extends SSAInstruction> phis = sb.iteratePhis(); phis.hasNext();) {
				SSAPhiInstruction phi = (SSAPhiInstruction) phis.next();
				if (phi == null) {
					continue;
				}
				
				PointerKey def = getPointerKeyForLocal(node, phi.getDef());
				if (hasNoInterestingUses(node, phi.getDef(), v.du)) {
					system.recordImplicitPointsToSet(def);
				} else {
					// the following test restricts the constraints to reachable
					// paths, according to verification constraints
					if (phi.getUse(n) > 0) {
						PointerKey use = getPointerKeyForLocal(node, phi.getUse(n));
						if (contentsAreInvariant(v.symbolTable, v.du, phi.getUse(n))) {
							system.recordImplicitPointsToSet(use);
							InstanceKey[] ik = getInvariantContents(v.symbolTable, v.du, node, phi.getUse(n), this);
							for (int i = 0; i < ik.length; i++) {
								system.newConstraint(def, ik[i]);
							}
						} else {
							system.newConstraint(def, assignOperator, use);
						}
					}
				}
			}
		}
	}

	/**
	 * Add constraints to represent the flow of exceptions to the exceptional return value for this node
	 *
	 * @param node
	 * @param ir
	 */
	@Override
	protected void addNodePassthruExceptionConstraints(CGNode node, IRView ir, DefUse du) {
		// add constraints relating to thrown exceptions that reach the exit block.
		List<ProgramCounter> peis = getIncomingPEIs(ir, ir.getExitBlock());
		PointerKey exception = getPointerKeyForExceptionalReturnValue(node);

		TypeReference throwableType = node.getMethod().getDeclaringClass().getClassLoader().getLanguage().getThrowableType();
		IClass c = node.getClassHierarchy().lookupClass(throwableType);
		addExceptionDefConstraints(ir, du, node, peis, exception, Collections.singleton(c));
	}
	
	@Override
	protected void delNodePassthruExceptionConstraints(CGNode node, IRView ir, DefUse du, int iindex) {
		// add constraints relating to thrown exceptions that reach the exit block.
		List<ProgramCounter> peis = getIncomingPEIs(ir, ir.getExitBlock());
		PointerKey exception = getPointerKeyForExceptionalReturnValue(node);

		TypeReference throwableType = node.getMethod().getDeclaringClass().getClassLoader().getLanguage().getThrowableType();
		IClass c = node.getClassHierarchy().lookupClass(throwableType);
		delExceptionDefConstraints(ir, du, node, peis, exception, Collections.singleton(c), iindex);
	}

	/**
	 * Generate constraints which assign exception values into an exception pointer
	 *
	 * @param node governing node
	 * @param peis list of PEI instructions
	 * @param exceptionVar PointerKey representing a pointer to an exception value
	 * @param catchClasses the types "caught" by the exceptionVar
	 */
	@SuppressWarnings("unused")
	private void addExceptionDefConstraints(IRView ir, DefUse du, CGNode node, List<ProgramCounter> peis, PointerKey exceptionVar,
			Set<IClass> catchClasses) {
		if (DEBUG) {
			System.err.println("Add exception def constraints for node " + node);
		}
		for (Iterator<ProgramCounter> it = peis.iterator(); it.hasNext();) {
			ProgramCounter peiLoc = it.next();
			if (DEBUG) {
				System.err.println("peiLoc: " + peiLoc);
			}
			SSAInstruction pei = ir.getPEI(peiLoc);

			if (DEBUG) {
				System.err.println("Add exceptions from pei " + pei);
			}

			if (pei instanceof SSAAbstractInvokeInstruction) {
				SSAAbstractInvokeInstruction s = (SSAAbstractInvokeInstruction) pei;
				PointerKey e = getPointerKeyForLocal(node, s.getException());

				if (!SHORT_CIRCUIT_SINGLE_USES || !hasUniqueCatchBlock(s, ir)) {
					addAssignmentsForCatchPointerKey(exceptionVar, catchClasses, e);
				}// else {
				// System.err.println("SKIPPING ASSIGNMENTS TO " + exceptionVar + " FROM " +
				// e);
				// }
			} else if (pei instanceof SSAAbstractThrowInstruction) {
				SSAAbstractThrowInstruction s = (SSAAbstractThrowInstruction) pei;
				PointerKey e = getPointerKeyForLocal(node, s.getException());

				if (contentsAreInvariant(ir.getSymbolTable(), du, s.getException())) {
					InstanceKey[] ik = getInvariantContents(ir.getSymbolTable(), du, node, s.getException(), this);
					for (int i = 0; i < ik.length; i++) {
						system.findOrCreateIndexForInstanceKey(ik[i]);
						assignInstanceToCatch(exceptionVar, catchClasses, ik[i]);
					}
				} else {
					addAssignmentsForCatchPointerKey(exceptionVar, catchClasses, e);
				}
			}

			// Account for those exceptions for which we do not actually have a
			// points-to set for
			// the pei, but just instance keys
			Collection<TypeReference> types = pei.getExceptionTypes();
			if (types != null) {
				for (Iterator<TypeReference> it2 = types.iterator(); it2.hasNext();) {
					TypeReference type = it2.next();
					if (type != null) {
						InstanceKey ik = getInstanceKeyForPEI(node, peiLoc, type, instanceKeyFactory);
						if (ik == null) {
							continue;
						}
//						assert ik instanceof ConcreteTypeKey : "uh oh: need to implement getCaughtException constraints for instance " + ik;
						if(ik instanceof ConcreteTypeKey) {
							ConcreteTypeKey ck = (ConcreteTypeKey) ik;
							IClass klass = ck.getType();
							if (IPAPropagationCallGraphBuilder.catches(catchClasses, klass, cha)) {
								system.newConstraint(exceptionVar, getInstanceKeyForPEI(node, peiLoc, type, instanceKeyFactory));
							}
						}else if(ik instanceof IPAConcreteTypeKey) {//bz: added
							IPAConcreteTypeKey ck = (IPAConcreteTypeKey) ik;
							IClass klass = ck.getType();
							if (IPAPropagationCallGraphBuilder.catches(catchClasses, klass, cha)) {
								system.newConstraint(exceptionVar, getInstanceKeyForPEI(node, peiLoc, type, instanceKeyFactory));
							}
						}else {
							throw new IllegalArgumentException("uh oh: need to implement getCaughtException constraints for instance " + ik);
						}
					}
				}
			}
		}
	}
	
	private void delExceptionDefConstraints(IRView ir, DefUse du, CGNode node, List<ProgramCounter> peis, PointerKey exceptionVar,
			Set<IClass> catchClasses, int iindex) {
		if (DEBUG) {
			System.err.println("Add exception def constraints for node " + node);
		}
		for (Iterator<ProgramCounter> it = peis.iterator(); it.hasNext();) {
			ProgramCounter peiLoc = it.next();
			if (DEBUG) {
				System.err.println("peiLoc: " + peiLoc);
			}
			SSAInstruction pei = ir.getPEI(peiLoc);
			
			if(pei.iindex < iindex)
				continue;//unchanged statements...

			if (DEBUG) {
				System.err.println("Add exceptions from pei " + pei);
			}

			if (pei instanceof SSAAbstractInvokeInstruction) {
				SSAAbstractInvokeInstruction s = (SSAAbstractInvokeInstruction) pei;
				PointerKey e = getPointerKeyForLocal(node, s.getException());

				if (!SHORT_CIRCUIT_SINGLE_USES || !hasUniqueCatchBlock(s, ir)) {
					delAssignmentsForCatchPointerKey(exceptionVar, catchClasses, e);
				}// else {
				// System.err.println("SKIPPING ASSIGNMENTS TO " + exceptionVar + " FROM " +
				// e);
				// }
			} else if (pei instanceof SSAAbstractThrowInstruction) {
				SSAAbstractThrowInstruction s = (SSAAbstractThrowInstruction) pei;
				PointerKey e = getPointerKeyForLocal(node, s.getException());

				if (contentsAreInvariant(ir.getSymbolTable(), du, s.getException())) {
					InstanceKey[] ik = getInvariantContents(ir.getSymbolTable(), du, node, s.getException(), this);
					for (int i = 0; i < ik.length; i++) {
						system.findOrCreateIndexForInstanceKey(ik[i]);
						deAssignInstanceToCatch(exceptionVar, catchClasses, ik[i]);
					}
				} else {
					delAssignmentsForCatchPointerKey(exceptionVar, catchClasses, e);
				}
			}

			// Account for those exceptions for which we do not actually have a
			// points-to set for
			// the pei, but just instance keys
			Collection<TypeReference> types = pei.getExceptionTypes();
			if (types != null) {
				for (Iterator<TypeReference> it2 = types.iterator(); it2.hasNext();) {
					TypeReference type = it2.next();
					if (type != null) {
						InstanceKey ik = getInstanceKeyForPEI(node, peiLoc, type, instanceKeyFactory);
						if (ik == null) {
							continue;
						}
//						assert ik instanceof ConcreteTypeKey : "uh oh: need to implement getCaughtException constraints for instance " + ik;
						if(ik instanceof ConcreteTypeKey) {
							ConcreteTypeKey ck = (ConcreteTypeKey) ik;
							IClass klass = ck.getType();
							if (IPAPropagationCallGraphBuilder.catches(catchClasses, klass, cha)) {
								system.delConstraint(exceptionVar, getInstanceKeyForPEI(node, peiLoc, type, instanceKeyFactory));
							}
						}else if(ik instanceof IPAConcreteTypeKey) {//bz: added
							IPAConcreteTypeKey ck = (IPAConcreteTypeKey) ik;
							IClass klass = ck.getType();
							if (IPAPropagationCallGraphBuilder.catches(catchClasses, klass, cha)) {
								system.delConstraint(exceptionVar, getInstanceKeyForPEI(node, peiLoc, type, instanceKeyFactory));
							}
						}else {
							throw new IllegalArgumentException("uh oh: need to implement getCaughtException constraints for instance " + ik);
						}
					}
				}
			}
		}
	}


	/**
	 * @return true iff there's a unique catch block which catches all exceptions thrown by a certain call site.
	 */
	public static boolean hasUniqueCatchBlock(SSAAbstractInvokeInstruction call, IRView ir) { //bz: protected -> public
		ISSABasicBlock[] bb = ir.getBasicBlocksForCall(call.getCallSite());
		if (bb.length == 1) {
			Iterator<ISSABasicBlock> it = ir.getControlFlowGraph().getExceptionalSuccessors(bb[0]).iterator();
			// check that there's exactly one element in the iterator
			if (it.hasNext())  {
				ISSABasicBlock sb = it.next();
				return (!it.hasNext() && (sb.isExitBlock() || ((sb instanceof ExceptionHandlerBasicBlock) && ((ExceptionHandlerBasicBlock)sb).getCatchInstruction() != null)));
			}
		}
		return false;
	}

	/**
	 * precondition: hasUniqueCatchBlock(call,node,cg)
	 *
	 * @return the unique pointer key which catches the exceptions thrown by a call
	 * @throws IllegalArgumentException if ir == null
	 * @throws IllegalArgumentException if call == null
	 */
	public PointerKey getUniqueCatchKey(SSAAbstractInvokeInstruction call, IRView ir, CGNode node) throws IllegalArgumentException,
	IllegalArgumentException {
		if (call == null) {
			throw new IllegalArgumentException("call == null");
		}
		if (ir == null) {
			throw new IllegalArgumentException("ir == null");
		}
		ISSABasicBlock[] bb = ir.getBasicBlocksForCall(call.getCallSite());
		assert bb.length == 1;
		SSACFG.BasicBlock cb = (BasicBlock) ir.getControlFlowGraph().getExceptionalSuccessors(bb[0]).iterator().next();
		if (cb.isExitBlock()) {
			return getPointerKeyForExceptionalReturnValue(node);
		} else {
			SSACFG.ExceptionHandlerBasicBlock ehbb = (ExceptionHandlerBasicBlock) cb;
			SSAGetCaughtExceptionInstruction ci = ehbb.getCatchInstruction();
			return getPointerKeyForLocal(node, ci.getDef());
		}
	}

	/**
	 * @return a List of Instructions that may transfer control to bb via an exceptional edge
	 * @throws IllegalArgumentException if ir is null
	 */
	public static List<ProgramCounter> getIncomingPEIs(IRView ir, ISSABasicBlock bb) {
		if (ir == null) {
			throw new IllegalArgumentException("ir is null");
		}
		if (DEBUG) {
			System.err.println("getIncomingPEIs " + bb);
		}
		ControlFlowGraph<SSAInstruction, ISSABasicBlock> g = ir.getControlFlowGraph();
		List<ProgramCounter> result = new ArrayList<ProgramCounter>(g.getPredNodeCount(bb));
		for (Iterator it = g.getPredNodes(bb); it.hasNext();) {
			BasicBlock pred = (BasicBlock) it.next();
			if (DEBUG) {
				System.err.println("pred: " + pred);
			}
			if (pred.isEntryBlock())
				continue;
			int index = pred.getLastInstructionIndex();
			SSAInstruction pei = ir.getInstructions()[index];
			// Note: pei might be null if pred is unreachable.
			// TODO: consider pruning CFG for unreachable blocks.
			if (pei != null && pei.isPEI()) {
				if (DEBUG) {
					System.err.println("PEI: " + pei + " index " + index + " PC " + g.getProgramCounter(index));
				}
				result.add(new ProgramCounter(g.getProgramCounter(index)));
			}
		}
		return result;
	}

	private class CrossProductRec {
		private final InstanceKey[][] invariants;
		private final Consumer<InstanceKey[]> f;
		private final SSAAbstractInvokeInstruction call;
		private final CGNode caller;
		private final int[] params;
		private final CallSiteReference site;
		private final InstanceKey[] keys;

		private CrossProductRec(InstanceKey[][] invariants, SSAAbstractInvokeInstruction call, CGNode caller,
				Consumer<InstanceKey[]> f) {
			this.invariants = invariants;
			this.f = f;
			this.call = call;
			this.caller = caller;
			this.site = call.getCallSite();
			this.params = IPAIntSetUtil.toArray(getRelevantParameters(caller, site));
			this.keys  = new InstanceKey[ params.length ];
		}

		protected void rec(final int pi, final int rhsi) {
			if (pi == params.length) {
				f.accept(keys);
			} else {
				final int p = params[pi];
				InstanceKey[] ik = invariants != null ? invariants[p] : null;
				if (ik != null) {
					if (ik.length > 0) {
						for (int i = 0; i < ik.length; i++) {
							system.findOrCreateIndexForInstanceKey(ik[i]);
							keys[pi] = ik[i];
							rec(pi + 1, rhsi);
						}
					} /* else {
	            if (!site.isDispatch() || p != 0) {
	              keys[pi] = null;
	              rec(pi + 1, rhsi);
	            }
	          } */
				} else {
					IntSet s = getParamObjects(pi, rhsi);//bz: this is wrong when calling reflection methods
					if (s != null && !s.isEmpty()) {
						s.foreach(new IntSetAction() {
							@Override
							public void act(int x) {
								keys[pi] = system.getInstanceKey(x);
								rec(pi + 1, rhsi + 1);
							}
						});
					} /*else {
	            if (!site.isDispatch() || p != 0) {
	              keys[pi] = null;
	              rec(pi + 1, rhsi + 1);
	            }
	          } */
				}
			}
		}

		/**bz: reverse
		 * @param pi
		 * @param rhsi
		 */
		protected void recDel(final int pi, final int rhsi) {
			//find all keys
			if (pi == params.length) {
				f.accept(keys);//remove call graph constraints, first
			} else {
				final int p = params[pi];
				InstanceKey[] ik = invariants != null ? invariants[p] : null;
				if (ik != null) {
					if (ik.length > 0) {
						for (int i = 0; i < ik.length; i++) {
							system.findOrCreateIndexForInstanceKey(ik[i]);
							keys[pi] = ik[i];
							recDel(pi + 1, rhsi);
						}
					} /* else {
	            if (!site.isDispatch() || p != 0) {
	              keys[pi] = null;
	              rec(pi + 1, rhsi);
	            }
	          } */
				} else {
					IntSet s = getParamObjects(pi, rhsi);
					if (s != null && !s.isEmpty()) {
						s.foreach(new IntSetAction() {
							@Override
							public void act(int x) {
								keys[pi] = system.getInstanceKey(x);
								recDel(pi + 1, rhsi + 1);
							}
						});
					} /*else {
	            if (!site.isDispatch() || p != 0) {
	              keys[pi] = null;
	              rec(pi + 1, rhsi + 1);
	            }
	          } */
				}
			}
		}

		protected IntSet getParamObjects(int paramIndex, @SuppressWarnings("unused") int rhsi) {
			int paramVn = call.getUse(paramIndex);
			PointerKey var = getPointerKeyForLocal(caller, paramVn);
			IntSet s = system.findOrCreatePointsToSet(var).getValue();
			return s;
		}
	}
	
	public static boolean doCreateContext2(int def, SSANewInstruction instruction, CGNode node, MyInstructionVisitor visitor) {
		Iterator<SSAInstruction> iter_uses = node.getDU().getUses(def);
		while(iter_uses.hasNext()) {
			SSAInstruction use = iter_uses.next();
			if(use instanceof SSAAbstractInvokeInstruction) {
				SSAAbstractInvokeInstruction invoke = ((SSAAbstractInvokeInstruction) use);
				TypeReference type = invoke.getDeclaredTarget().getDeclaringClass();
				if(!JavaUtil.doExtendThreadClass(type))
					continue;
				//this is the invokespecial ... Thread, <init>(Runnable, ...) ...
				int num_params = invoke.getDeclaredTarget().getNumberOfParameters();//not include 'this'
				if(num_params == 0) {
					continue;
				}
				int param = invoke.getUse(1);//this is the runnable param, including 'this'
				if(param == def) {//match
					continue;
				}
				TypeReference reference = invoke.getDeclaredTarget().getParameterType(0);
				IClass param_class = JavaUtil.findIClassFromType(reference);
				if(JavaUtil.implementsRunnableInterface(param_class)) {//double check
					// we want the wrapper context (given for new Thread(...)) for this runnable instance
					// the wrapper context may also be ignored due to TWICE rule -> skip
					int _this = invoke.getUse(1);
					//bz: in case we have arrays involved here...
					SSAInstruction array_def = node.getDU().getDef(_this);
					if(array_def instanceof SSAArrayLoadInstruction) {
						SSAArrayLoadInstruction load = (SSAArrayLoadInstruction) array_def;
						int base = load.getUse(0);
						Iterator<SSAInstruction> iter = node.getDU().getUses(base);
						while (iter.hasNext()) {
							SSAInstruction array = iter.next();
							if(array instanceof SSAArrayStoreInstruction) {
								SSAArrayStoreInstruction store = (SSAArrayStoreInstruction) array;
								int _use = store.getValue();
							}
						}
					}
					//No ThreadCGNode -> probably filtered by duplicated creation of origins at the same call site location...
					//OR we havent traversed the corresponding new thread inst yet ...
					//=> we will create new context here, and assign it to its corresponding new thread obj later 
					return true;
				}
			} 
		}
		return true;
	}

	public static boolean doCreateContext(int def, SSANewInstruction instruction, CGNode node, MyInstructionVisitor visitor) {
		Iterator<SSAInstruction> iter_uses = node.getDU().getUses(def);
		while(iter_uses.hasNext()) {
			SSAInstruction use = iter_uses.next();
			if(use instanceof SSAAbstractInvokeInstruction) {
				SSAAbstractInvokeInstruction invoke = ((SSAAbstractInvokeInstruction) use);
				TypeReference type = invoke.getDeclaredTarget().getDeclaringClass();
				if(!JavaUtil.doExtendThreadClass(type))
					continue;
				//this is the invokespecial ... Thread, <init>(Runnable, ...) ...
				int num_params = invoke.getDeclaredTarget().getNumberOfParameters();//not include 'this'
				if(num_params == 0) {
					continue;
				}
				int param = invoke.getUse(1);//this is the runnable param, including 'this'
				if(param != def) {//match
					continue;
				}
				TypeReference reference = invoke.getDeclaredTarget().getParameterType(0);
				IClass param_class = JavaUtil.findIClassFromType(reference);
				if(JavaUtil.implementsRunnableInterface(param_class)) {//double check
					// we want the wrapper context (given for new Thread(...)) for this runnable instance
					// the wrapper context may also be ignored due to TWICE rule -> skip
					int _this = invoke.getUse(0);
					//No ThreadCGNode -> probably filtered by duplicated creation of origins at the same call site location...
					//OR we havent traversed the corresponding new thread inst yet ...
					//=> we will create new context here, and assign it to its corresponding new thread obj later 
					return true;
				}
			}else if(use instanceof SSAArrayStoreInstruction) {
				//bz: more complex dataflow; e.g., raytracer
				SSAArrayStoreInstruction store = (SSAArrayStoreInstruction) use;
				int base = store.getUse(0);
				Iterator<SSAInstruction> iter = node.getDU().getUses(base);
				while (iter.hasNext()) {
					SSAInstruction array = iter.next();
					if(array instanceof SSAArrayLoadInstruction) {
						SSAArrayLoadInstruction load = (SSAArrayLoadInstruction) array;
						int _def = load.getDef();
						return doCreateContext(_def, instruction, node, visitor);
					}
				}
			}
		}
		return true;// not follow any of above situations
	}

	

	/**
	 * A visitor that generates constraints based on statements in SSA form.
	 */
	public static class ConstraintVisitor extends MyInstructionVisitor {

		/**
		 * The governing call graph builder. This field is used instead of an inner class in order to allow more flexible reuse of this
		 * visitor in subclasses
		 */
		protected final IPASSAPropagationCallGraphBuilder builder;

		/**
		 * The node whose statements we are currently traversing
		 */
		protected final CGNode node;

		/**
		 * The governing call graph.
		 */
		protected final IPAExplicitCallGraph callGraph;

		/**
		 * The governing IR
		 */
		protected IRView ir; 

		/**
		 * The governing propagation system, into which constraints are added
		 */
		protected final IPAPropagationSystem system;

		/**
		 * The basic block currently being processed
		 */
		protected ISSABasicBlock basicBlock;

		/**
		 * Governing symbol table
		 */
		protected SymbolTable symbolTable;//bz

		/**
		 * Def-use information
		 */
		protected DefUse du;//bz

		public ConstraintVisitor(IPASSAPropagationCallGraphBuilder builder, CGNode node) {
			this.builder = builder;
			this.node = node;

			this.callGraph = builder.getCallGraph();
			this.system = builder.getSystem();
			
//			if(node.toString().contains("Ljava/lang/reflect/Constructor, newInstance([Ljava/lang/Object;)Ljava/lang/Object; > Context: CallStringContextPair: [ org.apache.hadoop.hbase.regionserver.HRegionServer.constructRegionServer(Ljava/lang/Class;Lorg/apache/hadoop/conf/Configuration;)Lorg/apache/hadoop/hbase/regionserver/HRegionServer;@24 ]:DelegatingContext [A=CallStringContext: [ org.apache.hadoop.hbase.regionserver.HRegionServer.constructRegionServer(Ljava/lang/Class;Lorg/apache/hadoop/conf/Configuration;)Lorg/apache/hadoop/hbase/regionserver/HRegionServer;@24 ], B=Everywhere]")
//					|| node.toString().contains("Ljava/lang/reflect/Constructor, newInstance([Ljava/lang/Object;)Ljava/lang/Object; > Context: DelegatingContext [A=DelegatingContext [A=ReceiverInstanceContext<[ConstantKey:< Application, Lorg/apache/hadoop/hbase/regionserver/HRegionServer, <init>(Lorg/apache/hadoop/conf/Configuration;)V >:<Primordial,Ljava/lang/reflect/Constructor>]>, B=CallStringContext: [ org.apache.hadoop.hbase.regionserver.HRegionServer.constructRegionServer(Ljava/lang/Class;Lorg/apache/hadoop/conf/Configuration;)Lorg/apache/hadoop/hbase/regionserver/HRegionServer;@24 ]], B=Everywhere]"))
//				System.out.println();
			
			if (builder.isChange) {
				//NOTE: interp.getIRView(node); and node.getIR() and node.getDU() can be different from what we need
				// -> because original code tried to use cached info (which is old and not valid ...), invalidate the olds do not work ...
				// -> create everything based on ir 
				getAnalysisCache().invalidate(node.getMethod(), node.getContext());
				this.ir = node.getIR();
				this.symbolTable = this.ir.getSymbolTable();
				this.du = new DefUse(node.getIR());
			} else {
				SSAContextInterpreter interp = builder.getCFAContextInterpreter();
				this.ir = interp.getIRView(node);
				this.symbolTable = this.ir.getSymbolTable();
				this.du = interp.getDU(node);
			}

			assert symbolTable != null;
		}

		/**
		 * bz
		 */
		public void setIR(IR ir) {
			this.ir = ir;
			this.symbolTable = this.ir.getSymbolTable();
			assert symbolTable != null;
		}
		
		@Override
		public IRView getIR() {
			return ir;
		}

		/**
		 * bz
		 */
		public void setDefUse(DefUse du)
		{
			this.du = du;
		}

		protected IPASSAPropagationCallGraphBuilder getBuilder() {
			return builder;
		}

		protected AnalysisOptions getOptions() {
			return builder.options;
		}

		protected IAnalysisCacheView getAnalysisCache() {
			return builder.getAnalysisCache();
		}
		
		public PointerKey getPointerKeyAndConstraintForConstantValue(int valueNumber) {
			return getBuilder().getPointerKeyAndConstraintForConstantValue(node, valueNumber);
		}

		public PointerKey getPointerKeyForLocal(int valueNumber) {
			return getBuilder().getPointerKeyForLocal(node, valueNumber);
		}

		public IPAFilteredPointerKey getFilteredPointerKeyForLocal(int valueNumber, IPAFilteredPointerKey.IPATypeFilter filter) {
			return getBuilder().getFilteredPointerKeyForLocal(node, valueNumber, filter);
		}


		public PointerKey getPointerKeyForReturnValue() {
			return getBuilder().getPointerKeyForReturnValue(node);
		}

		public PointerKey getPointerKeyForExceptionalReturnValue() {
			return getBuilder().getPointerKeyForExceptionalReturnValue(node);
		}

		public PointerKey getPointerKeyForStaticField(IField f) {
			return getBuilder().getPointerKeyForStaticField(f);
		}

		public PointerKey getPointerKeyForInstanceField(InstanceKey I, IField f) {
			return getBuilder().getPointerKeyForInstanceField(I, f);
		}

		public PointerKey getPointerKeyForArrayContents(InstanceKey I) {
			return getBuilder().getPointerKeyForArrayContents(I);
		}
		
		public <T> InstanceKey getInstanceKeyForConstant(T S) {//bz
			return getBuilder().getInstanceKeyForConstant(node, S);
		}

		public InstanceKey getInstanceKeyForAllocation(NewSiteReference allocation) {
			return getBuilder().getInstanceKeyForAllocation(node, allocation);
		}

		public InstanceKey getInstanceKeyForMultiNewArray(NewSiteReference allocation, int dim) {
			return getBuilder().getInstanceKeyForMultiNewArray(node, allocation, dim);
		}


		public InstanceKey getInstanceKeyForPEI(ProgramCounter instr, TypeReference type) {
			return getBuilder().getInstanceKeyForPEI(node, instr, type);
		}

		public InstanceKey getInstanceKeyForClassObject(Object obj, TypeReference type) {
			return getBuilder().getInstanceKeyForMetadataObject(obj, type);
		}

		public CGNode getTargetForCall(CGNode caller, CallSiteReference site, IClass recv, InstanceKey iKey[]) {
			return getBuilder().getTargetForCall(caller, site, recv, iKey);
		}

		protected boolean contentsAreInvariant(SymbolTable symbolTable, DefUse du, int valueNumber) {
			return getBuilder().contentsAreInvariant(symbolTable, du, valueNumber);
		}

		protected boolean contentsAreInvariant(SymbolTable symbolTable, DefUse du, int valueNumber[]) {
			return getBuilder().contentsAreInvariant(symbolTable, du, valueNumber);
		}

		protected InstanceKey[] getInvariantContents(int valueNumber) {
			return getInvariantContents(ir.getSymbolTable(), du, node, valueNumber);
		}

		protected InstanceKey[] getInvariantContents(SymbolTable symbolTable, DefUse du, CGNode node, int valueNumber) {
			return getBuilder().getInvariantContents(symbolTable, du, node, valueNumber, getBuilder());
		}

		protected IClassHierarchy getClassHierarchy() {
			return getBuilder().getClassHierarchy();
		}

		protected boolean hasNoInterestingUses(int vn) {
			return getBuilder().hasNoInterestingUses(node, vn, du);
		}

		protected boolean isRootType(IClass klass) {
			return IPASSAPropagationCallGraphBuilder.isRootType(klass);
		}
		
		/*
		 * @see com.ibm.wala.ssa.SSAInstruction.Visitor#visitArrayLoad(com.ibm.wala.ssa.SSAArrayLoadInstruction)
		 */
		@Override
		public void visitArrayLoad(SSAArrayLoadInstruction instruction) {
			// skip arrays of primitive type
			if (instruction.typeIsPrimitive()) {
				return;
			}
			doVisitArrayLoad(instruction.getDef(), instruction.getArrayRef());
		}

		protected void doVisitArrayLoad(int def, int arrayRef) {//bz
			PointerKey result = getPointerKeyForLocal(def);
			PointerKey arrayRefPtrKey = getPointerKeyForLocal(arrayRef);
			if (hasNoInterestingUses(def)) {
				if(!isDelete)
					system.recordImplicitPointsToSet(result);
				else
					system.derecordImplicitPointsToSet(result);
			} else {
				if (contentsAreInvariant(symbolTable, du, arrayRef)) {
					if(!isDelete)
						system.recordImplicitPointsToSet(arrayRefPtrKey);
					InstanceKey[] ik = getInvariantContents(arrayRef);
					if(isDelete){
						for (int i = 0; i < ik.length; i++) {
							if (!representsNullType(ik[i])) {
								system.findOrCreateIndexForInstanceKey(ik[i]);
								PointerKey p = getPointerKeyForArrayContents(ik[i]);
								if (p != null) {
									system.delConstraint(result, assignOperator, p);
								}
							}
						}
					}else{
						for (int i = 0; i < ik.length; i++) {
							if (!representsNullType(ik[i])) {
								system.findOrCreateIndexForInstanceKey(ik[i]);
								PointerKey p = getPointerKeyForArrayContents(ik[i]);
								if (p == null) {
								} else {
									system.newConstraint(result, assignOperator, p);
								}
							}
						}
					}
				} else {
					if(isDelete)
						system.delSideEffect(getBuilder().new ArrayLoadOperator(system.findOrCreatePointsToSet(result)), arrayRefPtrKey);
					else
						system.newSideEffect(getBuilder().new ArrayLoadOperator(system.findOrCreatePointsToSet(result)), arrayRefPtrKey);
				}
			}
		}

		/*
		 * @see com.ibm.wala.ssa.SSAInstruction.Visitor#visitArrayStore(com.ibm.wala.ssa.SSAArrayStoreInstruction)
		 */
		public void doVisitArrayStore(int arrayRef, int value) {
			// (requires the creation of assign constraints as
			// the set points-to(a[]) grows.)
			PointerKey valuePtrKey = getPointerKeyForLocal(value);
			PointerKey arrayRefPtrKey = getPointerKeyForLocal(arrayRef);
			// if (!supportFullPointerFlowGraph &&
			// contentsAreInvariant(instruction.getArrayRef())) {
			if (contentsAreInvariant(symbolTable, du, arrayRef)) {
				if(!isDelete)
					system.recordImplicitPointsToSet(arrayRefPtrKey);
				else
					system.derecordImplicitPointsToSet(arrayRefPtrKey);

				InstanceKey[] ik = getInvariantContents(arrayRef);
				
				for (int i = 0; i < ik.length; i++) {
					if (!representsNullType(ik[i]) && !(ik[i] instanceof ZeroLengthArrayInNode)) {
						system.findOrCreateIndexForInstanceKey(ik[i]);
						PointerKey p = getPointerKeyForArrayContents(ik[i]);
						IClass contents = ((ArrayClass) ik[i].getConcreteType()).getElementClass();
						if (p == null) {
						} else {
							if (contentsAreInvariant(symbolTable, du, value)) {
								if(!isDelete)
									system.recordImplicitPointsToSet(valuePtrKey);
								else
									system.derecordImplicitPointsToSet(valuePtrKey);
								InstanceKey[] vk = getInvariantContents(value);
								if(isDelete){
									for (int j = 0; j < vk.length; j++)  {
										if (vk[j].getConcreteType() != null) {
											if (getClassHierarchy().isAssignableFrom(contents, vk[j].getConcreteType())) {
												//--- # of new constraints: ik.size()*vk.size()
												system.delConstraint(p, vk[j]);
											}
										}
									}
								}else{
									for (int j = 0; j < vk.length; j++) {
										system.findOrCreateIndexForInstanceKey(vk[j]);
										if (vk[j].getConcreteType() != null) {
											if (getClassHierarchy().isAssignableFrom(contents, vk[j].getConcreteType())) {
												system.newConstraint(p, vk[j]);
											}
										}
									}
								}
							} else {
								if (isRootType(contents)) {
									if(isDelete)
										system.delConstraint(p, assignOperator, valuePtrKey);
									else
										system.newConstraint(p, assignOperator, valuePtrKey);
								} else {
									if(isDelete)
										system.delConstraint(p, getBuilder().filterOperator, valuePtrKey);
									else
										system.newConstraint(p, getBuilder().filterOperator, valuePtrKey);
								}
							}
						}
					}
				}
			} else {
				if (contentsAreInvariant(symbolTable, du, value)) {
					if(!isDelete)
						system.recordImplicitPointsToSet(valuePtrKey);
					else
						system.derecordImplicitPointsToSet(valuePtrKey);
					
					InstanceKey[] ik = getInvariantContents(value);
					for (int i = 0; i < ik.length; i++) {
						system.findOrCreateIndexForInstanceKey(ik[i]);
						if(isDelete)
							system.delSideEffect(getBuilder().new InstanceArrayStoreOperator(ik[i]), arrayRefPtrKey);
						else
							system.newSideEffect(getBuilder().new InstanceArrayStoreOperator(ik[i]), arrayRefPtrKey);
					}
				} else {
					if(isDelete)
						system.delSideEffect(getBuilder().new ArrayStoreOperator(system.findOrCreatePointsToSet(valuePtrKey)), arrayRefPtrKey);
					else
						system.newSideEffect(getBuilder().new ArrayStoreOperator(system.findOrCreatePointsToSet(valuePtrKey)), arrayRefPtrKey);
				}
			}
		}

		@Override
		public void visitArrayStore(SSAArrayStoreInstruction instruction) {
			// skip arrays of primitive type
			if (instruction.typeIsPrimitive()) {
				return;
			}
			doVisitArrayStore(instruction.getArrayRef(), instruction.getValue());
		}

		/*
		 * @see com.ibm.wala.ssa.SSAInstruction.Visitor#visitCheckCast(com.ibm.wala.ssa.SSACheckCastInstruction)
		 */
		@Override
		public void visitCheckCast(SSACheckCastInstruction instruction) {

			boolean isRoot = false;
			Set<IClass> types = HashSetFactory.make();

			for(TypeReference t : instruction.getDeclaredResultTypes()) {
				IClass cls = getClassHierarchy().lookupClass(t);
				if (cls == null) {
					Warnings.add(CheckcastFailure.create(t));
					return;
				} else {
					if (isRootType(cls)) {
						isRoot = true;
					}
					types.add(cls);
				}
			}

			PointerKey result = getFilteredPointerKeyForLocal(instruction.getResult(), new IPAFilteredPointerKey.MultipleClassesFilter(types.toArray(new IClass[ types.size() ])));
			PointerKey value = getPointerKeyForLocal(instruction.getVal());

			if (hasNoInterestingUses(instruction.getDef())) {
				if(isDelete)
					system.derecordImplicitPointsToSet(result);
				else
					system.recordImplicitPointsToSet(result);
			} else {
				if (contentsAreInvariant(symbolTable, du, instruction.getVal())) {
					if(isDelete)
						system.derecordImplicitPointsToSet(value);
					else
						system.recordImplicitPointsToSet(value);
					
					InstanceKey[] ik = getInvariantContents(instruction.getVal());
					for(TypeReference t : instruction.getDeclaredResultTypes()) {
						IClass cls = getClassHierarchy().lookupClass(t);

						if (cls.isInterface()) {
							for (int i = 0; i < ik.length; i++) {
								system.findOrCreateIndexForInstanceKey(ik[i]);
								if (getClassHierarchy().implementsInterface(ik[i].getConcreteType(), cls)) {
									if(isDelete)
										system.delConstraint(result, ik[i]);
									else
										system.newConstraint(result, ik[i]);
								}
							}
						} else {
							for (int i = 0; i < ik.length; i++) {
								system.findOrCreateIndexForInstanceKey(ik[i]);
								if (getClassHierarchy().isSubclassOf(ik[i].getConcreteType(), cls)) {
									if(isDelete)
										system.delConstraint(result, ik[i]);
									else
										system.newConstraint(result, ik[i]);
								}
							}
						}
					}
				} else {
					if (isRoot) {
						if(isDelete)
							system.delConstraint(result, assignOperator, value);
						else
							system.newConstraint(result, assignOperator, value);
					} else {
						if(isDelete)
							system.delConstraint(result, getBuilder().filterOperator, value);
						else
							system.newConstraint(result, getBuilder().filterOperator, value);
					}
				}
			}
		}

		/*
		 * @see com.ibm.wala.ssa.SSAInstruction.Visitor#visitReturn(com.ibm.wala.ssa.SSAReturnInstruction)
		 */
		@Override
		public void visitReturn(SSAReturnInstruction instruction) {
			// skip returns of primitive type
			if (instruction.returnsPrimitiveType() || instruction.returnsVoid()) {
				return;
			}
			if (DEBUG) {
				if(isDelete)
					System.err.println("delReturn: " + instruction);
				else
					System.err.println("visitReturn: " + instruction);
			}

			PointerKey returnValue = getPointerKeyForReturnValue();
			PointerKey result = getPointerKeyForLocal(instruction.getResult());
			if (contentsAreInvariant(symbolTable, du, instruction.getResult())) {
				if(!isDelete)
					system.recordImplicitPointsToSet(result);
				else
					system.derecordImplicitPointsToSet(result);
				InstanceKey[] ik = getInvariantContents(instruction.getResult());
				if(isDelete){
					for (int i = 0; i < ik.length; i++) {
						if (DEBUG) {
							System.err.println("invariant contents: " + returnValue + " " + ik[i]);
						}
						system.delConstraint(returnValue, ik[i]);
					}
				}else{
					for (int i = 0; i < ik.length; i++) {
						if (DEBUG) {
							System.err.println("invariant contents: " + returnValue + " " + ik[i]);
						}
						system.newConstraint(returnValue, ik[i]);
					}
				}
			} else {
				if(isDelete)
					system.delConstraint(returnValue, assignOperator, result);
				else
					system.newConstraint(returnValue, assignOperator, result);
			}
		}

		/*
		 * @see com.ibm.wala.ssa.SSAInstruction.Visitor#visitGet(com.ibm.wala.ssa.SSAGetInstruction)
		 */
		@Override
		public void visitGet(SSAGetInstruction instruction) {
//			if(instruction.toString().contains("getstatic < Application, Lbenchmarks/testcases/TestRace6, x, <Primordial,I>"))
//				System.out.println();
			visitGetInternal(instruction.getDef(), instruction.getRef(), instruction.isStatic(), instruction.getDeclaredField());
		}

		protected void visitGetInternal(int lval, int ref, boolean isStatic, FieldReference field) {
			if (DEBUG) {
				if(isDelete)
					System.err.println("delGet " + field);
				else
					System.err.println("visitGet " + field);
			}

			PointerKey def = getPointerKeyForLocal(lval);
			assert def != null;

			IField f = getClassHierarchy().resolveField(field);
			if (f == null && callGraph.getFakeRootNode().getMethod().getDeclaringClass().getReference().equals(field.getDeclaringClass())) {
				f = callGraph.getFakeRootNode().getMethod().getDeclaringClass().getField(field.getName());
			}

			if (f == null) {
				return;
			}
			
			if(isStatic){
				IClass klass = getClassHierarchy().lookupClass(field.getDeclaringClass());
				if (klass == null) {
				} else {
					// side effect of getstatic: may call class initializer
					if (DEBUG) {
						System.err.println("getstatic call class init " + klass);
					}
					if(!isDelete)
						processClassInitializer(klass);
				}
			}

			// skip getfields of primitive type (optimisation)
			if (field.getFieldType().isPrimitiveType(true)) {
				return;
			}

			if (hasNoInterestingUses(lval)) {
				if(!isDelete)
					system.recordImplicitPointsToSet(def);
				else
					system.derecordImplicitPointsToSet(def);
			} else {
				if (isStatic) {
					PointerKey fKey = getPointerKeyForStaticField(f);
					if(isDelete)
						system.delConstraint(def, assignOperator, fKey);
					else
						system.newConstraint(def, assignOperator, fKey);
				} else {
					PointerKey refKey = getPointerKeyForLocal(ref);
					// if (!supportFullPointerFlowGraph &&
					// contentsAreInvariant(ref)) {
					if (contentsAreInvariant(symbolTable, du, ref)) {
						if(isDelete)
							system.derecordImplicitPointsToSet(refKey);
						else
							system.recordImplicitPointsToSet(refKey);
						InstanceKey[] ik = getInvariantContents(ref);
						for (int i = 0; i < ik.length; i++) {
							if (!representsNullType(ik[i])) {
								system.findOrCreateIndexForInstanceKey(ik[i]);
								PointerKey p = getPointerKeyForInstanceField(ik[i], f);
								if(isDelete)
									system.delConstraint(def, assignOperator, p);
								else
									system.newConstraint(def, assignOperator, p);
							}
						}
					} else {
						if(isDelete)
							system.delSideEffect(getBuilder().new GetFieldOperator(f, system.findOrCreatePointsToSet(def)), refKey);
						else
							system.newSideEffect(getBuilder().new GetFieldOperator(f, system.findOrCreatePointsToSet(def)), refKey);
					}
				}
			}
		}

		/*
		 * @see com.ibm.wala.ssa.Instruction.Visitor#visitPut(com.ibm.wala.ssa.PutInstruction)
		 */
		@Override
		public void visitPut(SSAPutInstruction instruction) {
			visitPutInternal(instruction.getVal(), instruction.getRef(), instruction.isStatic(), instruction.getDeclaredField());
		}

		public void visitPutInternal(int rval, int ref, boolean isStatic, FieldReference field) {

			if (DEBUG) {
				if(isDelete)
					System.err.println("delPut " + field);
				else
					System.err.println("visitPut " + field);
			}

			// skip putfields of primitive type
			if (field.getFieldType().isPrimitiveType(true)) {
				return;
			} 
			IField f = getClassHierarchy().resolveField(field);
			if (f == null) {
				if (DEBUG) {
					System.err.println("Could not resolve field " + field);
				}
				Warnings.add(FieldResolutionFailure.create(field));
				return;
			}
			assert f.getFieldTypeReference().getName().equals(field.getFieldType().getName()) :
				"name clash of two fields with the same name but different type: " + f.getReference() + " <=> " + field;
			assert isStatic || !symbolTable.isStringConstant(ref) : "put to string constant shouldn't be allowed?";
			if (isStatic) {
				processPutStatic(rval, field, f);
			} else {
				processPutField(rval, ref, f);
			}
		}

		public void processPutField(int rval, int ref, IField f) {
			assert !f.getFieldTypeReference().isPrimitiveType(true);
			PointerKey refKey = getPointerKeyForLocal(ref);
			PointerKey rvalKey = getPointerKeyForLocal(rval);
			
			// if (!supportFullPointerFlowGraph &&
			// contentsAreInvariant(rval)) {
			if (contentsAreInvariant(symbolTable, du, rval)) {
				if(!isDelete)
					system.recordImplicitPointsToSet(rvalKey);
				else
					system.derecordImplicitPointsToSet(rvalKey);
				InstanceKey[] ik = getInvariantContents(rval);
				if (contentsAreInvariant(symbolTable, du, ref)) {
					if(!isDelete)
						system.recordImplicitPointsToSet(refKey);
					else
						system.derecordImplicitPointsToSet(refKey);
					InstanceKey[] refk = getInvariantContents(ref);
					if(isDelete){
						MutableIntSet delset = IPAIntSetUtil.getDefaultIntSetFactory().make();
						for (int i = 0; i < ik.length; i++) {
							int index = system.findOrCreateIndexForInstanceKey(ik[i]);
							delset.add(index);
						}
						for (int j = 0; j < refk.length; j++) {
							if (!representsNullType(refk[j])) {
								system.findOrCreateIndexForInstanceKey(refk[j]);
								PointerKey p = getPointerKeyForInstanceField(refk[j], f);
								system.delConstraint(p, delset);
							}
						}
					}else{
						for (int j = 0; j < refk.length; j++) {
							if (!representsNullType(refk[j])) {
								system.findOrCreateIndexForInstanceKey(refk[j]);
								PointerKey p = getPointerKeyForInstanceField(refk[j], f);
								for (int i = 0; i < ik.length; i++) {
									system.newConstraint(p, ik[i]);
								}
							}
						}
					}
				} else {
					for (int i = 0; i < ik.length; i++) {
						system.findOrCreateIndexForInstanceKey(ik[i]);
						if(isDelete)
							system.delSideEffect(getBuilder().new InstancePutFieldOperator(f, ik[i]), refKey);
						else
							system.newSideEffect(getBuilder().new InstancePutFieldOperator(f, ik[i]), refKey);
					}
				}
			} else {
				if (contentsAreInvariant(symbolTable, du, ref)) {
					if(!isDelete)
						system.recordImplicitPointsToSet(refKey);
					else
						system.derecordImplicitPointsToSet(refKey);
					
					InstanceKey[] refk = getInvariantContents(ref);
					for (int j = 0; j < refk.length; j++) {
						if (!representsNullType(refk[j])) {
							system.findOrCreateIndexForInstanceKey(refk[j]);
							PointerKey p = getPointerKeyForInstanceField(refk[j], f);
							if(isDelete)
								system.delConstraint(p, assignOperator, rvalKey);
							else
								system.newConstraint(p, assignOperator, rvalKey);
						}
					}
				} else {
					if (DEBUG) {
						System.err.println("adding side effect " + f);
					}
					if(isDelete)
						system.delSideEffect(getBuilder().new PutFieldOperator(f, system.findOrCreatePointsToSet(rvalKey)), refKey);
					else
						system.newSideEffect(getBuilder().new PutFieldOperator(f, system.findOrCreatePointsToSet(rvalKey)), refKey);
				}
			}
		}

		protected void processPutStatic(int rval, FieldReference field, IField f) {
			PointerKey fKey = getPointerKeyForStaticField(f);
			PointerKey rvalKey = getPointerKeyForLocal(rval);
			
			// if (!supportFullPointerFlowGraph &&
			// contentsAreInvariant(rval)) {
			if (contentsAreInvariant(symbolTable, du, rval)) {
				if(!isDelete)
					system.recordImplicitPointsToSet(rvalKey);
				else
					system.derecordImplicitPointsToSet(rvalKey);
				InstanceKey[] ik = getInvariantContents(rval);
				if(isDelete){
					for (int i = 0; i < ik.length; i++) {
						system.delConstraint(fKey, ik[i]);
					}
				}else{
					for (int i = 0; i < ik.length; i++) {
						system.newConstraint(fKey, ik[i]);
					}
				}
			} else {
				if(isDelete)
					system.delConstraint(fKey, assignOperator, rvalKey);
				else
					system.newConstraint(fKey, assignOperator, rvalKey);
			}
			if (DEBUG) {
				System.err.println("visitPut class init " + field.getDeclaringClass() + " " + field);
			}
			// side effect of putstatic: may call class initializer
			IClass klass = getClassHierarchy().lookupClass(field.getDeclaringClass());
			if (klass == null) {
				Warnings.add(FieldResolutionFailure.create(field));
			} else {
				if(!isDelete)
					processClassInitializer(klass);
			}
		}

		/*
		 * @see com.ibm.wala.ssa.Instruction.Visitor#visitInvoke(com.ibm.wala.ssa.InvokeInstruction)
		 */
		@Override
		public void visitInvoke(SSAInvokeInstruction instruction) {
			visitInvokeInternal(instruction, new DefaultInvariantComputer());
		}
		
		protected void visitInvokeInternal(final SSAAbstractInvokeInstruction instruction, InvariantComputer invs) {
			//move to here; since edu.tamu.wala.increpta.cast.java.AstJavaIPASSAPropagationCallGraphBuilder.AstJavaIPAConstraintVisitor.visitJavaInvoke(AstJavaInvokeInstruction instruction)
			// will invoke this method
			if(isDelete)
				delInvokeInternalAction(instruction, invs);
			else
				visitInvokeInternalAction(instruction, invs);
		}

		/**bz
		 * @param instruction
		 * @param invs
		 */
		protected void delInvokeInternalAction(final SSAAbstractInvokeInstruction instruction, InvariantComputer invs) {
			if (DEBUG) {
				System.err.println("visitInvoke: " + instruction);
			}
			
//			if(instruction.getDeclaredTarget().getDeclaringClass().toString().contains("Ljava/"))
//				return;//bz: currently do not handle this.... too expensive

//			if(instruction.toString().contains("34 = invokevirtual < Source, Ljava/lang/Thread, getId()J > 35"))
//				System.out.println();
			
			PointerKey uniqueCatch = null;
			if (hasUniqueCatchBlock(instruction, ir)) {
				uniqueCatch = getBuilder().getUniqueCatchKey(instruction, ir, node);
			}

			InstanceKey[][] invariantParameters = invs.computeInvariantParameters(instruction);

			IntSet params = getBuilder().getContextSelector().getRelevantParameters(node, instruction.getCallSite());
			if (!instruction.getCallSite().isStatic() && !params.contains(0) && (invariantParameters == null || invariantParameters[0] == null)) {
				params = IPAIntSetUtil.makeMutableCopy(params);
				((MutableIntSet)params).add(0);
			}

			if (invariantParameters != null) {
				for(int i = 0; i < invariantParameters.length; i++) {
					if (invariantParameters[i] != null) {
						params = IPAIntSetUtil.makeMutableCopy(params);
						((MutableIntSet)params).remove(i);
					}
				}
			}
			if (params.isEmpty()) {
				for (CGNode n : getBuilder().getTargetsForCall(node, instruction, invariantParameters)) {
					getBuilder().processDelCall(node, instruction, n, invariantParameters, uniqueCatch);
					if (DEBUG) {
						System.err.println("visitInvoke class init " + n);
					}
					// side effect of invoke: may call class initializer
					//					processClassInitializer(n.getMethod().getDeclaringClass());
				}
			} else {
				// Add a side effect that will fire when we determine a value
				// for a dispatch parameter. This side effect will create a new node
				// and new constraints based on the new callee context.
				final int vns[] = new int[ params.size() ];
				params.foreach(new IntSetAction() {
					private int i = 0;
					@Override
					public void act(int x) {
						vns[i++] = instruction.getUse(x);
					}
				});

				if (contentsAreInvariant(symbolTable, du, vns)) {
					for(CGNode n : getBuilder().getTargetsForCall(node, instruction, invariantParameters)) {
						getBuilder().processDelCall(node, instruction, n, invariantParameters, uniqueCatch);
						// side effect of invoke: may call class initializer
						//						processClassInitializer(n.getMethod().getDeclaringClass());
					}
				} else {
					if (DEBUG) {
						System.err.println("Add side effect, dispatch to " + instruction + " for " + params);
					}

					final List<PointerKey> pks = new ArrayList<PointerKey>(params.size());
					params.foreach(new IntSetAction() {
						@Override
						public void act(int x) {
							if (!contentsAreInvariant(symbolTable, du, instruction.getUse(x))) {
								pks.add(getBuilder().getPointerKeyForLocal(node, instruction.getUse(x)));
							}
						}
					});

					IPADispatchOperator dispatchOperator = getBuilder().new IPADispatchOperator(instruction, node,
							invariantParameters, uniqueCatch, params);
					system.delSideEffect(dispatchOperator, pks.toArray(new PointerKey[pks.size()]));
				}
			}
		}

		protected void visitInvokeInternalAction(final SSAAbstractInvokeInstruction instruction, InvariantComputer invs) {
			if (DEBUG) {
				System.err.println("visitInvoke: " + instruction);
			}

			PointerKey uniqueCatch = null;
			if (hasUniqueCatchBlock(instruction, ir)) {
				uniqueCatch = getBuilder().getUniqueCatchKey(instruction, ir, node);
			}
			
			InstanceKey[][] invariantParameters = invs.computeInvariantParameters(instruction);

			IntSet params = getBuilder().getContextSelector().getRelevantParameters(node, instruction.getCallSite());
			if (!instruction.getCallSite().isStatic() && !params.contains(0) && (invariantParameters == null || invariantParameters[0] == null)) {
				params = IPAIntSetUtil.makeMutableCopy(params);
				((MutableIntSet)params).add(0);
			}

			if (invariantParameters != null) {
				for(int i = 0; i < invariantParameters.length; i++) {
					if (invariantParameters[i] != null) {
						params = IPAIntSetUtil.makeMutableCopy(params);
						((MutableIntSet)params).remove(i);
					}
				}
			}
			if (params.isEmpty()) {
				for (CGNode n : getBuilder().getTargetsForCall(node, instruction, invariantParameters)) {
					getBuilder().processResolvedCall(node, instruction, n, invariantParameters, uniqueCatch);//
					if (DEBUG) {
						System.err.println("visitInvoke class init " + n);
					}

					// side effect of invoke: may call class initializer
					processClassInitializer(n.getMethod().getDeclaringClass());
				}
			} else {
				// Add a side effect that will fire when we determine a value
				// for a dispatch parameter. This side effect will create a new node
				// and new constraints based on the new callee context.
				final int vns[] = new int[ params.size() ];
				params.foreach(new IntSetAction() {
					private int i = 0;
					@Override
					public void act(int x) {
						vns[i++] = instruction.getUse(x);
					}
				});

				if (contentsAreInvariant(symbolTable, du, vns)) {
					for(CGNode n : getBuilder().getTargetsForCall(node, instruction, invariantParameters)) {
						getBuilder().processResolvedCall(node, instruction, n, invariantParameters, uniqueCatch);
						// side effect of invoke: may call class initializer
						processClassInitializer(n.getMethod().getDeclaringClass());
					}
				} else {
					if (DEBUG) {
						System.err.println("Add side effect, dispatch to " + instruction + " for " + params);
					}

					final List<PointerKey> pks = new ArrayList<PointerKey>(params.size());
					params.foreach(new IntSetAction() {
						@Override
						public void act(int x) {
							if (!contentsAreInvariant(symbolTable, du, instruction.getUse(x))) {
								pks.add(getBuilder().getPointerKeyForLocal(node, instruction.getUse(x)));
							}
						}
					});
					/* !!! 
					 * cpa handles invariantParameters in a separate way 
					 * since in processCallingConstraints(), constParams == invariantParameters == null
					 * it will use the actual pointer key and our filter for java/lang/Thread.start()
					 */
					IPADispatchOperator dispatchOperator = getBuilder().new IPADispatchOperator(instruction, node,
							invariantParameters, uniqueCatch, params);
					system.newSideEffect(dispatchOperator, pks.toArray(new PointerKey[pks.size()]));
				}
			}
		}

		/*
		 * @see com.ibm.wala.ssa.Instruction.Visitor#visitNew(com.ibm.wala.ssa.NewInstruction)
		 */
		@Override
		public void visitNew(SSANewInstruction instruction) {
			InstanceKey iKey = getInstanceKeyForAllocation(instruction.getNewSite());

			if (iKey == null) {
				// something went wrong. I hope someone raised a warning.
				return;
			}
			
			PointerKey def = getPointerKeyForLocal(instruction.getDef());
			IClass klass = iKey.getConcreteType();

			if (DEBUG) {
				System.err.println("visitNew: " + instruction + " i:" + iKey + " " + system.findOrCreateIndexForInstanceKey(iKey));
			}

			if (klass == null) {
				if (DEBUG) {
					System.err.println("Resolution failure: " + instruction);
				}
				return;
			}

			if (!contentsAreInvariant(symbolTable, du, instruction.getDef())) {
				if(isDelete)
					system.delConstraint(def, iKey);
				else
					system.newConstraint(def, iKey);
			} else {
				system.findOrCreateIndexForInstanceKey(iKey);
				if(!isDelete)
					system.recordImplicitPointsToSet(def);
				else
					system.derecordImplicitPointsToSet(def);
			}

			// side effect of new: may call class initializer
			if (DEBUG) {
				System.err.println("visitNew call clinit: " + klass);
			}
			if(!isDelete){
				processClassInitializer(klass);
				processFinalizeMethod(klass);
			}

			// add instance keys and pointer keys for array contents
			int dim = 0;
			InstanceKey lastInstance = iKey;
			while (klass != null && klass.isArrayClass()) {
				klass = ((ArrayClass) klass).getElementClass();
				// klass == null means it's a primitive
				if (klass != null && klass.isArrayClass()) {
					if (instruction.getNumberOfUses() <= (dim + 1)) {
						break;
					}
					int sv = instruction.getUse(dim + 1);
					if (ir.getSymbolTable().isIntegerConstant(sv)) {
						Integer c = (Integer) ir.getSymbolTable().getConstantValue(sv);
						if (c.intValue() == 0) {
							break;
						}
					}
					InstanceKey ik = getInstanceKeyForMultiNewArray(instruction.getNewSite(), dim);
					PointerKey pk = getPointerKeyForArrayContents(lastInstance);
					if (DEBUG_MULTINEWARRAY) {
						System.err.println("multinewarray constraint: ");
						System.err.println("   pk: " + pk);
						System.err.println("   ik: " + system.findOrCreateIndexForInstanceKey(ik) + " concrete type " + ik.getConcreteType()
						+ " is " + ik);
						System.err.println("   klass:" + klass);
					}
					if(isDelete)
						system.delConstraint(pk, ik);
					else
						system.newConstraint(pk, ik);
					lastInstance = ik;
					dim++;
				}
			}
		}


		/*
		 * @see com.ibm.wala.ssa.Instruction.Visitor#visitThrow(com.ibm.wala.ssa.ThrowInstruction)
		 */
		@Override
		public void visitThrow(SSAThrowInstruction instruction) {
			// don't do anything: we handle exceptional edges
			// in a separate pass
		}

		/*
		 * @see com.ibm.wala.ssa.Instruction.Visitor#visitGetCaughtException(com.ibm.wala.ssa.GetCaughtExceptionInstruction)
		 */
		@Override
		public void visitGetCaughtException(SSAGetCaughtExceptionInstruction instruction) {
			List<ProgramCounter> peis = getIncomingPEIs(ir, getBasicBlock());
			PointerKey def = getPointerKeyForLocal(instruction.getDef());
			// SJF: we don't optimize based on dead catch blocks yet ... it's a little
			// tricky due interaction with the SINGLE_USE optimization which directly
			// shoves exceptional return values from calls into exception vars.
			// it may not be worth doing this.
			// if (hasNoInterestingUses(instruction.getDef(), du)) {
			// solver.recordImplicitPointsToSet(def);
			// } else {
			Set<IClass> types = getCaughtExceptionTypes(instruction, ir);
			if(isDelete)
				getBuilder().delExceptionDefConstraints(ir, du, node, peis, def, types, instruction.iindex - 1);
			else
				getBuilder().addExceptionDefConstraints(ir, du, node, peis, def, types);
			// }
		}

		/**
		 * TODO: What is this doing? Document me!
		 */
		private int booleanConstantTest(SSAConditionalBranchInstruction c, int v) {
			int result = 0;

			// right for OPR_eq
			if ((symbolTable.isZeroOrFalse(c.getUse(0)) && c.getUse(1) == v)
					|| (symbolTable.isZeroOrFalse(c.getUse(1)) && c.getUse(0) == v)) {
				result = -1;
			} else if ((symbolTable.isOneOrTrue(c.getUse(0)) && c.getUse(1) == v)
					|| (symbolTable.isOneOrTrue(c.getUse(1)) && c.getUse(0) == v)) {
				result = 1;
			}

			if (c.getOperator() == ConditionalBranchInstruction.Operator.NE) {
				result = -result;
			}

			return result;
		}

		/**
		 * TODO: What is this doing? Document me!
		 */
		private int nullConstantTest(SSAConditionalBranchInstruction c, int v) {
			if ((symbolTable.isNullConstant(c.getUse(0)) && c.getUse(1) == v)
					|| (symbolTable.isNullConstant(c.getUse(1)) && c.getUse(0) == v)) {
				if (c.getOperator() == ConditionalBranchInstruction.Operator.EQ) {
					return 1;
				} else {
					return -1;
				}
			} else {
				return 0;
			}
		}

		@Override
		public void visitPhi(SSAPhiInstruction instruction) {//bz: path-sensitive: do not handle now, should not affect the result
			if (ir.getMethod() instanceof AbstractRootMethod) {
				PointerKey dst = getPointerKeyForLocal(instruction.getDef());
				if (hasNoInterestingUses(instruction.getDef())) {
					if(isDelete)
						system.derecordImplicitPointsToSet(dst);
					else
						system.recordImplicitPointsToSet(dst);
				} else {
					for (int i = 0; i < instruction.getNumberOfUses(); i++) {
						PointerKey use = getPointerKeyForLocal(instruction.getUse(i));
						if (contentsAreInvariant(symbolTable, du, instruction.getUse(i))) {
							if(isDelete)
								system.derecordImplicitPointsToSet(use);
							else
								system.recordImplicitPointsToSet(use);
							InstanceKey[] ik = getInvariantContents(instruction.getUse(i));
							for (int j = 0; j < ik.length; j++) {
								if (isDelete) {
									system.delConstraint(dst, ik[j]);
								}else {
									system.newConstraint(dst, ik[j]);
								}
							}
						} else {
							if (isDelete) {
								system.delConstraint(dst, assignOperator, use);
							}else {
								system.newConstraint(dst, assignOperator, use);
							}
						}
					}
				}
			}
		}

		/*
		 * @see com.ibm.wala.ssa.SSAInstruction.Visitor#visitPi(com.ibm.wala.ssa.SSAPiInstruction)
		 */
		@Override
		public void visitPi(SSAPiInstruction instruction) {
//			System.out.println(instruction);
			int dir;
			if (hasNoInterestingUses(instruction.getDef())) {
				PointerKey dst = getPointerKeyForLocal(instruction.getDef());
				if(isDelete)
					system.derecordImplicitPointsToSet(dst);
				else
					system.recordImplicitPointsToSet(dst);
			} else {
				ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg = ir.getControlFlowGraph();
				int val = instruction.getVal();
				if (com.ibm.wala.cfg.Util.endsWithConditionalBranch(cfg, getBasicBlock()) && cfg.getSuccNodeCount(getBasicBlock()) == 2) {
					SSAConditionalBranchInstruction cond = (SSAConditionalBranchInstruction) com.ibm.wala.cfg.Util.getLastInstruction(cfg,
							getBasicBlock());
					SSAInstruction cause = instruction.getCause();
					BasicBlock target = (BasicBlock) cfg.getNode(instruction.getSuccessor());

					if ((cause instanceof SSAInstanceofInstruction)) {
						int direction = booleanConstantTest(cond, cause.getDef());
						if (direction != 0) {
							TypeReference type = ((SSAInstanceofInstruction) cause).getCheckedType();
							IClass cls = getClassHierarchy().lookupClass(type);
							if (cls == null) {
								PointerKey dst = getPointerKeyForLocal(instruction.getDef());
								if(isDelete)
									delPiAssignment(dst, val);
								else
									addPiAssignment(dst, val);
							} else {
								PointerKey dst = getFilteredPointerKeyForLocal(instruction.getDef(), new IPAFilteredPointerKey.SingleClassFilter(cls));
								// if true, only allow objects assignable to cls.  otherwise, only allow objects
								// *not* assignable to cls
								boolean useFilter = (target == com.ibm.wala.cfg.Util.getTakenSuccessor(cfg, getBasicBlock()) && direction == 1)
										|| (target == com.ibm.wala.cfg.Util.getNotTakenSuccessor(cfg, getBasicBlock()) && direction == -1);
								PointerKey src = getPointerKeyForLocal(val);
								if (contentsAreInvariant(symbolTable, du, val)) {
									if(isDelete)
										system.derecordImplicitPointsToSet(src);
									else
										system.recordImplicitPointsToSet(src);
									InstanceKey[] ik = getInvariantContents(val);
									for (int j = 0; j < ik.length; j++) {
										boolean assignable = getClassHierarchy().isAssignableFrom(cls, ik[j].getConcreteType());
										if ((assignable && useFilter) || (!assignable && !useFilter)) {
											if(isDelete)
												system.delConstraint(dst, ik[j]);
											else
												system.newConstraint(dst, ik[j]);
										}
									}
								} else {
									IPAFilterOperator op = useFilter ? getBuilder().filterOperator : getBuilder().inverseFilterOperator;
									if(isDelete)
										system.delConstraint(dst, op, src);
									else
										system.newConstraint(dst, op, src);
								}
							}
						}
					} else if ((dir = nullConstantTest(cond, val)) != 0) {
						if ((target == com.ibm.wala.cfg.Util.getTakenSuccessor(cfg, getBasicBlock()) && dir == -1)
								|| (target == com.ibm.wala.cfg.Util.getNotTakenSuccessor(cfg, getBasicBlock()) && dir == 1)) {
							PointerKey dst = getPointerKeyForLocal(instruction.getDef());
							if(isDelete)
								delPiAssignment(dst, val);
							else
								addPiAssignment(dst, val);
						}
					} else {
						PointerKey dst = getPointerKeyForLocal(instruction.getDef());
						if(isDelete)
							delPiAssignment(dst, val);
						else
							addPiAssignment(dst, val);
					}
				} else {
					PointerKey dst = getPointerKeyForLocal(instruction.getDef());
					if(isDelete)
						delPiAssignment(dst, val);
					else
						addPiAssignment(dst, val);
				}
			}
		}

		/**
		 * Add a constraint to the system indicating that the contents of local src flows to dst, with no special type filter.
		 */
		private void addPiAssignment(PointerKey dst, int src) {
			PointerKey srcKey = getPointerKeyForLocal(src);
			if (contentsAreInvariant(symbolTable, du, src)) {
				system.recordImplicitPointsToSet(srcKey);
				InstanceKey[] ik = getInvariantContents(src);
				for (int j = 0; j < ik.length; j++) {
					system.newConstraint(dst, ik[j]);
				}
			} else {
				system.newConstraint(dst, assignOperator, srcKey);
			}
		}
		
		private void delPiAssignment(PointerKey dst, int src) {
			PointerKey srcKey = getPointerKeyForLocal(src);
			if (contentsAreInvariant(symbolTable, du, src)) {
				system.derecordImplicitPointsToSet(srcKey);
				InstanceKey[] ik = getInvariantContents(src);
				for (int j = 0; j < ik.length; j++) {
					system.delConstraint(dst, ik[j]);
				}
			} else {
				system.delConstraint(dst, assignOperator, srcKey);
			}
		}

		public ISSABasicBlock getBasicBlock() {
			return basicBlock;
		}

		/**
		 * The calling loop must call this in each iteration!
		 */
		@Override
		public void setBasicBlock(ISSABasicBlock block) {
			basicBlock = block;
		}

		protected interface InvariantComputer {

			InstanceKey[][] computeInvariantParameters(SSAAbstractInvokeInstruction call);

		}

		public class DefaultInvariantComputer implements InvariantComputer {
			/**
			 * Side effect: records invariant parameters as implicit points-to-sets.
			 *
			 * @return if non-null, then result[i] holds the set of instance keys which may be passed as the ith parameter. (which must be
			 *         invariant)
			 */
			@Override
			public InstanceKey[][] computeInvariantParameters(SSAAbstractInvokeInstruction call) {
				InstanceKey[][] constParams = null;
				for (int i = 0; i < call.getNumberOfUses(); i++) {
					// not sure how getUse(i) <= 0 .. dead code?
					// TODO: investigate
					if (call.getUse(i) > 0) {
						if (contentsAreInvariant(symbolTable, du, call.getUse(i))) {
							/**
							 * bz: should not record implicit pts during deletion
							 */
							if(isDelete)
								system.derecordImplicitPointsToSet(getPointerKeyForLocal(call.getUse(i)));
							else
								system.recordImplicitPointsToSet(getPointerKeyForLocal(call.getUse(i)));
							
							if (constParams == null) {
								constParams = new InstanceKey[call.getNumberOfUses()][];
							}
							constParams[i] = getInvariantContents(call.getUse(i));
							for (int j = 0; j < constParams[i].length; j++) {
								system.findOrCreateIndexForInstanceKey(constParams[i][j]);
							}
						}
					}
				}
				return constParams;
			}
		}

		@Override
		public void visitLoadMetadata(SSALoadMetadataInstruction instruction) {
			PointerKey def = getPointerKeyForLocal(instruction.getDef());
			InstanceKey iKey = getInstanceKeyForClassObject(instruction.getToken(), instruction.getType());

			if (instruction.getToken() instanceof TypeReference) {
				IClass klass = getClassHierarchy().lookupClass((TypeReference) instruction.getToken());
				if (klass != null) {
					processClassInitializer(klass);
				}
			}

			if (!contentsAreInvariant(symbolTable, du, instruction.getDef())) {
				if(isDelete)
					system.delConstraint(def, iKey);
				else
					system.newConstraint(def, iKey);
			} else {
				system.findOrCreateIndexForInstanceKey(iKey);
				if(isDelete)
					system.derecordImplicitPointsToSet(def);
				else
					system.recordImplicitPointsToSet(def);
			}
		}


		private void processFinalizeMethod(final IClass klass) {
			if (! getBuilder().finalizeVisited.contains(klass)) {
				getBuilder().finalizeVisited.add(klass);
				IMethod finalizer = klass.getMethod(MethodReference.finalizeSelector);
				if (finalizer != null && ! finalizer.getDeclaringClass().getReference().equals(TypeReference.JavaLangObject)) {
					Entrypoint ef = new DefaultEntrypoint(finalizer, getClassHierarchy()) {
						@Override
						protected TypeReference[] makeParameterTypes(IMethod method, int i) {
							if (i == 0) {
								return new TypeReference[]{ klass.getReference() };
							} else {
								return super.makeParameterTypes(method, i);
							}
						}
					};
					ef.addCall((AbstractRootMethod)callGraph.getFakeRootNode().getMethod());
					getBuilder().markChanged(callGraph.getFakeRootNode());
				}
			}
		}

		/**
		 * TODO: lift most of this logic to PropagationCallGraphBuilder
		 *
		 * Add a call to the class initializer from the root method.
		 */
		protected void processClassInitializer(IClass klass) {

			assert klass != null;

			if (!getBuilder().getOptions().getHandleStaticInit()) {
				return;
			}

			if (getBuilder().clinitVisited.contains(klass)) {
				return;
			}
			getBuilder().clinitVisited.add(klass);

			if (klass.getClassInitializer() != null) {
				if (DEBUG) {
					System.err.println("process class initializer for " + klass);
				}

				// add an invocation from the fake root method to the <clinit>
				MethodReference m = klass.getClassInitializer().getReference();
				CallSiteReference site = CallSiteReference.make(1, m, IInvokeInstruction.Dispatch.STATIC);
				IMethod targetMethod = getOptions().getMethodTargetSelector().getCalleeTarget(callGraph.getFakeRootNode(), site, null);
				if (targetMethod != null) {
					CGNode target = getTargetForCall(callGraph.getFakeRootNode(), site, null, null);
					if (target != null && callGraph.getPredNodeCount(target) == 0) {
						AbstractRootMethod fakeWorldClinitMethod = (AbstractRootMethod) callGraph.getFakeWorldClinitNode().getMethod();
						SSAAbstractInvokeInstruction s = fakeWorldClinitMethod.addInvocation(new int[0], site);
						PointerKey uniqueCatch = getBuilder().getPointerKeyForExceptionalReturnValue(callGraph.getFakeRootNode());
						getBuilder().processResolvedCall(callGraph.getFakeWorldClinitNode(), s, target, null, uniqueCatch);
					}
				}
			}

			IClass sc = klass.getSuperclass();
			if (sc != null) {
				processClassInitializer(sc);
			}
		}

		@Override
		public void updateOriginSensitiveObjectForNew(int type, CGNode ciCGNode, CGNode originCGNode,
				SSANewInstruction instruction) {
			// TODO Auto-generated method stub
			
		}
	}
	
	
	// only used to give convenience when overwriting in its sub classes
	protected void updateAddTargets(CGNode caller, InstanceKey[] actualParameters, SSAAbstractInvokeInstruction instruction, CGNode target) {
		return;
	}
	
	protected void updateDelTargets(CGNode caller, InstanceKey[] actualParameters, SSAAbstractInvokeInstruction instruction, CGNode target) {
		return;
	}


	/**
	 * Add constraints for a call site after we have computed a reachable target for the dispatch
	 *
	 * Side effect: add edge to the call graph.
	 *
	 * @param instruction
	 * @param constParams if non-null, then constParams[i] holds the set of instance keys that are passed as param i, or null if param
	 *          i is not invariant
	 * @param uniqueCatchKey if non-null, then this is the unique PointerKey that catches all exceptions from this call site.
	 */
	@SuppressWarnings("deprecation")
	private void processResolvedCall(CGNode caller, SSAAbstractInvokeInstruction instruction, CGNode target,
			InstanceKey[][] constParams, PointerKey uniqueCatchKey) {

		if (DEBUG) {
			System.err.println("processResolvedCall: " + caller + " ," + instruction + " , " + target);
		}

		if (DEBUG) {
			System.err.println("addTarget: " + caller + " ," + instruction + " , " + target);
		}
		
		
		boolean new_target = caller.addTarget(instruction.getCallSite(), target);
		
		if(DEBUG && new_target) {
			System.out.println(caller.toString() + "\n  -> " + instruction.toString() + "\n       " + target.toString());
		}

		if (callGraph.getFakeRootNode().equals(caller)) {
			if (entrypointCallSites.contains(instruction.getCallSite())) {
				callGraph.registerEntrypoint(target);
			}
		}

		if (!haveAlreadyVisited(target)) {
			markDiscovered(target);
		} 
//		else {
//			/**
//			 * bz: path-sensitive
//			 * for visited nodes, already computed everything, the caller can directly use them. skip the computation.
//			 * however, the input parameter may affect the taken branches inside target, may causes different branches.....
//			 */
//			if(PATH_SENSITIVE && PATH_CONTEXT_SENSITIVE) {
//				IPAExplicitNode _target = (IPAExplicitNode) target;
//				if (_target.HAS_BRANCH) {
//					//create another copy of node, temporarily using 1-call-site as the context
//					target = callGraph.createNewCopyNode(target, caller, instruction.getCallSite());
//					if(haveAlreadyVisited(target)) {
//						return;
//					}
//					System.out.println("** CREATE NEW COPY: " + target.toString());
//					NUM_CREATED_CGNODES++;
//				}
//			}
//		}
//		caller.addTarget(instruction.getCallSite(), target);

		processCallingConstraints(caller, instruction, target, constParams, uniqueCatchKey);
	}


	/**
	 * bz : delete a call
	 */
	private void processDelCall(CGNode caller, SSAAbstractInvokeInstruction instruction, CGNode target,
			InstanceKey[][] constParams, PointerKey uniqueCatchKey){
		if (DEBUG) {
			System.err.println("processDelCall: " + caller + " ," + instruction);
			System.err.println("and target is : "+target);
		}

		if (DEBUG) {
			System.err.println("delTarget: " + caller + " ," + instruction + " , " + target);
		}
		((IPAExplicitNode) caller).removeTarget(instruction.getCallSite(), target);//bz

		if (callGraph.getFakeRootNode().equals(caller)) {
			if (entrypointCallSites.contains(instruction.getCallSite())) {
				callGraph.deRegisterEntrypoint(target);
			}
		}
		/**
		 * IF TARGET IS A THREAD START METHOD, 
		 * do we remove all the CGNodes/Pointers/Objects/PointsToSetVariables created by the THREAD?
		 * Currently, we keep them...
		 */
		if (haveAlreadyVisited(target)) {//&& target.getMethod().getName().toString().contains("start")
			IntSet other_callers = callGraph.getPredNodeNumbers(target);
			if((other_callers == null || other_callers.size() == 0) //no other callers
					&& !(target.getMethod().isStatic() && target.getMethod().getNumberOfParameters() == 0)) {
				/* ---> to save time, for static method with no parameters, 
				 * we keep them no matter it has other callers or not...
				 */
				if(system.kcfa || system.kobj) {
					return; // should already computed before reaching here
				}
				markWillRemove(target); 
			}
		}
		
		//process change later, since we need to keep the value in param/return value
		processDelCallingConstraints(caller, instruction, target, constParams, uniqueCatchKey);
	}

	protected void processCallingConstraints(CGNode caller, SSAAbstractInvokeInstruction instruction, CGNode target,
			InstanceKey[][] constParams, PointerKey uniqueCatchKey) {
		// TODO: i'd like to enable this optimization, but it's a little tricky
		// to recover the implicit points-to sets with recursion. TODO: don't
		// be lazy and code the recursive logic to enable this.
		// if (hasNoInstructions(target)) {
		// // record points-to sets for formals implicitly .. computed on
		// // demand.
		// // TODO: generalize this by using hasNoInterestingUses on parameters.
		// // however .. have to be careful to cache results in that case ... don't
		// // want
		// // to recompute du each time we process a call to Object.<init> !
		// for (int i = 0; i < instruction.getNumberOfUses(); i++) {
		// // we rely on the invariant that the value number for the ith parameter
		// // is i+1
		// final int vn = i + 1;
		// PointerKey formal = getPointerKeyForLocal(target, vn);
		// if (target.getMethod().getParameterType(i).isReferenceType()) {
		// system.recordImplicitPointsToSet(formal);
		// }
		// }
		// } else {
		// generate contraints from parameter passing
		int nUses = instruction.getNumberOfPositionalParameters();
		int nExpected = target.getMethod().getNumberOfParameters();

		/*
		 * int nExpected = target.getMethod().getReference().getNumberOfParameters(); if (!target.getMethod().isStatic() &&
		 * !target.getMethod().isClinit()) { nExpected++; }
		 */

		if (nUses != nExpected) {
			// some sort of unverifiable code mismatch. give up.
			return;
		}
		
		/**bz: SPECIAL HANDLING WHEN caller and callee have different thread contexts:
		 * e.g. main() invokes java/lang/Thread.start() => have to match the context for parameters, 
		 * since v1.<init> and v1.start() is special;
		 * only happens when assigning constParams for v1 (this), later assignments will be automatically solved.
		 * TODO: this checking is so complex... 
		 */
//		boolean match = false;
//		IMethod tMethod = target.getMethod();
//		if((THREAD_SENSITIVE || THREAD_EVENT_SENSITIVE)) {   //this filter for other pta
//			if(JavaUtil.doExtendThreadRelatedClass(tMethod.getDeclaringClass()) 
//				&& (tMethod.getName().toString().equals("<init>"))){
//				//for v1.<init>; match ik by thread contexts; 
//				match = true;
//			}
//			if(tMethod.toString().contains("Ljava/lang/Thread, start()V")) {
//				//for v1.start()
//				match = true;
//			}
//		}
		// generate contraints from parameter.
		// we're a little sloppy for now ... we don't filter calls to java.lang.Object.
		// TODO: we need much more precise filters than cones in order to handle
		// the various types of dispatch logic. We need a filter that expresses
		// "the set of types s.t. x.foo resolves to y.foo."
		//bz: due to @com.ibm.wala.cast.ir.translator.AstTranslator, we change the i here
		for (int i = 0; i < instruction.getNumberOfPositionalParameters(); i++) {
			if (target.getMethod().getParameterType(i).isReferenceType()) {
				PointerKey formal = getTargetPointerKey(target, i); 
//				int use = instruction.getUse(i);
				if (constParams != null && constParams[i] != null) {
					InstanceKey[] ik = constParams[i];
					for (int j = 0; j < ik.length; j++) {
//						if(match && i == 0) {//only for the base variable: v1.  v2 ~ vn might be thread-shared variables
//							//special handling to match ik with formal for loop-created threads
//							Context tmp = ((LocalPointerKey) formal).getNode().getContext();
//							if(tmp instanceof ThreadSensitiveContext) {
//								ThreadSensitiveContext formal_cts = (ThreadSensitiveContext) tmp; 
//								if(ik[j] instanceof ThreadNormalAllocationInNode) {
//									ThreadSensitiveContext ik_cts = (ThreadSensitiveContext) ((ThreadNormalAllocationInNode) ik[j]).getNode().getContext();
//									if(!formal_cts.equals(ik_cts))
//										continue;
//								}
////								else {
////									System.out.println("DEBUG: This supposed to be ThreadNormalAllocationInNode: " + ik[j]);
////								}
//							}else if(tmp instanceof ThreadEHandlerContext) {
//								//bz: both thread and event handler context should match....
//								ThreadEHandlerContext formal_ctx = (ThreadEHandlerContext) tmp;
//								if(ik[j] instanceof ThreadNormalAllocationInNode) {
//									ThreadEHandlerContext ik_cts = (ThreadEHandlerContext) ((ThreadNormalAllocationInNode) ik[j]).getNode().getContext();
//									if(!formal_ctx.equals(ik_cts))
//										continue;
//								}
////								else {
////									System.out.println("DEBUG: This supposed to be ThreadNormalAllocationInNode: " + ik[j]);
////								}
//							}else if(tmp instanceof ThreadEventContext){
//								//bz: both thread and event context should match....
//								ThreadEventContext formal_ctx = (ThreadEventContext) tmp;
//								if(ik[j] instanceof ThreadNormalAllocationInNode) {
//									ThreadEventContext ik_cts = (ThreadEventContext) ((ThreadNormalAllocationInNode) ik[j]).getNode().getContext();
//									if(!formal_ctx.equals(ik_cts))
//										continue;
//								}
////								else {
////									System.out.println("DEBUG: This supposed to be ThreadNormalAllocationInNode: " + ik[j]);
////								}
//							}
////							else {//from reflection
////								System.out.println("DEBUG: Invalid context type here: " + tmp.toString());
////							}
//						}
						system.newConstraint(formal, ik[j]);
					}
				} else {
					if (instruction.getUse(i) < 0) {
						Assertions.UNREACHABLE("unexpected " + instruction + " in " + caller);
					}
//					if(match) {
//						//special handling to match ik with formal for loop-created threads
//						//bz: do matchThreadSensitiveContexts() in SingleThreadFilter 
//						if(! (target.getContext() instanceof DelegatingContext))//from reflection ...
//							formal = getFilteredTargetPointerKey(target, i);
//					}
					PointerKey actual = getPointerKeyForLocal(caller, instruction.getUse(i));
					if (formal instanceof IPAFilteredPointerKey) {
						system.newConstraint(formal, filterOperator, actual);
					} else {
						system.newConstraint(formal, assignOperator, actual);
					}
				}
			}
		}
		
		// generate contraints from return value.
		if (instruction.hasDef() && instruction.getDeclaredResultType().isReferenceType()) {
			//SPECIAL should not be affecting this return assignment.
			PointerKey result = getPointerKeyForLocal(caller, instruction.getDef());
			PointerKey ret = getPointerKeyForReturnValue(target);
			system.newConstraint(result, assignOperator, ret);
		}
		
////		if(caller.getMethod().getDeclaringClass().getReference().getName().toString().equals(YARN_RM)
////		    && instruction.getCallSite().toString().contains(HADOOP_CONF)) {
//		if(instruction.getCallSite().toString().contains(HADOOP_CONF)) {
//		  //bz: special code for reflection in yarn:
//		  //we directly assign the string values in org/apache/hadoop/conf/Configuration or YarnConfiguration
//		  for (int i = 0; i < instruction.getNumberOfPositionalParameters(); i++) {
//	      if (target.getMethod().getParameterType(i).isReferenceType()) {
//	        PointerKey result = getPointerKeyForLocal(caller, instruction.getDef());
//	        if (constParams != null && constParams[i] != null) {
//	          InstanceKey[] ik = constParams[i];
//	          for (int j = 0; j < ik.length; j++) {
//	            system.newConstraint(result, ik[j]);
//	          }
//	        }
//	      }
//		  }
//		}
		
		// generate constraints from exception return value.
		PointerKey e = getPointerKeyForLocal(caller, instruction.getException());
		PointerKey er = getPointerKeyForExceptionalReturnValue(target);
		if (SHORT_CIRCUIT_SINGLE_USES && uniqueCatchKey != null) {
			// e has exactly one use. so, represent e implicitly
			system.newConstraint(uniqueCatchKey, assignOperator, er);
		} else {
			system.newConstraint(e, assignOperator, er);
		}
		// }
	}
	
	
	

	/**bz
	 * @param caller
	 * @param instruction
	 * @param target
	 * @param constParams
	 * @param uniqueCatchKey
	 */
	protected void processDelCallingConstraints(CGNode caller, SSAAbstractInvokeInstruction instruction, CGNode target,
			InstanceKey[][] constParams, PointerKey uniqueCatchKey) {
		if (callGraph.getFakeRootNode().equals(caller)) {
			if (entrypointCallSites.contains(instruction.getCallSite())) {
				callGraph.deRegisterEntrypoint(target);
			}
		}
		
		//TODO: bz: handle SPECIAL??
		for (int i = 0; i < instruction.getNumberOfPositionalParameters(); i++) {
			if (target.getMethod().getParameterType(i).isReferenceType()) {
				PointerKey formal = getTargetPointerKey(target, i);
				int use = instruction.getUse(i);
//				if(SPECIAL && ((IPAExplicitNode) caller).createAnyDef(use))
//					formal = getSpecialTargetPointerKey(target, i);
				if (constParams != null && constParams[i] != null) {
					InstanceKey[] ik = constParams[i];
					for (int j = 0; j < ik.length; j++) {
						system.delConstraint(formal, ik[j]);
					}
				} else {
					if (instruction.getUse(i) < 0) {
						Assertions.UNREACHABLE("unexpected " + instruction + " in " + caller);
					}
					PointerKey actual = getPointerKeyForLocal(caller, instruction.getUse(i));
					if (formal instanceof IPAFilteredPointerKey) {
						system.delConstraint(formal, filterOperator, actual);
					} else {
						system.delConstraint(formal, assignOperator, actual);
					}
				}
			}
		}
		// delete contraints from return value.
		if (instruction.hasDef() && instruction.getDeclaredResultType().isReferenceType()) {
			PointerKey result = getPointerKeyForLocal(caller, instruction.getDef());
			PointerKey ret = getPointerKeyForReturnValue(target);
//			if(ret.toString().contains("Ret-V:Node: < Primordial, Ljava/lang/Runtime, getRuntime()Ljava/lang/Runtime; > Context: CallStringContext: [ java.lang.System.exit(I)V@0 tsp.TspSolver.recursive_solve(I)V@127 ]"))
//				System.out.println();
			system.delConstraint(result, assignOperator, ret);
		}
		
//		// delete constraints from exception return value.
//		PointerKey e = getPointerKeyForLocal(caller, instruction.getException());
//		PointerKey er = getPointerKeyForExceptionalReturnValue(target);
//		if (SHORT_CIRCUIT_SINGLE_USES && uniqueCatchKey != null) {
//			// e has exactly one use. so, represent e implicitly
//			system.delConstraint(uniqueCatchKey, assignOperator, er);
//		} else {
//			system.delConstraint(e, assignOperator, er);
//		}
	}

	/**
	 * An operator to fire when we discover a potential new callee for a virtual or interface call site.
	 *
	 * This operator will create a new callee context and constraints if necessary.
	 */
	public final class IPADispatchOperator extends IPAAbstractOperator<IPAPointsToSetVariable> implements IPointerOperator {
		private final SSAAbstractInvokeInstruction call;

		private final CGNode node;

		private final InstanceKey[][] constParams;

		private final PointerKey uniqueCatch;

		/**
		 * relevant parameter indices for the registered {@link ContextSelector}
		 *
		 * @see ContextSelector#getRelevantParameters(CGNode, CallSiteReference)
		 */
		private final int[] dispatchIndices;

		/**
		 * The set of instance keys that have already been processed.
		 * previousPtrs[i] contains the processed instance keys for parameter
		 * position dispatchIndices[i]
		 */
		final private MutableIntSet[] previousPtrs;

		/**
		 * @param call
		 * @param node
		 * @param constParams if non-null, then constParams[i] holds the String constant that is passed as param i, or null if param i
		 *          is not a String constant
		 */
		IPADispatchOperator(SSAAbstractInvokeInstruction call, CGNode node, InstanceKey[][] constParams,
				PointerKey uniqueCatch, IntSet dispatchIndices) {
			this.call = call;
			this.node = node;
			this.constParams = constParams;
			this.uniqueCatch = uniqueCatch;
			this.dispatchIndices = IPAIntSetUtil.toArray(dispatchIndices);
			// we better always be interested in the receiver
			// assert this.dispatchIndices[0] == 0;
			previousPtrs = new MutableIntSet[dispatchIndices.size()];
			for(int i = 0; i < previousPtrs.length; i++) {
				previousPtrs[i] = IPAIntSetUtil.getDefaultIntSetFactory().make();
			}
		}
		
		//bz: the following getters are added due to the graph database ...
		public SSAAbstractInvokeInstruction getCall() {
			return call;
		}
		
		public CGNode getNode() {
			return node;
		}
		
		public InstanceKey[][] getConstParams() {
			return constParams;
		}
		
		public PointerKey getUniqueCatch() {
			return uniqueCatch;
		}
		
		public int[] getDispatchIndices() {
			return dispatchIndices;
		}
        ////////////////////////////////////////////////////////////////
		
		private byte cpa(final IPAPointsToSetVariable[] rhs) { 
			final MutableBoolean changed = new MutableBoolean();
			for(int rhsIndex = 0; rhsIndex < rhs.length; rhsIndex++) {
				final int y = rhsIndex;
				IntSet currentObjs = rhs[rhsIndex].getValue();
				if (currentObjs != null) {
					final IntSet oldObjs = previousPtrs[rhsIndex];
					IntSetAction action = new IntSetAction() {//TODO: process multiple edges together for scc
						@Override
						public void act(final int x) {
							new CrossProductRec(constParams, call, node,
									new Consumer<InstanceKey[]>() {
										@Override
										public void accept(InstanceKey[] v) {
											IClass recv = null;
											if (call.getCallSite().isDispatch()) {
												recv = v[0].getConcreteType();
											}
											CGNode target = getTargetForCall(node, call.getCallSite(), recv, v);
											if (target != null) {
												changed.b = true;
												processResolvedCall(node, call, target, constParams, uniqueCatch);
												if (!haveAlreadyVisited(target)) {
													markDiscovered(target);
												}
												updateAddTargets(node, v, call, target);
											}
										}
									}) {

								{
									rec(0, 0);
								}

								@Override
								protected IntSet getParamObjects(int paramVn, int rhsi) {
									if (rhsi == y) {
										return IPAIntSetUtil.make(new int[]{ x });
									} else {
										return previousPtrs[rhsi];
									}
								}
							};
						}
					};
//					if(system.isChange) {
//						//!!!!for incremental kcfa: previousPtrs will include the currentObjs automatically,
//						//which prevents to discover other cgnodes
//						//solution: foreachExcluding => foreach to always traverse the nodes.
//						currentObjs.foreach(action);
//					}else {//for whole program
						currentObjs.foreachExcluding(oldObjs, action);
//					}
					previousPtrs[rhsIndex].addAll(currentObjs);
				}
			}

			byte sideEffectMask = changed.b ? (byte) SIDE_EFFECT_MASK : 0;
			return (byte) (NOT_CHANGED | sideEffectMask);
		}

		/*
		 * @see com.ibm.wala.dataflow.fixpoint.UnaryOperator#evaluate(com.ibm.wala.dataflow.fixpoint.IVariable,
		 * com.ibm.wala.dataflow.fixpoint.IVariable)
		 */
		@Override
		public byte evaluate(IPAPointsToSetVariable lhs, final IPAPointsToSetVariable[] rhs) {
			assert dispatchIndices.length >= rhs.length : "bad operator at " + call;
			return cpa(rhs);
		}

		/**bz*/
		@SuppressWarnings("unused")
		private void delAllReceivers(MutableIntSet receiverVals, InstanceKey[] keys, MutableBoolean sideEffect) {
			assert keys[0] == null;
			IntIterator receiverIter = receiverVals.intIterator();
			while (receiverIter.hasNext()) {
				final int rcvr = receiverIter.next();
				keys[0] = system.getInstanceKey(rcvr);
				if (clone2Assign) {
					// for efficiency: assume that only call sites that reference
					// clone() might dispatch to clone methods
					if (call.getCallSite().getDeclaredTarget().getSelector().equals(cloneSelector)) {
						IClass recv = (keys[0] != null) ? keys[0].getConcreteType() : null;
						IMethod targetMethod = getOptions().getMethodTargetSelector().getCalleeTarget(node, call.getCallSite(), recv);
						if (targetMethod != null && targetMethod.getReference().equals(CloneInterpreter.CLONE)) {
							// treat this call to clone as an assignment
							PointerKey result = getPointerKeyForLocal(node, call.getDef());
							PointerKey receiver = getPointerKeyForLocal(node, call.getReceiver());
							system.delConstraint(result, assignOperator, receiver);
							return;
						}
					}
				}
				CGNode target = getTargetForCall(node, call.getCallSite(), keys[0].getConcreteType(), keys);
				if (target == null) {
					// This indicates an error; I sure hope getTargetForCall
					// raised a warning about this!
					if (DEBUG) {
						System.err.println("Warning: null target for call " + call);
					}
				} else {
					IntSet targets = getCallGraph().getPossibleTargetNumbers(node, call.getCallSite());
					// even if we've seen this target before, if we have constant
					// parameters, we may need to re-process the call, as the constraints
					// for the first time we reached this target may not have been fully
					// general. TODO a more refined check?
					if (targets != null && targets.contains(target.getGraphNodeId()) && noConstParams()) {
						// do nothing; we've previously discovered and handled this
						// receiver for this call site.
					} else {
						// process the newly discovered target for this call
						sideEffect.b = true;
						processDelCall(node, call, target, constParams, uniqueCatch);
					}
				}
			}
			keys[0] = null;
		}

		/**
		 * bz
		 * @param rhs
		 * @return
		 */
		private byte cpaDel(final IPAPointsToSetVariable[] rhs) {
			final MutableBoolean changed = new MutableBoolean();
			for(int rhsIndex = 0; rhsIndex < rhs.length; rhsIndex++) {
				final int y = rhsIndex;
				IntSet removeObjs = null;
				if(system.getFirstDel()){
					removeObjs = rhs[rhsIndex].getValue();
				}else{
					removeObjs = rhs[rhsIndex].getChange();
				}
				if (removeObjs != null) {
					final IntSet oldObjs = previousPtrs[rhsIndex];
					removeObjs.foreachExcluding(oldObjs, new IntSetAction() {//TODO: process multiple edges together for scc
						@Override
						public void act(final int x) {
							new CrossProductRec(constParams, call, node,
									new Consumer<InstanceKey[]>() {
										@Override
										public void accept(InstanceKey[] v) {
											IClass recv = null;
											if (call.getCallSite().isDispatch()) {
												recv = v[0].getConcreteType();
											}
											CGNode target = getTargetForCall(node, call.getCallSite(), recv, v);
											if (target != null) {
												changed.b = true;
												processDelCall(node, call, target, constParams, uniqueCatch);
												updateDelTargets(node, v, call, target);
											}
										}
									}) {

								{
									recDel(0, 0);
								}

								@Override
								protected IntSet getParamObjects(int paramVn, int rhsi) {
									if (rhsi == y) {
										return IPAIntSetUtil.make(new int[]{ x });
									} else {
										return previousPtrs[rhsi];
									}
								}
							};
						}
					});
					removeObjs.foreach(new IntSetAction() {
						@Override
						public void act(int x) {
							previousPtrs[y].remove(x);
						}
					});
				}
			}

			byte sideEffectMask = changed.b ? (byte) SIDE_EFFECT_MASK : 0;
			return (byte) (NOT_CHANGED | sideEffectMask);
		}


		/**
		 * bz:
		 */
		@Override
		public byte evaluateDel(IPAPointsToSetVariable lhs, final IPAPointsToSetVariable[] rhs){
			assert dispatchIndices.length >= rhs.length : "bad operator at " + call;
			return cpaDel(rhs);
		}

		@SuppressWarnings("unused")
		private void handleAllReceivers(MutableIntSet receiverVals, InstanceKey[] keys, MutableBoolean sideEffect) {
			assert keys[0] == null;
			IntIterator receiverIter = receiverVals.intIterator();
			while (receiverIter.hasNext()) {
				final int rcvr = receiverIter.next();
				keys[0] = system.getInstanceKey(rcvr);
				if (clone2Assign) {
					// for efficiency: assume that only call sites that reference
					// clone() might dispatch to clone methods
					if (call.getCallSite().getDeclaredTarget().getSelector().equals(cloneSelector)) {
						IClass recv = (keys[0] != null) ? keys[0].getConcreteType() : null;
						IMethod targetMethod = getOptions().getMethodTargetSelector().getCalleeTarget(node, call.getCallSite(), recv);
						if (targetMethod != null && targetMethod.getReference().equals(CloneInterpreter.CLONE)) {
							// treat this call to clone as an assignment
							PointerKey result = getPointerKeyForLocal(node, call.getDef());
							PointerKey receiver = getPointerKeyForLocal(node, call.getReceiver());
							system.newConstraint(result, assignOperator, receiver);
							return;
						}
					}
				}
				CGNode target = getTargetForCall(node, call.getCallSite(), keys[0].getConcreteType(), keys);
				if (target == null) {
					// This indicates an error; I sure hope getTargetForCall
					// raised a warning about this!
					if (DEBUG) {
						System.err.println("Warning: null target for call " + call);
					}
				} else {
					IntSet targets = getCallGraph().getPossibleTargetNumbers(node, call.getCallSite());
					// even if we've seen this target before, if we have constant
					// parameters, we may need to re-process the call, as the constraints
					// for the first time we reached this target may not have been fully
					// general. TODO a more refined check?
					if (targets != null && targets.contains(target.getGraphNodeId()) && noConstParams()) {
						// do nothing; we've previously discovered and handled this
						// receiver for this call site.
					} else {
						// process the newly discovered target for this call
						sideEffect.b = true;
						processResolvedCall(node, call, target, constParams, uniqueCatch);
						if (!haveAlreadyVisited(target)) {
							markDiscovered(target);
						}
					}
				}
			}
			keys[0] = null;
		}

		private boolean noConstParams() {
			if (constParams != null) {
				for (int i = 0; i < constParams.length; i++) {
					if (constParams[i] != null) {
						for (int j = 0; j < constParams[i].length; i++) {
							if (constParams[i][j] != null) {
								return false;
							}
						}
					}
				}
			}
			return true;
		}



		@Override
		public String toString() {
			return "Dispatch to " + call + " in node " + node;
		}

		@Override
		public int hashCode() {
			int h = 1;
			if (constParams != null) {
				for(InstanceKey[] cs : constParams) {
					if (cs != null) {
						for(InstanceKey c : cs) {
							if (c != null) {
								h = h ^ c.hashCode();
							}
						}
					}
				}
			}
			return h * node.hashCode() + 90289 * call.hashCode();
		}

		@Override
		public boolean equals(Object o) {
			// note that these are not necessarily canonical, since
			// with synthetic factories we may regenerate constraints
			// many times. TODO: change processing of synthetic factories
			// so that we guarantee to insert each dispatch equation
			// only once ... if this were true we could optimize this
			// with reference equality

			// instanceof is OK because this class is final
			if (o instanceof IPADispatchOperator) {
				IPADispatchOperator other = (IPADispatchOperator) o;
				return node.equals(other.node) && call.equals(other.call) && Arrays.deepEquals(constParams, other.constParams);
			} else {
				return false;
			}
		}

		/*
		 * @see com.ibm.wala.ipa.callgraph.propagation.IPointerOperator#isComplex()
		 */
		@Override
		public boolean isComplex() {
			return true;
		}
		
		@Override
		public void translate(OpVisitor visitor, IPAAbstractOperator op, IPAAbstractStatement stmt) {
			if (visitor == null) {
				throw new IllegalArgumentException("visitor is null");
			}
			visitor.translateDispatch(this, (IPAGeneralStatement) stmt);
		}
	}

	protected void iterateCrossProduct(final CGNode caller, final SSAAbstractInvokeInstruction call, final InstanceKey[][] invariants,
			final Consumer<InstanceKey[]> f) {
		new CrossProductRec(invariants, call, caller, f).rec(0, 0);
	}

	public Set<CGNode> getTargetsForCall(final CGNode caller, final SSAAbstractInvokeInstruction instruction, InstanceKey[][] invs) {
		// This method used to take a CallSiteReference as a parameter, rather than
		// an SSAAbstractInvokeInstruction. This was bad, since it's
		// possible for multiple invoke instructions with different actual
		// parameters to be associated with a single CallSiteReference. Changed
		// to take the invoke instruction as a parameter instead, since invs is
		// associated with the instruction
		final CallSiteReference site = instruction.getCallSite();
		final Set<CGNode> targets = HashSetFactory.make();
		Consumer<InstanceKey[]> f = new Consumer<InstanceKey[]>() {
			@Override
			public void accept(InstanceKey[] v) {
				IClass recv = null;
				if (site.isDispatch()) {
					recv = v[0].getConcreteType();
				}
				CGNode target = getTargetForCall(caller, site, recv, v); 
				if (target != null) {
					targets.add(target);
					updateAddTargets(caller, v, instruction, target);
				}
			}
		};
		iterateCrossProduct(caller, instruction, invs, f);
		return targets;
	}

	private IntSet getRelevantParameters(final CGNode caller, final CallSiteReference site) throws UnimplementedError {
		IntSet params = contextSelector.getRelevantParameters(caller, site);
		if (!site.isStatic() && !params.contains(0)) {
			params = IPAIntSetUtil.makeMutableCopy(params);
			((MutableIntSet)params).add(0);
		}
		return params;
	}

	public boolean hasNoInterestingUses(CGNode node, int vn, DefUse du) {
		if (du == null) {
			throw new IllegalArgumentException("du is null");
		}
		if (vn <= 0) {
			throw new IllegalArgumentException("v is invalid: " + vn);
		}
		// todo: enhance this by solving a dead-code elimination
		// problem.
		InterestingVisitor v = makeInterestingVisitor(node, vn);
		for (Iterator it = du.getUses(v.vn); it.hasNext();) {
			SSAInstruction s = (SSAInstruction) it.next();
			s.visit(v);
			if (v.bingo) {
				return false;
			}
		}
		return true;
	}

	protected InterestingVisitor makeInterestingVisitor(@SuppressWarnings("unused") CGNode node, int vn) {
		return new InterestingVisitor(vn);
	}

	/**
	 * sets bingo to true when it visits an interesting instruction
	 */
	protected static class InterestingVisitor extends SSAInstruction.Visitor {
		protected final int vn;

		public InterestingVisitor(int vn) {
			this.vn = vn;
		}

		protected boolean bingo = false;
		
		@Override//bz: added for path
		public void visitConditionalBranch(SSAConditionalBranchInstruction instruction) {
			bingo = false;//true; -> if path-sensitive
		}
		
		@Override//bz: previously pta will not compute this pts... and have no other implementation to compute this...
		public void visitMonitor(SSAMonitorInstruction instruction) {
			bingo = true;
		}

		@Override
		public void visitArrayLoad(SSAArrayLoadInstruction instruction) {
			if (!instruction.typeIsPrimitive() && instruction.getArrayRef() == vn) {
				bingo = true;
			}
		}

		@Override
		public void visitArrayStore(SSAArrayStoreInstruction instruction) {
			if (!instruction.typeIsPrimitive() && (instruction.getArrayRef() == vn || instruction.getValue() == vn)) {
				bingo = true;
			}
		}

		@Override
		public void visitCheckCast(SSACheckCastInstruction instruction) {
			bingo = true;
		}

		@Override
		public void visitGet(SSAGetInstruction instruction) {
			FieldReference field = instruction.getDeclaredField();
			if (!field.getFieldType().isPrimitiveType(true)) {
				bingo = true;
			}
		}

		@Override
		public void visitGetCaughtException(SSAGetCaughtExceptionInstruction instruction) {
			bingo = true;
		}

		@Override
		public void visitInvoke(SSAInvokeInstruction instruction) {
			bingo = true;
		}

		@Override
		public void visitPhi(SSAPhiInstruction instruction) {
			bingo = true;
		}

		@Override
		public void visitPi(SSAPiInstruction instruction) {
			bingo = true;
		}

		@Override
		public void visitPut(SSAPutInstruction instruction) {
			FieldReference field = instruction.getDeclaredField();
			if (!field.getFieldType().isPrimitiveType(true)) {
				bingo = true;
			}
		}

		@Override
		public void visitReturn(SSAReturnInstruction instruction) {
			bingo = true;
		}

		@Override
		public void visitThrow(SSAThrowInstruction instruction) {
			bingo = true;
		}
	}


	public static boolean isRootType(IClass klass) {//bz: db, private to public
		return klass.getClassHierarchy().isRootClass(klass);
	}
	
	/**
	 * bz: for special handling parameters
	 * @param target
	 * @param index
	 * @return
	 */
	public PointerKey getFilteredTargetPointerKey(CGNode target, int index) {
		int vn;
		if (target.getIR() != null) {
			vn = target.getIR().getSymbolTable().getParameter(index);
		} else {
			vn = index+1;
		}

		IPAFilteredPointerKey.IPATypeFilter filter = null;
		return getFilteredPointerKeyForLocal(target, vn, filter);
	}

	/**
	 * TODO: enhance this logic using type inference TODO!!!: enhance filtering to consider concrete types, not just cones.
	 * precondition: needs Filter
	 *
	 * @param target
	 * @return an IClass which represents
	 */
	public PointerKey getTargetPointerKey(CGNode target, int index) {
		int vn;
		if (target.getIR() != null) {
			vn = target.getIR().getSymbolTable().getParameter(index);
		} else {
			vn = index+1;
		}

		IPAFilteredPointerKey.IPATypeFilter filter;
		if(target.getContext().get(ContextKey.PARAMETERS[index]) instanceof FilteredPointerKey.TypeFilter){//bz: maybe changed from FilteredPointerKey.TypeFilter to IPAFilteredPointerKey.IPATypeFilter...
			IClass cl = target.getMethod().getDeclaringClass();
			filter = new IPAFilteredPointerKey.SingleClassFilter(cl);
		}else {
			filter = (IPAFilteredPointerKey.IPATypeFilter) target.getContext().get(ContextKey.PARAMETERS[index]);
		}

		if (filter != null && !filter.isRootFilter()) {
			return getFilteredPointerKeyForLocal(target, vn, filter);
		}
		
		// the context does not select a particular concrete type for the
		// receiver, so use the type of the method
		IClass C;
		if (index == 0 && !target.getMethod().isStatic()) {
			C = getReceiverClass(target.getMethod());
		} else {
			C = cha.lookupClass(target.getMethod().getParameterType(index));
		}

		if (C == null || C.getClassHierarchy().getRootClass().equals(C)) {
			return getPointerKeyForLocal(target, vn);
		} else {
			return getFilteredPointerKeyForLocal(target, vn, new IPAFilteredPointerKey.SingleClassFilter(C));
		}
	}

	/**
	 * @param method
	 * @return the receiver class for this method.
	 */
	private IClass getReceiverClass(IMethod method) {
		TypeReference formalType = method.getParameterType(0);
		IClass C = getClassHierarchy().lookupClass(formalType);
		if (method.isStatic()) {
			Assertions.UNREACHABLE("asked for receiver of static method " + method);
		}
		if (C == null) {
			Assertions.UNREACHABLE("no class found for " + formalType + " recv of " + method);
		}
		return C;
	}

	/**
	 * bz: db; change from protected to public
	 * 
	 * A value is "invariant" if we can figure out the instances it can ever point to locally, without resorting to propagation.
	 *
	 * @param valueNumber
	 * @return true iff the contents of the local with this value number can be deduced locally, without propagation
	 */
	public boolean contentsAreInvariant(SymbolTable symbolTable, DefUse du, int valueNumber) {
		if (isConstantRef(symbolTable, valueNumber)) {
			return true;
		} else if (SHORT_CIRCUIT_INVARIANT_SETS) {
			SSAInstruction def = du.getDef(valueNumber);
			if (def instanceof SSANewInstruction) {
				return true;
			}
			return false;
		} else {
			return false;
		}
	}
	
	//bz: db; change from protected to public
	public boolean contentsAreInvariant(SymbolTable symbolTable, DefUse du, int valueNumbers[]) {
		for(int i = 0; i < valueNumbers.length; i++) {
			if (! contentsAreInvariant(symbolTable, du, valueNumbers[i])) {
				return false;
			}
		}
		return true;
	}

	/**
	 * precondition:contentsAreInvariant(valueNumber)
	 *
	 * @param valueNumber
	 * @return the complete set of instances that the local with vn=valueNumber may point to.
	 */
	public InstanceKey[] getInvariantContents(SymbolTable symbolTable, DefUse du, CGNode node, int valueNumber, IPAHeapModel hm) {
		return getInvariantContents(symbolTable, du, node, valueNumber, hm, false);
	}

	protected InstanceKey[] getInvariantContents(SymbolTable symbolTable, DefUse du, CGNode node, int valueNumber, IPAHeapModel hm,
			boolean ensureIndexes) {
		InstanceKey[] result = null;
		if (isConstantRef(symbolTable, valueNumber)) {
			Object x = symbolTable.getConstantValue(valueNumber);
			if (x instanceof String) {
				// this is always the case in Java. use strong typing in the call to getInstanceKeyForConstant.
				String S = (String) x;
				TypeReference type = node.getMethod().getDeclaringClass().getClassLoader().getLanguage().getConstantType(S);
				if (type == null) {
					return new InstanceKey[0];
				}
				InstanceKey ik = hm.getInstanceKeyForConstant(type, S);
				if (ik != null) {
					result = new InstanceKey[] { ik };
				} else {
					result = new InstanceKey[0];
				}
			} else {
				// some non-built in type (e.g. Integer). give up on strong typing.
				// language-specific subclasses (e.g. Javascript) should override this method to get strong typing
				// with generics if desired.
				TypeReference type = node.getMethod().getDeclaringClass().getClassLoader().getLanguage().getConstantType(x);
				if (type == null) {
					return new InstanceKey[0];
				}
				InstanceKey ik = hm.getInstanceKeyForConstant(type, x);
				if (ik != null) {
					result = new InstanceKey[] { ik };
				} else {
					result = new InstanceKey[0];
				}
			}
		} else {
				SSANewInstruction def = (SSANewInstruction) du.getDef(valueNumber);
				InstanceKey iKey = hm.getInstanceKeyForAllocation(node, def.getNewSite());
				result = (iKey == null) ? new InstanceKey[0] : new InstanceKey[] { iKey };
		}

		if (ensureIndexes) {
			for (int i = 0; i < result.length; i++) {
				system.findOrCreateIndexForInstanceKey(result[i]);
			}
		}

		return result;
	}

	public boolean isConstantRef(SymbolTable symbolTable, int valueNumber) {//bz: db: protected to public
		if (valueNumber == -1) {
			return false;
		}
		if (symbolTable.isConstant(valueNumber)) {
			Object v = symbolTable.getConstantValue(valueNumber);
			return (!(v instanceof Number));
		}
		return false;
	}

	/**
	 * @author sfink
	 *
	 *         A warning for when we fail to resolve the type for a checkcast
	 */
	private static class CheckcastFailure extends Warning {

		final TypeReference type;

		CheckcastFailure(TypeReference type) {
			super(Warning.SEVERE);
			this.type = type;
		}

		@Override
		public String getMsg() {
			return getClass().toString() + " : " + type;
		}

		public static CheckcastFailure create(TypeReference type) {
			return new CheckcastFailure(type);
		}
	}

	/**
	 * @author sfink
	 *
	 *         A warning for when we fail to resolve the type for a field
	 */
	protected static class FieldResolutionFailure extends Warning {

		final FieldReference field;

		FieldResolutionFailure(FieldReference field) {
			super(Warning.SEVERE);
			this.field = field;
		}

		@Override
		public String getMsg() {
			return getClass().toString() + " : " + field;
		}

		public static FieldResolutionFailure create(FieldReference field) {
			return new FieldResolutionFailure(field);
		}
	}

	/*
	 * @see com.ibm.wala.ipa.callgraph.propagation.HeapModel#iteratePointerKeys()
	 */
	@Override
	public Iterator<PointerKey> iteratePointerKeys() {
		return system.iteratePointerKeys();
	}

	public static Set<IClass> getCaughtExceptionTypes(SSAGetCaughtExceptionInstruction instruction, IRView ir) {
		if (ir == null) {
			throw new IllegalArgumentException("ir is null");
		}
		if (instruction == null) {
			throw new IllegalArgumentException("instruction is null");
		}
		Iterator<TypeReference> exceptionTypes = ((ExceptionHandlerBasicBlock) ir.getControlFlowGraph().getNode(
				instruction.getBasicBlockNumber())).getCaughtExceptionTypes();
		HashSet<IClass> types = HashSetFactory.make(10);
		for (; exceptionTypes.hasNext();) {
			IClass c = ir.getMethod().getClassHierarchy().lookupClass(exceptionTypes.next());
			if (c != null) {
				types.add(c);
			}
		}
		return types;
	}

	@Override
	public InstanceKey getInstanceKeyForPEI(CGNode node, ProgramCounter instr, TypeReference type) {
		return getInstanceKeyForPEI(node, instr, type, instanceKeyFactory);
	}
	
	@Override//bz ... 
	public <T> InstanceKey getInstanceKeyForConstant(TypeReference type, T S) {
		if (instanceKeyFactory == null) {
			throw new IllegalArgumentException("ikFactory is null");
		}
		return instanceKeyFactory.getInstanceKeyForConstant(type, S);
	}


	/*
	 * @see com.ibm.wala.ipa.callgraph.propagation.PropagationCallGraphBuilder#makeSolver()
	 */
	@Override
	protected IPointsToSolver makeSolver() {
		return new IPAStandardSolver(system, this);
	}


	/**
	 * my simple implementation: only from v = new C, <init> to v.invoke()
	 * @param caller
	 * @param node
	 * @return 
	 */
	private HashMap<SSAInstruction, BasicBlock> doBackwardSlicing(CGNode caller, CGNode node) {
		HashMap<SSAInstruction, BasicBlock> revisit = new HashMap<>();
		Iterator<CallSiteReference> iter = callGraph.getPossibleSites(caller, node);
		while (iter.hasNext()) {
			CallSiteReference site = iter.next();
			SSAAbstractInvokeInstruction[] invokes = caller.getIR().getCalls(site);
			for (int i = 0; i < invokes.length; i++) {
				SSAAbstractInvokeInstruction invoke = invokes[i];
				BasicBlock bb = (BasicBlock) caller.getIR().getBasicBlockForInstruction(invoke);
				revisit.put(invoke, bb);
//				int base = invoke.getReceiver();//TODO:bz: this may need more iteration (for annotated constructor)
//				SSAInstruction def = caller.getDU().getDef(base);
//				bb = (BasicBlock) caller.getIR().getBasicBlockForInstruction(def);
//				revisit.put(def, bb);
			}
		}
		return revisit;
	}
	
	
	
	
	
	
	
	
	

//////////////////////////////////// incremental (API) stuffs ///////////////////////////////////////////
	
	/**
	 * used by test/evaluation
	 * @param targetNode
	 * @param delInsts
	 * @param addInsts
	 * @throws CancelException
	 */
	public void updatePointsToAnalysis(CGNode targetNode, HashSet<SSAInstruction> delInsts,
			HashSet<SSAInstruction> addInsts) throws CancelException {
		//true -> Indicate we are performing incremental analysis
		setChange(true);
		IR ir = targetNode.getIR();
		DefUse du = new DefUse(ir);
		ConstraintVisitor v = this.makeVisitor(targetNode);
		v.setIR(ir);
		v.setDefUse(du);
		ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg = ir.getControlFlowGraph();

		//true -> Perform SSAInstruction Deletion
		this.setDelete(true);
		for (SSAInstruction delInst : delInsts) {
			ISSABasicBlock basicBlock = cfg.getBlockForInstruction(delInst.iindex);
			v.setBasicBlock(basicBlock);
			system.setFirstDel(true);
			delInst.visit(v);
			system.setFirstDel(false);
			do{
				system.solveDel(null);
			}while(!system.emptyWorkList());
			system.clearChanges();
		}

		//false -> Perform SSAInstruction Addition
		this.setDelete(false);
		for (SSAInstruction addInst : addInsts) {
			ISSABasicBlock basicBlock = cfg.getBlockForInstruction(addInst.iindex);
			v.setBasicBlock(basicBlock);
			addInst.visit(v);
			do{
				system.solveAdd(null);
				addConstraintsFromNewNodes(null);
			} while (!system.emptyWorkList());
			system.clearChanges();
		}

	}

	
	
	/**
	 * used by plugin: only annotation
	 * CURRENTLY only annotated method, no annotated constructor
	 * @param summary
	 * @throws CancelException 
	 */
	public IRChangedSummary updatePointerAnalysisAnnotation(IRChangedSummary summary) throws CancelException {
		HashMap<CGNode, IRDiff> map = summary.getChangedAnnotationCGNodes();
		setChange(true);
		for (CGNode node : map.keySet()) { 
			//node = cgnode_old
			System.out.println("ANNOTATION UPDATING: " + node.toString());
			//locate callers and invoke stmts, since we need to rebuild the relation later
			HashMap<CGNode, HashMap<SSAInstruction, BasicBlock>> exisit_caller_invoke = new HashMap<>();
			Iterator<CGNode> iter = callGraph.getPredNodes(node);
			while (iter.hasNext()) {
				CGNode caller = iter.next();
				//we need backwards slicing to determine what constraints needs to be deleted and revisited later...
				HashMap<SSAInstruction, BasicBlock> revisit = doBackwardSlicing(caller, node);
				exisit_caller_invoke.put(caller, revisit);
			}
			//remove all constraints
			IRDiff irDiff = map.get(node);
			removeDeletedConstraintsForCGNode(node, irDiff, summary);
			
			//delete node from call graph
			callGraph.removeNodeAndEdges(node);
			
			//traverse existing callers for new invokes
			for (CGNode caller : exisit_caller_invoke.keySet()) {
				//do addition
				IR ir_caller = caller.getIR();
				DefUse du_caller = new DefUse(ir_caller);
				ConstraintVisitor v_caller = this.makeVisitor(caller); 
				v_caller.setIR(ir_caller);
				v_caller.setDefUse(du_caller);
				
				HashMap<SSAInstruction, BasicBlock> revisit = exisit_caller_invoke.get(caller);
				for (SSAInstruction add : revisit.keySet()) {
					BasicBlock bb = revisit.get(add);
					v_caller.setBasicBlock(bb);
					
					add.visit(v_caller);
					do{
						system.solveAdd(null);
						addConstraintsFromNewNodes(null);
					} while (!system.emptyWorkList());
				}
			}
			irDiff.updateCallerChangesByAnnotation(exisit_caller_invoke);//record this, will use to update shb
			summary.updateAddedChangedV(IPAAbstractFixedPointSolver.changes);
			system.clearChanges();
		}

		//summarize changes
		summary.updateRemovedCGNodes(getAlreadyRemoved());
		summary.updateAddedCGNodes(getNewlyDiscoveredNodes());
		
		clearAlreadyRemoved();
		clearNewlyDiscoveredNodes();
		
		return summary;
	}
	
	
	/**
	 * used by plugin: no annotation; but others
	 * @param summary
	 * @throws CancelException 
	 */
	public IRChangedSummary updatePointerAnalysis(IRChangedSummary summary) throws CancelException {
		setChange(true);//double make sure 

		HashMap<CGNode, IRDiff> map = summary.getChangedCodeCGNodes();
		for (CGNode node : map.keySet()) { 
			//node = cgnode_old
			System.out.println(" - PTA UPDATING: " + node.toString());
			if (node.toString().contains("Lorg/apache/zookeeper/cli/ReconfigCommand, parse([Ljava/lang/String;)Lorg/apache/zookeeper/cli/CliCommand;")) {
				//java.util.zip.ZipException: ZipFile invalid LOC header (bad signature)
				System.out.println("skip this update due to bad zipfile exception. ");
				continue; 
			}
			
			/*
			 * deletion
			 */
			long update2_del_start = System.currentTimeMillis();
			IRDiff irDiff = map.get(node);
			((IPAExplicitNode) node).updateMethod(irDiff.getOldMethod(), irDiff.getOldIR());//make sure the node has the right old ir
			removeDeletedConstraintsForCGNode(node, irDiff, summary);
			
			//firstly handle cg changes: create cgnode_new, update cg edges, map key -> make sure the node has the right new ir
			callGraph.updateNode(node, irDiff.getOldMethod(), irDiff.getNewMethod(), irDiff.getNewIR());
			//NOW: node => cgnode_new
//			oldNode.clearAllTargets();//clear old targets for remove all the nodes??
			summary.total_del += (System.currentTimeMillis() - update2_del_start);
			
			/*
			 * addition
			 */
			long update2_add_start = System.currentTimeMillis();
			ArrayList<BasicBlock> add_bb = irDiff.getAddBasicBlocks();
			if(!add_bb.isEmpty()) {
				//do addition
				IR ir_new = irDiff.getNewIR();
				DefUse du_new = new DefUse(ir_new);
				ConstraintVisitor v_new = this.makeVisitor(node);//node = cgnode_new
				v_new.setIR(ir_new);
				v_new.setDefUse(du_new);
				
				//Perform SSAInstruction Addition
				for (BasicBlock bb : add_bb) {
					v_new.setBasicBlock(bb);
					
					for (Iterator<SSAInstruction> it = bb.iterator(); it.hasNext();) {
						SSAInstruction add = it.next();
						if (add != null) {
							long update2_cur = System.currentTimeMillis();

							add.visit(v_new);
							
							do{
								system.solveAdd(null);
								addConstraintsFromNewNodes(null);
							} while (!system.emptyWorkList());
							
							// update time
							long update2_add_worst = System.currentTimeMillis() - update2_cur;
							if(summary.worst_add < update2_add_worst) {
								summary.worst_add = update2_add_worst;
								summary.worstAddInstruction = add;
							}
						}
					}
				}
			}
			summary.updateAddedChangedV(IPAAbstractFixedPointSolver.changes);
			system.clearChanges();
			
			summary.total_add += (System.currentTimeMillis() - update2_add_start);
		}

		//summarize changes
		summary.updateRemovedCGNodes(getAlreadyRemoved());
		summary.updateAddedCGNodes(getNewlyDiscoveredNodes());
		
		clearAlreadyRemoved();
		clearNewlyDiscoveredNodes();
		
//		system.setChange(false);
		return summary;
	}
	
	/**
	 * remove points-to constraints/cg edges in irDiff.deleted_bb
	 * @param node
	 * @param irDiff
	 * @param summary
	 * @throws CancelException
	 */
	private void removeDeletedConstraintsForCGNode(CGNode node, IRDiff irDiff, IRChangedSummary summary) throws CancelException {
		ArrayList<BasicBlock> delete_bb = irDiff.getDeleteBasicBlocks();
		if(!delete_bb.isEmpty()) {
			//do deletion
			IR ir_old = irDiff.getOldIR();
			DefUse du_old = new DefUse(ir_old);
			ConstraintVisitor v_old = this.makeVisitor(node);//node = cgnode_new ?? why??
			v_old.setIR(ir_old);
			v_old.setDefUse(du_old);
			
			BasicBlock first_bb = delete_bb.get(0);
			SSAInstruction first_stmt = first_bb.getAllInstructions().get(0);
			int start_id = first_stmt.iindex;//1st changed statement
			
			//Perform SSAInstruction Deletion
			this.setDelete(true);
			//we do this in the reversed order, since later constraints depend on previous ones, we cannot firsly remove their pts...
			List<BasicBlock> reverseOrder = new ArrayList<BasicBlock>(delete_bb);
			Collections.reverse(reverseOrder);
			for(BasicBlock bb: reverseOrder){
//			for(BasicBlock bb: delete_bb) {
				v_old.setBasicBlock(bb);
				for (Iterator<SSAInstruction> it = bb.iterator(); it.hasNext();) {
					SSAInstruction delete = it.next();
					if (delete != null) {
						long update2_cur = System.currentTimeMillis();

						system.setFirstDel(true);
						delete.visit(v_old);
						system.setFirstDel(false);

						do{
							system.solveDel(null);
							if(system.kcfa || system.kobj) {
								clearWillRemoveNodes();
								continue; //should not have will remove here
							}
							delConstraintsFromWillRemoveNodes(null);
						}while(!system.emptyWorkList());
						
						// update time
						long update2_del_worst = System.currentTimeMillis() - update2_cur;
						if(summary.worst_del < update2_del_worst) {
							summary.worst_del = update2_del_worst;
							summary.worstDelInstruction = delete;
						}
					}
				}
			}
			this.setDelete(false);
			
			//pointers might be exception pointers that we did not handle and will be used later ..... 
			delNodePassthruExceptionConstraints(node, ir_old, du_old, start_id);
			
			//remove all the def variable <-> pts relations from pointsToMap
			removeAllFromPointsToMap(node, delete_bb);
		}
		summary.updateDeletedChangedV(IPAAbstractFixedPointSolver.changes);
		system.clearChanges();
	}
	
	/**
	 * used by plugin: only has Sync Modifier Changes
	 * @param summary
	 * @return
	 */
	public IRChangedSummary updateCallGraph(IRChangedSummary summary) {
		HashMap<CGNode, IRDiff> map = summary.getChangedSyncCGNodes();
		setChange(true);
		
		long update2_del_start = System.currentTimeMillis();

		for (CGNode node : map.keySet()) { 
			//node = cgnode_old
			System.out.println(" - CG UPDATING: " + node.toString());
			
			IRDiff irDiff = map.get(node);
			// handle cg changes: create cgnode_new, update cg edges, map key
			callGraph.updateNode(node, irDiff.getOldMethod(), irDiff.getNewMethod(), irDiff.getNewIR());
			//NOW: node => cgnode_new
		}
		
		summary.total_del += (System.currentTimeMillis() - update2_del_start);
		return summary;
	}
	
	
	//TODO: bz: to handle other pointer keys... 
	private void removeAllFromPointsToMap(CGNode node, ArrayList<BasicBlock> delete_bb) {
		for (BasicBlock bb : delete_bb) {
			for (Iterator<SSAInstruction> it = bb.iterator(); it.hasNext();) {
				SSAInstruction s = it.next();
				if(s.hasDef()) {
					int pc = s.getDef();
					PointerKey pKey = getPointerKeyForLocal(node, pc);
					getSystem().getPointsToMap().remove(pKey);
				}
			}
		}
	}




//////////////////////////// all incremental tests ////////////////////////////////////////////////////////////////////////////////////
	/*
	 *bz: to compute the average time of incremental analysis
	 */
	public static long total_add = 0;
	public static long total_del = 0;
	public static int total_inst = 0;
	
	public static long worst_add = 0;
	public static long worst_del = 0;
	
	public static int total_inst_add = 0;
	public static int total_inst_del = 0;
	
	public static void clearPerformanceData() {
		total_add = 0;
		total_del = 0;
		total_inst = 0;
		
		worst_add = 0;
		worst_del = 0;
		
		total_inst_add = 0;
		total_inst_del = 0;
	}
	
	private static boolean TEST_DEBUG = false;
	
	/**
	 * bz: for incremental pta check
	 * @param var_pts_map
	 * @param measurePerformance
	 * @param node
	 * @return
	 */
	public boolean testChange(CGNode node, HashMap<PointerKey, MutableIntSet> var_pts_map, boolean measurePerformance) {
		boolean correct = true;
		system.setChange(true);
		IR ir = node.getIR();
		if(ir==null)
			return correct;

		DefUse du = new DefUse(ir);
		ConstraintVisitor v = ((IPASSAPropagationCallGraphBuilder)this).makeVisitor(node);
		v.setIR(ir);
		v.setDefUse(du);

		ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg = ir.getControlFlowGraph();
		SSAInstruction[] insts = ir.getInstructions();
		int size = insts.length;

		for(int i=0; i<size; i++){
			SSAInstruction inst = insts[i];

			if(inst==null)
				continue;//skip null

//			if(!inst.toString().contains(
//					"invokestatic < Application, Lorg/sunflow/system/UI, printInfo(Lorg/sunflow/system/UI$Module;Ljava/lang/String;[Ljava/lang/Object;)V > 3,4,6 @9 exception:7"
//					))
//				continue;
			
			total_inst++;
			ISSABasicBlock bb = cfg.getBlockForInstruction(inst.iindex);
			//delete
			try{
				System.out.println("... Deleting SSAInstruction:      "+ inst.toString());
				this.setDelete(true);
				system.setFirstDel(true);
				v.setBasicBlock(bb);

				long start_delete = System.currentTimeMillis();
				inst.visit(v);
				system.setFirstDel(false);
				do{
					system.solveDel(null);
				}while(!system.emptyWorkList());

				setDelete(false);
				long delete_time = System.currentTimeMillis() - start_delete;
				HashSet<IVariable> temp = new HashSet<>();
				temp.addAll(IPAAbstractFixedPointSolver.changes);
				system.clearChanges();

				//add
				System.out.println("... Adding SSAInstruction:      "+ inst.toString());
				long start_add = System.currentTimeMillis();
				inst.visit(v);
				do{
					system.solveAdd(null);
					addConstraintsFromNewNodes(null);
				} while (!system.emptyWorkList());
				system.clearChanges();

				long add_time = System.currentTimeMillis() - start_add;

				boolean nochange = true;
				Iterator<IVariable> it = temp.iterator();
				while(it.hasNext()){
					IPAPointsToSetVariable var = (IPAPointsToSetVariable) it.next();
					if(var != null){
						MutableIntSet update = var.getValue();
						PointerKey key = var.getPointerKey();
						MutableIntSet origin = var_pts_map.get(key);
						if(update != null && origin != null){
							if(inst instanceof SSAInvokeInstruction){//new instance created, different id, only test the size of pts
								if(update.size() != origin.size()){
									nochange = false;
									correct = false;
								}
							}else{
								if(!update.sameValue(origin)){
									nochange = false;
									correct = false;
								}
							}
						}else if ((update == null && origin != null)){//(update != null && origin == null)
							nochange = false;
							correct = false;
						}
					}
				}


				if(nochange){
					System.out.println("...... points-to sets are the same before deleting inst and after adding back inst. ");
				}else{
					Assert.assertTrue("Points-to sets are different after changing stmt: " + inst.toString(), correct);
				}

				if(measurePerformance){
					System.out.println("------> use " + delete_time + "ms to delete the inst, use " + add_time +"ms to add the inst back.");
					total_del = total_del + delete_time;
					total_add = total_add + add_time;
					if(worst_add < add_time) {
						worst_add = add_time;
					}
					if(worst_del < delete_time) {
						worst_del = delete_time;
					}
				}

				System.out.println();

			}catch(Exception e)
			{
				e.printStackTrace();
			}
		}
		return correct;
	}



	/**
	 * multi side effect edge changes in parallel
	 * @param node
	 * @param var_pts_map
	 * @param measurePerformance
	 * @return
	 */
	public boolean testChange_multiSideEffect(CGNode node, HashMap<PointerKey, MutableIntSet> var_pts_map,
			boolean measurePerformance) {
		boolean correct = true;
		system.setChange(true);
		IR ir = node.getIR();
		if(ir == null)
			return correct;

		DefUse du = new DefUse(ir);
		ConstraintVisitor v = ((IPASSAPropagationCallGraphBuilder)this).makeVisitor(node);
		v.setIR(ir);
		v.setDefUse(du);

		ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg = ir.getControlFlowGraph();
		SSAInstruction[] insts = ir.getInstructions();
		int size = insts.length;

		for(int i=0; i<size; i++){
			SSAInstruction inst = insts[i];

			if(inst==null)
				continue;//skip null

			total_inst++;
			ISSABasicBlock bb = cfg.getBlockForInstruction(inst.iindex);
			//delete
			try{
				System.out.println("... Deleting SSAInstruction:      "+ inst.toString());
				this.setDelete(true);
				system.setFirstDel(true);
				v.setBasicBlock(bb);

				long start_delete = System.currentTimeMillis();
				inst.visit(v);
				//				do{
				//					system.solveDel(null);
				//				}while(!system.emptyWorkList());

				setDelete(false);
				long delete_time = System.currentTimeMillis() - start_delete;
				HashSet<IVariable> temp = new HashSet<>();
				temp.addAll(IPAAbstractFixedPointSolver.changes);
				system.clearChanges();

				//add
				System.out.println("... Adding SSAInstruction:      "+ inst.toString());
				long start_add = System.currentTimeMillis();
				inst.visit(v);
				do{
					system.solveAdd(null);
					addConstraintsFromNewNodes(null);
				} while (!system.emptyWorkList());
				system.clearChanges();

				long add_time = System.currentTimeMillis() - start_add;

				boolean nochange = true;
				Iterator<IVariable> it = temp.iterator();
				while(it.hasNext()){
					IPAPointsToSetVariable var = (IPAPointsToSetVariable) it.next();
					if(var != null){
						MutableIntSet update = var.getValue();
						PointerKey key = var.getPointerKey();
						MutableIntSet origin = var_pts_map.get(key);
						if(update != null && origin != null){
							if(inst instanceof SSAInvokeInstruction){//new instance created, different id, only test the size of pts
								if(update.size() != origin.size()){
									nochange = false;
									correct = false;
								}
							}else{
								if(!update.sameValue(origin)){
									nochange = false;
									correct = false;
								}
							}
						}else if ((update == null && origin != null)){//(update != null && origin == null)
							nochange = false;
							correct = false;
						}
					}
				}


				if(nochange){
					System.out.println("...... points-to sets are the same before deleting inst and after adding back inst. ");
				}else{
					throw new RuntimeException("****** points-to sets are different before deleting inst and after adding back inst. ");
				}

				if(measurePerformance){
					System.out.println("------> use " + delete_time + "ms to delete the inst, use " + add_time +"ms to add the inst back.");
					total_del = total_del + delete_time;
					total_add = total_add + add_time;
				}

				system.clearChanges();

				System.out.println();

			}catch(Exception e)
			{
				e.printStackTrace();
			}
		}
		return correct;
	}


	public int testChange_delMultiMethods(CGNode node, SSAInstruction inst) {
		system.setChange(true);
		IR ir = node.getIR();
		DefUse du = new DefUse(ir);
		ConstraintVisitor v = ((IPASSAPropagationCallGraphBuilder)this).makeVisitor(node);
		v.setIR(ir);
		v.setDefUse(du);

		ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg = ir.getControlFlowGraph();
		total_inst++;
		ISSABasicBlock bb = cfg.getBlockForInstruction(inst.iindex);
		//delete
		try{
			this.setDelete(true);
			system.setFirstDel(true);
			v.setBasicBlock(bb);

			inst.visit(v);
			do{
				system.solveDel(null);
			}while(!system.emptyWorkList());

			setDelete(false);
			system.clearChanges();
		}catch(Exception e){
			e.printStackTrace();
		}
		return 0;
	}

	public int testChange_addMultiMethods(CGNode node, SSAInstruction inst) {
		IR ir = node.getIR();
		DefUse du = new DefUse(ir);
		ConstraintVisitor v = ((IPASSAPropagationCallGraphBuilder)this).makeVisitor(node);
		v.setIR(ir);
		v.setDefUse(du);

		ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg = ir.getControlFlowGraph();
		ISSABasicBlock bb = cfg.getBlockForInstruction(inst.iindex);
		v.setBasicBlock(bb);
		//add
		try{
			inst.visit(v);
			do{
				system.solveAdd(null);
				addConstraintsFromNewNodes(null);
			} while (!system.emptyWorkList());
			system.clearChanges();
		}catch(Exception e){
			e.printStackTrace();
		}

		system.clearChanges();
		return 0;
	}
	
	

	/**
	 * see if the incremental pts is correct 
	 * @param temp
	 * @param var_pts_map
	 * @return
	 */
	public boolean check(HashSet<IVariable> temp, HashMap<PointerKey, MutableIntSet> var_pts_map){
		boolean correct = true;
		boolean nochange = true;
		Iterator<IVariable> it = temp.iterator();
		while(it.hasNext()){
			IPAPointsToSetVariable var = (IPAPointsToSetVariable) it.next();
			if(var != null){
				MutableIntSet update = var.getValue();
				PointerKey key = var.getPointerKey();
				MutableIntSet origin = var_pts_map.get(key);
				if(update != null && origin != null){
					//					if(inst instanceof SSAInvokeInstruction){//new instance created, different id, only test the size of pts
					//						if(update.size() != origin.size()){
					//							nochange = false;
					//							correct = false;
					//						}
					//					}else{
					if(!update.sameValue(origin)){
						nochange = false;
						correct = false;
					}
					//					}
				}else if ((update == null && origin != null)){//(update != null && origin == null)
					nochange = false;
					correct = false;
				}
			}
		}
		if(nochange){
			System.out.println("...... both points-to sets are the same before deleting inst and after adding back inst. ");
		}else{
			throw new RuntimeException("****** some points-to sets are different before deleting inst and after adding back inst. ");
		}

		return correct;
	}



	/**
	 * test git version commit: context-insensitive now
	 * @param node
	 * @param addedmap
	 * @param deletedmap
	 * @param ir_old
	 * @param ir
	 * @param performance
	 */
	public void testGitVersionChange(CGNode node, HashMap<ISSABasicBlock, ArrayList<SSAInstruction>> addedmap,
			HashMap<ISSABasicBlock, ArrayList<SSAInstruction>> deletedmap, IR ir_old, IR ir, boolean performance) {
		if(ir_old != null) {
			DefUse du_old = new DefUse(ir_old);
			ConstraintVisitor v_old = this.makeVisitor(node);
			v_old.setIR(ir_old);
			v_old.setDefUse(du_old);

			try {
				//Perform SSAInstruction Deletion
				system.setChange(true);
				this.setDelete(true);
				ISSABasicBlock dorder[] = new ISSABasicBlock[2048]; 
				for(ISSABasicBlock bb: deletedmap.keySet()){
					dorder[bb.getNumber()] = bb;
				}

				for(ISSABasicBlock bb: dorder){
					if(bb == null)
						continue;

					v_old.setBasicBlock(bb);
					ArrayList<SSAInstruction> deletes = deletedmap.get(bb);
					for (SSAInstruction diff : deletes) {
						total_inst_del++;
						if(TEST_DEBUG)
							System.out.println("... Deleting Inst:      "+ diff.toString());
						long start_delete = System.currentTimeMillis();
						system.setFirstDel(true);
						diff.visit(v_old);
						system.setFirstDel(false);
						do{
							system.solveDel(null);
						}while(!system.emptyWorkList());
						long delete_time = System.currentTimeMillis() - start_delete;

						if(performance){
							if(TEST_DEBUG)
								System.out.println("------> use " + delete_time + "ms to delete the inst");
							total_del = total_del + delete_time;
							if(worst_del < delete_time) {
								worst_del = delete_time;
							}
						}
					}
				}
				this.setDelete(false);
			}catch(Exception e) {
				e.printStackTrace();
			}
		}

		if(ir != null) {
			DefUse du = new DefUse(ir);
			ConstraintVisitor v = this.makeVisitor(node);
			v.setIR(ir);
			v.setDefUse(du);

			try {
				//Perform SSAInstruction Addition
				ISSABasicBlock aorder[] = new ISSABasicBlock[2048]; 
				for(ISSABasicBlock bb: addedmap.keySet()){
					aorder[bb.getNumber()] = bb;
				}
				for(ISSABasicBlock bb: aorder){
					if(bb == null)
						continue;

					v.setBasicBlock(bb);
					ArrayList<SSAInstruction> adds = addedmap.get(bb);
					for (SSAInstruction diff : adds) {
						total_inst_add++;
						if(TEST_DEBUG)
							System.out.println("... Adding Inst:      "+ diff.toString());
						long start_add = System.currentTimeMillis();
						diff.visit(v);
						do{
							system.solveAdd(null);
						}while(!system.emptyWorkList());
						long add_time = System.currentTimeMillis() - start_add;

						if(performance){
							if(TEST_DEBUG)
								System.out.println("------> use " + add_time +"ms to add the inst back.");
							total_add = total_add + add_time;
							if(worst_add < add_time) {
								worst_add = add_time;
							}
						}
					}
				}
			}catch(Exception e) {
				e.printStackTrace();
			}
		}

	}




}

