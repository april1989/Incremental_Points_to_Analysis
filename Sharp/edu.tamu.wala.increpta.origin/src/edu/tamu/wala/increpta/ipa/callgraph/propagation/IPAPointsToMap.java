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

import java.util.HashMap;
import java.util.Iterator;
import java.util.function.Predicate;

import com.ibm.wala.ipa.callgraph.propagation.LocalPointerKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.callgraph.propagation.ReturnValueKey;
import com.ibm.wala.util.collections.FilterIterator;
import com.ibm.wala.util.collections.IVector;
import com.ibm.wala.util.collections.SimpleVector;
import com.ibm.wala.util.debug.Assertions;
import com.ibm.wala.util.intset.BitVector;
import com.ibm.wala.util.intset.IntegerUnionFind;
import com.ibm.wala.util.intset.MutableMapping;

import edu.tamu.wala.increpta.pointerkey.IPAFilteredPointerKey;
import edu.tamu.wala.increpta.pointerkey.IPALocalPointerKeyWithFilter;
import edu.tamu.wala.increpta.pointerkey.IPAReturnValueKeyWithFilter;

public class IPAPointsToMap {

	/**
	 * wala original: An object that manages the numbering of pointer keys
	 * !!!!!!! this cannot be replace, will have problem, e.g., pts(pointer) = { } 
	 * but actually have points-to objects during computation.
	 */
	private final MutableMapping<PointerKey> pointerKeys = MutableMapping.make();
	private final IntegerUnionFind uf = new IntegerUnionFind();
	
	/**
	 * tried, but will have empty pts when quering... see above warning
	 * bz: pointerKeys only uses mapping from object to integer (id)
	 */
//	private HashMap<PointerKey, Integer> pointerKeys = new HashMap<>();
//	private int nextIndex = 0;//mark index of pointer keys

	/**
	 * pointsToSets[i] says something about the representation of the points-to set for the ith {@link PointerKey}, as determined by
	 * the pointerKeys mapping. pointsToSets[i] can be one of the following:
	 * <ul>
	 * <li>a IPAPointsToSetVariable
	 * <li>IMPLICIT
	 * </ul>
	 * 
	 * bz: cannot remove, PointerKey is not identical everytime it has been allocated... require a map to match pts.
	 */
	private final IVector<Object> pointsToSets = new SimpleVector<Object>();
	private final IVector<Object> implicitPointsToSets = new SimpleVector<Object>();

	/**
	 * A hack: used to represent points-to-sets that are represented implicitly
	 */
	final static Object IMPLICIT = new Object() {
		@Override
		public String toString() {
			return "IMPLICIT points-to set";
		}
	};

	/**
	 * Numbers of pointer keys (non locals) that are roots of transitive closure. A "root" is a points-to-set whose contents do not
	 * result from flow from other points-to-sets; these points-to-sets are the primordial assignments from which the transitive
	 * closure flows.
	 */
	private final BitVector transitiveRoots = new BitVector();

	/**
	 * @return iterator of all PointerKeys tracked
	 */
	public Iterator<PointerKey> iterateKeys() {
		return pointerKeys.iterator();
	}

	/**
	 * If p is unified, returns the representative for p.
	 */
	public IPAPointsToSetVariable getPointsToSet(PointerKey p) {
		if (p == null) {
			throw new IllegalArgumentException("null p");
		}
		//bz: implicit is separated from others. skip checking
//		if (isImplicit(p)) {
//			throw new IllegalArgumentException("unexpected: shouldn't ask a PointsToMap for an implicit points-to-set: " + p);
//		}
		int i = pointerKeys.getMappedIndex(p);
//		Integer i = pointerKeys.get(p);
//		int i = p.getIdx();
		if (i == -1) {
			return null;
		}
		int repI = uf.find(i);//wala original: repI always == i
		IPAPointsToSetVariable result = (IPAPointsToSetVariable) pointsToSets.get(repI); 
		if (result != null 
				&& p instanceof IPAFilteredPointerKey 
				&& (!(result.getPointerKey() instanceof IPAFilteredPointerKey))) {
			upgradeToFilter(result, ((IPAFilteredPointerKey) p).getTypeFilter());
		}
		return result;
	}


	public boolean isImplicit(PointerKey p) {
		if (p == null) {
			throw new IllegalArgumentException("null key");
		}
		int i = getIndex(p);
//		return i != -1 && pointsToSets.get(i) == IMPLICIT;
		return i != -1 && implicitPointsToSets.get(i) == IMPLICIT;
	}

	/**
	 * record that a particular points-to-set is represented implicitly
	 */
	public void recordImplicit(PointerKey key) {
		if (key == null) {
			throw new IllegalArgumentException("null key");
		}
		int i = findOrCreateIndex(key);
		implicitPointsToSets.set(i, IMPLICIT);
	}
	
	public void derecordImplicit(PointerKey key) {
		if (key == null) {
			throw new IllegalArgumentException("null key");
		}
		int i = findOrCreateIndex(key);
		implicitPointsToSets.set(i, null);
	}

	public void put(PointerKey key, IPAPointsToSetVariable v) {
		int i = findOrCreateIndex(key); 
		pointsToSets.set(i, v);
	}
	
	/**
	 * remove mapping from here...
	 * @param key
	 */
	public void remove(PointerKey key) {
		int i = findOrCreateIndex(key);
		pointsToSets.set(i, null);
	}

	private int findOrCreateIndex(PointerKey key) {
		int result = pointerKeys.getMappedIndex(key);
		if (result == -1) {
//		Integer result = pointerKeys.get(key);
//		if (result == null) {
			result = pointerKeys.add(key);
//			result = nextIndex;
//			pointerKeys.put(key, result);
//			key.setIdx(result);
//			nextIndex ++;
		}
		return result;
	}

	/**
	 * record points-to-sets that are "roots" of the transitive closure. These points-to-sets can't be thrown away for a
	 * pre-transitive solver. A "root" is a points-to-set whose contents do not result from flow from other points-to-sets; there
	 * points-to-sets are the primordial assignments from which the transitive closure flows.
	 */
	public void recordTransitiveRoot(PointerKey key) {
		if (key == null) {
			throw new IllegalArgumentException("null key");
		}
		int i = findOrCreateIndex(key);
		transitiveRoots.set(i);
	}

	/**
	 * A "root" is a points-to-set whose contents do not result from flow from other points-to-sets; there points-to-sets are the
	 * primordial assignments from which the transitive closure flows.
	 */
	boolean isTransitiveRoot(PointerKey key) {
		int i = findOrCreateIndex(key);
		return transitiveRoots.get(i);
	}


	protected int getNumberOfPointerKeys() {
		return pointerKeys.getSize();
//		return pointerKeys.size();
	}


	/**
	 * @return {@link Iterator}&lt;{@link PointerKey}&gt;
	 */
	public Iterator<PointerKey> getTransitiveRoots() {
		return new FilterIterator<PointerKey>(iterateKeys(), new Predicate() {
			@Override public boolean test(Object o) {
				return isTransitiveRoot((PointerKey) o);
			}
		});
	}


	private void upgradeToFilter(IPAPointsToSetVariable p, IPAFilteredPointerKey.IPATypeFilter typeFilter) {
		if (p.getPointerKey() instanceof LocalPointerKey) {
			LocalPointerKey lpk = (LocalPointerKey) p.getPointerKey();
			IPALocalPointerKeyWithFilter f = new IPALocalPointerKeyWithFilter(lpk.getNode(), lpk.getValueNumber(), typeFilter);
			p.setPointerKey(f);
			pointerKeys.replace(lpk, f);
//			int id = pointerKeys.get(lpk);
//			pointerKeys.remove(lpk); 
//			pointerKeys.put(f, id);
//			f.setIdx(id);
		} else if (p.getPointerKey() instanceof ReturnValueKey) {
			ReturnValueKey r = (ReturnValueKey) p.getPointerKey();
			IPAReturnValueKeyWithFilter f = new IPAReturnValueKeyWithFilter(r.getNode(), typeFilter);
			p.setPointerKey(f);
			pointerKeys.replace(r, f);
//			int id = pointerKeys.get(r);
//			pointerKeys.remove(r); 
//			pointerKeys.put(f, id);
//			f.setIdx(id);
		} else {
			Assertions.UNREACHABLE(p.getPointerKey().getClass().toString());
		}
	}

	/**
	 * @return the unique integer that identifies this pointer key
	 */
	public int getIndex(PointerKey p) {
		return pointerKeys.getMappedIndex(p);
//		Integer id = pointerKeys.get(p);
//		return id == null? -1 : id;
//		return p.getIdx();
	}

}
