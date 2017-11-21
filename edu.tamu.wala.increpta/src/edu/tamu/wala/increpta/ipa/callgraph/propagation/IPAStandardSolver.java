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

import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.MonitorUtil.IProgressMonitor;

public class IPAStandardSolver extends IPAAbstractPointsToSolver{

	private static final boolean DEBUG_PHASES = DEBUG || false;

	public IPAStandardSolver(IPAPropagationSystem system, IPAPropagationCallGraphBuilder builder) {
		super(system, builder);
	}

	/*
	 * @see com.ibm.wala.ipa.callgraph.propagation.IPointsToSolver#solve()
	 */
	@Override
	public void solve(IProgressMonitor monitor) throws IllegalArgumentException, CancelException {
		int i = 0;
		do {
			i++;

			if (DEBUG_PHASES) {
				System.err.println("Iteration " + i);
			}
			getSystem().solve(monitor);
			if (DEBUG_PHASES) {
				System.err.println("Solved " + i);
			}

			if (getBuilder().getOptions().getMaxNumberOfNodes() > -1) {
				if (getBuilder().getCallGraph().getNumberOfNodes() >= getBuilder().getOptions().getMaxNumberOfNodes()) {
					if (DEBUG) {
						System.err.println("Bail out from call graph limit" + i);
					}
					throw CancelException.make("reached call graph size limit");
				}
			}

			// Add constraints until there are no new discovered nodes
			if (DEBUG_PHASES) {
				System.err.println("adding constraints");
			}
			getBuilder().addConstraintsFromNewNodes(monitor);

			// getBuilder().callGraph.summarizeByPackage();

			if (DEBUG_PHASES) {
				System.err.println("handling reflection");
			}
			if (i <= getBuilder().getOptions().getReflectionOptions().getNumFlowToCastIterations()) {
				getReflectionHandler().updateForReflection(monitor);
			}
			// Handling reflection may have discovered new nodes!
			if (DEBUG_PHASES) {
				System.err.println("adding constraints again");
			}
			getBuilder().addConstraintsFromNewNodes(monitor);

			if (monitor != null) { monitor.worked(i); }
			// Note that we may have added stuff to the
			// worklist; so,
		} while (!getSystem().emptyWorkList());

	}

}
