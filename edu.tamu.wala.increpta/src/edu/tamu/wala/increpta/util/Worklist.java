package edu.tamu.wala.increpta.util;

import java.util.HashSet;
import java.util.NoSuchElementException;

import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.Heap;

import edu.tamu.wala.increpta.operators.IPAAbstractStatement;


@SuppressWarnings("rawtypes")
public class Worklist extends Heap<IPAAbstractStatement> {

	private final HashSet<IPAAbstractStatement> contents = HashSetFactory.make();

	public Worklist() {
		super(100);
	}

	@Override
	protected final boolean compareElements(IPAAbstractStatement eq1, IPAAbstractStatement eq2) {
		return (eq1.getOrderNumber() < eq2.getOrderNumber());
	}

	public IPAAbstractStatement takeStatement() throws NoSuchElementException {
		IPAAbstractStatement result = super.take();
		contents.remove(result);
		return result;
	}

	public void insertStatement(IPAAbstractStatement eq) {
		if (!contents.contains(eq)) {
			contents.add(eq);
			super.insert(eq);
		}
	}
}
