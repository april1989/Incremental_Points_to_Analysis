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

import java.util.Iterator;

import com.ibm.wala.ipa.callgraph.propagation.InstanceKeyFactory;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.cha.IClassHierarchy;

public interface IPAHeapModel extends InstanceKeyFactory, IPAPointerKeyFactory {

	/**
	 * @return an Iterator of all PointerKeys that are modeled.
	 */
	Iterator<PointerKey> iteratePointerKeys();

	/**
	 * @return the governing class hierarchy for this heap model
	 */
	IClassHierarchy getClassHierarchy();

}
