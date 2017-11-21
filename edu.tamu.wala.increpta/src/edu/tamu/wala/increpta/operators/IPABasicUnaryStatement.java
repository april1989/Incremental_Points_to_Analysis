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

public class IPABasicUnaryStatement<T extends IVariable<T>> extends IPAUnaryStatement<T> {

	private final IPAUnaryOperator<T> operator;

	public IPABasicUnaryStatement(T lhs, IPAUnaryOperator<T> operator, T rhs) {
		super(lhs, rhs);
		this.operator = operator;
	}

	@Override
	public IPAUnaryOperator<T> getOperator() {
		return operator;
	}
}
