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
package edu.tamu.wala.increpta.ipa.callgraph.propagation;

import com.ibm.wala.classLoader.IField;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.propagation.ArrayContentsKey;
import com.ibm.wala.ipa.callgraph.propagation.FilteredPointerKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceFieldKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.LocalPointerKey;
import com.ibm.wala.ipa.callgraph.propagation.LocalPointerKeyWithFilter;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.callgraph.propagation.ReturnValueKey;
import com.ibm.wala.ipa.callgraph.propagation.StaticFieldKey;
import com.ibm.wala.ipa.callgraph.propagation.cfa.ExceptionReturnValueKey;

public class IPADefaultPointerKeyFactory implements IPAPointerKeyFactory {
	/**
	 * for new type of filteredpointerkey
	 */
	public IPADefaultPointerKeyFactory() {
	}

	@Override
	public PointerKey getPointerKeyForLocal(CGNode node, int valueNumber) {
		if (valueNumber <= 0) {
			throw new IllegalArgumentException("illegal value number: " + valueNumber + " in " + node);
		}
		return new LocalPointerKey(node, valueNumber);
	}

	@Override
	public FilteredPointerKey getFilteredPointerKeyForLocal(CGNode node, int valueNumber, FilteredPointerKey.TypeFilter filter) {
		if (filter == null) {
			throw new IllegalArgumentException("null filter");
		}
		assert valueNumber > 0 : "illegal value number: " + valueNumber + " in " + node;
		// TODO: add type filters!
		return new LocalPointerKeyWithFilter(node, valueNumber, filter);
	}

	@Override
	public IPAFilteredPointerKey getFilteredPointerKeyForLocal(CGNode node, int valueNumber, IPAFilteredPointerKey.IPATypeFilter filter) {
		if (filter == null) {
			throw new IllegalArgumentException("null filter");
		}
		assert valueNumber > 0 : "illegal value number: " + valueNumber + " in " + node;
		// TODO: add type filters!
		return new IPALocalPointerKeyWithFilter(node, valueNumber, filter);
	}

	@Override
	public PointerKey getPointerKeyForReturnValue(CGNode node) {
		return new ReturnValueKey(node);
	}

	@Override
	public PointerKey getPointerKeyForExceptionalReturnValue(CGNode node) {
		return new ExceptionReturnValueKey(node);
	}

	@Override
	public PointerKey getPointerKeyForStaticField(IField f) {
		if (f == null) {
			throw new IllegalArgumentException("null f");
		}
		return new StaticFieldKey(f);
	}

	@Override
	public PointerKey getPointerKeyForInstanceField(InstanceKey I, IField field) {
		if (field == null) {
			throw new IllegalArgumentException("field is null");
		}
		return new InstanceFieldKey(I, field);
	}

	@Override
	public PointerKey getPointerKeyForArrayContents(InstanceKey I) {//bz: for new filterpointerkey
		return new IPAArrayContentsKey(I);
	}
}
