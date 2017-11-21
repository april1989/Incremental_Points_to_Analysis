package edu.tamu.wala.increpta.util;

import com.ibm.wala.fixedpoint.impl.DefaultFixedPointSystem;
import com.ibm.wala.fixpoint.IFixedPointSystem;
import com.ibm.wala.fixpoint.IVariable;

public abstract class IPADefaultFixedPointSolver <T extends IVariable<T>> extends IPAAbstractFixedPointSolver<T> {

	  private final DefaultFixedPointSystem<T> graph;

	  /**
	   * @param expectedOut number of expected out edges in the "usual" case
	   * for constraints .. used to tune graph representation
	   * bz: same with the DefaultFixedPointSolver in wala core
	   */
	  public IPADefaultFixedPointSolver(int expectedOut) {
	    super();
	    graph = new DefaultFixedPointSystem<>(expectedOut);
	  }

	  public IPADefaultFixedPointSolver() {
	    super();
	    graph = new DefaultFixedPointSystem<>();
	  }

	  @Override
	  public IFixedPointSystem<T> getFixedPointSystem() {
	    return graph;
	  }
}
