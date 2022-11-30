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
package edu.tamu.wala.increpta.operators;

import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPointsToSetVariable;

public abstract class IPAUnarySideEffect extends IPAUnaryOperator<IPAPointsToSetVariable> {
	private IPAPointsToSetVariable fixedSet;

	public IPAUnarySideEffect(IPAPointsToSetVariable fixedSet) {
		this.fixedSet = fixedSet;
	}

	@Override
	public final byte evaluate(IPAPointsToSetVariable lhs, IPAPointsToSetVariable rhs) {
		return evaluate(rhs);
	}

	public abstract byte evaluate(IPAPointsToSetVariable rhs);

	@Override
	public final byte evaluateDel(IPAPointsToSetVariable lhs, IPAPointsToSetVariable rhs) {
		return evaluateDel(rhs);
	}

	public abstract byte evaluateDel(IPAPointsToSetVariable rhs);


	/**
	 * @return Returns the fixed points-to-set associated with this side effect.
	 * protected -> public
	 */
	public IPAPointsToSetVariable getFixedSet() {
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
	 * bz: ?? Update the fixed points-to-set associated with this side effect.
	 */
	public void replaceFixedSet(IPAPointsToSetVariable p) {
		fixedSet = p;
	}
}
