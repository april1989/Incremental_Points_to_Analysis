package edu.tamu.wala.increpta.ipa.callgraph.propagation;

import com.ibm.wala.analysis.typeInference.TypeInference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.fixpoint.IntSetVariable;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.LocalPointerKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.debug.Assertions;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.MutableMapping;
import com.ibm.wala.util.intset.MutableSharedBitVectorIntSet;
import com.ibm.wala.util.intset.MutableSharedBitVectorIntSetFactory;
import com.ibm.wala.util.intset.MutableSparseIntSet;

public class IPAPointsToSetVariable extends IntSetVariable<IPAPointsToSetVariable>{

	/**
	 * if set, emits a warning whenever a points-to set grows bigger than {@link #SIZE_THRESHOLD}
	 */
	public static final boolean CRY_ABOUT_BIG_POINTSTO_SETS = false;
	public static final int SIZE_THRESHOLD = 100;

	/**
	 * if set, check that all instance keys in a points-to set are consistent with the type of the corresponding pointer key
	 */
	public static final boolean PARANOID = false;

	/**
	 * used only for paranoid checking. a bit ugly, but avoids adding an instance field just for debugging
	 */
	public static MutableMapping<InstanceKey> instanceKeys = null;

	private PointerKey pointerKey;

	//to mark to change (diff)
	private MutableSharedBitVectorIntSet change;

	public IPAPointsToSetVariable(PointerKey key) {
		super();
		if (key == null) {
			throw new IllegalArgumentException("null key");
		}
		this.pointerKey = key;
		this.change = new MutableSharedBitVectorIntSetFactory().make();
	}


	/**
	 * for single instance
	 * @param i
	 */
	public synchronized void setChange(int i){
		change.clear();//clear before each change set
		change.add(i);
	}

	/**
	 * for a set of instances
	 */
	public synchronized void setChange(MutableIntSet set){
		change.clear();//clear before each change set
		change.addAll(set);
	}

	/**
	 * clear after one stmt propagation
	 */
	public synchronized void clearChange(){
		change.clear();
	}

	public synchronized MutableSharedBitVectorIntSet getChange(){
		return change;
	}

	public PointerKey getPointerKey() {
		return pointerKey;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof IPAPointsToSetVariable) {
			return pointerKey.equals(((IPAPointsToSetVariable) obj).pointerKey);
		} else {
			return false;
		}
	}

	private boolean cried = false;
	@SuppressWarnings("unused")
	private void cryIfTooBig() {
		if (CRY_ABOUT_BIG_POINTSTO_SETS && !cried && super.size() > SIZE_THRESHOLD) {
			cried = true;
			System.err.println("too big: " + pointerKey + ": " + size());
		}
	}

	@Override
	public void add(int b) {
		if (PARANOID) {
			MutableSparseIntSet m = MutableSparseIntSet.createMutableSparseIntSet(1);
			m.add(b);
			checkTypes(m);
		}
		super.add(b);
		cryIfTooBig();
	}

	@Override
	public boolean addAll(IntSet B) {
		if (PARANOID) {
			checkTypes(B);
		}
		boolean v = super.addAll(B);
		cryIfTooBig();
		return v;
	}

	/**
	 * check that the types of all instance keys are assignable to declared type of pointer key
	 */
	private void checkTypes(IntSet b) {
		assert PARANOID;
		if (b == null)
			return;
		if (!(pointerKey instanceof LocalPointerKey)) {
			return;
		}
		final LocalPointerKey lpk = (LocalPointerKey) pointerKey;
		CGNode node = lpk.getNode();
		final IClassHierarchy cha = node.getClassHierarchy();
		final IR ir = node.getIR();
		if (ir == null)
			return;
		TypeInference ti = TypeInference.make(ir, false);
		final IClass type = ti.getType(lpk.getValueNumber()).getType();
		if (type == null)
			return;
		// don't perform checking for exception variables
		if (cha.isAssignableFrom(cha.lookupClass(TypeReference.JavaLangThrowable), type)) {
			return;
		}
		b.foreach(x -> {
			InstanceKey ik = instanceKeys.getMappedObject(x);
			IClass concreteType = ik.getConcreteType();
			if (!cha.isAssignableFrom(type, concreteType)) {
				System.err.println("BOOM");
				System.err.println(ir);
				System.err.println(lpk + " type " + type);
				System.err.println(ik + " type " + concreteType);
				Assertions.UNREACHABLE();
			}
		});
	}

	@Override
	public boolean addAll(IPAPointsToSetVariable other) {
		if (PARANOID) {
			checkTypes(other.getValue());
		}
		// TODO Auto-generated method stub
		boolean v = super.addAll(other);
		cryIfTooBig();
		return v;
	}

	/**
	 * Use this with extreme care, to add filters to this variable..
	 *
	 * @param pointerKey The pointerKey to set.
	 */
	public void setPointerKey(PointerKey pointerKey) {
		// check that we haven't modified the hash code!!! this is crucial
		assert this.pointerKey.hashCode() == pointerKey.hashCode();
		this.pointerKey = pointerKey;
	}

	@Override
	public String toString() {
		return pointerKey.toString() + ":" + super.toString();
	}

}
