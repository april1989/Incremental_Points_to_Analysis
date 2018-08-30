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

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.propagation.ReturnValueKey;

import edu.tamu.wala.increpta.pointerkey.IPAFilteredPointerKey;

public class IPAReturnValueKeyWithFilter extends ReturnValueKey implements IPAFilteredPointerKey {

	  private final IPATypeFilter typeFilter;

	  public IPAReturnValueKeyWithFilter(CGNode node, IPATypeFilter typeFilter) {
	    super(node);
	    if (typeFilter == null) {
	      throw new IllegalArgumentException("null typeFilter");
	    }
	    this.typeFilter = typeFilter;
	  }

	  @Override
	  public IPATypeFilter getTypeFilter() {
	    return typeFilter;
	  }

	}
