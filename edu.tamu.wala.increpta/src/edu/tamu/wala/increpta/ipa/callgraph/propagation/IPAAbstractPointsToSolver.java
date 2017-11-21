package edu.tamu.wala.increpta.ipa.callgraph.propagation;

import com.ibm.wala.ipa.callgraph.propagation.IPointsToSolver;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.MonitorUtil.IProgressMonitor;

public abstract class IPAAbstractPointsToSolver implements IPointsToSolver {

	protected final static boolean DEBUG = false;

	private final IPAPropagationSystem system;

	private final IPAPropagationCallGraphBuilder builder;

	private final IPAReflectionHandler reflectionHandler;

	public IPAAbstractPointsToSolver(IPAPropagationSystem system, IPAPropagationCallGraphBuilder builder) {
		if (system == null) {
			throw new IllegalArgumentException("null system");
		}
		this.system = system;
		this.builder = builder;
		this.reflectionHandler = new IPAReflectionHandler(builder);
	}

	/*
	 * @see com.ibm.wala.ipa.callgraph.propagation.IPointsToSolver#solve()
	 */
	@Override
	public abstract void solve(IProgressMonitor monitor) throws IllegalArgumentException, CancelException;

	protected IPAPropagationCallGraphBuilder getBuilder() {
		return builder;
	}

	protected IPAReflectionHandler getReflectionHandler() {
		return reflectionHandler;
	}

	protected IPAPropagationSystem getSystem() {
		return system;
	}
}
