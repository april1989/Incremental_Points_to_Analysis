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

import com.ibm.wala.fixpoint.FixedPointConstants;
import com.ibm.wala.fixpoint.IVariable;

public abstract class IPAAbstractOperator <T extends IVariable<T>> implements FixedPointConstants {

	/**
	 * Evaluate this equation, setting a new value for the left-hand side.
	 *
	 * @return a code that indicates: 1) has the lhs value changed? 2) has this equation reached a fixed-point, in that we never have
	 *         to evaluate the equation again, even if rhs operands change?
	 */
	public abstract byte evaluate(T lhs, T[] rhs);

	@Override
	public abstract int hashCode();

	@Override
	public abstract boolean equals(Object o);

	@Override
	public abstract String toString();

	/**
	 * bz : add for deletion, may change to abstract?
	 * @param lhs
	 * @param rhs
	 * @return
	 */
	public byte evaluateDel(T lhs, T[] rhs) {
		return 0;
	}
}
