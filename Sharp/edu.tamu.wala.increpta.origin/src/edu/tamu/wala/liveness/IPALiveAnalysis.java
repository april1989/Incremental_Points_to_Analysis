package edu.tamu.wala.liveness;

import java.util.HashMap;

import com.ibm.wala.cast.ir.ssa.analysis.LiveAnalysis;
import com.ibm.wala.cast.ir.ssa.analysis.LiveAnalysis.Result;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ssa.IR;

public class IPALiveAnalysis {
	
	private CallGraph cg;
	private HashMap<CGNode, Result> liveness = new HashMap<>();

	/**
	 * compute liveness analysis for each method node in cg
	 * @param cg
	 */
	public IPALiveAnalysis(CallGraph cg) {
		this.cg = cg;
	}
	
	public Result getLivenessResultFor(CGNode node) {
		return liveness.get(node);
	}
	
	public HashMap<CGNode, Result> getLivenessResults() {
		return liveness;
	}
	
	/**
	 * uses LiveAnalysis from com.ibm.wala.cast
	 * @return
	 */
	public HashMap<CGNode, Result> compute(){
		for (CGNode node : cg) {
			IR ir = node.getIR();
			if(ir != null) {
				//this is the lazy result with a closure, should not be null
				Result result = LiveAnalysis.perform(ir);
				liveness.put(node, result);
			}
		}
		return liveness;
	}

}
