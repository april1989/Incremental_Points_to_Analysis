package edu.tamu.wala.increpta.util.intset;

import com.ibm.wala.util.intset.BitVectorIntSet;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableSparseIntSet;

@SuppressWarnings("serial")
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

	/*
	 * @see
	 * com.ibm.wala.util.intset.MutableIntSet#addAllInIntersection(com.ibm.wala
	 * .util.intset.IntSet, com.ibm.wala.util.intset.IntSet)
	 */
	@Override
	public boolean addAllInIntersection(IntSet other, IntSet filter) {
		if (other == null) {
			throw new IllegalArgumentException("other is null");
		}
		if (filter == null) {
			throw new IllegalArgumentException("invalid filter");
		}
		// a hack. TODO: better algorithm
		if (other.size() < 5) {
			boolean result = false;
			for (IntIterator it = other.intIterator(); it.hasNext();) {
				int i = it.next();
				if (filter.contains(i)) {
					result |= add(i);
//					IPAMutableSharedBitVectorIntSet.diff.add(i);
				}
			}
			return result;
		} else if (filter.size() < 5) {
			boolean result = false;
			for (IntIterator it = filter.intIterator(); it.hasNext();) {
				int i = it.next();
				if (other.contains(i)) {
					result |= add(i);
//					IPAMutableSharedBitVectorIntSet.diff.add(i);
				}
			}
			return result;
		} else {
			BitVectorIntSet o = new BitVectorIntSet(other);
			o.intersectWith(filter);
//			IPAMutableSharedBitVectorIntSet.diff.sharedPart = o;
			return addAll(o);
		}
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
