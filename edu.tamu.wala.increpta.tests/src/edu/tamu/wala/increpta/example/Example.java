package edu.tamu.wala.increpta.example;

import java.awt.Point;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;

import com.ibm.wala.core.tests.callGraph.CallGraphTestUtil;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.util.intset.OrdinalSet;
import com.ibm.wala.util.io.FileProvider;

import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPASSAPropagationCallGraphBuilder;
import edu.tamu.wala.increpta.util.IPAUtil;

public class Example {

	public static String Exclusion_Packages = "ExclusionPackages.txt";
	public static String Scope_File = "Scope.txt";

	public static void main(String[] args) throws IOException, ClassHierarchyException, IllegalArgumentException, CancelException {
		//Indicate analysis scope for the target program and excluded packages
		AnalysisScope scope = AnalysisScopeReader.readJavaScope(Scope_File, (new FileProvider()).getFile(Exclusion_Packages), Example.class.getClassLoader());
		ClassHierarchy cha = ClassHierarchyFactory.make(scope);
		//Compute entrypoints in the scope
		Iterable<Entrypoint> entrypoints = Util.makeMainEntrypoints(scope, cha);
		AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
		IAnalysisCacheView cache = new AnalysisCacheImpl();
		//Create incremental call graph/points-to graph builder, and compute the graphs for the whole target program
		IPASSAPropagationCallGraphBuilder builder = IPAUtil.makeIPAZeroCFABuilder(options, cache, cha, scope);
		CallGraph callGraph = builder.makeCallGraph(options, null);
		PointerAnalysis<InstanceKey> pta = builder.getPointerAnalysis();
		//targetNode -> Store the CGNode where SSAInstructions are going to change
		//delInsts -> Deleted SSAInstructions in targetNode
		//addInsts -> Added SSAInstructions in targetNode
		CGNode targetNode = null;
		HashSet<SSAInstruction> delInsts = new HashSet<>();
		HashSet<SSAInstruction> addInsts = new HashSet<>();
		//obtain all the SSAInstruction (IR in WALA) of the target program
		Iterator<CGNode> iter_CGNode = callGraph.iterator();
		while(iter_CGNode.hasNext()){
			CGNode node = iter_CGNode.next();
			SSAInstruction[] insts = node.getIR().getInstructions();
			//Assign the node to targetNode, and add the selected insts in delInsts or addInsts. For example:
			//targetNode = node;
			//delInsts.add(insts[i]);
			//addInsts.add(insts[j]);
		}
		//Perform the incremental points-to analysis
		builder.updatePointsToAnalysis(targetNode, delInsts, addInsts);
		//Query the points-to set of a specific variable (PointerKey in WALA)
		Iterator<PointerKey> iterPointerKey = pta.getPointerKeys().iterator();
		while(iterPointerKey.hasNext()){
			PointerKey pointerKey = iterPointerKey.next();
			OrdinalSet<InstanceKey> pts = pta.getPointsToSet(pointerKey);
			//print out the points-to set of pointerKey
			System.out.println("Points-to Set of : " + pointerKey.toString());
			for (InstanceKey instanceKey : pts) {
				System.out.println(" -- " + instanceKey.toString());
			}
		}

	}




}
