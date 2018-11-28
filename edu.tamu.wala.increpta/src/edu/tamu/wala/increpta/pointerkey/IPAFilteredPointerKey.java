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
package edu.tamu.wala.increpta.pointerkey;

import java.util.Arrays;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.ContextItem;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetAction;
import com.ibm.wala.util.intset.IntSetUtil;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.MutableSharedBitVectorIntSet;

import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPointsToSetVariable;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationSystem;
import edu.tamu.wala.increpta.util.DeletionUtil;

public interface IPAFilteredPointerKey extends PointerKey{

	public interface IPATypeFilter extends ContextItem {

		boolean addFiltered(IPAPropagationSystem system, IPAPointsToSetVariable lhs, IPAPointsToSetVariable rhs);

		boolean addInverseFiltered(IPAPropagationSystem system, IPAPointsToSetVariable L, IPAPointsToSetVariable R);

		boolean isRootFilter();

		/**
		 * bz: for delete constraints
		 * @param system
		 * @param lhs
		 * @param rhs
		 * @return
		 */
		boolean delFiltered(IPAPropagationSystem system, IPAPointsToSetVariable lhs, IPAPointsToSetVariable rhs);
		//used for propagation
		boolean delFiltered(IPAPropagationSystem system, IPAPointsToSetVariable lhs, MutableSharedBitVectorIntSet set);

	}

	public class SingleClassFilter implements IPATypeFilter {
		private final IClass concreteType;

		public SingleClassFilter(IClass concreteType) {
			this.concreteType = concreteType;
		}

		@Override
		public String toString() {
			return "SingleClassFilter: " + concreteType;
		}

		public IClass getConcreteType() {
			return concreteType;
		}

		@Override
		public int hashCode() {
			return concreteType.hashCode();
		}

		@Override
		public boolean equals(Object o) {
			return (o instanceof SingleClassFilter) && ((SingleClassFilter) o).getConcreteType().equals(concreteType);
		}

		@Override
		public boolean addFiltered(IPAPropagationSystem system, IPAPointsToSetVariable L, IPAPointsToSetVariable R) {
			IntSet f = system.getInstanceKeysForClass(concreteType);
			return (f == null) ? false : L.addAllInIntersection(R, f);
		}

		@Override
		public boolean addInverseFiltered(IPAPropagationSystem system, IPAPointsToSetVariable L, IPAPointsToSetVariable R) {
			IntSet f = system.getInstanceKeysForClass(concreteType);
			// SJF: this is horribly inefficient. we really don't want to do
			// diffs in here. TODO: fix it. probably keep not(f) cached and
			// use addAllInIntersection
			return (f == null) ? L.addAll(R) : L.addAll(IntSetUtil.diff(R.getValue(), f));
		}

		@Override
		public boolean isRootFilter() {
			return concreteType.equals(concreteType.getClassHierarchy().getRootClass());
		}

		/**
		 * bz
		 */
		@Override
		public boolean delFiltered(IPAPropagationSystem system, IPAPointsToSetVariable L, IPAPointsToSetVariable R){
			IntSet f = system.getInstanceKeysForClass(concreteType);
			if(f == null)
				return false;
			IntSet intersection = R.getValue().intersection(f);
			DeletionUtil.removeSome(L, intersection);
			if(L.getChange().size() > 0)
				return true;
			else
				return false;
		}

		/**
		 * bz
		 */
		@Override
		public boolean delFiltered(IPAPropagationSystem system, IPAPointsToSetVariable L, MutableSharedBitVectorIntSet set) {
			IntSet f = system.getInstanceKeysForClass(concreteType);
			if(f == null)
				return false;
			IntSet intersection = set.intersection(f);
			DeletionUtil.removeSome(L, intersection);
			if(L.getChange().size() > 0)
				return true;
			else
				return false;
		}
	}

	public class MultipleClassesFilter implements IPATypeFilter {
		private final IClass[] concreteType;

		public MultipleClassesFilter(IClass[] concreteType) {
			this.concreteType = concreteType;
		}

		@Override
		public String toString() {
			return "MultipleClassesFilter: " + Arrays.toString(concreteType);
		}

		public IClass[] getConcreteTypes() {
			return concreteType;
		}

		@Override
		public int hashCode() {
			return concreteType[0].hashCode();
		}

		@Override
		public boolean equals(Object o) {
			if (! (o instanceof MultipleClassesFilter)) {
				return false;
			}

			MultipleClassesFilter f = (MultipleClassesFilter)o;

			if (concreteType.length != f.concreteType.length) {
				return false;
			}

			for(int i = 0; i < concreteType.length; i++) {
				if (! (concreteType[i].equals(f.concreteType[i]))) {
					return false;
				}
			}

			return true;
		}

		private IntSet bits(IPAPropagationSystem system) {
			IntSet f = null;
			for(IClass cls : concreteType) {
				if (f == null) {
					f = system.getInstanceKeysForClass(cls);
				} else {
					f = f.union(system.getInstanceKeysForClass(cls));
				}
			}
			return f;
		}

		@Override
		public boolean addFiltered(IPAPropagationSystem system, IPAPointsToSetVariable L, IPAPointsToSetVariable R) {
			IntSet f = bits(system);
			return (f == null) ? false : L.addAllInIntersection(R, f);
		}

		@Override
		public boolean addInverseFiltered(IPAPropagationSystem system, IPAPointsToSetVariable L, IPAPointsToSetVariable R) {
			IntSet f = bits(system);

			// SJF: this is horribly inefficient. we really don't want to do
			// diffs in here. TODO: fix it. probably keep not(f) cached and
			// use addAllInIntersection
			return (f == null) ? L.addAll(R) : L.addAll(IntSetUtil.diff(R.getValue(), f));
		}

		@Override
		public boolean isRootFilter() {
			return concreteType.length == 1 && concreteType[0].getClassHierarchy().getRootClass().equals(concreteType[0]);
		}

		/**
		 * bz:
		 * @param system
		 * @return
		 */
		private IntSet reverseBits(IPAPropagationSystem system){
			IntSet f = null;
			for(IClass cls : concreteType) {
				if (f == null) {
					f = system.getInstanceKeysForClass(cls);
				} else {
					f = f.union(system.getInstanceKeysForClass(cls));
				}
			}
			return f;
		}

		@Override
		public boolean delFiltered(IPAPropagationSystem system, IPAPointsToSetVariable L, IPAPointsToSetVariable R){
			if(L.getValue() == null){
				return false;
			}
			IntSet f = reverseBits(system);//same with bits()
			if(f == null)
				return false;
			IntSet intersection = R.getValue().intersection(f);
			DeletionUtil.removeSome(L, intersection);
			if(L.getChange().size() > 0)
				return true;
			else
				return false;
		}

		@Override
		public boolean delFiltered(IPAPropagationSystem system, IPAPointsToSetVariable L, MutableSharedBitVectorIntSet set) {
			if(L.getValue() == null){
				return false;
			}
			IntSet f = reverseBits(system);//same with bits()
			if(f == null)
				return false;
			IntSet intersection = set.intersection(f);
			DeletionUtil.removeSome(L, intersection);
			if(L.getChange().size() > 0)
				return true;
			else
				return false;
		}
	}

	public class SingleInstanceFilter implements IPATypeFilter {
		private final InstanceKey concreteType;

		public SingleInstanceFilter(InstanceKey concreteType) {
			this.concreteType = concreteType;
		}

		@Override
		public String toString() {
			return "SingleInstanceFilter: " + concreteType + " (" + concreteType.getClass() + ")";
		}

		public InstanceKey getInstance() {
			return concreteType;
		}

		@Override
		public int hashCode() {
			return concreteType.hashCode();
		}

		@Override
		public boolean equals(Object o) {
			return (o instanceof SingleInstanceFilter) && ((SingleInstanceFilter) o).getInstance().equals(concreteType);
		}

		@Override
		public boolean addFiltered(IPAPropagationSystem system, IPAPointsToSetVariable L, IPAPointsToSetVariable R) {
			int idx = system.findOrCreateIndexForInstanceKey(concreteType);
			if (R.contains(idx)) {
				if (!L.contains(idx)) {
					L.add(idx);
					return true;
				}
			}

			return false;
		}

		@Override
		public boolean addInverseFiltered(IPAPropagationSystem system, IPAPointsToSetVariable L, IPAPointsToSetVariable R) {
			int idx = system.findOrCreateIndexForInstanceKey(concreteType);
			if (!R.contains(idx) || L.contains(idx)) {
				return L.addAll(R);
			} else {
				MutableIntSet copy = IntSetUtil.makeMutableCopy(R.getValue());
				copy.remove(idx);
				return L.addAll(copy);
			}
		}

		@Override
		public boolean isRootFilter() {
			return false;
		}

		//bz
		@Override
		public boolean delFiltered(IPAPropagationSystem system, IPAPointsToSetVariable L, IPAPointsToSetVariable R){
			int idx = system.findOrCreateIndexForInstanceKey(concreteType);
			if(R.contains(idx) && L.contains(idx)){
				L.remove(idx);
				return true;
			}
			return false;
		};

		@Override
		public boolean delFiltered(IPAPropagationSystem system, IPAPointsToSetVariable L, MutableSharedBitVectorIntSet set) {
			int idx = system.findOrCreateIndexForInstanceKey(concreteType);
			if(set.contains(idx) && L.contains(idx)){
				L.remove(idx);
				return true;
			}
			return false;
		}
	}

	public class TargetMethodFilter implements IPATypeFilter {
		private final IMethod targetMethod;

		public TargetMethodFilter(IMethod targetMethod) {
			this.targetMethod = targetMethod;
		}

		@Override
		public String toString() {
			return "TargetMethodFilter: " + targetMethod;
		}

		public IMethod getMethod() {
			return targetMethod;
		}

		@Override
		public int hashCode() {
			return targetMethod.hashCode();
		}

		@Override
		public boolean equals(Object o) {
			return (o instanceof TargetMethodFilter) && ((TargetMethodFilter) o).getMethod().equals(targetMethod);
		}

		private class UpdateAction implements IntSetAction {
			private boolean result = false;

			private final IPAPointsToSetVariable L;

			private final IPAPropagationSystem system;

			private final boolean sense;

			private UpdateAction(IPAPropagationSystem system, IPAPointsToSetVariable L, boolean sense) {
				this.L = L;
				this.sense = sense;
				this.system = system;
			}

			@Override
			public void act(int i) {
				InstanceKey I = system.getInstanceKey(i);
				IClass C = I.getConcreteType();
				if ((C.getMethod(targetMethod.getSelector()) == targetMethod) == sense) {
					if (!L.contains(i)) {
						result = true;
						L.add(i);
					}
				}
			}
		}

		@Override
		public boolean addFiltered(IPAPropagationSystem system, IPAPointsToSetVariable L, IPAPointsToSetVariable R) {
			if (R.getValue() == null) {
				return false;
			} else {
				UpdateAction act = new UpdateAction(system, L, true);
				R.getValue().foreach(act);
				return act.result;
			}
		}

		@Override
		public boolean addInverseFiltered(IPAPropagationSystem system, IPAPointsToSetVariable L, IPAPointsToSetVariable R) {
			if (R.getValue() == null) {
				return false;
			} else {
				UpdateAction act = new UpdateAction(system, L, false);
				R.getValue().foreach(act);
				return act.result;
			}
		}

		@Override
		public boolean isRootFilter() {
			return false;
		}

		/**
		 * bz: action for delete constraint
		 * @author Bozhen
		 */
		private class ReverseUpdateAction implements IntSetAction {
			private boolean result = false;

			private final IPAPointsToSetVariable L;

			private final IPAPropagationSystem system;

			private final boolean sense;

			private ReverseUpdateAction(IPAPropagationSystem system, IPAPointsToSetVariable L, boolean sense) {
				this.L = L;
				this.sense = sense;
				this.system = system;
			}

			@Override
			public void act(int i) {
				InstanceKey I = system.getInstanceKey(i);
				IClass C = I.getConcreteType();
				if ((C.getMethod(targetMethod.getSelector()) == targetMethod) == sense) {
					if (L.contains(i)) {
						result = true;
						L.remove(i);
					}
				}
			}
		}


		@Override
		public boolean delFiltered(IPAPropagationSystem system, IPAPointsToSetVariable L, IPAPointsToSetVariable R){
			if (R.getValue() == null) {
				return false;
			} else {
				ReverseUpdateAction act = new ReverseUpdateAction(system, L, true);
				R.getValue().foreach(act);
				return act.result;
			}
		}

		@Override
		public boolean delFiltered(IPAPropagationSystem system, IPAPointsToSetVariable L, MutableSharedBitVectorIntSet set) {
			if (set == null) {
				return false;
			} else {
				ReverseUpdateAction act = new ReverseUpdateAction(system, L, true);
				set.foreach(act);
				return act.result;
			}
		}
	}

	/**
	 * @return the class which should govern filtering of instances to which this pointer points, or null if no filtering needed
	 */
	public IPATypeFilter getTypeFilter();
}
