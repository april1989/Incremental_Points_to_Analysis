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

import com.ibm.wala.fixpoint.IFixedPointStatement;
import com.ibm.wala.fixpoint.IVariable;
import com.ibm.wala.util.graph.impl.NodeWithNumber;

public abstract class IPAAbstractStatement <T extends IVariable<T>, O extends IPAAbstractOperator<T>> extends NodeWithNumber implements IFixedPointStatement<T>{

	public abstract O getOperator();

	/**
	 * Subclasses must implement this, to prevent non-determinism.
	 */
	@Override
	public abstract int hashCode();

	@Override
	public abstract boolean equals(Object o);

	@Override
	public String toString() {
		StringBuffer result = new StringBuffer("");
		if (getLHS() == null) {
			result.append("null ");
		} else {
			result.append(getLHS().toString());
			result.append(" ");
		}
		result.append(getOperator().toString());
		result.append(" ");
		for (int i = 0; i < getRHS().length; i++) {
			if (getRHS()[i] == null) {
				result.append("null");
			} else {
				result.append(getRHS()[i].toString());
			}
			result.append(" ");
		}
		return result.toString();
	}

	/**
	 * bz: record to save time
	 */
	private int order_number = -1;
	
	public final int getOrderNumber() {
		if(order_number == -1) {
			T lhs = getLHS();
			if(lhs == null)
				order_number = 0;
			else
				order_number = lhs.getOrderNumber();
		}
		return order_number;
		//wala original code 
//		T lhs = getLHS();
//		return (lhs == null) ? 0 : lhs.getOrderNumber();
	}

	public abstract byte evaluateDel();


}
