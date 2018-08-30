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
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

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
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.ContextKey;
import com.ibm.wala.ipa.callgraph.ContextSelector;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.callgraph.impl.AbstractRootMethod;
import com.ibm.wala.ipa.callgraph.impl.DefaultEntrypoint;
import com.ibm.wala.ipa.callgraph.impl.FakeRootMethod;
import com.ibm.wala.ipa.callgraph.propagation.ConcreteTypeKey;
import com.ibm.wala.ipa.callgraph.propagation.FilteredPointerKey;
import com.ibm.wala.ipa.callgraph.propagation.IPointerOperator;
import com.ibm.wala.ipa.callgraph.propagation.IPointsToSolver;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKeyFactory;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.callgraph.propagation.PointsToSetVariable;
import com.ibm.wala.ipa.callgraph.propagation.SSAContextInterpreter;
import com.ibm.wala.ipa.callgraph.propagation.ZeroLengthArrayInNode;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.shrikeBT.ConditionalBranchInstruction;
import com.ibm.wala.shrikeBT.IInvokeInstruction;
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
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAGetCaughtExceptionInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstanceofInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSALoadMetadataInstruction;
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
import com.ibm.wala.util.intset.IntSetUtil;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.ref.ReferenceCleanser;
import com.ibm.wala.util.warnings.Warning;
import com.ibm.wala.util.warnings.Warnings;

import edu.tamu.wala.increpta.callgraph.impl.IPACGNode;
import edu.tamu.wala.increpta.callgraph.impl.IPAExplicitCallGraph;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPASSAPropagationCallGraphBuilder.ConstraintVisitor;
import edu.tamu.wala.increpta.operators.IPAAbstractOperator;
import edu.tamu.wala.increpta.pointerkey.IPAFilteredPointerKey;
import edu.tamu.wala.increpta.pointerkey.IPAPointerKeyFactory;

public abstract class IPASSAPropagationCallGraphBuilder extends IPAPropagationCallGraphBuilder implements IPAHeapModel {

	private final static boolean DEBUG = false;

	private final static boolean DEBUG_MULTINEWARRAY = DEBUG | false;

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
	 * An optimization: if we can locally determine that a particular pointer p has exactly one use, then we don't actually create the
	 * points-to-set for p, but instead short-circuit by propagating the final solution to the unique use.
	 *
	 * Doesn't play well with pre-transitive solver; turning off for now.
	 */
	protected final static boolean SHORT_CIRCUIT_SINGLE_USES = false;

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

	private final Set<IClass> finalizeVisited = HashSetFactory.make();

	public IProgressMonitor monitor;

	protected IPASSAPropagationCallGraphBuilder(IMethod abstractRootMethod, AnalysisOptions options, IAnalysisCacheView cache,
			IPAPointerKeyFactory pointerKeyFactory) {
	    super(abstractRootMethod, options, cache, pointerKeyFactory);
		// this.usePreTransitiveSolver = options.usePreTransitiveSolver();
	}

	public SSAContextInterpreter getCFAContextInterpreter() {
		return (SSAContextInterpreter) getContextInterpreter();
	}

	/**bz
	 * @param delete
	 */
	public static boolean isDelete= false;

	@Override
	public void setDelete(boolean delete){
		IPASSAPropagationCallGraphBuilder.isDelete = delete;
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
	 * Visit all instructions in a node, and add dataflow constraints induced by each statement in the SSA form.
	 * @throws CancelException
	 *
	 * @see com.ibm.wala.ipa.callgraph.propagation.PropagationCallGraphBuilder#addConstraintsFromNode(com.ibm.wala.ipa.callgraph.CGNode)
	 */
	@Override
	protected boolean addConstraintsFromNode(CGNode node, IProgressMonitor monitor) throws CancelException {
		this.monitor = monitor;
		if (haveAlreadyVisited(node)) {
			return false;
		} else {
			markAlreadyVisited(node);
		}
		return unconditionallyAddConstraintsFromNode(node, monitor);
	}

	@Override
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

		addNodeInstructionConstraints(node, monitor);

		addNodeValueConstraints(node, monitor);

		DefUse du = getCFAContextInterpreter().getDU(node);
		addNodePassthruExceptionConstraints(node, ir, du);
		// conservatively assume something changed
		return true;
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

		IRView ir = v.ir;
		for (Iterator<ISSABasicBlock> x = ir.getBlocks(); x.hasNext();) {
			BasicBlock b = (BasicBlock) x.next();
			addBlockInstructionConstraints(node, ir, b, v, monitor);
			if (wasChanged(node)) {
				return;
			}
		}
	}

	/**
	 * Hook for aubclasses to add pointer flow constraints based on values in a given node
	 * @throws CancelException
	 */
	@SuppressWarnings("unused")
	protected void addNodeValueConstraints(CGNode node, IProgressMonitor monitor) throws CancelException {

	}

	/**
	 * Add constraints for a particular basic block.
	 * @throws CancelException
	 */
	protected void addBlockInstructionConstraints(CGNode node, IRView ir, BasicBlock b,
			ConstraintVisitor v, IProgressMonitor monitor) throws CancelException {
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

		addPhiConstraints(node, ir.getControlFlowGraph(), b, v);
	}

	private void addPhiConstraints(CGNode node, ControlFlowGraph<SSAInstruction, ISSABasicBlock> controlFlowGraph, BasicBlock b,
			ConstraintVisitor v) {
		// visit each phi instruction in each successor block
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
	protected void addNodePassthruExceptionConstraints(CGNode node, IRView ir, DefUse du) {
		// add constraints relating to thrown exceptions that reach the exit block.
		List<ProgramCounter> peis = getIncomingPEIs(ir, ir.getExitBlock());
		PointerKey exception = getPointerKeyForExceptionalReturnValue(node);

		TypeReference throwableType = node.getMethod().getDeclaringClass().getClassLoader().getLanguage().getThrowableType();
		IClass c = node.getClassHierarchy().lookupClass(throwableType);
		addExceptionDefConstraints(ir, du, node, peis, exception, Collections.singleton(c));
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
						assert ik instanceof ConcreteTypeKey : "uh oh: need to implement getCaughtException constraints for instance " + ik;
						ConcreteTypeKey ck = (ConcreteTypeKey) ik;
						IClass klass = ck.getType();
						if (IPAPropagationCallGraphBuilder.catches(catchClasses, klass, cha)) {
							system.newConstraint(exceptionVar, getInstanceKeyForPEI(node, peiLoc, type, instanceKeyFactory));
						}
					}
				}
			}
		}
	}

	/**
	 * @return true iff there's a unique catch block which catches all exceptions thrown by a certain call site.
	 */
	protected static boolean hasUniqueCatchBlock(SSAAbstractInvokeInstruction call, IRView ir) {
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
			this.params = IntSetUtil.toArray(getRelevantParameters(caller, site));
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
					IntSet s = getParamObjects(pi, rhsi);
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

	/**
	 * A visitor that generates constraints based on statements in SSA form.
	 */
	public static class ConstraintVisitor extends SSAInstruction.Visitor {

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
		private final IPAExplicitCallGraph callGraph;

		/**
		 * The governing IR
		 */
		protected IRView ir;//bz

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

			SSAContextInterpreter interp = builder.getCFAContextInterpreter();
			this.ir = interp.getIRView(node);
			this.symbolTable = this.ir.getSymbolTable();

			this.du = interp.getDU(node);

			assert symbolTable != null;
		}

		/**bz
		 * @return
		 */
		public void setIR(IR ir)
		{
			this.ir = ir;
			this.symbolTable = this.ir.getSymbolTable();
			assert symbolTable != null;

		}

		/**bz
		 * @return
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

		public InstanceKey getInstanceKeyForAllocation(NewSiteReference allocation) {
			return getBuilder().getInstanceKeyForAllocation(node, allocation);
		}

		public InstanceKey getInstanceKeyForMultiNewArray(NewSiteReference allocation, int dim) {
			return getBuilder().getInstanceKeyForMultiNewArray(node, allocation, dim);
		}

		public <T> InstanceKey getInstanceKeyForConstant(T S) {
			TypeReference type = node.getMethod().getDeclaringClass().getClassLoader().getLanguage().getConstantType(S);
			return getBuilder().getInstanceKeyForConstant(type, S);
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
					assert !system.isUnified(result);
					assert !system.isUnified(arrayRefPtrKey);
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
					InstanceKey[] ik = getInvariantContents(value);
					for (int i = 0; i < ik.length; i++) {
						system.findOrCreateIndexForInstanceKey(ik[i]);
						assert !system.isUnified(arrayRefPtrKey);
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
				system.recordImplicitPointsToSet(result);
			} else {
				if (contentsAreInvariant(symbolTable, du, instruction.getVal())) {
					system.recordImplicitPointsToSet(value);
					InstanceKey[] ik = getInvariantContents(instruction.getVal());
					for(TypeReference t : instruction.getDeclaredResultTypes()) {
						IClass cls = getClassHierarchy().lookupClass(t);

						if (cls.isInterface()) {
							for (int i = 0; i < ik.length; i++) {
								system.findOrCreateIndexForInstanceKey(ik[i]);
								if (getClassHierarchy().implementsInterface(ik[i].getConcreteType(), cls)) {
									system.newConstraint(result, ik[i]);
								}
							}
						} else {
							for (int i = 0; i < ik.length; i++) {
								system.findOrCreateIndexForInstanceKey(ik[i]);
								if (getClassHierarchy().isSubclassOf(ik[i].getConcreteType(), cls)) {
									system.newConstraint(result, ik[i]);
								}
							}
						}
					}
				} else {
					if (isRoot) {
						system.newConstraint(result, assignOperator, value);
					} else {
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
			if (field.getFieldType().isPrimitiveType()) {
				return;
			}

			if (hasNoInterestingUses(lval)) {
				if(!isDelete)
					system.recordImplicitPointsToSet(def);
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
			if (field.getFieldType().isPrimitiveType()) {
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
			assert !f.getFieldTypeReference().isPrimitiveType();
			PointerKey refKey = getPointerKeyForLocal(ref);
			PointerKey rvalKey = getPointerKeyForLocal(rval);
			// if (!supportFullPointerFlowGraph &&
			// contentsAreInvariant(rval)) {
			if (contentsAreInvariant(symbolTable, du, rval)) {
				if(!isDelete)
					system.recordImplicitPointsToSet(rvalKey);
				InstanceKey[] ik = getInvariantContents(rval);
				if (contentsAreInvariant(symbolTable, du, ref)) {
					if(!isDelete)
						system.recordImplicitPointsToSet(refKey);
					InstanceKey[] refk = getInvariantContents(ref);
					if(isDelete){
						MutableIntSet delset = IntSetUtil.getDefaultIntSetFactory().make();
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
			if(isDelete)
				doDelInvokeInternal(instruction, new DefaultInvariantComputer());
			else
				visitInvokeInternal(instruction, new DefaultInvariantComputer());
		}

		/**bz
		 * @param instruction
		 * @param invs
		 */
		protected void doDelInvokeInternal(final SSAAbstractInvokeInstruction instruction, InvariantComputer invs) {
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
				params = IntSetUtil.makeMutableCopy(params);
				((MutableIntSet)params).add(0);
			}

			if (invariantParameters != null) {
				for(int i = 0; i < invariantParameters.length; i++) {
					if (invariantParameters[i] != null) {
						params = IntSetUtil.makeMutableCopy(params);
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

					DispatchOperator dispatchOperator = getBuilder().new DispatchOperator(instruction, node,
							invariantParameters, uniqueCatch, params);
					system.delSideEffect(dispatchOperator, pks.toArray(new PointerKey[pks.size()]));
				}
			}
		}

		protected void visitInvokeInternal(final SSAAbstractInvokeInstruction instruction, InvariantComputer invs) {
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
				params = IntSetUtil.makeMutableCopy(params);
				((MutableIntSet)params).add(0);
			}

			if (invariantParameters != null) {
				for(int i = 0; i < invariantParameters.length; i++) {
					if (invariantParameters[i] != null) {
						params = IntSetUtil.makeMutableCopy(params);
						((MutableIntSet)params).remove(i);
					}
				}
			}
			if (params.isEmpty()) {
				for (CGNode n : getBuilder().getTargetsForCall(node, instruction, invariantParameters)) {
					getBuilder().processResolvedCall(node, instruction, n, invariantParameters, uniqueCatch);
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

					DispatchOperator dispatchOperator = getBuilder().new DispatchOperator(instruction, node,
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
		public void visitPhi(SSAPhiInstruction instruction) {
			if (ir.getMethod() instanceof AbstractRootMethod) {
				PointerKey dst = getPointerKeyForLocal(instruction.getDef());
				if (hasNoInterestingUses(instruction.getDef())) {
					system.recordImplicitPointsToSet(dst);
				} else {
					for (int i = 0; i < instruction.getNumberOfUses(); i++) {
						PointerKey use = getPointerKeyForLocal(instruction.getUse(i));
						if (contentsAreInvariant(symbolTable, du, instruction.getUse(i))) {
							system.recordImplicitPointsToSet(use);
							InstanceKey[] ik = getInvariantContents(instruction.getUse(i));
							for (int j = 0; j < ik.length; j++) {
								system.newConstraint(dst, ik[j]);
							}
						} else {
							system.newConstraint(dst, assignOperator, use);
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
			int dir;

			if (hasNoInterestingUses(instruction.getDef())) {
				PointerKey dst = getPointerKeyForLocal(instruction.getDef());
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
								addPiAssignment(dst, val);
							} else {
								PointerKey dst = getFilteredPointerKeyForLocal(instruction.getDef(), new IPAFilteredPointerKey.SingleClassFilter(cls));
								// if true, only allow objects assignable to cls.  otherwise, only allow objects
								// *not* assignable to cls
								boolean useFilter = (target == com.ibm.wala.cfg.Util.getTakenSuccessor(cfg, getBasicBlock()) && direction == 1)
										|| (target == com.ibm.wala.cfg.Util.getNotTakenSuccessor(cfg, getBasicBlock()) && direction == -1);
								PointerKey src = getPointerKeyForLocal(val);
								if (contentsAreInvariant(symbolTable, du, val)) {
									system.recordImplicitPointsToSet(src);
									InstanceKey[] ik = getInvariantContents(val);
									for (int j = 0; j < ik.length; j++) {
										boolean assignable = getClassHierarchy().isAssignableFrom(cls, ik[j].getConcreteType());
										if ((assignable && useFilter) || (!assignable && !useFilter)) {
											system.newConstraint(dst, ik[j]);
										}
									}
								} else {
									IPAFilterOperator op = useFilter ? getBuilder().filterOperator : getBuilder().inverseFilterOperator;
									system.newConstraint(dst, op, src);
								}
							}
						}
					} else if ((dir = nullConstantTest(cond, val)) != 0) {
						if ((target == com.ibm.wala.cfg.Util.getTakenSuccessor(cfg, getBasicBlock()) && dir == -1)
								|| (target == com.ibm.wala.cfg.Util.getNotTakenSuccessor(cfg, getBasicBlock()) && dir == 1)) {
							PointerKey dst = getPointerKeyForLocal(instruction.getDef());
							addPiAssignment(dst, val);
						}
					} else {
						PointerKey dst = getPointerKeyForLocal(instruction.getDef());
						addPiAssignment(dst, val);
					}
				} else {
					PointerKey dst = getPointerKeyForLocal(instruction.getDef());
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

		public ISSABasicBlock getBasicBlock() {
			return basicBlock;
		}

		/**
		 * The calling loop must call this in each iteration!
		 */
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
				system.newConstraint(def, iKey);
			} else {
				system.findOrCreateIndexForInstanceKey(iKey);
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
		caller.addTarget(instruction.getCallSite(), target);

		if (callGraph.getFakeRootNode().equals(caller)) {
			if (entrypointCallSites.contains(instruction.getCallSite())) {
				callGraph.registerEntrypoint(target);
			}
		}

		if (!haveAlreadyVisited(target)) {
			markDiscovered(target);
		}

		processCallingConstraints(caller, instruction, target, constParams, uniqueCatchKey);
	}

	/**bz
	 * delete call
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
		((IPACGNode)caller).delTarget(instruction.getCallSite(), target);//bz

		if (callGraph.getFakeRootNode().equals(caller)) {
			if (entrypointCallSites.contains(instruction.getCallSite())) {
				callGraph.deRegisterEntrypoint(target);
			}
		}

		processDelCallingConstraints(caller, instruction, target, constParams, uniqueCatchKey);

	}

	@SuppressWarnings("unused")
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

		// generate contraints from parameter.
		// we're a little sloppy for now ... we don't filter calls to
		// java.lang.Object.
		// TODO: we need much more precise filters than cones in order to handle
		// the various types of dispatch logic. We need a filter that expresses
		// "the set of types s.t. x.foo resolves to y.foo."
		for (int i = 0; i < instruction.getNumberOfPositionalParameters(); i++) {
			if (target.getMethod().getParameterType(i).isReferenceType()) {
				PointerKey formal = getTargetPointerKey(target, i);
				if (constParams != null && constParams[i] != null) {
					InstanceKey[] ik = constParams[i];
					for (int j = 0; j < ik.length; j++) {
						system.newConstraint(formal, ik[j]);
					}
				} else {
					if (instruction.getUse(i) < 0) {
						Assertions.UNREACHABLE("unexpected " + instruction + " in " + caller);
					}
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
			PointerKey result = getPointerKeyForLocal(caller, instruction.getDef());
			PointerKey ret = getPointerKeyForReturnValue(target);
			system.newConstraint(result, assignOperator, ret);
		}

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
		for (int i = 0; i < instruction.getNumberOfPositionalParameters(); i++) {
			if (target.getMethod().getParameterType(i).isReferenceType()) {

				PointerKey formal = getTargetPointerKey(target, i);
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
			system.delConstraint(result, assignOperator, ret);
		}
		// delete constraints from exception return value.
		//--- first: hasUniqueCatchBlock is true
		//--- uniqueCatch = getBuilder().getUniqueCatchKey(instruction, ir, node);
		//	    PointerKey e = getPointerKeyForLocal(caller, instruction.getException());
		//	    PointerKey er = getPointerKeyForExceptionalReturnValue(target);
		//	    if (SHORT_CIRCUIT_SINGLE_USES && uniqueCatchKey != null) {
		//	      // e has exactly one use. so, represent e implicitly
		//	      system.delConstraint(uniqueCatchKey, assignOperator, er);
		//	    } else {
		//	      system.delConstraint(e, assignOperator, er);
		//	    }
	}

	/**
	 * An operator to fire when we discover a potential new callee for a virtual or interface call site.
	 *
	 * This operator will create a new callee context and constraints if necessary.
	 */
	final class DispatchOperator extends IPAAbstractOperator<PointsToSetVariable> implements IPointerOperator {
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
		DispatchOperator(SSAAbstractInvokeInstruction call, CGNode node, InstanceKey[][] constParams,
				PointerKey uniqueCatch, IntSet dispatchIndices) {
			this.call = call;
			this.node = node;
			this.constParams = constParams;
			this.uniqueCatch = uniqueCatch;
			this.dispatchIndices = IntSetUtil.toArray(dispatchIndices);
			// we better always be interested in the receiver
			// assert this.dispatchIndices[0] == 0;
			previousPtrs = new MutableIntSet[dispatchIndices.size()];
			for(int i = 0; i < previousPtrs.length; i++) {
				previousPtrs[i] = IntSetUtil.getDefaultIntSetFactory().make();
			}
		}

		private byte cpa(final PointsToSetVariable[] rhs) {
			final MutableBoolean changed = new MutableBoolean();
			for(int rhsIndex = 0; rhsIndex < rhs.length; rhsIndex++) {
				final int y = rhsIndex;
				IntSet currentObjs = rhs[rhsIndex].getValue();
				if (currentObjs != null) {
					final IntSet oldObjs = previousPtrs[rhsIndex];
					currentObjs.foreachExcluding(oldObjs, new IntSetAction() {
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
											}
										}
									}) {

								{
									rec(0, 0);
								}

								@Override
								protected IntSet getParamObjects(int paramVn, int rhsi) {
									if (rhsi == y) {
										return IntSetUtil.make(new int[]{ x });
									} else {
										return previousPtrs[rhsi];
									}
								}
							};
						}
					});
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
		public byte evaluate(PointsToSetVariable lhs, final PointsToSetVariable[] rhs) {
			assert dispatchIndices.length >= rhs.length : "bad operator at " + call;

			return cpa(rhs);

			/*
	      // did evaluating the dispatch operation add a new possible target
	      // to the call site?
	      final MutableBoolean addedNewTarget = new MutableBoolean();

	      final MutableIntSet receiverVals;
	      if (constParams != null && constParams[0] != null) {
	        receiverVals = IntSetUtil.make();
	        for(InstanceKey ik : constParams[0]) {
	          receiverVals.add(system.getInstanceIndex(ik));
	        }
	      } else {
	        receiverVals = rhs[0].getValue();
	      }

	      if (receiverVals == null) {
	        // this constraint was put on the work list, probably by
	        // initialization,
	        // even though the right-hand-side is empty.
	        // TODO: be more careful about what goes on the worklist to
	        // avoid this.
	        if (DEBUG) {
	          System.err.println("EVAL dispatch with value null");
	        }
	        return NOT_CHANGED;

	      }
	      // we handle the parameter positions one by one, rather than enumerating
	      // the cartesian product of possibilities. this disallows
	      // context-sensitivity policies like true CPA, but is necessary for
	      // performance.
	      InstanceKey keys[] = new InstanceKey[constParams == null? dispatchIndices[dispatchIndices.length-1]+1: constParams.length];
	      // determine whether we're handling a new receiver; used later
	      // to check for redundancy
	      boolean newReceiver = !receiverVals.isSubset(previousPtrs[0]);
	      // keep separate rhsIndex, since it doesn't advance for constant
	      // parameters
	      int rhsIndex = (constParams != null && constParams[0] != null)? 0: 1;
	      // this flag is set to true if we ever call handleAllReceivers() in the
	      // loop below. we need to catch the case where we have a new receiver, but
	      // there are no other dispatch indices with new values
	      boolean propagatedReceivers = false;
	      // we start at index 1 since we need to handle the receiver specially; see
	      // below
	      for (int index = 1; index < dispatchIndices.length; index++) {
	        try {
	          MonitorUtil.throwExceptionIfCanceled(monitor);
	        } catch (CancelException e) {
	          throw new CancelRuntimeException(e);
	        }
	        int paramIndex = dispatchIndices[index];
	        assert keys[paramIndex] == null;
	        final MutableIntSet prevAtIndex = previousPtrs[index];
	        if (constParams != null && constParams[paramIndex] != null) {
	          // we have a constant parameter.  only need to propagate again if we've never done it before or if we have a new receiver
	          if (newReceiver || prevAtIndex.isEmpty()) {
	            for(int i = 0; i < constParams[paramIndex].length; i++) {
	              keys[paramIndex] = constParams[paramIndex][i];
	              handleAllReceivers(receiverVals,keys, addedNewTarget);
	              propagatedReceivers = true;
	              int ii = system.instanceKeys.getMappedIndex(constParams[paramIndex][i]);
	              prevAtIndex.add(ii);
	            }
	          }
	        } else { // non-constant parameter
	          PointsToSetVariable v = rhs[rhsIndex];
	          if (v.getValue() != null) {
	            IntIterator ptrs = v.getValue().intIterator();
	            while (ptrs.hasNext()) {
	              int ptr = ptrs.next();
	              if (newReceiver || !prevAtIndex.contains(ptr)) {
	                keys[paramIndex] = system.getInstanceKey(ptr);
	                handleAllReceivers(receiverVals,keys, addedNewTarget);
	                propagatedReceivers = true;
	                prevAtIndex.add(ptr);
	              }
	            }
	          }
	          rhsIndex++;
	        }
	        keys[paramIndex] = null;
	      }
	      if (newReceiver) {
	        if (!propagatedReceivers) {
	          // we have a new receiver value, and it wasn't propagated at all,
	          // so propagate it now
	          handleAllReceivers(receiverVals, keys, addedNewTarget);
	        }
	        // update receiver cache
	        previousPtrs[0].addAll(receiverVals);
	      }

	      byte sideEffectMask = addedNewTarget.b ? (byte) SIDE_EFFECT_MASK : 0;
	      return (byte) (NOT_CHANGED | sideEffectMask);
			 */
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

						//	            if (!haveAlreadyVisited(target)) {
						//	              if(DEBUG)
						//	                System.out.println("--- discover new node from handleAllReceivers: "+ target.toString());
						//	              markDiscovered(target);
						//	            }
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
		private byte cpaDel(final PointsToSetVariable[] rhs) {
			final MutableBoolean changed = new MutableBoolean();
			for(int rhsIndex = 0; rhsIndex < rhs.length; rhsIndex++) {
				final int y = rhsIndex;
				IntSet removeObjs = rhs[rhsIndex].getValue();
				if (removeObjs != null) {
					final IntSet oldObjs = previousPtrs[rhsIndex];
					removeObjs.foreach(new IntSetAction() {
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
											}
										}
									}) {

								{
									recDel(0, 0);
								}

								@Override
								protected IntSet getParamObjects(int paramVn, int rhsi) {
									if (rhsi == y) {
										return IntSetUtil.make(new int[]{ x });
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


		/**bz:
		 */
		@Override
		public byte evaluateDel(PointsToSetVariable lhs, final PointsToSetVariable[] rhs){
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
			if (o instanceof DispatchOperator) {
				DispatchOperator other = (DispatchOperator) o;
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
	}

	protected void iterateCrossProduct(final CGNode caller, final SSAAbstractInvokeInstruction call, final InstanceKey[][] invariants,
			final Consumer<InstanceKey[]> f) {
		new CrossProductRec(invariants, call, caller, f).rec(0, 0);
	}

	protected Set<CGNode> getTargetsForCall(final CGNode caller, final SSAAbstractInvokeInstruction instruction, InstanceKey[][] invs) {
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
				}
			}
		};
		iterateCrossProduct(caller, instruction, invs, f);
		return targets;
	}

	private IntSet getRelevantParameters(final CGNode caller, final CallSiteReference site) throws UnimplementedError {
		IntSet params = contextSelector.getRelevantParameters(caller, site);
		if (!site.isStatic() && !params.contains(0)) {
			params = IntSetUtil.makeMutableCopy(params);
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
			if (!field.getFieldType().isPrimitiveType()) {
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
			if (!field.getFieldType().isPrimitiveType()) {
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

	/**
	 * TODO: enhance this logic using type inference
	 *
	 * @param instruction
	 * @return true if we need to filter the receiver type to account for virtual dispatch
	 */
	@SuppressWarnings("unused")
	private boolean needsFilterForReceiver(SSAAbstractInvokeInstruction instruction, CGNode target) {

		IPAFilteredPointerKey.IPATypeFilter f = (IPAFilteredPointerKey.IPATypeFilter) target.getContext().get(ContextKey.PARAMETERS[0]);

		if (f != null) {
			// the context selects a particular concrete type for the receiver.
			// we need to filter, unless the declared receiver type implies the
			// concrete type (TODO: need to implement this optimization)
			return true;
		}

		// don't need to filter for invokestatic
		if (instruction.getCallSite().isStatic() || instruction.getCallSite().isSpecial()) {
			return false;
		}

		MethodReference declaredTarget = instruction.getDeclaredTarget();
		IMethod resolvedTarget = getClassHierarchy().resolveMethod(declaredTarget);
		if (resolvedTarget == null) {
			// there's some problem that will be flagged as a warning
			return true;
		}

		return true;
	}

	private static boolean isRootType(IClass klass) {
		return klass.getClassHierarchy().isRootClass(klass);
	}

	@SuppressWarnings("unused")
	private static boolean isRootType(IPAFilteredPointerKey.IPATypeFilter filter) {
		if (filter instanceof IPAFilteredPointerKey.SingleClassFilter) {
			return isRootType(((IPAFilteredPointerKey.SingleClassFilter) filter).getConcreteType());
		} else {
			return false;
		}
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
		if(target.getContext().get(ContextKey.PARAMETERS[index]) instanceof FilteredPointerKey.TypeFilter){
//			System.out.println(target.getContext().get(ContextKey.PARAMETERS[index]));
//			FilteredPointerKey.TypeFilter filter0 = (FilteredPointerKey.TypeFilter) target.getContext().get(ContextKey.PARAMETERS[index]);
			IClass cl = target.getMethod().getDeclaringClass();
//			System.out.println("$$$$$$$$ " + cl.toString());
			filter = new IPAFilteredPointerKey.SingleClassFilter(cl);
		}else
			filter = (IPAFilteredPointerKey.IPATypeFilter) target.getContext().get(ContextKey.PARAMETERS[index]);

		if (filter != null && !filter.isRootFilter()) {
			return getFilteredPointerKeyForLocal(target, vn, filter);

		} else {
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
	 * A value is "invariant" if we can figure out the instances it can ever point to locally, without resorting to propagation.
	 *
	 * @param valueNumber
	 * @return true iff the contents of the local with this value number can be deduced locally, without propagation
	 */
	protected boolean contentsAreInvariant(SymbolTable symbolTable, DefUse du, int valueNumber) {
		if (isConstantRef(symbolTable, valueNumber)) {
			return true;
		} else if (SHORT_CIRCUIT_INVARIANT_SETS) {
			SSAInstruction def = du.getDef(valueNumber);
			if (def instanceof SSANewInstruction) {
				return true;
			} else {
				return false;
			}
		} else {
			return false;
		}
	}

	protected boolean contentsAreInvariant(SymbolTable symbolTable, DefUse du, int valueNumbers[]) {
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
		InstanceKey[] result;
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

	protected boolean isConstantRef(SymbolTable symbolTable, int valueNumber) {
		if (valueNumber == -1) {
			return false;
		}
		if (symbolTable.isConstant(valueNumber)) {
			Object v = symbolTable.getConstantValue(valueNumber);
			return (!(v instanceof Number));
		} else {
			return false;
		}
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
	private static class FieldResolutionFailure extends Warning {

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

	/*
	 * @see com.ibm.wala.ipa.callgraph.propagation.PropagationCallGraphBuilder#makeSolver()
	 */
	@Override
	protected IPointsToSolver makeSolver() {
		return new IPAStandardSolver(system, this);
	}


	public void updatePointsToAnalysis(CGNode targetNode, HashSet<SSAInstruction> delInsts,
			HashSet<SSAInstruction> addInsts) throws CancelException {
		//true -> Indicate we are performing incremental analysis
	    system.setChange(true);
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

	public void updatePointerAnalaysis(CGNode node, HashMap<SSAInstruction, ISSABasicBlock> deleted,
			IR ir_old) {
		DefUse du_old = new DefUse(ir_old);
		ConstraintVisitor v_old = this.makeVisitor(node);
		v_old.setIR(ir_old);
		v_old.setDefUse(du_old);

	    try {
	    	//true -> Perform SSAInstruction Deletion
	    	system.setChange(true);
	    	this.setDelete(true);
	    	for(Object key: deleted.keySet()){
	    		SSAInstruction diff = (SSAInstruction)key;
	    		ISSABasicBlock bb = (ISSABasicBlock)deleted.get(key);
	    		v_old.setBasicBlock(bb);

	    		system.setFirstDel(true);
	    		diff.visit(v_old);
	    		system.setFirstDel(false);
	    		do{
	    			system.solveDel(null);
	    		}while(!system.emptyWorkList());
	    	}
	    	this.setDelete(false);
		    //false -> Perform SSAInstruction Addition
	    	//solved when updating call graph
	    }catch(Exception e) {
	    	e.printStackTrace();
	    }

	}

}

