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
import com.ibm.wala.util.intset.MutableSharedBitVectorIntSet;
import com.ibm.wala.util.intset.MutableSharedBitVectorIntSetFactory;

import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPointsToSetVariable;

public class DeletionUtil {

	/**
	 * bz: remove the elements in set from v
	 * @param set
	 * @return
	 */
	public static MutableSharedBitVectorIntSet removeSome(IPAPointsToSetVariable v, IntSet set){
		MutableSharedBitVectorIntSet removed = new MutableSharedBitVectorIntSetFactory().make();
		MutableIntSet V = v.getValue();
		if(V != null && set != null){
			IntIterator iterator = set.intIterator();
			while(iterator.hasNext()){
				Integer del = iterator.next();
				if(V.remove(del)){
					removed.add(del);
					if(removed.size() == set.size())
						break;
				}
			}
		}
		//bz: update propagated changes;
		if(removed.size() > 0){
			v.setChange(removed);
		}else{
			v.clearChange();
		}
		return removed;
	}

	/**
	 * remove set from V
	 * @param V
	 * @param set
	 */
	public static void removeSome(MutableSharedBitVectorIntSet V, IntSet set) {
		if(V != null && set != null){
			IntIterator iterator = set.intIterator();
			while(iterator.hasNext()){
				Integer del = iterator.next();
				V.remove(del);
				if(V.size() == 0)
					break;
			}
		}
	}
}
