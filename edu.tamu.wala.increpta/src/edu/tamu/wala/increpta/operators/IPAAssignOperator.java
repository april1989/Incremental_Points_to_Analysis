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
package edu.tamu.wala.increpta.operators;

import com.ibm.wala.ipa.callgraph.propagation.IPointerOperator;

import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPointsToSetVariable;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationCallGraphBuilder;

/**
 * Corresponds to: "is a superset of". Used for assignment.
 *
 * Unary op: <lhs>:= Assign( <rhs>)
 *
 * (Technically, it's a binary op, since it includes lhs as an implicit input; this allows it to compose with other ops that define
 * the same lhs, so long as they're all Assign ops)
 */
public class IPAAssignOperator extends IPAUnaryOperator<IPAPointsToSetVariable> implements IPointerOperator {

  @Override
  public IPAUnaryStatement<IPAPointsToSetVariable> makeEquation(IPAPointsToSetVariable lhs, IPAPointsToSetVariable rhs) {
    return new IPAAssignEquation(lhs, rhs);
  }

  @Override
  public byte evaluate(IPAPointsToSetVariable lhs, IPAPointsToSetVariable rhs) {

    if (IPAPropagationCallGraphBuilder.DEBUG_ASSIGN) {
      String S = "EVAL Assign " + lhs.getPointerKey() + " " + rhs.getPointerKey();
      S = S + "\nEVAL " + lhs + " " + rhs;
      System.err.println(S);
    }
    boolean changed = lhs.addAll(rhs);
    if (IPAPropagationCallGraphBuilder.DEBUG_ASSIGN) {
      System.err.println("RESULT " + lhs + (changed ? " (changed)" : ""));
    }

    return changed ? CHANGED : NOT_CHANGED;
  }

   /**
   * bz: leave for procedureToDelPointsToSet()
   */
  @Override
  public byte evaluateDel(IPAPointsToSetVariable lhs, IPAPointsToSetVariable rhs) {
	  return 0;
  }

  @Override
  public String toString() {
    return "Assign";
  }

  @Override
  public int hashCode() {
    return 9883;
  }

  @Override
  public final boolean equals(Object o) {
    // this is a singleton
    return (this == o);
  }

  /*
   * @see com.ibm.wala.ipa.callgraph.propagation.IPointerOperator#isComplex()
   */
  @Override
  public boolean isComplex() {
    return false;
  }
}
