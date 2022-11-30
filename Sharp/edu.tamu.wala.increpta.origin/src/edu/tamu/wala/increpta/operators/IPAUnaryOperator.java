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

import com.ibm.wala.fixpoint.IVariable;
import com.ibm.wala.util.debug.Assertions;
import com.ibm.wala.util.debug.UnimplementedError;

public abstract class IPAUnaryOperator <T extends IVariable<T>> extends IPAAbstractOperator<T> {

	/**
	 * Evaluate this equation, setting a new value for the left-hand side.
	 *
	 * @return true if the lhs value changes. false otherwise.
	 */
	public abstract byte evaluate(T lhs, T rhs);

	/**
	 * Create an equation which uses this operator Override in subclasses for
	 * efficiency.
	 */
	public IPAUnaryStatement<T> makeEquation(T lhs, T rhs) {
		return new IPABasicUnaryStatement<>(lhs, this, rhs);
	}

	public boolean isIdentity() {
		return false;
	}

	@Override
	public byte evaluate(T lhs, T[] rhs) throws UnimplementedError {
		// this should never be called. Use the other, more efficient form.
		Assertions.UNREACHABLE();
		return 0;
	}

	/**
	 * bz: for deletion
	 * @param lhs
	 * @param rhs
	 * @return
	 */
	public  abstract byte evaluateDel(T lhs, T rhs);

}
