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

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.propagation.LocalPointerKey;

public class IPALocalPointerKeyWithFilter extends LocalPointerKey implements IPAFilteredPointerKey {

	private final IPATypeFilter typeFilter;

	public IPALocalPointerKeyWithFilter(CGNode node, int valueNumber, IPATypeFilter typeFilter) {
		super(node,valueNumber);
		assert typeFilter != null;
		this.typeFilter = typeFilter;
	}


	@Override
	public IPATypeFilter getTypeFilter() {
		return typeFilter;
	}


}
