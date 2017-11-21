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
