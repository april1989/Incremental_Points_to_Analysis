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

import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableIntSet;
//import com.ibm.wala.util.intset.MutableSharedBitVectorIntSet;
//import com.ibm.wala.util.intset.MutableSharedBitVectorIntSetFactory;

import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPointsToSetVariable;
import edu.tamu.wala.increpta.util.intset.IPAMutableSharedBitVectorIntSet;

public class DeletionUtil {

	/**
	 * bz: remove the elements in set from v
	 * @param set
	 * @return
	 */
	public static IPAMutableSharedBitVectorIntSet removeSome(IPAPointsToSetVariable v, IntSet set){
		IPAMutableSharedBitVectorIntSet V = (IPAMutableSharedBitVectorIntSet) v.getValue();
		IPAMutableSharedBitVectorIntSet intersection = (IPAMutableSharedBitVectorIntSet) V.intersection(set);
		if(intersection.size() > 0) {
			V.removeAll(intersection);
			v.setChange(intersection);
		}else {
			v.clearChange();
		}
		return intersection;
	}

	/**
	 * remove set from V: updated -> faster
	 * @param V
	 * @param set
	 */
	public static boolean removeSome(IPAMutableSharedBitVectorIntSet V, IntSet s) {
		IPAMutableSharedBitVectorIntSet set = (IPAMutableSharedBitVectorIntSet) s;
		if(V != null && set != null){
			return V.removeAllInIntersectionInternal(set);
		}
		return false;
	}
}
