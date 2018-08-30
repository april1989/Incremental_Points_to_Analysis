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
package edu.tamu.wala.increpta.pointerkey;

import com.ibm.wala.classLoader.ArrayClass;
import com.ibm.wala.ipa.callgraph.propagation.AbstractFieldPointerKey;
import com.ibm.wala.ipa.callgraph.propagation.ArrayContentsKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

public final class IPAArrayContentsKey extends AbstractFieldPointerKey implements IPAFilteredPointerKey {
	public IPAArrayContentsKey(InstanceKey instance) {
		super(instance);
	}

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof ArrayContentsKey) {
			ArrayContentsKey other = (ArrayContentsKey) obj;
			return instance.equals(other.getInstanceKey());
		} else {
			return false;
		}
	}

	@Override
	public int hashCode() {
		return 1061 * instance.hashCode();
	}

	@Override
	public String toString() {
		return "[" + instance + "[]]";
	}

	/*
	 * @see com.ibm.wala.ipa.callgraph.propagation.PointerKey#getTypeFilter()
	 */
	@Override
	public IPATypeFilter getTypeFilter() {
		return new SingleClassFilter(((ArrayClass) instance.getConcreteType()).getElementClass());
	}
}
