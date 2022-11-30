package edu.tamu.wala.increpta.util.intset;

import java.util.TreeSet;

import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableIntSetFactory;
import com.ibm.wala.util.intset.SparseIntSet;

public class IPAMutableSparseIntSetFactory implements MutableIntSetFactory<IPAMutableSparseIntSet>{

	/**
	 * @throws IllegalArgumentException  if set is null
	 */
	@Override
	public IPAMutableSparseIntSet make(int[] set) {
		if (set == null) {
			throw new IllegalArgumentException("set is null");
		}
		if (set.length == 0) {
			return IPAMutableSparseIntSet.makeEmpty();
		} else {
			// XXX not very efficient.
			TreeSet<Integer> T = new TreeSet<>();
			for (int element : set) {
				T.add(element);
			}
			int[] copy = new int[T.size()];
			int i = 0;
			for (Integer I : T) {
				copy[i++] = I.intValue();
			}
			IPAMutableSparseIntSet result = new IPAMutableSparseIntSet(copy);
			return result;
		}
	}

	@Override
	public IPAMutableSparseIntSet parse(String string) throws NumberFormatException {
		int[] backingStore = SparseIntSet.parseIntArray(string);
		return new IPAMutableSparseIntSet(backingStore);
	}

	/*
	 * @see com.ibm.wala.util.intset.MutableIntSetFactory#make(com.ibm.wala.util.intset.IntSet)
	 */
	@Override
	public IPAMutableSparseIntSet makeCopy(IntSet x) throws IllegalArgumentException {
		if (x == null) {
			throw new IllegalArgumentException("x == null");
		}
		return IPAMutableSparseIntSet.make(x);
	}

	/*
	 * @see com.ibm.wala.util.intset.MutableIntSetFactory#make()
	 */
	@Override
	public IPAMutableSparseIntSet make() {
		return IPAMutableSparseIntSet.makeEmpty();
	}

}
