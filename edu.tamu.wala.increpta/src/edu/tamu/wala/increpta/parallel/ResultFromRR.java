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
package edu.tamu.wala.increpta.parallel;

import java.util.ArrayList;

import com.ibm.wala.util.intset.MutableSharedBitVectorIntSet;

import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPointsToSetVariable;


public class ResultFromRR {

  private IPAPointsToSetVariable user;
  private ArrayList<IPAPointsToSetVariable> next;
  private MutableSharedBitVectorIntSet newtargets;

  public ResultFromRR(IPAPointsToSetVariable user, ArrayList<IPAPointsToSetVariable> next, MutableSharedBitVectorIntSet remaining) {
    this.user = user;
    this.newtargets = remaining;
    this.next = next;
  }

  public IPAPointsToSetVariable getUser(){
    return user;
  }

  public ArrayList<IPAPointsToSetVariable> getCheckNext(){
    return next;
  }

  public MutableSharedBitVectorIntSet getNewTargets(){
    return newtargets;
  }


}
