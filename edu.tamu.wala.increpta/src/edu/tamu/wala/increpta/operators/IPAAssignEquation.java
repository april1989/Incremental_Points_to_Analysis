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


import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPointsToSetVariable;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationCallGraphBuilder;

/**
 * A specialized equation class introduced for efficiency.
 */
public final class IPAAssignEquation extends IPAUnaryStatement<IPAPointsToSetVariable> {

  public IPAAssignEquation(IPAPointsToSetVariable lhs, IPAPointsToSetVariable rhs) {
    super(lhs, rhs);
  }

  @Override
  public IPAUnaryOperator<IPAPointsToSetVariable> getOperator() {
    return IPAPropagationCallGraphBuilder.assignOperator;
  }

  @Override
  public boolean equals(Object o) {
    if (o instanceof IPAAssignEquation) {
      IPAAssignEquation other = (IPAAssignEquation) o;
      return getLHS().equals(other.getLHS()) && getRightHandSide().equals(other.getRightHandSide());
    } else {
      return false;
    }
  }
}
