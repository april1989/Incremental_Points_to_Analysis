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
package edu.tamu.wala.increpta.tests.basic;

public class IPATestInfo {

	//sunflow:
	public static final String SCOPE_FILE3 = "wala.testdata_sunflow.txt";
	public static final String TEST_SUNFLOW = "Lorg/sunflow/Benchmark";
	//eclipse:
	public static final String SCOPE_FILE4 = "wala.testdata_eclipse.txt";
	public static final String TEST_ECLIPSE = "Lorg/eclipse/core/runtime/adaptor/EclipseStarter";
	//jython:
	public static final String SCOPE_FILE5 = "wala.testdata_jython.txt";
	public static final String TEST_JYTHON = "Lorg/python/util/jython";
	//h2
	public static final String SCOPE_FILE6 = "wala.testdata_h2.txt";
	public static final String TEST_H2 = "Lorg/h2/tools/Shell";
	//tsp
	public static final String SCOPE_FILE7 = "wala.testdata_tsp.txt";
	public static final String TEST_TSP = "Ltsp/Tsp";

	//scctest
	public static final String SCOPE_FILE8 = "wala.testdata_scctest.txt";
	public static final String TEST_SCCTEST = "Lscctest/SCCTest";
}
