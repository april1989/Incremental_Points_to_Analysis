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
package edu.tamu.wala.increpta.parallel;

import com.ibm.wala.ipa.callgraph.propagation.PointsToSetVariable;

public class Result {

	private PointsToSetVariable ptvs;

	public Result(PointsToSetVariable ptvs) {
		this.ptvs = ptvs;
	}

	public PointsToSetVariable getResult(){
		return ptvs;
	}

}
