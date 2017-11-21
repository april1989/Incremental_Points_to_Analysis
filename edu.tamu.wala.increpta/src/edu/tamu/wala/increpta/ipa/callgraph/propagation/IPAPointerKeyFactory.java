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
import com.ibm.wala.ipa.callgraph.propagation.PointerKeyFactory;

public interface IPAPointerKeyFactory extends PointerKeyFactory{

	  /**
	   * @return the PointerKey that acts as a representative for the class of pointers that includes the local variable identified by
	   *         the value number parameter.
	   * for the new IPAFilteredPointerKey
	   */
	  IPAFilteredPointerKey getFilteredPointerKeyForLocal(CGNode node, int valueNumber, IPAFilteredPointerKey.IPATypeFilter filter);

}
