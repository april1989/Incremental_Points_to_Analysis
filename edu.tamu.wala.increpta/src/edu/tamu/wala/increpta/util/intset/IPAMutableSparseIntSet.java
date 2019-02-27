package edu.tamu.wala.increpta.util.intset;

import com.ibm.wala.util.intset.BitVectorIntSet;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableSparseIntSet;

public class IPAMutableSparseIntSet extends MutableSparseIntSet{


	protected IPAMutableSparseIntSet(IntSet set) {
		super();
		copySet(set);
	}

	protected IPAMutableSparseIntSet() {
		super();
	}

	protected IPAMutableSparseIntSet(int[] backingStore) {
		super(backingStore);
	}
	
	/**
	 * Create an empty set with a non-zero capacity
	 */
	private IPAMutableSparseIntSet(int initialCapacity)
			throws IllegalArgumentException {
		super(new int[initialCapacity]);
		size = 0;
		if (initialCapacity <= 0) {
			throw new IllegalArgumentException(
					"initialCapacity must be positive");
		}
	}

	public static IPAMutableSparseIntSet make(IntSet set) {
		return new IPAMutableSparseIntSet(set);
	}

	public static IPAMutableSparseIntSet makeEmpty() {
		return new IPAMutableSparseIntSet();
	}
	
	public static IPAMutableSparseIntSet createMutableSparseIntSet(
			int initialCapacity) throws IllegalArgumentException {
		if (initialCapacity < 0) {
			throw new IllegalArgumentException("illegal initialCapacity: "
					+ initialCapacity);
		}
		return new IPAMutableSparseIntSet(initialCapacity);
	}


	public boolean removeAllInIntersection(IntSet other) {
		if (other == null) {
			throw new IllegalArgumentException("other is null");
		}
		// inherent a hack. TODO: better algorithm
		if (other.size() < 5) {
			boolean result = false;
			for (IntIterator it = other.intIterator(); it.hasNext();) {
				int i = it.next();
				if (contains(i)) {
					result |= remove(i);
				}
			}
			return result;
		} else {
			BitVectorIntSet o = new BitVectorIntSet(other);
			o.intersectWith(this);
			int oldsize = this.size();
			removeAll(o);
			if(oldsize != this.size()) {
				return true;
			}else {
				return false;
			}
		}
	}
}
