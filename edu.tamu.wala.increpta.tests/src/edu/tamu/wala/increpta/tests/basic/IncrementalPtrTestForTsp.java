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

import java.io.IOException;

import org.junit.Test;

import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;
import com.ibm.wala.ipa.cha.ClassHierarchyException;

public class IncrementalPtrTestForTsp extends IPAAbstractPtrTest{

  public IncrementalPtrTestForTsp() {
    super(IPATestInfo.SCOPE_FILE7);
  }

  @Test
  public void testJython() throws IllegalArgumentException, ClassHierarchyException, CallGraphBuilderCancelException, IOException {
    doIncrementalPTATest(IPATestInfo.TEST_TSP);
  }

}
