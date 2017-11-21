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
