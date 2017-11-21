package edu.tamu.wala.increpta.operators;

import com.ibm.wala.ipa.callgraph.propagation.PointsToSetVariable;

public abstract class IPAUnarySideEffect extends IPAUnaryOperator<PointsToSetVariable> {
	private PointsToSetVariable fixedSet;

	public IPAUnarySideEffect(PointsToSetVariable fixedSet) {
		this.fixedSet = fixedSet;
	}

	@Override
	public final byte evaluate(PointsToSetVariable lhs, PointsToSetVariable rhs) {
		return evaluate(rhs);
	}

	public abstract byte evaluate(PointsToSetVariable rhs);

	@Override
	public final byte evaluateDel(PointsToSetVariable lhs, PointsToSetVariable rhs) {
		return evaluateDel(rhs);
	}

	public abstract byte evaluateDel(PointsToSetVariable rhs);


	/**
	 * @return Returns the fixed points-to-set associated with this side effect.
	 */
	protected PointsToSetVariable getFixedSet() {
		return fixedSet;
	}

	@Override
	public boolean equals(Object o) {
		if (o == null) {
			return false;
		}
		if (getClass().equals(o.getClass())) {
			IPAUnarySideEffect other = (IPAUnarySideEffect) o;
			return fixedSet.equals(other.fixedSet);
		} else {
			return false;
		}
	}

	@Override
	public int hashCode() {
		return 8059 * fixedSet.hashCode();
	}

	/**
	 * A "load" operator generates defs of the fixed set. A "store" operator generates uses of the fixed set.
	 */
	abstract protected boolean isLoadOperator();

	/**
	 * Update the fixed points-to-set associated with this side effect.
	 */
	public void replaceFixedSet(PointsToSetVariable p) {
		fixedSet = p;
	}
}
