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

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.classLoader.ProgramCounter;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.AbstractPointerAnalysis;
import com.ibm.wala.ipa.callgraph.propagation.ConcreteTypeKey;
import com.ibm.wala.ipa.callgraph.propagation.ConstantKey;
import com.ibm.wala.ipa.callgraph.propagation.FilteredPointerKey;
import com.ibm.wala.ipa.callgraph.propagation.HeapModel;
import com.ibm.wala.ipa.callgraph.propagation.InstanceFieldKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKeyFactory;
import com.ibm.wala.ipa.callgraph.propagation.LocalPointerKey;
import com.ibm.wala.ipa.callgraph.propagation.NullConstantKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.callgraph.propagation.StringConstantCharArray;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAGetCaughtExceptionInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPiInstruction;
import com.ibm.wala.ssa.SSAThrowInstruction;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.Iterator2Iterable;
import com.ibm.wala.util.debug.Assertions;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableMapping;
//import com.ibm.wala.util.intset.MutableSparseIntSet;
import com.ibm.wala.util.intset.OrdinalSet;
import com.ibm.wala.util.intset.OrdinalSetMapping;

import edu.tamu.wala.increpta.pointerkey.IPAFilteredPointerKey;
import edu.tamu.wala.increpta.pointerkey.IPAPointerKeyFactory;
import edu.tamu.wala.increpta.util.intset.IPAIntSetUtil;
import edu.tamu.wala.increpta.util.intset.IPAMutableSharedBitVectorIntSet;
import edu.tamu.wala.increpta.util.intset.IPAMutableSparseIntSet;

public class IPAPointerAnalysisImpl extends AbstractPointerAnalysis{
	
	/**
	 * bz: 
	 * when consider path-sensitive,
	 * do we create instance keys for constant values, e.g., null, true, false
	 */
	public static boolean CONSIDER_CONSTANT = true;
	
	/**
	 * bz: used when translating to database
	 */
	private static boolean RECORD_ROOT = false;
	public void recordRootRelation(boolean b) {
		RECORD_ROOT = b;
	}
	
	/**
	 * mapping from PointerKey to IPAPointsToSetVariable
	 */
	private final IPAPointsToMap pointsToMap;

	/**
	 * Meta-data regarding heap abstractions
	 */
	private final IPAHeapModel H;

	/**
	 * An object that abstracts how to model pointers in the heap.
	 */
	protected final IPAPointerKeyFactory pointerKeys;

	/**
	 * An object that abstracts how to model instances in the heap.
	 */
	private final InstanceKeyFactory iKeyFactory;

	protected final IPAPropagationCallGraphBuilder builder;

	private static boolean DETAIL = true;

	public IPAPointerAnalysisImpl(IPAPropagationCallGraphBuilder builder, CallGraph cg, IPAPointsToMap pointsToMap,
			MutableMapping<InstanceKey> instanceKeys, IPAPointerKeyFactory pointerKeys, InstanceKeyFactory iKeyFactory) {
		super(cg, instanceKeys);
		this.builder = builder;
		this.pointerKeys = pointerKeys;
		this.iKeyFactory = iKeyFactory;
		this.pointsToMap = pointsToMap;
		if (iKeyFactory == null) {
			throw new IllegalArgumentException("null iKeyFactory");
		}
		H = makeHeapModel();
	}
	
	public void print() {
		System.out.println("PointerAnalysis:\n");
		for (Iterator it = pointsToMap.iterateKeys(); it.hasNext();) {
			StringBuffer buffer = new StringBuffer();
			PointerKey p = (PointerKey) it.next();
			buffer.append("  ").append(p).append("\n  => \n");
			if(DETAIL) {
				OrdinalSet O = getPointsToSet(p);
				for (Iterator it2 = O.iterator(); it2.hasNext();) {
					buffer.append("     ").append(it2.next()).append("\n");
				}
				System.out.print(buffer.toString());
			}else {
				IntSet set = getPointsToIntSet(p);
				if(set == null) {
					buffer.append("     ").append("{null}").append("\n");
				}else {
					if(set.size() == 0) {
						buffer.append("     ").append("{ }").append("\n");
					}
//					buffer.append("     ").append(set.toString()).append("\n");
				}
				System.out.print(buffer.toString());
			}
		}
	}
	
	

	@Override
	public String toString() {//ExceptionReturnValueKey
		StringBuffer result = new StringBuffer("PointerAnalysis:\n");
		for (Iterator it = pointsToMap.iterateKeys(); it.hasNext();) {
			PointerKey p = (PointerKey) it.next();
			result.append("  ").append(p).append("\n  => \n");
			if(DETAIL) {
				OrdinalSet O = getPointsToSet(p);
				for (Iterator it2 = O.iterator(); it2.hasNext();) {
					result.append("     ").append(it2.next()).append("\n");
				}
			}else {
				IntSet set = getPointsToIntSet(p);
				result.append("     ").append(set.toString()).append("\n");
			}
		}
		return result.toString();
	}

	protected IPAHeapModel makeHeapModel() {
		return new HModel();
	}

	/**
	 * bz
	 */
	public IPAPointsToMap getPointsToMap(){
		return pointsToMap;
	}

	/*
	 * @see com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis#getPointsToSet(com.ibm.wala.ipa.callgraph.propagation.PointerKey)
	 */
	@Override
	@SuppressWarnings("unchecked")
	public OrdinalSet<InstanceKey> getPointsToSet(PointerKey key) {
		// bz: key.toString().contains("[Node: < Application, Lorg/python/util/InterpreterTest, testMultipleThreads()V > Context: ThreadContext: [ThreadID:0, <Lcom/ibm/wala/FakeRootClass, fakeRootMethod>], v7]")
		//jython will treat this key as non implicit...
		if (pointsToMap.isImplicit(key)) {
			return computeImplicitPointsToSet(key);
		}

		// special logic to handle contents of char[] from string constants.
		if (key instanceof InstanceFieldKey) {
			InstanceFieldKey ifk = (InstanceFieldKey) key;
			if (ifk.getInstanceKey() instanceof ConstantKey) {
				ConstantKey<?> i = (ConstantKey<?>) ifk.getInstanceKey();
				if (i.getValue() instanceof String && i.getConcreteType().getClassLoader().getLanguage().equals(Language.JAVA)) {
					StringConstantCharArray contents = StringConstantCharArray.make((ConstantKey<String>) i);
					instanceKeys.add(contents);
					Collection<InstanceKey> singleton = HashSetFactory.make();
					singleton.add(contents);
					return OrdinalSet.toOrdinalSet(singleton, instanceKeys);
				}
			}
		}

		IPAPointsToSetVariable v = pointsToMap.getPointsToSet(key);

		if (v == null) {
			return OrdinalSet.empty();
		} else {
			IntSet S = v.getValue();
			return new OrdinalSet<InstanceKey>(S, instanceKeys);
		}
	}
	
	public IntSet getPointsToIntSet(PointerKey key) {
		if (pointsToMap.isImplicit(key)) {
			OrdinalSet<InstanceKey> set = computeImplicitPointsToSet(key);
			return transferToIntSet(set);
		}

		// special logic to handle contents of char[] from string constants.
		if (key instanceof InstanceFieldKey) {
			InstanceFieldKey ifk = (InstanceFieldKey) key;
			if (ifk.getInstanceKey() instanceof ConstantKey) {
				ConstantKey<?> i = (ConstantKey<?>) ifk.getInstanceKey();
				if (i.getValue() instanceof String && i.getConcreteType().getClassLoader().getLanguage().equals(Language.JAVA)) {
					StringConstantCharArray contents = StringConstantCharArray.make((ConstantKey<String>) i);
					instanceKeys.add(contents);
					Collection<InstanceKey> singleton = HashSetFactory.make();
					singleton.add(contents);
					OrdinalSet<InstanceKey> set = OrdinalSet.toOrdinalSet(singleton, instanceKeys);
					return transferToIntSet(set);
				}
			}
		}

		IPAPointsToSetVariable v = pointsToMap.getPointsToSet(key);

		if (v == null) {
			return IPAIntSetUtil.make();
		} else {
			return v.getValue();
		}
	}
	

	private IntSet transferToIntSet(OrdinalSet<InstanceKey> set) {
		IPAMutableSharedBitVectorIntSet result = (IPAMutableSharedBitVectorIntSet) IPAIntSetUtil.make();
		OrdinalSetMapping<InstanceKey> map = getInstanceKeyMapping();
		Iterator<InstanceKey> iter = set.iterator();
		while(iter.hasNext()) {
			InstanceKey key = iter.next();
			int id = map.getMappedIndex(key);
			if(id != -1) {
				result.add(id);
			}
		}
		return result;
	}

	/**
	 * did the pointer analysis use a type filter for a given points-to set? (this is ugly).
	 */
	@Override
	public boolean isFiltered(PointerKey key) {
		if (pointsToMap.isImplicit(key)) {
			return false;
		}
		IPAPointsToSetVariable v = pointsToMap.getPointsToSet(key);
		if (v == null) {
			return false;
		} else {
			return v.getPointerKey() instanceof IPAFilteredPointerKey;
		}
	}

	public static class ImplicitPointsToSetVisitor extends SSAInstruction.Visitor {
		protected final IPAPointerAnalysisImpl analysis;

		protected final CGNode node;

		protected final LocalPointerKey lpk;

		protected OrdinalSet<InstanceKey> pointsToSet = null;

		protected ImplicitPointsToSetVisitor(IPAPointerAnalysisImpl analysis, LocalPointerKey lpk) {
			this.lpk = lpk;
			this.node = lpk.getNode();
			this.analysis = analysis;
		}

		@Override
		public void visitNew(SSANewInstruction instruction) {
			pointsToSet = OrdinalSet.empty();
		}

		@Override
		public void visitInvoke(SSAInvokeInstruction instruction) {
			pointsToSet = analysis.computeImplicitPointsToSetAtCall(lpk, node, instruction);
		}

		@Override
		public void visitCheckCast(SSACheckCastInstruction instruction) {
			pointsToSet = analysis.computeImplicitPointsToSetAtCheckCast(node, instruction);
		}

		@Override
		public void visitGetCaughtException(SSAGetCaughtExceptionInstruction instruction) {
			pointsToSet = analysis.computeImplicitPointsToSetAtCatch(node, instruction);
		}

		@Override
		public void visitGet(SSAGetInstruction instruction) {
			pointsToSet = analysis.computeImplicitPointsToSetAtGet(node, instruction);
		}

		@Override
		public void visitPhi(SSAPhiInstruction instruction) {
			pointsToSet = analysis.computeImplicitPointsToSetAtPhi(node, instruction);
		}

		@Override
		public void visitPi(SSAPiInstruction instruction) {
			pointsToSet = analysis.computeImplicitPointsToSetAtPi(node, instruction);
		}

		@Override
		public void visitArrayLoad(SSAArrayLoadInstruction instruction) {
			pointsToSet = analysis.computeImplicitPointsToSetAtALoad(node, instruction);
		}
	}

	protected ImplicitPointsToSetVisitor makeImplicitPointsToVisitor(LocalPointerKey lpk) {
		return new ImplicitPointsToSetVisitor(this, lpk);
	}

	private OrdinalSet<InstanceKey> computeImplicitPointsToSet(PointerKey key) {
		IPAPointsToSetVariable tmp = pointsToMap.getPointsToSet(key);
		if (tmp != null) {
			IntSet S = tmp.getValue();
			return new OrdinalSet<InstanceKey>(S, instanceKeys);
		}
		
		if (key instanceof LocalPointerKey) {
			LocalPointerKey lpk = (LocalPointerKey) key;
			CGNode node = lpk.getNode();
			IR ir = node.getIR();
			DefUse du = node.getDU();
			if (((IPASSAPropagationCallGraphBuilder) builder).contentsAreInvariant(ir.getSymbolTable(), du, lpk.getValueNumber())) {
				// cons up the points-to set for invariant contents
				InstanceKey[] ik = ((IPASSAPropagationCallGraphBuilder) builder).getInvariantContents(ir.getSymbolTable(), du, node, lpk
						.getValueNumber(), H, true);
				if(RECORD_ROOT) {
					//bz: udpate for graph db ...
					for(InstanceKey iKey : ik) {
						builder.getSystem().getRoot().putIfAbsent(key, instanceKeys.getMappedIndex(iKey));
					}
				}
				return toOrdinalSet(ik);
			} else {
				SSAInstruction def = du.getDef(lpk.getValueNumber());
				if (def != null) {
					ImplicitPointsToSetVisitor v = makeImplicitPointsToVisitor(lpk);
					def.visit(v);
					if (v.pointsToSet != null) {
						return v.pointsToSet;
					} else {
						Assertions.UNREACHABLE("saw " + key + ": time to implement for " + def.getClass());
						return null;
					}
				} else {
					Assertions.UNREACHABLE("unexpected null def for " + key);
					return null;
				}
			}
		} else {
			Assertions.UNREACHABLE("unexpected implicit key " + key + " that's not a local pointer key");
			return null;
		}
	}

	private OrdinalSet<InstanceKey> computeImplicitPointsToSetAtPi(CGNode node, SSAPiInstruction instruction) {
		IPAMutableSparseIntSet S = IPAMutableSparseIntSet.makeEmpty();
		for (int i = 0; i < instruction.getNumberOfUses(); i++) {
			int vn = instruction.getUse(i);
			if (vn != -1) {
				PointerKey lpk = pointerKeys.getPointerKeyForLocal(node, vn);
				OrdinalSet pointees = getPointsToSet(lpk);
				IntSet set = pointees.getBackingSet();
				if (set != null) {
					S.addAll(set);
				}
			}
		}
		return new OrdinalSet<InstanceKey>(S, instanceKeys);
	}

	private OrdinalSet<InstanceKey> computeImplicitPointsToSetAtPhi(CGNode node, SSAPhiInstruction instruction) {
		IPAMutableSparseIntSet S = IPAMutableSparseIntSet.makeEmpty();
		for (int i = 0; i < instruction.getNumberOfUses(); i++) {
			int vn = instruction.getUse(i);
			if (vn != -1) {
				PointerKey lpk = pointerKeys.getPointerKeyForLocal(node, vn);
				OrdinalSet pointees = getPointsToSet(lpk);
				IntSet set = pointees.getBackingSet();
				if (set != null) {
					S.addAll(set);
				}
			}
		}
		return new OrdinalSet<InstanceKey>(S, instanceKeys);
	}

	private OrdinalSet<InstanceKey> computeImplicitPointsToSetAtALoad(CGNode node, SSAArrayLoadInstruction instruction) {
		PointerKey arrayRef = pointerKeys.getPointerKeyForLocal(node, instruction.getArrayRef());
		IPAMutableSparseIntSet S = IPAMutableSparseIntSet.makeEmpty();
		OrdinalSet refs = getPointsToSet(arrayRef);
		for (Iterator it = refs.iterator(); it.hasNext();) {
			InstanceKey ik = (InstanceKey) it.next();
			PointerKey key = pointerKeys.getPointerKeyForArrayContents(ik);
			OrdinalSet pointees = getPointsToSet(key);
			IntSet set = pointees.getBackingSet();
			if (set != null) {
				S.addAll(set);
			}
		}
		return new OrdinalSet<InstanceKey>(S, instanceKeys);
	}

	private OrdinalSet<InstanceKey> computeImplicitPointsToSetAtGet(CGNode node, SSAGetInstruction instruction) {
		return computeImplicitPointsToSetAtGet(node, instruction.getDeclaredField(), instruction.getRef(), instruction.isStatic());
	}

	public OrdinalSet<InstanceKey> computeImplicitPointsToSetAtGet(CGNode node, FieldReference field, int refVn, boolean isStatic) {
		IField f = getCallGraph().getClassHierarchy().resolveField(field);
		if (f == null) {
			return OrdinalSet.empty();
		}
		if (isStatic) {
			PointerKey fKey = pointerKeys.getPointerKeyForStaticField(f);
			return getPointsToSet(fKey);
		} else {
			PointerKey ref = pointerKeys.getPointerKeyForLocal(node, refVn);
			IPAMutableSparseIntSet S = IPAMutableSparseIntSet.makeEmpty();
			OrdinalSet refs = getPointsToSet(ref);
			for (Iterator it = refs.iterator(); it.hasNext();) {
				InstanceKey ik = (InstanceKey) it.next();
				PointerKey fkey = pointerKeys.getPointerKeyForInstanceField(ik, f);
				if (fkey != null) {
					OrdinalSet pointees = getPointsToSet(fkey);
					IntSet set = pointees.getBackingSet();
					if (set != null) {
						S.addAll(set);
					}
				}
			}
			return new OrdinalSet<InstanceKey>(S, instanceKeys);
		}
	}

	private OrdinalSet<InstanceKey> computeImplicitPointsToSetAtCatch(CGNode node, SSAGetCaughtExceptionInstruction instruction) {
		IR ir = node.getIR();
		List<ProgramCounter> peis = IPASSAPropagationCallGraphBuilder.getIncomingPEIs(ir, ir.getBasicBlockForCatch(instruction));
		Set<IClass> caughtTypes = IPASSAPropagationCallGraphBuilder.getCaughtExceptionTypes(instruction, ir);
		IPAMutableSparseIntSet S = IPAMutableSparseIntSet.makeEmpty();
		// add the instances from each incoming pei ...
		for (Iterator<ProgramCounter> it = peis.iterator(); it.hasNext();) {
			ProgramCounter peiLoc = it.next();
			SSAInstruction pei = ir.getPEI(peiLoc);
			PointerKey e = null;
			// first deal with exception variables from calls and throws.
			if (pei instanceof SSAAbstractInvokeInstruction) {
				SSAAbstractInvokeInstruction s = (SSAAbstractInvokeInstruction) pei;
				e = pointerKeys.getPointerKeyForLocal(node, s.getException());
			} else if (pei instanceof SSAThrowInstruction) {
				SSAThrowInstruction s = (SSAThrowInstruction) pei;
				e = pointerKeys.getPointerKeyForLocal(node, s.getException());
			}
			if (e != null) {
				OrdinalSet ep = getPointsToSet(e);
				for (Iterator it2 = ep.iterator(); it2.hasNext();) {
					InstanceKey ik = (InstanceKey) it2.next();
					if (IPAPropagationCallGraphBuilder.catches(caughtTypes, ik.getConcreteType(), getCallGraph().getClassHierarchy())) {
						S.add(instanceKeys.getMappedIndex(ik));
					}
				}
			}

			// Account for those exceptions for which we do not actually have a
			// points-to set for
			// the pei, but just instance keys
			Collection types = pei.getExceptionTypes();
			if (types != null) {
				for (Iterator it2 = types.iterator(); it2.hasNext();) {
					TypeReference type = (TypeReference) it2.next();
					if (type != null) {
						InstanceKey ik = IPASSAPropagationCallGraphBuilder.getInstanceKeyForPEI(node, peiLoc, type, iKeyFactory);
						ConcreteTypeKey ck = (ConcreteTypeKey) ik;
						IClass klass = ck.getType();
						if (IPAPropagationCallGraphBuilder.catches(caughtTypes, klass, getCallGraph().getClassHierarchy())) {
							S.add(instanceKeys.getMappedIndex(IPASSAPropagationCallGraphBuilder
									.getInstanceKeyForPEI(node, peiLoc, type, iKeyFactory)));
						}
					}
				}
			}
		}
		return new OrdinalSet<InstanceKey>(S, instanceKeys);
	}

	private OrdinalSet<InstanceKey> computeImplicitPointsToSetAtCheckCast(CGNode node, SSACheckCastInstruction instruction) {
		PointerKey rhs = pointerKeys.getPointerKeyForLocal(node, instruction.getVal());
		OrdinalSet<InstanceKey> rhsSet = getPointsToSet(rhs);
		IPAMutableSparseIntSet S = IPAMutableSparseIntSet.makeEmpty();
		for (TypeReference t : instruction.getDeclaredResultTypes()) {
			IClass klass = getCallGraph().getClassHierarchy().lookupClass(t);
			if (klass == null) {
				// could not find the type. conservatively assume Object
				return rhsSet;
			} else {
				if (klass.isInterface()) {
					for (Iterator it = rhsSet.iterator(); it.hasNext();) {
						InstanceKey ik = (InstanceKey) it.next();
						if (getCallGraph().getClassHierarchy().implementsInterface(ik.getConcreteType(), klass)) {
							S.add(getInstanceKeyMapping().getMappedIndex(ik));
						}
					}
				} else {
					for (Iterator it = rhsSet.iterator(); it.hasNext();) {
						InstanceKey ik = (InstanceKey) it.next();
						if(CONSIDER_CONSTANT) {
							// bz: we have null constant key here that has no type, 
							// assume it is subclass of all types, since all key can be null...
							if(ik instanceof NullConstantKey) {
								S.add(getInstanceKeyMapping().getMappedIndex(ik));
							}
						}else
						if (getCallGraph().getClassHierarchy().isSubclassOf(ik.getConcreteType(), klass)) {
							S.add(getInstanceKeyMapping().getMappedIndex(ik));
						}
					}
				}
			}
		}
		return new OrdinalSet<InstanceKey>(S, instanceKeys);
	}

	private OrdinalSet<InstanceKey> computeImplicitPointsToSetAtCall(LocalPointerKey lpk, CGNode node, SSAInvokeInstruction call) {
		int exc = call.getException();
		int ret = call.getDef();
		if (lpk.getValueNumber() == exc) {
			return computeImplicitExceptionsForCall(node, call);
		}else if (lpk.getValueNumber() == ret) {
		  //bz: implement this.
		  return computeImplicitReturnValForCall(node, call);
		} else {
			Assertions.UNREACHABLE("time to implement me.");
			return null;
		}
	}
	
	public IPAMutableSparseIntSet toMutableSet(OrdinalSet<InstanceKey> ik) {
		IPAMutableSparseIntSet s = IPAMutableSparseIntSet.makeEmpty();
		for (InstanceKey instance : ik) {
			int index = instanceKeys.getMappedIndex(instance);
			if (index != -1) {
				s.add(index);
			} else {
				assert index != -1 : "instance " + instance + " not mapped!";
			}
		}
		return s;
	}

	private OrdinalSet<InstanceKey> toOrdinalSet(InstanceKey[] ik) {
		IPAMutableSparseIntSet s = IPAMutableSparseIntSet.makeEmpty();
		for (int i = 0; i < ik.length; i++) {
			int index = instanceKeys.getMappedIndex(ik[i]);
			if (index != -1) {
				s.add(index);
			} else {
				assert index != -1 : "instance " + ik[i] + " not mapped!";
			}
		}
		return new OrdinalSet<InstanceKey>(s, instanceKeys);
	}

	/**
	 * @return the points-to set for the exceptional return values from a particular call site
	 */
	private OrdinalSet<InstanceKey> computeImplicitExceptionsForCall(CGNode node, SSAInvokeInstruction call) {
		IPAMutableSparseIntSet S = IPAMutableSparseIntSet.makeEmpty();
		for (Iterator it = getCallGraph().getPossibleTargets(node, call.getCallSite()).iterator(); it.hasNext();) {
			CGNode target = (CGNode) it.next();
			PointerKey retVal = pointerKeys.getPointerKeyForExceptionalReturnValue(target);
			IntSet set = getPointsToSet(retVal).getBackingSet();
			if (set != null) {
				S.addAll(set);
			}
		}
		return new OrdinalSet<InstanceKey>(S, instanceKeys);
	}
	
	/**
   * @return the points-to set for the return value pointer from a particular call site
   */
	private OrdinalSet<InstanceKey> computeImplicitReturnValForCall(CGNode node, SSAInvokeInstruction call) {
	  IPAMutableSparseIntSet S = IPAMutableSparseIntSet.makeEmpty();
    for (Iterator it = getCallGraph().getPossibleTargets(node, call.getCallSite()).iterator(); it.hasNext();) {
      CGNode target = (CGNode) it.next();
      PointerKey retVal = pointerKeys.getPointerKeyForReturnValue(target);
      IntSet set = getPointsToSet(retVal).getBackingSet();
      if (set != null) {
        S.addAll(set);
      }
    }
    return new OrdinalSet<InstanceKey>(S, instanceKeys);
	}

	/*
	 * @see com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis#getHeapModel()
	 */
	@Override
	public HeapModel getHeapModel() {
		return (HeapModel) H;
	}

	public IPAHeapModel getIPAHeapModel() {//bz
		return H;
	}

	protected class HModel implements IPAHeapModel {

		@Override
		public Iterator<PointerKey> iteratePointerKeys() {
			return pointsToMap.iterateKeys();
		}

		@Override
		public InstanceKey getInstanceKeyForAllocation(CGNode node, NewSiteReference allocation) {
			return iKeyFactory.getInstanceKeyForAllocation(node, allocation);
		}

		@Override
		public InstanceKey getInstanceKeyForMultiNewArray(CGNode node, NewSiteReference allocation, int dim) {
			return iKeyFactory.getInstanceKeyForMultiNewArray(node, allocation, dim);
		}

		@Override
		public <T> InstanceKey getInstanceKeyForConstant(TypeReference type, T S) {
			return iKeyFactory.getInstanceKeyForConstant(type, S);
		}

		@Override
		public InstanceKey getInstanceKeyForPEI(CGNode node, ProgramCounter peiLoc, TypeReference type) {
			return iKeyFactory.getInstanceKeyForPEI(node, peiLoc, type);
		}

		@Override
		public InstanceKey getInstanceKeyForMetadataObject(Object obj, TypeReference objType) {
			return iKeyFactory.getInstanceKeyForMetadataObject(obj, objType);
		}

		/*
		 * @see com.ibm.wala.ipa.callgraph.propagation.PointerKeyFactory#getPointerKeyForLocal(com.ibm.detox.ipa.callgraph.CGNode, int)
		 */
		@Override
		public PointerKey getPointerKeyForLocal(CGNode node, int valueNumber) {
			return pointerKeys.getPointerKeyForLocal(node, valueNumber);
		}

		@Override
		public FilteredPointerKey getFilteredPointerKeyForLocal(CGNode node, int valueNumber, FilteredPointerKey.TypeFilter filter) {
			return pointerKeys.getFilteredPointerKeyForLocal(node, valueNumber, filter);
		}

		@Override
		public IPAFilteredPointerKey getFilteredPointerKeyForLocal(CGNode node, int valueNumber, IPAFilteredPointerKey.IPATypeFilter filter) {
			return pointerKeys.getFilteredPointerKeyForLocal(node, valueNumber, filter);
		}

		/*
		 * @see com.ibm.wala.ipa.callgraph.propagation.PointerKeyFactory#getPointerKeyForReturnValue(com.ibm.detox.ipa.callgraph.CGNode)
		 */
		@Override
		public PointerKey getPointerKeyForReturnValue(CGNode node) {
			return pointerKeys.getPointerKeyForReturnValue(node);
		}

		/*
		 * @see
		 * com.ibm.wala.ipa.callgraph.propagation.PointerKeyFactory#getPointerKeyForExceptionalReturnValue(com.ibm.detox.ipa.callgraph
		 * .CGNode)
		 */
		@Override
		public PointerKey getPointerKeyForExceptionalReturnValue(CGNode node) {
			return pointerKeys.getPointerKeyForExceptionalReturnValue(node);
		}

		/*
		 * @see
		 * com.ibm.wala.ipa.callgraph.propagation.PointerKeyFactory#getPointerKeyForStaticField(com.ibm.wala.classLoader.FieldReference)
		 */
		@Override
		public PointerKey getPointerKeyForStaticField(IField f) {
			return pointerKeys.getPointerKeyForStaticField(f);
		}

		/*
		 * @see
		 * com.ibm.wala.ipa.callgraph.propagation.PointerKeyFactory#getPointerKeyForInstance(com.ibm.wala.ipa.callgraph.propagation.
		 * InstanceKey, com.ibm.wala.classLoader.FieldReference)
		 */
		@Override
		public PointerKey getPointerKeyForInstanceField(InstanceKey I, IField field) {
			assert field != null;
			return pointerKeys.getPointerKeyForInstanceField(I, field);
		}

		/*
		 * @see
		 * com.ibm.wala.ipa.callgraph.propagation.PointerKeyFactory#getPointerKeyForArrayContents(com.ibm.wala.ipa.callgraph.propagation
		 * .InstanceKey)
		 */
		@Override
		public PointerKey getPointerKeyForArrayContents(InstanceKey I) {
			return pointerKeys.getPointerKeyForArrayContents(I);
		}

		@Override
		public IClassHierarchy getClassHierarchy() {
			return getCallGraph().getClassHierarchy();
		}
	}

	/*
	 * @see com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis#iteratePointerKeys()
	 */
	@Override
	public Iterable<PointerKey> getPointerKeys() {
		return Iterator2Iterable.make(pointsToMap.iterateKeys());
	}

	@Override
	public IClassHierarchy getClassHierarchy() {
		return builder.getClassHierarchy();
	}

	public int getNumOfPointerKeys() {
		int size = 0;
		Iterator<PointerKey> iter = pointsToMap.iterateKeys();
		while(iter.hasNext()){
			iter.next();
			size++;
		}
		return size;
	}
}
