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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

import org.junit.Assert;

import com.ibm.wala.classLoader.Language;
import com.ibm.wala.core.tests.callGraph.CallGraphTestUtil;
import com.ibm.wala.core.tests.demandpa.AbstractPtrTest;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.MutableSharedBitVectorIntSetFactory;
import com.ibm.wala.util.intset.OrdinalSet;
import com.ibm.wala.util.intset.OrdinalSetMapping;

import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPointerAnalysisImpl;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationCallGraphBuilder;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPASSAPropagationCallGraphBuilder;
import edu.tamu.wala.increpta.util.IPAUtil;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPointsToSetVariable;

public abstract class IPAAbstractPtrTest extends AbstractPtrTest{

	/**
	 * var_pts_map: a map to record pointer keys and their original points-to sets
	 */
	private static HashMap<PointerKey, MutableIntSet> var_pts_map = new HashMap<>();
	public static String MY_EXCLUSIONS = "EclipseDefaultExclusions.txt";


	public IPAAbstractPtrTest(String scopeFile) {
		super(scopeFile);
	}

	protected void doIncrementalPTATest(String mainClass) throws ClassHierarchyException, IllegalArgumentException, CallGraphBuilderCancelException, IOException{
		performTest(mainClass);
	}

	protected void performTest(String mainClassName) throws ClassHierarchyException, IllegalArgumentException, IOException, CallGraphBuilderCancelException {
		// perform incremental pta test on the testcase in com.ibm.wala.core.testdata
		System.err.println("Start the test of incremental pointer analysis for test case: " + mainClassName);
		long start_time = System.currentTimeMillis();
		if(mainClassName.contains("jython")){
			MY_EXCLUSIONS = "EclipseDefaultExclusions_short.txt";
		}
		AnalysisScope scope = CallGraphTestUtil.makeJ2SEAnalysisScope(scopeFile, MY_EXCLUSIONS);//CallGraphTestUtil.REGRESSION_EXCLUSIONS
		ClassHierarchy cha = ClassHierarchyFactory.make(scope);
		Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha, mainClassName);
		AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
		IAnalysisCacheView cache = new AnalysisCacheImpl();
		//works for different call graph/ptg builders
		IPASSAPropagationCallGraphBuilder builder = IPAUtil.makeIPAZeroCFABuilder(Language.JAVA, options, cache, cha, scope);
		//SSAPropagationCallGraphBuilder builder = Util.makeZeroOneContainerCFABuilder(options, cache, cha, scope);
		//SSAPropagationCallGraphBuilder builder = Util.makeVanillaZeroOneCFABuilder(options, cache, cha, scope);

		CallGraph callGraph = builder.makeCallGraph(options, null);
		PointerAnalysis<InstanceKey> pta = builder.getPointerAnalysis();
		long whole_analysis_time = System.currentTimeMillis() - start_time;
		//copy pts
		copyPointsToSet(builder, pta);
		System.err.println("Finish the whole program pta analysis for " + mainClassName + " using " + whole_analysis_time + "ms");
		System.err.println("Start the correctness check for incremental pointer analysis in " + mainClassName);
		Iterator<CGNode> iter = callGraph.iterator();
		HashSet<CGNode> storeCG = new HashSet<>();
		while(iter.hasNext()){
			CGNode n = iter.next();
			storeCG.add(n);
		}
		//initial and set scc
		builder.getSystem().createSCCEngine();
 		builder.getSystem().getPropagationGraph().initialRunSCCEngine();
		builder.getSystem().getPropagationGraph().setChange(true);
		//initialize parallel system
		builder.getSystem().initialParallelSystem(false, 8);
		doIncrementalCheck(builder, storeCG);
		System.err.println("========= Complete the correctness check for incremental pointer analysis in " + mainClassName + "\n");
	}


	private static void copyPointsToSet(IPASSAPropagationCallGraphBuilder builder, PointerAnalysis<InstanceKey> ptg) {
		IPAPointerAnalysisImpl pta = (IPAPointerAnalysisImpl) ptg;
		Iterable<PointerKey> iterable = pta.getPointerKeys();
		Iterator<PointerKey> iterator = iterable.iterator();
		while(iterator.hasNext()){
			PointerKey key = iterator.next();
			if(pta.getPointsToMap().isImplicit(key)){
				OrdinalSet<InstanceKey> set = pta.getPointsToSet(key);
				OrdinalSetMapping<InstanceKey> instancekeyMapping = pta.getInstanceKeyMapping();
				MutableIntSet pts = new MutableSharedBitVectorIntSetFactory().make();
				for (InstanceKey instanceKey : set) {
					int value = instancekeyMapping.getMappedIndex(instanceKey);
					pts.add(value);
				}
				var_pts_map.put(key, pts);
			}else{
				IPAPointsToSetVariable ptsv = builder.getSystem().findOrCreatePointsToSet(key);
				if(ptsv != null){
					MutableIntSet pts = ptsv.getValue();
					if(pts != null){
						var_pts_map.put(key, new MutableSharedBitVectorIntSetFactory().makeCopy(pts));
					}
				}
			}
		}
	}

	private static void doIncrementalCheck(IPASSAPropagationCallGraphBuilder builder, HashSet<CGNode> storeCG) {
		//we set up a time limit for the test
		long start = System.currentTimeMillis();
		//true: by perform the performance comparison
		//false: not perform the performance comparison, no output for performance
		boolean performance = true;
		for(CGNode n: storeCG){
			if(!n.getMethod().getSignature().contains("com.ibm.wala")
					&& !n.getMethod().getSignature().contains("java.") ){
				System.out.println("-- Test Method " + n.getMethod().getSignature());
				boolean correct = builder.testChange(n, var_pts_map, performance);
				Assert.assertTrue("Points-to sets are different after changing " + n.getMethod().getSignature(), correct);
				System.out.println();
			}
			if((System.currentTimeMillis() - start) >= 120000)
				break;
		}
		if(performance){
			//to compute average time
			int total_inst = IPAPropagationCallGraphBuilder.total_inst;
			long total_add = IPAPropagationCallGraphBuilder.total_add;
			long total_del = IPAPropagationCallGraphBuilder.total_del;
			double avg_add = (double)total_add/total_inst;
			double avg_del = (double)total_del/total_inst;

			System.err.println("The average time to compute the incremental analysis for deleting insts is " + avg_del + "ms");
			System.err.println("The average time to compute the incremental analysis for adding insts is " + avg_add + "ms");
		}
	}


}
