/*
 * Copyright (c) 2002 - 2006 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 */
package edu.tamu.wala.increpta.util.intset;

import com.ibm.wala.util.debug.Assertions;
import com.ibm.wala.util.debug.UnimplementedError;
import com.ibm.wala.util.intset.IntIterator;

/**
 * An Iterator which provides a concatenation of two IntIterators.
 */
public class IPACompoundIntIterator implements IntIterator {

	final IntIterator A;
	final IntIterator B;
	int size;

	/**
	 * @param A the first iterator in the concatenated result
	 * @param B the second iterator in the concatenated result
	 */
	public IPACompoundIntIterator(IntIterator A, IntIterator B, int A_size, int B_size) {
		if (A == null) {
			throw new IllegalArgumentException("null A");
		}
		if (B == null) {
			throw new IllegalArgumentException("null B");
		}
		this.A = A;
		this.B = B;
		this.size = A_size + B_size;
	}


	@Override
	public boolean hasNext() {
//		return A.hasNext() || B.hasNext();
		return size > 0;
	}


	@Override
	public int next() {
		size--;
		if (A.hasNext()) {
			return A.next();
		}
		return B.next();
	}

	@Override
	public int hashCode() throws UnimplementedError {
		Assertions.UNREACHABLE("define a custom hash code to avoid non-determinism");
		return 0;
	}
}
