package edu.tamu.wala.increpta.tests.basic;

import java.io.IOException;

import org.junit.Test;

import com.ibm.wala.core.tests.demandpa.TestInfo;
import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;
import com.ibm.wala.ipa.cha.ClassHierarchyException;

public class IncrementalPtrTestForSunflow extends IPAAbstractPtrTest{

	public IncrementalPtrTestForSunflow() {
		super(IPATestInfo.SCOPE_FILE3);
	}

	@Test
	public void testSunflow() throws IllegalArgumentException, ClassHierarchyException, CallGraphBuilderCancelException, IOException {
		doIncrementalPTATest(IPATestInfo.TEST_SUNFLOW);
	}

}
