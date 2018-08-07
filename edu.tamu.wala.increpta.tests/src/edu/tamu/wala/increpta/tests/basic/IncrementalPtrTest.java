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

import com.ibm.wala.core.tests.demandpa.TestInfo;
import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;
import com.ibm.wala.ipa.cha.ClassHierarchyException;

public class IncrementalPtrTest extends IPAAbstractPtrTest{

  /**testdata_1.0.0.jar includes all test cases in com.ibm.wala.core.testdata/src/demandpa for pointer analysis,
   * you can change the argument of doIncrementalPTATest() to test the cases in TesfInfo.
   *
   *
   * for each test case, we initially compute the whole program pointer analysis, record the points-to set for each variable
   * in a map. Then, we start to check the correctness of the incremental pointer analysis:
   * (1) delete an SSAInstruction, compute incremental pts
   * (2) add back the SSAInstruction, compute incremental pts and record the changed variables
   * (3) for each changed variable, we compare the points-to sets between the one stored in the map and
   * the one after incremental computation. If they are the same, it means we correctly updated the points-to graph.
   * Otherwise, it violates the assertion.
   *
   * for the parallel version, change edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationSystem.nrOfWorkers to any number > 1.
   * nrOfWorkers means the number of threads to perform parallel work.
   *
   *
   */
  public IncrementalPtrTest() {
    super(TestInfo.SCOPE_FILE);
  }

  @Test
  public void test1() throws IllegalArgumentException, ClassHierarchyException, CallGraphBuilderCancelException, IOException {
    doIncrementalPTATest(TestInfo.TEST_ARRAY_LIST);
  }

  @Test
  public void test2() throws ClassHierarchyException, IllegalArgumentException, CallGraphBuilderCancelException, IOException {
    doIncrementalPTATest(TestInfo.TEST_FIELDS);
  }

  @Test
  public void test3() throws ClassHierarchyException, IllegalArgumentException, CallGraphBuilderCancelException, IOException {
    doIncrementalPTATest(TestInfo.TEST_METHOD_RECURSION);
  }

  @Test
  public void test4() throws ClassHierarchyException, IllegalArgumentException, CallGraphBuilderCancelException, IOException {
    doIncrementalPTATest(TestInfo.TEST_FIELDS_HARDER);
  }

  @Test
  public void test5() throws ClassHierarchyException, IllegalArgumentException, CallGraphBuilderCancelException, IOException {
    doIncrementalPTATest(TestInfo.TEST_ARRAY_LIST);
  }

  @Test
  public void test6() throws ClassHierarchyException, IllegalArgumentException, CallGraphBuilderCancelException, IOException {
    doIncrementalPTATest(TestInfo.TEST_LINKED_LIST);
  }

  @Test
  public void test7() throws ClassHierarchyException, IllegalArgumentException, CallGraphBuilderCancelException, IOException {
    doIncrementalPTATest(TestInfo.TEST_ONTHEFLY_CS);
  }

  @Test
  public void test8() throws ClassHierarchyException, IllegalArgumentException, CallGraphBuilderCancelException, IOException {
    doIncrementalPTATest(TestInfo.TEST_NASTY_PTRS);
  }

  @Test
  public void test9() throws ClassHierarchyException, IllegalArgumentException, CallGraphBuilderCancelException, IOException {
    doIncrementalPTATest(TestInfo.FLOWSTO_TEST_FIELDS);
  }

  @Test
  public void test10() throws ClassHierarchyException, IllegalArgumentException, CallGraphBuilderCancelException, IOException {
    doIncrementalPTATest(TestInfo.FLOWSTO_TEST_HASHSET);
  }


}
