package edu.tamu.wala.increpta.util.intset;

import com.ibm.wala.util.intset.BitVectorIntSet;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableIntSetFactory;
import com.ibm.wala.util.intset.MutableSparseIntSetFactory;
import com.ibm.wala.util.intset.SparseIntSet;

public class IPAMutableSharedBitVectorIntSetFactory implements MutableIntSetFactory<IPAMutableSharedBitVectorIntSet>{

	private final MutableSparseIntSetFactory sparseFactory = new MutableSparseIntSetFactory();

	  /*
	   * @see com.ibm.wala.util.intset.MutableIntSetFactory#make(int[])
	   */
	  @Override
	  public IPAMutableSharedBitVectorIntSet make(int[] set) {
	    SparseIntSet s = sparseFactory.make(set);
	    return new IPAMutableSharedBitVectorIntSet(s);
	  }

	  /*
	   * @see com.ibm.wala.util.intset.MutableIntSetFactory#parse(java.lang.String)
	   */
	  @Override
	  public IPAMutableSharedBitVectorIntSet parse(String string) throws NumberFormatException {
	    SparseIntSet s = sparseFactory.parse(string);
	    return new IPAMutableSharedBitVectorIntSet(s);
	  }

	  /*
	   * @see com.ibm.wala.util.intset.MutableIntSetFactory#makeCopy(com.ibm.wala.util.intset.IntSet)
	   */
	  @Override
	  public IPAMutableSharedBitVectorIntSet makeCopy(IntSet x) throws IllegalArgumentException {
	    if (x == null) {
	      throw new IllegalArgumentException("x == null");
	    }
	    if (x instanceof IPAMutableSharedBitVectorIntSet) {
	      return new IPAMutableSharedBitVectorIntSet((IPAMutableSharedBitVectorIntSet) x);
	    } else if (x instanceof SparseIntSet) {
	      return new IPAMutableSharedBitVectorIntSet((SparseIntSet) x);
	    } else if (x instanceof BitVectorIntSet) {
	      return new IPAMutableSharedBitVectorIntSet((BitVectorIntSet) x);
	    } 
//	    else if (x instanceof DebuggingMutableIntSet) {
//	      return new IPAMutableSharedBitVectorIntSet(new SparseIntSet(x));
//	    } 
	    else {
	      // really slow.  optimize as needed.
	    	IPAMutableSharedBitVectorIntSet result = new IPAMutableSharedBitVectorIntSet();
	      for (IntIterator it = x.intIterator(); it.hasNext(); ) {
	        result.add(it.next());
	      }
	      return result;
	    }
	  }

	  /*
	   * @see com.ibm.wala.util.intset.MutableIntSetFactory#make()
	   */
	  @Override
	  public IPAMutableSharedBitVectorIntSet make() {
	    return new IPAMutableSharedBitVectorIntSet();
	  }

}
