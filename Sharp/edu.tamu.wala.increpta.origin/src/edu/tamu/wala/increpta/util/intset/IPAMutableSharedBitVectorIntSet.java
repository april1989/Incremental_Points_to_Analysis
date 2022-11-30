package edu.tamu.wala.increpta.util.intset;

//import com.ibm.wala.util.collections.CompoundIntIterator;
import com.ibm.wala.util.collections.EmptyIntIterator;
import com.ibm.wala.util.debug.Assertions;
import com.ibm.wala.util.debug.UnimplementedError;
import com.ibm.wala.util.intset.BimodalMutableIntSet;
import com.ibm.wala.util.intset.BitVectorIntSet;
import com.ibm.wala.util.intset.BitVectorRepository;
//import com.ibm.wala.util.intset.DebuggingMutableIntSet;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetAction;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.MutableSharedBitVectorIntSet;
import com.ibm.wala.util.intset.SemiSparseMutableIntSet;
//import com.ibm.wala.util.intset.MutableSparseIntSet;
//import com.ibm.wala.util.intset.MutableSparseIntSetFactory;
import com.ibm.wala.util.intset.SparseIntSet;

public class IPAMutableSharedBitVectorIntSet implements MutableIntSet {

	private static final long serialVersionUID = -6630888692508092370L;

	private final static boolean DEBUG = false;

	private final static int OVERFLOW = 20;//same with super

	private IPAMutableSparseIntSet privatePart;

	public BitVectorIntSet sharedPart;


	public IPAMutableSharedBitVectorIntSet() {
	}


	public IPAMutableSharedBitVectorIntSet(MutableSharedBitVectorIntSet set) {
		if (set == null) {
			throw new IllegalArgumentException("set is null");
		}
		set.foreach(new IntSetAction() {
			@Override
			public void act(int x) {
				add(x);
			}
		});
//		if (set.privatePart != null) {
//			this.privatePart = IPAMutableSparseIntSet.make(set.privatePart);
//		}
//		this.sharedPart = set.sharedPart;
	}

	/**
	 * @throws IllegalArgumentException if set is null
	 */
	public IPAMutableSharedBitVectorIntSet(IPAMutableSharedBitVectorIntSet set) {
		if (set == null) {
			throw new IllegalArgumentException("set is null");
		}
		if (set.privatePart != null) {
			this.privatePart = IPAMutableSparseIntSet.make(set.privatePart);
		}
		this.sharedPart = set.sharedPart;
	}

	/**
	 * @throws IllegalArgumentException if s is null
	 */
	public IPAMutableSharedBitVectorIntSet(SparseIntSet s) {
		if (s == null) {
			throw new IllegalArgumentException("s is null");
		}
		if (s.size() == 0) {
			return;
		}
		this.privatePart = IPAMutableSparseIntSet.make(s);
		checkOverflow();
	}

	/**
	 * @throws IllegalArgumentException if s is null
	 */
	public IPAMutableSharedBitVectorIntSet(BitVectorIntSet s) {
		if (s == null) {
			throw new IllegalArgumentException("s is null");
		}
		copyValue(s);
	}


	private void copyValue(BitVectorIntSet s) {
		if (s.size() == 0) {
			sharedPart = null;
			privatePart = null;
		} else if (s.size() < OVERFLOW) {
			sharedPart = null;
			privatePart = IPAMutableSparseIntSet.make(s);
		} else {
			sharedPart = BitVectorRepository.findOrCreateSharedSubset(s);
			if (sharedPart.size() == s.size()) {
				privatePart = null;
			} else {
				BitVectorIntSet temp = new BitVectorIntSet(s);
				temp.removeAll(sharedPart);
				if (!temp.isEmpty()) {
					privatePart = IPAMutableSparseIntSet.make(temp);
				} else {
					privatePart = null;
				}
			}
		}
	}

	@Override
	public boolean contains(int i) {
		if (privatePart != null && privatePart.contains(i)) {
			return true;
		}
		if (sharedPart != null && sharedPart.contains(i)) {
			return true;
		}
		return false;
	}

	@Override
	public IntSet union(IntSet that) {
		IPAMutableSharedBitVectorIntSet temp = new IPAMutableSharedBitVectorIntSet();
		temp.addAll(this);
		temp.addAll(that);
		return temp;
	}

	@Override
	public boolean isEmpty() {
		return privatePart == null && sharedPart == null;
	}

	/*
	 * @see com.ibm.wala.util.intset.IntSet#size()
	 */
	@Override
	public int size() {
		int result = 0;
		result += (privatePart == null) ? 0 : privatePart.size();
		result += (sharedPart == null) ? 0 : sharedPart.size();
		return result;
	}

	@Override
	public IntIterator intIterator() {
		if (privatePart == null) {
			return (sharedPart == null) ? EmptyIntIterator.instance() : sharedPart.intIterator();
		} else {
			return (sharedPart == null) ? privatePart.intIterator() : new IPACompoundIntIterator(privatePart.intIterator(), sharedPart
					.intIterator(), privatePart.size(), sharedPart.size());
		}
	}

	/*
	 * @see com.ibm.wala.util.intset.IntSet#foreach(com.ibm.wala.util.intset.IntSetAction)
	 */
	@Override
	public void foreach(IntSetAction action) {
		if (privatePart != null) {
			privatePart.foreach(action);
		}
		if (sharedPart != null) {
			sharedPart.foreach(action);
		}
	}

	/*
	 * @see com.ibm.wala.util.intset.IntSet#foreachExcluding(com.ibm.wala.util.intset.IntSet, com.ibm.wala.util.intset.IntSetAction)
	 */
	@Override
	public void foreachExcluding(IntSet X, IntSetAction action) {
		if (X instanceof IPAMutableSharedBitVectorIntSet) {
			foreachExcludingInternal((IPAMutableSharedBitVectorIntSet) X, action);
		} else {
			foreachExcludingGeneral(X, action);
		}
	}

	/*
	 * @see com.ibm.wala.util.intset.IntSet#foreachExcluding(com.ibm.wala.util.intset.IntSet, com.ibm.wala.util.intset.IntSetAction)
	 */
	private void foreachExcludingInternal(IPAMutableSharedBitVectorIntSet X, IntSetAction action) {
		if (sameSharedPart(this, X)) {
			if (privatePart != null) {
				if (X.privatePart != null) {
					privatePart.foreachExcluding(X.privatePart, action);
				} else {
					privatePart.foreach(action);
				}
			}
		} else {
			if (privatePart != null) {
				privatePart.foreachExcluding(X, action);
			}
			if (sharedPart != null) {
				sharedPart.foreachExcluding(X.makeDenseCopy(), action);
			}
		}
	}

	/*
	 * @see com.ibm.wala.util.intset.IntSet#foreachExcluding(com.ibm.wala.util.intset.IntSet, com.ibm.wala.util.intset.IntSetAction)
	 */
	private void foreachExcludingGeneral(IntSet X, IntSetAction action) {
		if (privatePart != null) {
			privatePart.foreachExcluding(X, action);
		}
		if (sharedPart != null) {
			sharedPart.foreachExcluding(X, action);
		}
	}

	/*
	 * @see com.ibm.wala.util.intset.IntSet#max()
	 */
	@Override
	public int max() {
		int result = -1;
		if (privatePart != null && privatePart.size() > 0) {
			result = Math.max(result, privatePart.max());
		}
		if (sharedPart != null) {
			result = Math.max(result, sharedPart.max());
		}
		return result;
	}

	/*
	 * @see com.ibm.wala.util.intset.IntSet#sameValue(com.ibm.wala.util.intset.IntSet)
	 */
	@Override
	public boolean sameValue(IntSet that) throws IllegalArgumentException, UnimplementedError {
		if (that == null) {
			throw new IllegalArgumentException("that == null");
		}
		if (that instanceof IPAMutableSharedBitVectorIntSet) {
			return sameValue((IPAMutableSharedBitVectorIntSet) that);
		}else if (that instanceof MutableSharedBitVectorIntSet) {
			return sameValue((MutableSharedBitVectorIntSet) that);
		} else if (that instanceof SparseIntSet) {
			return sameValue((SparseIntSet) that);
		} else if (that instanceof BimodalMutableIntSet) {
			return that.sameValue(makeSparseCopy());
		} else if (that instanceof BitVectorIntSet) {
			return sameValue((BitVectorIntSet) that);
		} else if (that instanceof SemiSparseMutableIntSet) {
			return that.sameValue(this);
		} else {
			Assertions.UNREACHABLE("unexpected class " + that.getClass());
			return false;
		}
	}

	private boolean sameValue(SparseIntSet that) {
		if (size() != that.size()) {
			return false;
		}
		if (sharedPart == null) {
			if (privatePart == null)
				/* both parts empty, and that has same (i.e. 0) size */
				return true;
			else
				return privatePart.sameValue(that);
		} else {
			/* sharedPart != null */
			return makeSparseCopy().sameValue(that);
		}
	}

	private boolean sameValue(BitVectorIntSet that) {
		if (size() != that.size()) {
			return false;
		}
		if (sharedPart == null) {
			if (privatePart == null)
				/* both parts empty, and that has same (i.e. 0) size */
				return true;
			else
				// shared part is null and size is same, so number of bits is low
				return that.makeSparseCopy().sameValue(privatePart);
		} else {
			if (privatePart == null)
				return sharedPart.sameValue(that);
			else
				/* sharedPart != null */
				return makeDenseCopy().sameValue(that);
		}
	}

	/*
	 * @see com.ibm.wala.util.intset.IntSet#sameValue(com.ibm.wala.util.intset.IntSet)
	 */
//	private boolean sameValue(MutableSharedBitVectorIntSet that) {
//		if (size() != that.size()) {
//			return false;
//		}
//		if (sharedPart == null) {
//			if (privatePart == null) {
//				/* we must have size() == that.size() == 0 */
//				return true;
//			} else {
//				/* sharedPart == null, privatePart != null */
//				if (that.sharedPart == null) {
//					if (that.privatePart == null) {
//						return privatePart.isEmpty();
//					} else {
//						return privatePart.sameValue(that.privatePart);
//					}
//				} else {
//					/* sharedPart = null, privatePart != null, that.sharedPart != null */
//					if (that.privatePart == null) {
//						return privatePart.sameValue(that.sharedPart);
//					} else {
//						BitVectorIntSet temp = new BitVectorIntSet(that.sharedPart);
//						temp.addAllOblivious(that.privatePart);
//						return privatePart.sameValue(temp);
//					}
//				}
//			}
//		} else {
//			/* sharedPart != null */
//			if (privatePart == null) {
//				if (that.privatePart == null) {
//					if (that.sharedPart == null) {
//						return false;
//					}else {
//						return sharedPart.sameValue(that.sharedPart);
//					}
//				} else {
//					/* privatePart == null, sharedPart != null, that.privatePart != null */
//					if (that.sharedPart == null) {
//						return sharedPart.sameValue(that.privatePart);
//					} else {
//						IPAMutableSparseIntSet t = that.makeSparseCopy();
//						return sharedPart.sameValue(t);
//					}
//				}
//			} else {
//				/* sharedPart != null , privatePart != null */
//				if (that.sharedPart == null) {
//					//		          Assertions.UNREACHABLE();
//					/* sharedPart != null , privatePart != null that.sharedPart == null*/
//					if(that.privatePart == null) {
//						/* sharedPart != null , privatePart != null that.privatePart == null that.sharedPart == null*/
//						return false;
//					}else {
//						/* sharedPart != null , privatePart != null that.privatePart != null that.sharedPart == null*/
//						SparseIntSet s1 = makeSparseCopy();
//						return s1.sameValue(that.privatePart);
//					}
//				} else {
//					/* that.sharedPart != null */
//					if (that.privatePart == null) {
//						SparseIntSet s = makeSparseCopy();
//						return s.sameValue(that.sharedPart);
//					} else {
//						/* that.sharedPart != null, that.privatePart != null */
//						/* assume reference equality for canonical shared part */
//						if (sharedPart == that.sharedPart) {
//							return privatePart.sameValue(that.privatePart);
//						} else {
//							SparseIntSet s1 = makeSparseCopy();
//							SparseIntSet s2 = that.makeSparseCopy();
//							return s1.sameValue(s2);
//						}
//					}
//				}
//			}
//		}
//	}

	/*
	 * @see com.ibm.wala.util.intset.IntSet#isSubset(com.ibm.wala.util.intset.IntSet)
	 */
	@Override
	public boolean isSubset(IntSet that) {
		if (that == null) {
			throw new IllegalArgumentException("null that");
		}
		if (that instanceof IPAMutableSharedBitVectorIntSet) {
			return isSubset((IPAMutableSharedBitVectorIntSet) that);
		} else {
			// really slow. optimize as needed.
			for (IntIterator it = intIterator(); it.hasNext();) {
				if (!that.contains(it.next())) {
					return false;
				}
			}
			return true;
		}
	}

	private boolean isSubset(IPAMutableSharedBitVectorIntSet that) {
		if (size() > that.size()) {
			return false;
		}
		if (sharedPart == null) {
			if (privatePart == null) {
				return true;
			} else {
				if (that.sharedPart == null) {
					return privatePart.isSubset(that.privatePart);
				} else {
					/* sharedPart == null, that.sharedPart != null */
					if (that.privatePart == null) {
						return privatePart.isSubset(that.sharedPart);
					} else {
						SparseIntSet s1 = that.makeSparseCopy();
						return privatePart.isSubset(s1);
					}
				}
			}
		} else {
			/* sharedPart != null */
			if (privatePart == null) {
				/* sharedPart != null, privatePart == null */
				if (that.privatePart == null) {
					if (that.sharedPart == null) {
						return false;
					} else {
						return sharedPart.isSubset(that.sharedPart);
					}
				} else {
					if (that.sharedPart == null) {
						return sharedPart.isSubset(that.privatePart);
					} else {
						SparseIntSet s1 = that.makeSparseCopy();
						return sharedPart.isSubset(s1);
					}
				}
			} else {
				/* sharedPart != null, privatePart != null */
				if (that.privatePart == null) {
					return privatePart.isSubset(that.sharedPart) && sharedPart.isSubset(that.sharedPart);
				} else {
					/* sharedPart != null, privatePart!= null, that.privatePart != null */
					if (that.sharedPart == null) {
						return privatePart.isSubset(that.privatePart) && sharedPart.isSubset(that.privatePart);
					} else {
						/*
						 * sharedPart != null, privatePart!= null, that.privatePart != null, that.sharedPart != null
						 */
						if (sharedPart.isSubset(that.sharedPart)) {
							if (privatePart.isSubset(that.privatePart)) {
								return true;
							} else {
								SparseIntSet s1 = that.makeSparseCopy();
								return privatePart.isSubset(s1);
							}
						} else {
							/* !sharedPart.isSubset(that.sharedPart) */
							BitVectorIntSet temp = new BitVectorIntSet(sharedPart);
							temp.removeAll(that.sharedPart);
							if (temp.isSubset(that.privatePart)) {
								/* sharedPart.isSubset(that) */
								if (privatePart.isSubset(that.privatePart)) {
									return true;
								} else {
									IPAMutableSparseIntSet t = IPAMutableSparseIntSet.make(privatePart);
									t.removeAll(that.privatePart);
									if (t.isSubset(that.sharedPart)) {
										return true;
									} else {
										return false;
									}
								}
							} else {
								/*
								 * !((sharedPart-that.sharedPart).isSubset(that.privatePart)) i.e some bit in my shared part is in neither that's
								 * sharedPart nor that's privatePart, hence I am not a subset of that
								 */
								return false;
							}
						}
					}
				}
			}
		}
	}

	/*
	 * @see com.ibm.wala.util.intset.MutableIntSet#copySet(com.ibm.wala.util.intset.IntSet)
	 */
	@Override
	public void copySet(IntSet set) {
		if (set instanceof IPAMutableSharedBitVectorIntSet) {
			IPAMutableSharedBitVectorIntSet other = (IPAMutableSharedBitVectorIntSet) set;
			if (other.privatePart != null) {
				this.privatePart = IPAMutableSparseIntSet.make(other.privatePart);
			} else {
				this.privatePart = null;
			}
			this.sharedPart = other.sharedPart;
		} else {
			// really slow. optimize as needed.
			clear();
			addAll(set);
		}
	}



	/**
	 * Warning: inefficient; this should not be called often.
	 */
	private IPAMutableSparseIntSet makeSparseCopy() {
		if (privatePart == null) {
			if (sharedPart == null) {
				return IPAMutableSparseIntSet.makeEmpty();
			} else {
				return new IPAMutableSparseIntSetFactory().makeCopy(sharedPart);
			}
		} else {
			if (sharedPart == null) {
				return IPAMutableSparseIntSet.make(privatePart);
			} else {
				/* privatePart != null, sharedPart != null */
				IPAMutableSparseIntSet result = IPAMutableSparseIntSet.make(privatePart);
				result.addAll(sharedPart);
				return result;
			}
		}
	}

	/**
	 * hard copy with type BitVectorIntSet
	 */
	BitVectorIntSet makeDenseCopy() {
		if (privatePart == null) {
			if (sharedPart == null) {
				return new BitVectorIntSet();
			} else {
				return new BitVectorIntSet(sharedPart);
			}
		} else {
			if (sharedPart == null) {
				return new BitVectorIntSet(privatePart);
			} else {
				BitVectorIntSet temp = new BitVectorIntSet(sharedPart);
				temp.addAllOblivious(privatePart);
				return temp;
			}
		}
	}


	/**
	 * only for test; slow
	 */
	public IPAMutableSharedBitVectorIntSet makeHardCopy() {
		IPAMutableSharedBitVectorIntSet set = new IPAMutableSharedBitVectorIntSet();
		if(privatePart != null) {
			IntIterator iter = privatePart.intIterator();
			while(iter.hasNext()) {
				int ins = iter.next();
				set.add(ins);
			}
		}
		if(sharedPart != null) {
			IntIterator iter = sharedPart.intIterator();
			while(iter.hasNext()) {
				int ins = iter.next();
				set.add(ins);
			}
		}
		return set;
	}

	public boolean removeAll(IntSet other) {
		if(other instanceof IPAMutableSharedBitVectorIntSet) {
			boolean result = removeAll((IPAMutableSharedBitVectorIntSet) other);
			return result;
		}else if(other instanceof SparseIntSet) {
			boolean result = removeAllInternal((SparseIntSet) other);
			return result;
		}
		return false;
	}

	private boolean removeAllInIntersectionInternal(SparseIntSet other) {
		if (sharedPart == null) {
			if (privatePart == null) {
				/** sharedPart == null, privatePart == null */
				return false;
			} else {
				/** sharedPart == null, privatePart != null */
				return privatePart.removeAllInIntersection(other);
			}
		} else {
			/** sharedPart != null */
			if (privatePart == null) {
				privatePart = IPAMutableSparseIntSet.make(sharedPart);
				sharedPart = null;
				boolean result = privatePart.removeAllInIntersection(other);
				checkOverflow();
				return result;
			} else {
				/** sharedPart != null, privatePart != null */
				// note that "other" is likely small
				IPAMutableSparseIntSet temp = IPAMutableSparseIntSet.make(other);
				temp.intersectWith(this);
				return removeAll(temp);
			}
		}
	}

	public boolean removeAllInIntersectionInternal(IPAMutableSharedBitVectorIntSet other) {
		if (other.sharedPart == null) {
			if (other.privatePart == null) {
				return false;
			} else {
				// other.sharedPart == null, other.privatePart != null
				return removeAllInIntersectionInternal(other.privatePart);
			}
		} else {
			// other.sharedPart != null
			if (sharedPart == other.sharedPart) {
				sharedPart = null;
				// no need to add in other.sharedPart
				if (other.privatePart == null) {
					return true;
				} else {
					return removeAllInIntersectionInternal(other.privatePart);
				}
			} else {
				IPAMutableSharedBitVectorIntSet o = new IPAMutableSharedBitVectorIntSet(other);
				o.intersectWith(this);
				return removeAll(o);
			}
		}
	}


	private boolean removeAll(IPAMutableSharedBitVectorIntSet set) {
		if (set.isEmpty()) {
			return false;
		}
		if (isEmpty()) {
			return false;
		}

		if (set.sharedPart == null) {
			/** set.sharedPart == null  set.privatePart != null */
			return removeAllInternal(set.privatePart);
		} else {
			/** set.sharedPart != null */
			if (sameSharedPart(this, set)) {
				sharedPart = null;
				if (set.privatePart == null) {
					/** set.sharedPart != null  set.privatePart == null */
					return true;
				} else {
					/** set.sharedPart != null  set.privatePart != null */
					return removeAllInternal(set.privatePart);
				}
			} else {
				/** set.sharedPart != null   !sameSharedPart */
				if (set.privatePart == null) {
					if (sharedPart == null) {
						// a heuristic that should be profitable if this condition usually holds.
						sharedPart = null;
						int oldSize = size();
						if (privatePart != null) {
							privatePart.removeAll(set.sharedPart);
							privatePart = privatePart.isEmpty() ? null : privatePart;
						}
						return size() < oldSize;
					} else if(sharedPart.isSubset(set.sharedPart)) {
						sharedPart = null;
						return true;
					} else {
						BitVectorIntSet temp = makeDenseCopy();
						return temp.removeAll(set.sharedPart);//special ...
					}
				} else {
					/**  set.privatePart != null; */
					BitVectorIntSet temp = makeDenseCopy();
					BitVectorIntSet other = set.makeDenseCopy();
					return temp.removeAll(other);//special ...
				}
			}
		}
	}


	private boolean removeAllInternal(SparseIntSet set) {
		if (privatePart == null) {
			if (sharedPart == null) {
				return false;
			} else {
				/** sharedPart != null   privatePart == null*/
				privatePart = IPAMutableSparseIntSet.makeEmpty();
				privatePart.addAll(sharedPart);
				sharedPart = null;
				privatePart.removeAll((IPAMutableSparseIntSet) set);
				if (privatePart.isEmpty()) {
					privatePart = null;
					return true;
				} else {
					checkOverflow();
					return true;
				}
			}
		} else { 
			/** privatePart != null */
			if (sharedPart == null) {
				int oldsize = privatePart.size();
				privatePart.removeAll((IPAMutableSparseIntSet) set);
				return oldsize > privatePart.size();
			} else {
				/** sharedPart != null   privatePart != null*/
				privatePart.addAll(sharedPart);
				sharedPart = null;
				int oldSize = privatePart.size();
				privatePart.removeAll((IPAMutableSparseIntSet) set);
				boolean result = privatePart.size() < oldSize;
				checkOverflow();
				return result;
			}
		}
	}


	@Override
	public boolean remove(int i) {
		if (privatePart != null) {
			if (privatePart.contains(i)) {
				privatePart.remove(i);
				if (privatePart.size() == 0) {
					privatePart = null;
				}
				return true;
			}
		}
		if (sharedPart != null) {
			if (sharedPart.contains(i)) {
				privatePart = makeSparseCopy();
				privatePart.remove(i);
				if (privatePart.size() == 0) {
					privatePart = null;
				}
				sharedPart = null;
				checkOverflow();
				return true;
			}
		}
		return false;
	}

	/*
	 * @see com.ibm.wala.util.intset.IntSet#sameValue(com.ibm.wala.util.intset.IntSet)
	 */
	private boolean sameValue(IPAMutableSharedBitVectorIntSet that) {
		if (size() != that.size()) {
			return false;
		}
		if (sharedPart == null) {
			if (privatePart == null) {
				/* we must have size() == that.size() == 0 */
				return true;
			} else {
				/* sharedPart == null, privatePart != null */
				if (that.sharedPart == null) {
					if (that.privatePart == null) {
						return privatePart.isEmpty();
					} else {
						return privatePart.sameValue(that.privatePart);
					}
				} else {
					/* sharedPart = null, privatePart != null, that.sharedPart != null */
					if (that.privatePart == null) {
						return privatePart.sameValue(that.sharedPart);
					} else {
						BitVectorIntSet temp = new BitVectorIntSet(that.sharedPart);
						temp.addAllOblivious(that.privatePart);
						return privatePart.sameValue(temp);
					}
				}
			}
		} else {
			/* sharedPart != null */
			if (privatePart == null) {
				if (that.privatePart == null) {
					if (that.sharedPart == null) {
						return false;
					}else {
						return sharedPart.sameValue(that.sharedPart);
					}
				} else {
					/* privatePart == null, sharedPart != null, that.privatePart != null */
					if (that.sharedPart == null) {
						return sharedPart.sameValue(that.privatePart);
					} else {
						IPAMutableSparseIntSet t = that.makeSparseCopy();
						return sharedPart.sameValue(t);
					}
				}
			} else {
				/* sharedPart != null , privatePart != null */
				if (that.sharedPart == null) {
					//	          Assertions.UNREACHABLE();
					/* sharedPart != null , privatePart != null that.sharedPart == null*/
					if(that.privatePart == null) {
						/* sharedPart != null , privatePart != null that.privatePart == null that.sharedPart == null*/
						return false;
					}else {
						/* sharedPart != null , privatePart != null that.privatePart != null that.sharedPart == null*/
						SparseIntSet s1 = makeSparseCopy();
						return s1.sameValue(that.privatePart);
					}
				} else {
					/* that.sharedPart != null */
					if (that.privatePart == null) {
						SparseIntSet s = makeSparseCopy();
						return s.sameValue(that.sharedPart);
					} else {
						/* that.sharedPart != null, that.privatePart != null */
						/* assume reference equality for canonical shared part */
						if (sharedPart == that.sharedPart) {
							return privatePart.sameValue(that.privatePart);
						} else {
							SparseIntSet s1 = makeSparseCopy();
							SparseIntSet s2 = that.makeSparseCopy();
							return s1.sameValue(s2);
						}
					}
				}
			}
		}
	}

	/**
	 * 
	 */
	private void checkOverflow() {
		if (privatePart != null && privatePart.size() > OVERFLOW) {
			if (sharedPart == null) {
				BitVectorIntSet temp = new BitVectorIntSet(privatePart);
				sharedPart = BitVectorRepository.findOrCreateSharedSubset(temp);
				temp.removeAll(sharedPart);
				if (!temp.isEmpty())
					privatePart = IPAMutableSparseIntSet.make(temp);
				else
					privatePart = null;
			} else {
				BitVectorIntSet temp = new BitVectorIntSet(sharedPart);
				// when we call findOrCreateSharedSubset, we will ask size() on temp.
				// so use addAll instead of addAllOblivious: which incrementally
				// updates the population count.
				temp.addAll(privatePart);
				sharedPart = BitVectorRepository.findOrCreateSharedSubset(temp);
				temp.removeAll(sharedPart);
				if (!temp.isEmpty())
					privatePart = IPAMutableSparseIntSet.make(temp);
				else
					privatePart = null;
			}
		}
	}

	public IntSet intersection(IPAMutableSharedBitVectorIntSet that) {
		IPAMutableSparseIntSet t = makeSparseCopy();
		t.intersectWith(that);
		return new IPAMutableSharedBitVectorIntSet(t);
	}

	@Override
	public IntSet intersection(IntSet that) {
		if (that == null) {
			throw new IllegalArgumentException("null that");
		}
		if (that instanceof IPAMutableSharedBitVectorIntSet) {
			return intersection((IPAMutableSharedBitVectorIntSet) that);
		} else if (that instanceof MutableSharedBitVectorIntSet) {
			return intersection((MutableSharedBitVectorIntSet) that);
		} else if (that instanceof BitVectorIntSet) {
			IPAMutableSharedBitVectorIntSet m = new IPAMutableSharedBitVectorIntSet((BitVectorIntSet) that);
			return intersection(m);
		} else if (that instanceof SparseIntSet) {
			BitVectorIntSet bv = new BitVectorIntSet(that);
			return intersection(bv);
		} else {
			// really slow. optimize as needed.
			BitVectorIntSet result = new BitVectorIntSet();
			for (IntIterator it = intIterator(); it.hasNext();) {
				int x = it.next();
				if (that.contains(x)) {
					result.add(x);
				}
			}
			return result;
		}
	}

	@Override
	public void intersectWith(IntSet set) {
		if (set instanceof IPAMutableSharedBitVectorIntSet) {
			intersectWithInternal((IPAMutableSharedBitVectorIntSet) set);
		} else if (set instanceof BitVectorIntSet) {
			intersectWithInternal(new IPAMutableSharedBitVectorIntSet((BitVectorIntSet) set));
		} else {
			// this is really slow. optimize as needed.
			for (IntIterator it = intIterator(); it.hasNext();) {
				int x = it.next();
				if (!set.contains(x)) {
					remove(x);
				}
			}
		}
		if (DEBUG) {
			if (privatePart != null && sharedPart != null)
				assert privatePart.intersection(sharedPart).isEmpty();
		}
	}

	private void intersectWithInternal(IPAMutableSharedBitVectorIntSet set) {

		if (sharedPart != null) {
			if (sameSharedPart(this, set)) {
				// no need to intersect shared part
				if (privatePart != null) {
					if (set.privatePart == null) {
						privatePart = null;
					} else {
						privatePart.intersectWith(set.privatePart);
						if (privatePart.isEmpty()) {
							privatePart = null;
						}
					}
				}
			} else {
				// not the same shared part
				if (set.sharedPart == null) {
					if (set.privatePart == null) {
						privatePart = null;
						sharedPart = null;
					} else {
						IPAMutableSparseIntSet temp = IPAMutableSparseIntSet.make(set.privatePart);
						temp.intersectWith(this);
						sharedPart = null;
						if (temp.isEmpty()) {
							privatePart = null;
						} else {
							privatePart = temp;
							checkOverflow();
						}
					}
				} else {
					// set.sharedPart != null
					BitVectorIntSet b = makeDenseCopy();
					b.intersectWith(set.makeDenseCopy());
					copyValue(b);
				}
			}
		} else {
			if (privatePart != null) {
				privatePart.intersectWith(set);
				if (privatePart.isEmpty()) {
					privatePart = null;
				}
			}
		}
	}

	public static boolean sameSharedPart(IPAMutableSharedBitVectorIntSet a, IPAMutableSharedBitVectorIntSet b) {
		if (b == null) {
			throw new IllegalArgumentException("b is null");
		}
		if (a == null) {
			throw new IllegalArgumentException("a is null");
		}
		return a.sharedPart == b.sharedPart;
	}

	@Override
	public String toString() {
		return makeSparseCopy().toString();
	}


	public boolean hasSharedPart() {
		return sharedPart != null;
	}


	@Override
	public boolean addAll(IntSet set) throws IllegalArgumentException {
		if (set == null) {
			throw new IllegalArgumentException("set == null");
		}
		if (set instanceof IPAMutableSharedBitVectorIntSet) {
			boolean result = addAll((IPAMutableSharedBitVectorIntSet) set);
			return result;
		} else if (set instanceof SparseIntSet) {
			boolean result = addAllInternal((SparseIntSet) set);
			return result;
		} else if (set instanceof BitVectorIntSet) {
			boolean result = addAllInternal((BitVectorIntSet) set);
			return result;
		} else {
			// really slow. optimize as needed.
			boolean result = false;
			for (IntIterator it = set.intIterator(); it.hasNext();) {
				int x = it.next();
				if (!contains(x)) {
					result = true;
					add(x);
				}
			}
			return result;
		}
	}

	private boolean addAllInternal(BitVectorIntSet set) {
		// should have hijacked this case before getting here!
		assert sharedPart != set;
		if (privatePart == null) {
			if (sharedPart == null) {
				copyValue(set);
				return !set.isEmpty();
			}
		}
		BitVectorIntSet temp = makeDenseCopy();
		boolean result = temp.addAll(set);
		copyValue(temp);
		return result;
	}

	private boolean addAllInternal(SparseIntSet set) {
		if (privatePart == null) {
			if (sharedPart == null) {
				if (!set.isEmpty()) {
					privatePart = IPAMutableSparseIntSet.make(set);
					sharedPart = null;
					checkOverflow();
					return true;
				} else {
					return false;
				}
			} else {
				privatePart = IPAMutableSparseIntSet.make(set);
				privatePart.removeAll(sharedPart);
				if (privatePart.isEmpty()) {
					privatePart = null;
					return false;
				} else {
					checkOverflow();
					return true;
				}
			}
		} else { /* privatePart != null */
			if (sharedPart == null) {
				boolean result = privatePart.addAll(set);
				checkOverflow();
				return result;
			} else {
				int oldSize = privatePart.size();
				privatePart.addAll(set);
				privatePart.removeAll(sharedPart);
				boolean result = privatePart.size() > oldSize;
				checkOverflow();
				return result;
			}
		}
	}

//	public static IPAMutableSharedBitVectorIntSet diff = new IPAMutableSharedBitVectorIntSet();
	
	private boolean addAll(IPAMutableSharedBitVectorIntSet set) {
//		diff.clear();//
		if (set.isEmpty()) {
			return false;
		}
		if (isEmpty()) {
			if (set.privatePart != null) {
				privatePart = IPAMutableSparseIntSet.make(set.privatePart);
			}
			sharedPart = set.sharedPart;
//			diff.copySet(set);//
			return true;
		}

		if (set.sharedPart == null) {
//			diff.privatePart = IPAMutableSparseIntSet.make(set.privatePart);//
			return addAllInternal(set.privatePart);
		} else {
			// set.sharedPart != null
			if (sameSharedPart(this, set)) {
//				diff.sharedPart = sharedPart;//
				if (set.privatePart == null) {
					return false;
				} else {
//					diff.privatePart = IPAMutableSparseIntSet.make(set.privatePart);//
					return addAllInternal(set.privatePart);
				}
			} else {
				// !sameSharedPart
				if (set.privatePart == null) {
					if (sharedPart == null || sharedPart.isSubset(set.sharedPart)) {
						// a heuristic that should be profitable if this condition usually
						// holds.
						int oldSize = size();
						if (privatePart != null) {
							privatePart.removeAll(set.sharedPart);
							privatePart = privatePart.isEmpty() ? null : privatePart;
//							if(privatePart != null)//
//								diff.privatePart = IPAMutableSparseIntSet.make(privatePart);//
						}
						////
						if(sharedPart == null) {
//							diff.sharedPart = set.sharedPart;
						}else {
							BitVectorIntSet copy = new BitVectorIntSet(set.sharedPart);
							copy.removeAll(sharedPart);
//							diff.sharedPart = copy;
						}
						////
						sharedPart = set.sharedPart; 
						return size() > oldSize;
					} else {
						BitVectorIntSet temp = makeDenseCopy();
						boolean b = temp.addAll(set.sharedPart);
						if (b) {
							// a heuristic: many times these are the same value,
							// so avoid looking up the shared subset in the bv repository
							if (temp.sameValue(set.sharedPart)) {
								this.privatePart = null;
								this.sharedPart = set.sharedPart;
							} else {
								copyValue(temp);
							}
//							temp.removeAll(set.sharedPart);//
//							diff.sharedPart = temp;//
						}
						return b;
					}
				} else {
					// set.privatePart != null;
					BitVectorIntSet temp = makeDenseCopy();
					BitVectorIntSet other = set.makeDenseCopy();
					boolean b = temp.addAll(other);
					if (b) {
						// a heuristic: many times these are the same value,
						// so avoid looking up the shared subset in the bv repository
						if (temp.sameValue(other)) {
							this.privatePart = IPAMutableSparseIntSet.make(set.privatePart);
							this.sharedPart = set.sharedPart;
						} else {
							// System.err.println("COPY " + this + " " + set);
							copyValue(temp);
						}
//						temp.removeAll(other);//
//						diff.sharedPart = temp;//
					}
					return b;
				}
			}
		}
	}

	/*
	 * @see com.ibm.wala.util.intset.MutableIntSet#add(int)
	 */
	@Override
	public boolean add(int i) {
		if (privatePart == null) {
			if (sharedPart == null) {
				privatePart = IPAMutableSparseIntSet.makeEmpty();
				privatePart.add(i);
				return true;
			} else {
				if (sharedPart.contains(i)) {
					return false;
				} else {
					privatePart = IPAMutableSparseIntSet.makeEmpty();
					privatePart.add(i);
					return true;
				}
			}
		} else {
			if (sharedPart == null) {
				boolean result = privatePart.add(i);
				checkOverflow();
				return result;
			} else {
				if (sharedPart.contains(i)) {
					return false;
				} else {
					boolean result = privatePart.add(i);
					checkOverflow();
					return result;
				}
			}
		}
	}


	@Override
	public boolean containsAny(IntSet set) {
		if (set instanceof IPAMutableSharedBitVectorIntSet) {
			IPAMutableSharedBitVectorIntSet other = (IPAMutableSharedBitVectorIntSet) set;
			if (sharedPart != null) {
				// an optimization to make life easier on the underlying
				// bitvectorintsets
				if (other.sharedPart != null && sharedPart.containsAny(other.sharedPart)) {
					return true;
				}
				if (other.privatePart != null && sharedPart.containsAny(other.privatePart)) {
					return true;
				}
			}
			if (privatePart != null && privatePart.containsAny(set)) {
				return true;
			}
			return false;
		} else {
			if (sharedPart != null && sharedPart.containsAny(set)) {
				return true;
			}
			if (privatePart != null && privatePart.containsAny(set)) {
				return true;
			}
			return false;
		}
	}

	/*
	 * @see com.ibm.wala.util.intset.MutableIntSet#addAllExcluding(com.ibm.wala.util.intset.IntSet, com.ibm.wala.util.intset.IntSet)
	 */
	@Override
	public boolean addAllInIntersection(IntSet other, IntSet filter) {
		if (other instanceof IPAMutableSharedBitVectorIntSet) {
			return addAllInIntersectionInternal((IPAMutableSharedBitVectorIntSet) other, filter);
		}
		return addAllInIntersectionGeneral(other, filter);
	}

	/**
	 */
	private boolean addAllInIntersectionGeneral(IntSet other, IntSet filter) {
		BitVectorIntSet o = new BitVectorIntSet(other);
		o.intersectWith(filter);
		return addAll(o);
	}

	/**
	 */
	private boolean addAllInIntersectionInternal(IPAMutableSharedBitVectorIntSet other, IntSet filter) {
//		diff.clear();//
		if (other.sharedPart == null) {
			if (other.privatePart == null) {
				return false;
			} else {
				// other.sharedPart == null, other.privatePart != null
				return addAllInIntersectionInternal(other.privatePart, filter);
			}
		} else {
			// other.sharedPart != null
			if (sharedPart == other.sharedPart) {
				// no need to add in other.sharedPart
				if (other.privatePart == null) {
					return false;
				} else {
					return addAllInIntersectionInternal(other.privatePart, filter);
				}
			} else {
				IPAMutableSharedBitVectorIntSet o = new IPAMutableSharedBitVectorIntSet(other);
				o.intersectWith(filter);
				return addAll(o);
			}
		}
	}

	private boolean addAllInIntersectionInternal(SparseIntSet other, IntSet filter) {
		if (sharedPart == null) {
			if (privatePart == null) {
				privatePart = IPAMutableSparseIntSet.make(other);
				privatePart.intersectWith(filter);
				if (privatePart.size() == 0) {
					privatePart = null;
				}else {
//					diff.privatePart = IPAMutableSparseIntSet.make(privatePart);
				}
				checkOverflow();
				return size() > 0;
			} else {
				/** sharedPart == null, privatePart != null */
				boolean result = privatePart.addAllInIntersection(other, filter);
				checkOverflow();
				return result;
			}
		} else {
			/** sharedPart != null */
			if (privatePart == null) {
				privatePart = IPAMutableSparseIntSet.make(sharedPart);
				sharedPart = null;
				boolean result = privatePart.addAllInIntersection(other, filter);
				checkOverflow();
				return result;
			} else {
				/** sharedPart != null, privatePart != null */
				// note that "other" is likely small
				IPAMutableSparseIntSet temp = IPAMutableSparseIntSet.make(other);
				temp.intersectWith(filter);
				return addAll(temp);
			}
		}
	}

	@Override
	public void clear() {
		privatePart = null;
		sharedPart = null;
	}

}
